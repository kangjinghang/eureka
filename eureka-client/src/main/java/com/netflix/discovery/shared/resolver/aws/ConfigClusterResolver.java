package com.netflix.discovery.shared.resolver.aws;

import com.netflix.appinfo.InstanceInfo;
import com.netflix.discovery.EurekaClientConfig;
import com.netflix.discovery.endpoint.EndpointUtils;
import com.netflix.discovery.shared.resolver.ClusterResolver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * A resolver that on-demand resolves from configuration what the endpoints should be.
 *
 * @author David Liu
 */
public class ConfigClusterResolver implements ClusterResolver<AwsEndpoint> { // 基于配置文件的集群解析器
    private static final Logger logger = LoggerFactory.getLogger(ConfigClusterResolver.class);

    private final EurekaClientConfig clientConfig;
    private final InstanceInfo myInstanceInfo;

    public ConfigClusterResolver(EurekaClientConfig clientConfig, InstanceInfo myInstanceInfo) {
        this.clientConfig = clientConfig;
        this.myInstanceInfo = myInstanceInfo;
    }

    @Override
    public String getRegion() {
        return clientConfig.getRegion();
    }

    @Override
    public List<AwsEndpoint> getClusterEndpoints() {
        if (clientConfig.shouldUseDnsForFetchingServiceUrls()) { // 使用 DNS 获取 EndPoint。必须配置 eureka.shouldUseDns=true ，开启基于 DNS 获取 EndPoint 集群
            if (logger.isInfoEnabled()) {
                logger.info("Resolving eureka endpoints via DNS: {}", getDNSName());
            }
            return getClusterEndpointsFromDns();
        } else {
            logger.info("Resolving eureka endpoints via configuration");
            return getClusterEndpointsFromConfig(); // 直接配置实际访问地址
        }
    }
    // 使用 DNS 获取 EndPoint
    private List<AwsEndpoint> getClusterEndpointsFromDns() {
        String discoveryDnsName = getDNSName();  // 获取 集群根地址。必须配置 eureka.eurekaServer.domainName=${xxxxx} ，配置集群根地址
        int port = Integer.parseInt(clientConfig.getEurekaServerPort()); // 端口

        // cheap enough so just re-use
        DnsTxtRecordClusterResolver dnsResolver = new DnsTxtRecordClusterResolver(
                getRegion(),
                discoveryDnsName,
                true, // 解析 zone
                port,
                false,
                clientConfig.getEurekaServerURLContext()
        );

        List<AwsEndpoint> endpoints = dnsResolver.getClusterEndpoints(); // 调用 DnsTxtRecordClusterResolver 解析 EndPoint

        if (endpoints.isEmpty()) {
            logger.error("Cannot resolve to any endpoints for the given dnsName: {}", discoveryDnsName);
        }

        return endpoints;
    }
    // 直接配置实际访问地址
    private List<AwsEndpoint> getClusterEndpointsFromConfig() {
        String[] availZones = clientConfig.getAvailabilityZones(clientConfig.getRegion()); // 获得 可用区 数组，通过 eureka.${REGION}.availabilityZones 配置
        String myZone = InstanceInfo.getZone(availZones, myInstanceInfo); // 获取 应用实例自己 的 可用区
        // 获得 可用区与 serviceUrls 的映射
        Map<String, List<String>> serviceUrls = EndpointUtils
                .getServiceUrlsMapFromConfig(clientConfig, myZone, clientConfig.shouldPreferSameZoneEureka());
        // 拼装 EndPoint 集群结果
        List<AwsEndpoint> endpoints = new ArrayList<>();
        for (String zone : serviceUrls.keySet()) {
            for (String url : serviceUrls.get(zone)) {
                try {
                    endpoints.add(new AwsEndpoint(url, getRegion(), zone));
                } catch (Exception ignore) {
                    logger.warn("Invalid eureka server URI: {}; removing from the server pool", url);
                }
            }
        }
        // 打印日志，EndPoint 集群
        logger.debug("Config resolved to {}", endpoints);
        // 打印日志，解析结果为空
        if (endpoints.isEmpty()) {
            logger.error("Cannot resolve to any endpoints from provided configuration: {}", serviceUrls);
        }

        return endpoints;
    }

    private String getDNSName() {
        return "txt." + getRegion() + '.' + clientConfig.getEurekaServerDNSName();
    }
}
