/*
 * Copyright 2002-2010 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.huaweicloud.dis;

import java.net.URI;
import java.net.URISyntaxException;
import java.nio.ByteBuffer;
import java.nio.charset.Charset;
import java.security.InvalidAlgorithmParameterException;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.security.spec.InvalidKeySpecException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantLock;

import javax.crypto.BadPaddingException;
import javax.crypto.IllegalBlockSizeException;
import javax.crypto.NoSuchPaddingException;

import org.apache.http.HttpRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.cloud.sdk.DefaultRequest;
import com.cloud.sdk.Request;
import com.cloud.sdk.auth.credentials.BasicCredentials;
import com.cloud.sdk.auth.credentials.Credentials;
import com.cloud.sdk.http.HttpMethodName;
import com.cloud.sdk.util.StringUtils;
import com.huaweicloud.dis.DISConfig.BodySerializeType;
import com.huaweicloud.dis.core.restresource.CursorResource;
import com.huaweicloud.dis.core.restresource.FileResource;
import com.huaweicloud.dis.core.restresource.RecordResource;
import com.huaweicloud.dis.core.restresource.ResourcePathBuilder;
import com.huaweicloud.dis.core.restresource.StateResource;
import com.huaweicloud.dis.core.restresource.StreamResource;
import com.huaweicloud.dis.exception.DISClientException;
import com.huaweicloud.dis.iface.api.protobuf.ProtobufUtils;
import com.huaweicloud.dis.iface.data.request.GetPartitionCursorRequest;
import com.huaweicloud.dis.iface.data.request.GetRecordsRequest;
import com.huaweicloud.dis.iface.data.request.PutRecordRequest;
import com.huaweicloud.dis.iface.data.request.PutRecordsRequest;
import com.huaweicloud.dis.iface.data.request.PutRecordsRequestEntry;
import com.huaweicloud.dis.iface.data.request.QueryFileState;
import com.huaweicloud.dis.iface.data.response.FileUploadResult;
import com.huaweicloud.dis.iface.data.response.GetPartitionCursorResult;
import com.huaweicloud.dis.iface.data.response.GetRecordsResult;
import com.huaweicloud.dis.iface.data.response.PutRecordResult;
import com.huaweicloud.dis.iface.data.response.PutRecordsResult;
import com.huaweicloud.dis.iface.data.response.PutRecordsResultEntry;
import com.huaweicloud.dis.iface.data.response.Record;
import com.huaweicloud.dis.iface.stream.request.CreateStreamRequest;
import com.huaweicloud.dis.iface.stream.request.DeleteStreamRequest;
import com.huaweicloud.dis.iface.stream.request.DescribeStreamRequest;
import com.huaweicloud.dis.iface.stream.request.ListStreamsRequest;
import com.huaweicloud.dis.iface.stream.response.CreateStreamResult;
import com.huaweicloud.dis.iface.stream.response.DeleteStreamResult;
import com.huaweicloud.dis.iface.stream.response.DescribeStreamResult;
import com.huaweicloud.dis.iface.stream.response.ListStreamsResult;
import com.huaweicloud.dis.util.ExponentialBackOff;
import com.huaweicloud.dis.util.RestClientWrapper;
import com.huaweicloud.dis.util.Utils;
import com.huaweicloud.dis.util.config.IConfigProvider;
import com.huaweicloud.dis.util.encrypt.EncryptUtils;

public class DISClient implements DIS
{
    private static final Logger LOG = LoggerFactory.getLogger(DISClient.class);

    protected static final String HTTP_X_PROJECT_ID = "X-Project-Id";

    protected static final String HTTP_X_SECURITY_TOKEN = "X-Security-Token";

    protected String region;
    
    protected DISConfig disConfig;
    
    protected Credentials credentials;
    
    protected ReentrantLock recordsRetryLock = new ReentrantLock();
    
    public DISClient(DISConfig disConfig)
    {
        this.disConfig = configUpdate(DISConfig.buildConfig(disConfig));
        this.credentials = new BasicCredentials(this.disConfig.getAK(), this.disConfig.getSK());
        this.region = this.disConfig.getRegion();
        check();
    }
    
    /**
     * @deprecated use {@link DISClientBuilder#defaultClient()}
     */
    public DISClient()
    {
        this.disConfig = configUpdate(DISConfig.buildDefaultConfig());
        this.credentials = new BasicCredentials(disConfig.getAK(), disConfig.getSK());
        this.region = disConfig.getRegion();
        check();
    }    
    
    @Override
    public PutRecordsResult putRecords(PutRecordsRequest putRecordsParam)
    {
        return innerPutRecordsWithRetry(putRecordsParam);
    }

    protected PutRecordsResult innerPutRecordsWithRetry(PutRecordsRequest putRecordsParam)
    {
        PutRecordsResult putRecordsResult = null;
        PutRecordsResultEntry[] putRecordsResultEntryList = null;
        Integer[] retryIndex = null;
        PutRecordsRequest retryPutRecordsRequest = putRecordsParam;
        
        int retryCount = -1;
        int currentFailed = 0;
        ExponentialBackOff backOff = null;
        try
        {
            do
            {
                retryCount++;
                if (retryCount > 0)
                {
                    // 等待一段时间再发起重试
                    if (backOff == null)
                    {
                        recordsRetryLock.lock();
                        LOG.trace("Put records retry lock.");
                        backOff = new ExponentialBackOff(ExponentialBackOff.DEFAULT_INITIAL_INTERVAL,
                            ExponentialBackOff.DEFAULT_MULTIPLIER, disConfig.getBackOffMaxIntervalMs(),
                            ExponentialBackOff.DEFAULT_MAX_ELAPSED_TIME);
                    }
                    
                    if (putRecordsResult != null && currentFailed != putRecordsResult.getRecords().size())
                    {
                        // 部分失败则重置退避时间
                        backOff.resetCurrentInterval();
                    }
                    
                    long sleepMs = backOff.getNextBackOff();
                    
                    if (retryPutRecordsRequest.getRecords().size() > 0)
                    {
                        LOG.debug(
                            "Put {} records but {} failed, will re-try after backoff {} ms, current retry count is {}.",
                            putRecordsResult != null ? putRecordsResult.getRecords().size()
                                : putRecordsParam.getRecords().size(),
                            currentFailed,
                            sleepMs,
                            retryCount);
                    }
                    
                    backOff.backOff(sleepMs);
                }
                
                try
                {
                    putRecordsResult = innerPutRecords(retryPutRecordsRequest);
                }
                catch (Throwable t)
                {
                    if (putRecordsResultEntryList != null)
                    {
                        LOG.error(t.getMessage(), t);
                        break;
                    }
                    throw t;
                }
                
                if (putRecordsResult != null)
                {
                    currentFailed = putRecordsResult.getFailedRecordCount().get();
                    
                    if (putRecordsResultEntryList == null && currentFailed == 0 || disConfig.getRecordsRetries() == 0)
                    {
                        // 第一次发送全部成功或者不需要重试，则直接返回结果
                        return putRecordsResult;
                    }
                    
                    if (putRecordsResultEntryList == null)
                    {
                        // 存在发送失败的情况，需要重试，则使用数组来汇总每次请求后的结果。
                        putRecordsResultEntryList = new PutRecordsResultEntry[putRecordsParam.getRecords().size()];
                    }
                    
                    // 需要重试发送数据的原始下标
                    List<Integer> retryIndexTemp = new ArrayList<>(currentFailed);
                    
                    if (currentFailed > 0)
                    {
                        // 初始化重试发送的数据请求
                        retryPutRecordsRequest = new PutRecordsRequest();
                        retryPutRecordsRequest.setStreamName(putRecordsParam.getStreamName());
                        retryPutRecordsRequest.setRecords(new ArrayList<>(currentFailed));
                    }
                    
                    // 对每条结果分析，更新结果数据
                    for (int i = 0; i < putRecordsResult.getRecords().size(); i++)
                    {
                        // 获取重试数据在原始数据中的下标位置
                        int originalIndex = retryIndex == null ? i : retryIndex[i];
                        PutRecordsResultEntry putRecordsResultEntry = putRecordsResult.getRecords().get(i);
                        // 对所有异常进行重试 && "DIS.4303".equals(putRecordsResultEntry.getErrorCode())
                        if (!StringUtils.isNullOrEmpty(putRecordsResultEntry.getErrorCode()))
                        {
                            retryIndexTemp.add(originalIndex);
                            retryPutRecordsRequest.getRecords().add(putRecordsParam.getRecords().get(originalIndex));
                        }
                        putRecordsResultEntryList[originalIndex] = putRecordsResultEntry;
                    }
                    retryIndex = retryIndexTemp.size() > 0 ? retryIndexTemp.toArray(new Integer[retryIndexTemp.size()])
                        : new Integer[0];
                }
            } while ((retryIndex == null || retryIndex.length > 0) && retryCount < disConfig.getRecordsRetries());
        }
        finally
        {
            if (retryCount > 0)
            {
                recordsRetryLock.unlock();
                LOG.trace("Put records retry unlock.");
            }
        }
        putRecordsResult = new PutRecordsResult();
        if (retryIndex == null)
        {
            // 不可能存在此情况，完全没有发送出去会直接抛出异常
            putRecordsResult.setFailedRecordCount(new AtomicInteger(putRecordsParam.getRecords().size()));
        }
        else
        {
            putRecordsResult.setFailedRecordCount(new AtomicInteger(retryIndex.length));
            putRecordsResult.setRecords(Arrays.asList(putRecordsResultEntryList));
        }
        
        return putRecordsResult;
    }
    
    /**
     * Internal API
     */
    protected final PutRecordsResult innerPutRecords(PutRecordsRequest putRecordsParam)
    {
        if (isEncrypt())
        {
            if (putRecordsParam.getRecords() != null)
            {
                for (PutRecordsRequestEntry record : putRecordsParam.getRecords())
                {
                    record.setData(encrypt(record.getData()));
                }
            }
        }
        
        Request<HttpRequest> request = new DefaultRequest<>(Constants.SERVICENAME);
        request.setHttpMethod(HttpMethodName.POST);
        
        final String resourcePath =
            ResourcePathBuilder.standard()
                .withProjectId(disConfig.getProjectId())
                .withResource(new RecordResource(null))
                .build();
        request.setResourcePath(resourcePath);
        setEndpoint(request, disConfig.getEndpoint());
        if(BodySerializeType.protobuf.equals(disConfig.getBodySerializeType())){            
            request.addHeader("Content-Type", "application/x-protobuf; charset=utf-8");
            
            com.huaweicloud.dis.iface.api.protobuf.Message.PutRecordsRequest protoRequest = ProtobufUtils.toProtobufPutRecordsRequest(putRecordsParam);
            
            com.huaweicloud.dis.iface.api.protobuf.Message.PutRecordsResult putRecordsResult = request(protoRequest.toByteArray(), request, com.huaweicloud.dis.iface.api.protobuf.Message.PutRecordsResult.class);            
            
            PutRecordsResult result = ProtobufUtils.toPutRecordsResult(putRecordsResult);
            
            return result;
            
        }else{
            return request(putRecordsParam, request, PutRecordsResult.class);
        }
    }

    
    
    @Override
    public GetPartitionCursorResult getPartitionCursor(GetPartitionCursorRequest getPartitionCursorParam)
    {
        return innerGetPartitionCursor(getPartitionCursorParam);
    }
    
    /**
     * Internal API
     */
    protected final GetPartitionCursorResult innerGetPartitionCursor(GetPartitionCursorRequest getPartitionCursorParam)
    {
        Request<HttpRequest> request = new DefaultRequest<>(Constants.SERVICENAME);
        request.setHttpMethod(HttpMethodName.GET);
        
        final String resourcePath =
            ResourcePathBuilder.standard()
                .withProjectId(disConfig.getProjectId())
                .withResource(new CursorResource(null))
                .build();
        request.setResourcePath(resourcePath);
        setEndpoint(request, disConfig.getEndpoint());
        
        return request(getPartitionCursorParam, request, GetPartitionCursorResult.class);
    }
    
    @Override
    public GetRecordsResult getRecords(GetRecordsRequest getRecordsParam)
    {
        return innerGetRecords(getRecordsParam);
    }
    
    /**
     * Internal API
     */
    protected final GetRecordsResult innerGetRecords(GetRecordsRequest getRecordsParam)
    {
        Request<HttpRequest> request = new DefaultRequest<>(Constants.SERVICENAME);
        request.setHttpMethod(HttpMethodName.GET);
        
        final String resourcePath =
            ResourcePathBuilder.standard()
                .withProjectId(disConfig.getProjectId())
                .withResource(new RecordResource(null))
                .build();
        request.setResourcePath(resourcePath);
        setEndpoint(request, disConfig.getEndpoint());

        GetRecordsResult result;
        
        if(BodySerializeType.protobuf.equals(disConfig.getBodySerializeType())){            
            request.addHeader("Content-Type", "application/x-protobuf; charset=utf-8");
            
            com.huaweicloud.dis.iface.api.protobuf.Message.GetRecordsResult protoResult = request(getRecordsParam, request, com.huaweicloud.dis.iface.api.protobuf.Message.GetRecordsResult.class);
            result = ProtobufUtils.toGetRecordsResult(protoResult);
        }else{
            result = request(getRecordsParam, request, GetRecordsResult.class);
        }

        if (isEncrypt())
        {
            List<Record> records = result.getRecords();
            if (records != null)
            {
                for (Record record : records)
                {
                    record.setData(decrypt(record.getData()));
                }
            }
        }
        
        return result;
    }
    
    private boolean isEncrypt()
    {
        return disConfig.getIsDefaultDataEncryptEnabled() && !StringUtils.isNullOrEmpty(disConfig.getDataPassword());
    }
    
    private ByteBuffer encrypt(ByteBuffer src)
    {
        String cipher = null;
        try
        {
            cipher = EncryptUtils.gen(new String[] {disConfig.getDataPassword()}, src.array());
        }
        catch (InvalidKeyException | NoSuchAlgorithmException | InvalidKeySpecException | NoSuchPaddingException
            | IllegalBlockSizeException | BadPaddingException | InvalidAlgorithmParameterException e)
        {
            LOG.error(e.getMessage(), e);
            throw new RuntimeException(e);
        }
        return ByteBuffer.wrap(cipher.getBytes());
    }
    
    private ByteBuffer decrypt(ByteBuffer cipher)
    {
        Charset utf8 = Charset.forName("UTF-8");
        String src;
        try
        {
            src = EncryptUtils.dec(new String[] {disConfig.getDataPassword()}, new String(cipher.array(), utf8));
        }
        catch (InvalidKeyException | NoSuchAlgorithmException | InvalidKeySpecException | NoSuchPaddingException
            | IllegalBlockSizeException | BadPaddingException | InvalidAlgorithmParameterException e)
        {
            LOG.error(e.getMessage(), e);
            throw new RuntimeException(e);
        }
        
        return ByteBuffer.wrap(src.getBytes(utf8));
    }
    
    // protected <T> T request(Object param, Object target, Class<T> clazz){
    // check();
    //
    // String result = new RestClientWrapper(new DefaultRequest<>(Constants.SERVICENAME), disConfig).request(param,
    // credentials.getAccessKeyId(), credentials.getSecretKey(), region);
    // return JsonUtils.jsonToObj(result, clazz);
    // }

    protected <T> T request(Object param, Request<HttpRequest> request, Class<T> clazz)
    {
        request.addHeader(HTTP_X_PROJECT_ID, disConfig.getProjectId());
        
        String securityToken = disConfig.getSecurityToken();
        if (!StringUtils.isNullOrEmpty(securityToken))
        {
            request.addHeader(HTTP_X_SECURITY_TOKEN, securityToken);
        }
        
        // 发送请求
        return new RestClientWrapper(request, disConfig)
            .request(param, credentials.getAccessKeyId(), credentials.getSecretKey(), region, clazz);
    }
    
    private void check()
    {
        if (credentials == null)
        {
            throw new DISClientException("credentials can not be null.");
        }
        
        if (StringUtils.isNullOrEmpty(credentials.getAccessKeyId()))
        {
            throw new DISClientException("credentials ak can not be null.");
        }
        
        if (StringUtils.isNullOrEmpty(credentials.getSecretKey()))
        {
            throw new DISClientException("credentials sk can not be null.");
        }
        
        if (StringUtils.isNullOrEmpty(region))
        {
            throw new DISClientException("region can not be null.");
        }
        
        if (StringUtils.isNullOrEmpty(disConfig.getProjectId()))
        {
            throw new RuntimeException("project id can not be null.");
        }
        
        String endpoint = disConfig.getEndpoint();
        if (StringUtils.isNullOrEmpty(endpoint))
        {
            throw new DISClientException("endpoint can not be null.");
        }
        if (!Utils.isValidEndpoint(endpoint))
        {
            throw new DISClientException("invalid endpoint.");
        }
    }
    
    private void setEndpoint(Request<HttpRequest> request, String endpointStr)
    {
        URI endpoint;
        try
        {
            endpoint = new URI(endpointStr);
        }
        catch (URISyntaxException e)
        {
            throw new RuntimeException(e);
        }
        
        request.setEndpoint(endpoint);
    }
    
    // ###################### delegate IStreamService #########################
    @Override
    public CreateStreamResult createStream(CreateStreamRequest createStreamRequest)
    {
        Request<HttpRequest> request = new DefaultRequest<>(Constants.SERVICENAME);
        request.setHttpMethod(HttpMethodName.POST);
        
        final String resourcePath =
            ResourcePathBuilder.standard()
                .withProjectId(disConfig.getProjectId())
                .withResource(new StreamResource(null, null))
                .build();
        request.setResourcePath(resourcePath);
        setEndpoint(request, disConfig.getManagerEndpoint());
        
        CreateStreamResult result = request(createStreamRequest, request, CreateStreamResult.class);
        
        return result;
    }
    
    
    @Override
    public DescribeStreamResult describeStream(DescribeStreamRequest describeStreamRequest)
    {
        return innerDescribeStream(describeStreamRequest);
    }
    
    /**
     * Internal API
     */
    protected final DescribeStreamResult innerDescribeStream(DescribeStreamRequest describeStreamRequest)
    {
        Request<HttpRequest> request = new DefaultRequest<>(Constants.SERVICENAME);
        request.setHttpMethod(HttpMethodName.GET);
        
        final String resourcePath =
            ResourcePathBuilder.standard()
                .withProjectId(disConfig.getProjectId())
                .withResource(new StreamResource(null, describeStreamRequest.getStreamName()))
                .build();
        request.setResourcePath(resourcePath);
        setEndpoint(request, disConfig.getManagerEndpoint());
        
        DescribeStreamResult result = request(describeStreamRequest, request, DescribeStreamResult.class);
        
        return result;
    }
    

    
    @Override
    public DeleteStreamResult deleteStream(DeleteStreamRequest deleteStreamRequest)
    {
        Request<HttpRequest> request = new DefaultRequest<>(Constants.SERVICENAME);
        request.setHttpMethod(HttpMethodName.DELETE);
        
        final String resourcePath =
            ResourcePathBuilder.standard()
                .withProjectId(disConfig.getProjectId())
                .withResource(new StreamResource(null, deleteStreamRequest.getStreamName()))
                .build();
        
        request.setResourcePath(resourcePath);
        setEndpoint(request, disConfig.getManagerEndpoint());
        
        DeleteStreamResult result = request(deleteStreamRequest, request, DeleteStreamResult.class);
        return result;
    }
    
    @Override
    public ListStreamsResult listStreams(ListStreamsRequest listStreamsRequest)
    {
        Request<HttpRequest> request = new DefaultRequest<>(Constants.SERVICENAME);
        request.setHttpMethod(HttpMethodName.GET);
        
        final String resourcePath =
            ResourcePathBuilder.standard()
                .withProjectId(disConfig.getProjectId())
                .withResource(new StreamResource(null, null))
                .build();
        
        request.setResourcePath(resourcePath);
        setEndpoint(request, disConfig.getManagerEndpoint());
        
        ListStreamsResult result = request(listStreamsRequest, request, ListStreamsResult.class);
        return result;
    }
 
    
    
    
    
    
    
    
    
    //##################### extended Method ######################
    @Override
    public PutRecordResult putRecord(PutRecordRequest putRecordParam)
    {
        if (isEncrypt())
        {
            putRecordParam.setData(encrypt(putRecordParam.getData()));
        }
        
        return toPutRecordResult(putRecords(toPutRecordsRequest(putRecordParam)));
    }
    
    private PutRecordsRequest toPutRecordsRequest(PutRecordRequest putRecordRequest)
    {
        PutRecordsRequest putRecordsRequest = new PutRecordsRequest();
        putRecordsRequest.setStreamName(putRecordRequest.getStreamName());
        
        List<PutRecordsRequestEntry> putRecordsRequestEntryList = new ArrayList<PutRecordsRequestEntry>();
        PutRecordsRequestEntry putRecordsRequestEntry = new PutRecordsRequestEntry();
        putRecordsRequestEntry.setData(putRecordRequest.getData());
        putRecordsRequestEntry.setPartitionKey(putRecordRequest.getPartitionKey());
        putRecordsRequestEntry.setTimestamp(putRecordRequest.getTimestamp());
        putRecordsRequestEntryList.add(putRecordsRequestEntry);
        
        putRecordsRequest.setRecords(putRecordsRequestEntryList);
        
        return putRecordsRequest;
    }
    
    private PutRecordResult toPutRecordResult(PutRecordsResult putRecordsResult)
    {
        if (null != putRecordsResult && null != putRecordsResult.getRecords() && putRecordsResult.getRecords().size() > 0)
        {
            List<PutRecordsResultEntry> records = putRecordsResult.getRecords();
            PutRecordsResultEntry record = records.get(0);
            
            PutRecordResult result = new PutRecordResult();
            result.setPartitionId(record.getPartitionId());
            result.setSequenceNumber(record.getSequenceNumber());
            
            return result;
        }
        
        return null;
    }

    /**
     * Internal API
     */
    protected final FileUploadResult innerGetFileUploadResult(QueryFileState queryFileState)
    {
        Request<HttpRequest> request = new DefaultRequest<>(Constants.SERVICENAME);
        request.setHttpMethod(HttpMethodName.GET);
        
        final String resourcePath = ResourcePathBuilder.standard()
            .withProjectId(disConfig.getProjectId())
            .withResource(new FileResource(queryFileState.getDeliverDataId()))
            .withResource(new StateResource(null))
            .build();
        request.setResourcePath(resourcePath);
        setEndpoint(request, disConfig.getEndpoint());
        
        return request(queryFileState, request, FileUploadResult.class);
    }

    /**
     * 开放配置修改接口，用户自定义实现IConfigProvider，操作disConfig中的数据，可以进行配置加解密等操作
     *
     * @param disConfig
     * @return
     */
    private DISConfig configUpdate(DISConfig disConfig)
    {
        // Provider转换
        String configProviderClass = disConfig.get(DISConfig.PROPERTY_CONFIG_PROVIDER_CLASS, null);
        if (!StringUtils.isNullOrEmpty(configProviderClass))
        {
            try
            {
                IConfigProvider configProvider = (IConfigProvider)Class.forName(configProviderClass).newInstance();
                disConfig = configProvider.configUpdate(disConfig);
            }
            catch (Exception e)
            {
                throw new IllegalArgumentException(
                    "Failed to call IConfigProvider[" + configProviderClass + "], error [" + e.toString() + "]", e);
            }
        }
        return disConfig;
    }
}