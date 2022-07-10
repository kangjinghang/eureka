package com.netflix.discovery.shared.transport.jersey;

import com.netflix.appinfo.InstanceInfo;
import com.netflix.appinfo.InstanceInfo.InstanceStatus;
import com.netflix.discovery.shared.Application;
import com.netflix.discovery.shared.Applications;
import com.netflix.discovery.shared.transport.EurekaHttpClient;
import com.netflix.discovery.shared.transport.EurekaHttpResponse;
import com.netflix.discovery.shared.transport.EurekaHttpResponse.EurekaHttpResponseBuilder;
import com.netflix.discovery.util.StringUtil;
import com.sun.jersey.api.client.Client;
import com.sun.jersey.api.client.ClientResponse;
import com.sun.jersey.api.client.WebResource;
import com.sun.jersey.api.client.WebResource.Builder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.MultivaluedMap;
import javax.ws.rs.core.Response.Status;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;

import static com.netflix.discovery.shared.transport.EurekaHttpResponse.anEurekaHttpResponse;

/**
 * @author Tomasz Bak
 */
public abstract class AbstractJerseyEurekaHttpClient implements EurekaHttpClient { // 实现 EurekaHttpClient 的抽象类，真正实现了具体的 Eureka-Server API 调用方法

    private static final Logger logger = LoggerFactory.getLogger(AbstractJerseyEurekaHttpClient.class);
    protected static final String HTML = "html";
    // Jersey Client
    protected final Client jerseyClient;
    protected final String serviceUrl; // 请求的 Eureka-Server 地址

    protected AbstractJerseyEurekaHttpClient(Client jerseyClient, String serviceUrl) {
        this.jerseyClient = jerseyClient;
        this.serviceUrl = serviceUrl;
        logger.debug("Created client for url: {}", serviceUrl);
    }
    // 实现向 Eureka-Server 注册应用实例
    @Override
    public EurekaHttpResponse<Void> register(InstanceInfo info) {
        String urlPath = "apps/" + info.getAppName(); // 设置 请求地址
        ClientResponse response = null;
        try {
            Builder resourceBuilder = jerseyClient.resource(serviceUrl).path(urlPath).getRequestBuilder();
            addExtraHeaders(resourceBuilder); // 设置 请求头
            response = resourceBuilder // 请求 Eureka-Server
                    .header("Accept-Encoding", "gzip")  // GZIP
                    .type(MediaType.APPLICATION_JSON_TYPE)  // 请求参数格式 JSON
                    .accept(MediaType.APPLICATION_JSON) // 响应结果格式 JSON
                    .post(ClientResponse.class, info); // POST 请求 Eureka-Server 的 apps/${APP_NAME} 接口，参数为 InstanceInfo ，实现注册实例信息的注册
            return anEurekaHttpResponse(response.getStatus()).headers(headersOf(response)).build(); // 创建 EurekaHttpResponse
        } finally {
            if (logger.isDebugEnabled()) {
                logger.debug("Jersey HTTP POST {}{} with instance {}; statusCode={}", serviceUrl, urlPath, info.getId(),
                        response == null ? "N/A" : response.getStatus());
            }
            if (response != null) {
                response.close();
            }
        }
    }
    // 调用 AbstractJerseyEurekaHttpClient#cancel(...) 方法，DELETE 请求 Eureka-Server 的 apps/${APP_NAME}/${INSTANCE_INFO_ID} 接口，实现应用实例信息的下线
    @Override
    public EurekaHttpResponse<Void> cancel(String appName, String id) {
        String urlPath = "apps/" + appName + '/' + id;
        ClientResponse response = null;
        try {
            Builder resourceBuilder = jerseyClient.resource(serviceUrl).path(urlPath).getRequestBuilder();
            addExtraHeaders(resourceBuilder);
            response = resourceBuilder.delete(ClientResponse.class);
            return anEurekaHttpResponse(response.getStatus()).headers(headersOf(response)).build();
        } finally {
            if (logger.isDebugEnabled()) {
                logger.debug("Jersey HTTP DELETE {}{}; statusCode={}", serviceUrl, urlPath, response == null ? "N/A" : response.getStatus());
            }
            if (response != null) {
                response.close();
            }
        }
    }
    // 发起续租请求
    @Override
    public EurekaHttpResponse<InstanceInfo> sendHeartBeat(String appName, String id, InstanceInfo info, InstanceStatus overriddenStatus) {
        String urlPath = "apps/" + appName + '/' + id;
        ClientResponse response = null;
        try {
            WebResource webResource = jerseyClient.resource(serviceUrl)
                    .path(urlPath)
                    .queryParam("status", info.getStatus().toString())
                    .queryParam("lastDirtyTimestamp", info.getLastDirtyTimestamp().toString());
            if (overriddenStatus != null) {
                webResource = webResource.queryParam("overriddenstatus", overriddenStatus.name());
            }
            Builder requestBuilder = webResource.getRequestBuilder();
            addExtraHeaders(requestBuilder);
            response = requestBuilder.put(ClientResponse.class); // PUT 请求 Eureka-Server 的 apps/${APP_NAME}/${INSTANCE_INFO_ID} 接口，参数为 status、lastDirtyTimestamp、overriddenstatus，实现续租
            EurekaHttpResponseBuilder<InstanceInfo> eurekaResponseBuilder = anEurekaHttpResponse(response.getStatus(), InstanceInfo.class).headers(headersOf(response));
            if (response.hasEntity() &&
                    !HTML.equals(response.getType().getSubtype())) { //don't try and deserialize random html errors from the server
                eurekaResponseBuilder.entity(response.getEntity(InstanceInfo.class));
            }
            return eurekaResponseBuilder.build();
        } finally {
            if (logger.isDebugEnabled()) {
                logger.debug("Jersey HTTP PUT {}{}; statusCode={}", serviceUrl, urlPath, response == null ? "N/A" : response.getStatus());
            }
            if (response != null) {
                response.close();
            }
        }
    }

    @Override
    public EurekaHttpResponse<Void> statusUpdate(String appName, String id, InstanceStatus newStatus, InstanceInfo info) {
        String urlPath = "apps/" + appName + '/' + id + "/status";
        ClientResponse response = null;
        try {
            Builder requestBuilder = jerseyClient.resource(serviceUrl)
                    .path(urlPath)
                    .queryParam("value", newStatus.name())
                    .queryParam("lastDirtyTimestamp", info.getLastDirtyTimestamp().toString())
                    .getRequestBuilder();
            addExtraHeaders(requestBuilder);
            response = requestBuilder.put(ClientResponse.class);
            return anEurekaHttpResponse(response.getStatus()).headers(headersOf(response)).build();
        } finally {
            if (logger.isDebugEnabled()) {
                logger.debug("Jersey HTTP PUT {}{}; statusCode={}", serviceUrl, urlPath, response == null ? "N/A" : response.getStatus());
            }
            if (response != null) {
                response.close();
            }
        }
    }

    @Override
    public EurekaHttpResponse<Void> deleteStatusOverride(String appName, String id, InstanceInfo info) {
        String urlPath = "apps/" + appName + '/' + id + "/status";
        ClientResponse response = null;
        try {
            Builder requestBuilder = jerseyClient.resource(serviceUrl)
                    .path(urlPath)
                    .queryParam("lastDirtyTimestamp", info.getLastDirtyTimestamp().toString())
                    .getRequestBuilder();
            addExtraHeaders(requestBuilder);
            response = requestBuilder.delete(ClientResponse.class);
            return anEurekaHttpResponse(response.getStatus()).headers(headersOf(response)).build();
        } finally {
            if (logger.isDebugEnabled()) {
                logger.debug("Jersey HTTP DELETE {}{}; statusCode={}", serviceUrl, urlPath, response == null ? "N/A" : response.getStatus());
            }
            if (response != null) {
                response.close();
            }
        }
    }
    // 全量获取注册信息
    @Override
    public EurekaHttpResponse<Applications> getApplications(String... regions) {
        return getApplicationsInternal("apps/", regions);
    }
    // 调用 AbstractJerseyEurekaHttpClient#getApplicationsInternal(...) 方法，GET 请求 Eureka-Server 的 apps/detla 接口，参数为 regions，返回格式为 JSON，实现增量获取注册信息
    @Override
    public EurekaHttpResponse<Applications> getDelta(String... regions) {
        return getApplicationsInternal("apps/delta", regions);
    }

    @Override
    public EurekaHttpResponse<Applications> getVip(String vipAddress, String... regions) {
        return getApplicationsInternal("vips/" + vipAddress, regions);
    }

    @Override
    public EurekaHttpResponse<Applications> getSecureVip(String secureVipAddress, String... regions) {
        return getApplicationsInternal("svips/" + secureVipAddress, regions);
    }
    // 全量获取注册信息。GET 请求 Eureka-Server 的 apps/ 接口，参数为 regions 返回格式为 JSON，实现全量获取注册信息
    private EurekaHttpResponse<Applications> getApplicationsInternal(String urlPath, String[] regions) {
        ClientResponse response = null;
        String regionsParamValue = null;
        try {
            WebResource webResource = jerseyClient.resource(serviceUrl).path(urlPath);
            if (regions != null && regions.length > 0) {
                regionsParamValue = StringUtil.join(regions);
                webResource = webResource.queryParam("regions", regionsParamValue);
            }
            Builder requestBuilder = webResource.getRequestBuilder();
            addExtraHeaders(requestBuilder);
            response = requestBuilder.accept(MediaType.APPLICATION_JSON_TYPE).get(ClientResponse.class);

            Applications applications = null;
            if (response.getStatus() == Status.OK.getStatusCode() && response.hasEntity()) {
                applications = response.getEntity(Applications.class);
            }
            return anEurekaHttpResponse(response.getStatus(), Applications.class)
                    .headers(headersOf(response))
                    .entity(applications)
                    .build();
        } finally {
            if (logger.isDebugEnabled()) {
                logger.debug("Jersey HTTP GET {}{}?{}; statusCode={}",
                        serviceUrl, urlPath,
                        regionsParamValue == null ? "" : "regions=" + regionsParamValue,
                        response == null ? "N/A" : response.getStatus()
                );
            }
            if (response != null) {
                response.close();
            }
        }
    }

    @Override
    public EurekaHttpResponse<Application> getApplication(String appName) {
        String urlPath = "apps/" + appName;
        ClientResponse response = null;
        try {
            Builder requestBuilder = jerseyClient.resource(serviceUrl).path(urlPath).getRequestBuilder();
            addExtraHeaders(requestBuilder);
            response = requestBuilder.accept(MediaType.APPLICATION_JSON_TYPE).get(ClientResponse.class);

            Application application = null;
            if (response.getStatus() == Status.OK.getStatusCode() && response.hasEntity()) {
                application = response.getEntity(Application.class);
            }
            return anEurekaHttpResponse(response.getStatus(), Application.class)
                    .headers(headersOf(response))
                    .entity(application)
                    .build();
        } finally {
            if (logger.isDebugEnabled()) {
                logger.debug("Jersey HTTP GET {}{}; statusCode={}", serviceUrl, urlPath, response == null ? "N/A" : response.getStatus());
            }
            if (response != null) {
                response.close();
            }
        }
    }

    @Override
    public EurekaHttpResponse<InstanceInfo> getInstance(String id) {
        return getInstanceInternal("instances/" + id);
    }

    @Override
    public EurekaHttpResponse<InstanceInfo> getInstance(String appName, String id) {
        return getInstanceInternal("apps/" + appName + '/' + id);
    }

    private EurekaHttpResponse<InstanceInfo> getInstanceInternal(String urlPath) {
        ClientResponse response = null;
        try {
            Builder requestBuilder = jerseyClient.resource(serviceUrl).path(urlPath).getRequestBuilder();
            addExtraHeaders(requestBuilder);
            response = requestBuilder.accept(MediaType.APPLICATION_JSON_TYPE).get(ClientResponse.class);

            InstanceInfo infoFromPeer = null;
            if (response.getStatus() == Status.OK.getStatusCode() && response.hasEntity()) {
                infoFromPeer = response.getEntity(InstanceInfo.class);
            }
            return anEurekaHttpResponse(response.getStatus(), InstanceInfo.class)
                    .headers(headersOf(response))
                    .entity(infoFromPeer)
                    .build();
        } finally {
            if (logger.isDebugEnabled()) {
                logger.debug("Jersey HTTP GET {}{}; statusCode={}", serviceUrl, urlPath, response == null ? "N/A" : response.getStatus());
            }
            if (response != null) {
                response.close();
            }
        }
    }

    @Override
    public void shutdown() {
        // Do not destroy jerseyClient, as it is owned by the corresponding EurekaHttpClientFactory
    }

    protected abstract void addExtraHeaders(Builder webResource);

    private static Map<String, String> headersOf(ClientResponse response) {
        MultivaluedMap<String, String> jerseyHeaders = response.getHeaders();
        if (jerseyHeaders == null || jerseyHeaders.isEmpty()) {
            return Collections.emptyMap();
        }
        Map<String, String> headers = new HashMap<>();
        for (Entry<String, List<String>> entry : jerseyHeaders.entrySet()) {
            if (!entry.getValue().isEmpty()) {
                headers.put(entry.getKey(), entry.getValue().get(0));
            }
        }
        return headers;
    }
}
