package com.netflix.appinfo.providers;

import javax.inject.Singleton;
import javax.inject.Provider;
import java.util.Map;

import com.google.inject.Inject;
import com.netflix.appinfo.DataCenterInfo;
import com.netflix.appinfo.EurekaInstanceConfig;
import com.netflix.appinfo.InstanceInfo;
import com.netflix.appinfo.InstanceInfo.InstanceStatus;
import com.netflix.appinfo.InstanceInfo.PortType;
import com.netflix.appinfo.LeaseInfo;
import com.netflix.appinfo.RefreshableInstanceConfig;
import com.netflix.appinfo.UniqueIdentifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * InstanceInfo provider that constructs the InstanceInfo this this instance using
 * EurekaInstanceConfig.
 *
 * This provider is @Singleton scope as it provides the InstanceInfo for both DiscoveryClient
 * and ApplicationInfoManager, and need to provide the same InstanceInfo to both.
 *
 * @author elandau
 *
 */
@Singleton
public class EurekaConfigBasedInstanceInfoProvider implements Provider<InstanceInfo> { // 基于 EurekaInstanceConfig 创建 InstanceInfo 的工厂
    private static final Logger LOG = LoggerFactory.getLogger(EurekaConfigBasedInstanceInfoProvider.class);

    private final EurekaInstanceConfig config;

    private InstanceInfo instanceInfo;

    @Inject(optional = true)
    private VipAddressResolver vipAddressResolver = null;

    @Inject
    public EurekaConfigBasedInstanceInfoProvider(EurekaInstanceConfig config) {
        this.config = config; // 设置生成 InstanceInfo 的 EurekaInstanceConfig 配置
    }
    // 根据 EurekaInstanceConfig 创建 InstanceInfo。InstanceInfo 的绝大数属性和 EurekaInstanceConfig 是一致的
    @Override
    public synchronized InstanceInfo get() {
        if (instanceInfo == null) {
            // Build the lease information to be passed to the server based on config
            LeaseInfo.Builder leaseInfoBuilder = LeaseInfo.Builder.newBuilder() // 创建 租约信息构建器，并设置属性
                    .setRenewalIntervalInSecs(config.getLeaseRenewalIntervalInSeconds())
                    .setDurationInSecs(config.getLeaseExpirationDurationInSeconds());

            if (vipAddressResolver == null) {
                vipAddressResolver = new Archaius1VipAddressResolver(); // 创建 VIP地址解析器
            }

            // Builder the instance information to be registered with eureka server
            InstanceInfo.Builder builder = InstanceInfo.Builder.newBuilder(vipAddressResolver); // 创建 应用实例信息构建器

            // set the appropriate id for the InstanceInfo, falling back to datacenter Id if applicable, else hostname
            String instanceId = config.getInstanceId(); // 应用实例编号
            if (instanceId == null || instanceId.isEmpty()) {
                DataCenterInfo dataCenterInfo = config.getDataCenterInfo();
                if (dataCenterInfo instanceof UniqueIdentifier) {
                    instanceId = ((UniqueIdentifier) dataCenterInfo).getId();
                } else {
                    instanceId = config.getHostName(false); // 通常是 hostname
                }
            }
            // 获得 主机名
            String defaultAddress;
            if (config instanceof RefreshableInstanceConfig) { // 默认的是MyDataCenterInstanceConfig，不会走这个分支
                // Refresh AWS data center info, and return up to date address
                defaultAddress = ((RefreshableInstanceConfig) config).resolveDefaultAddress(false);
            } else {
                defaultAddress = config.getHostName(false);
            }

            // fail safe
            if (defaultAddress == null || defaultAddress.isEmpty()) {
                defaultAddress = config.getIpAddress();
            }
            // 设置 应用实例信息构建器 的 属性
            builder.setNamespace(config.getNamespace())
                    .setInstanceId(instanceId)
                    .setAppName(config.getAppname())
                    .setAppGroupName(config.getAppGroupName())
                    .setDataCenterInfo(config.getDataCenterInfo())
                    .setIPAddr(config.getIpAddress())
                    .setHostName(defaultAddress)  // 主机名
                    .setPort(config.getNonSecurePort())
                    .enablePort(PortType.UNSECURE, config.isNonSecurePortEnabled())
                    .setSecurePort(config.getSecurePort())
                    .enablePort(PortType.SECURE, config.getSecurePortEnabled())
                    .setVIPAddress(config.getVirtualHostName())
                    .setSecureVIPAddress(config.getSecureVirtualHostName())  // VIP 地址
                    .setHomePageUrl(config.getHomePageUrlPath(), config.getHomePageUrl())
                    .setStatusPageUrl(config.getStatusPageUrlPath(), config.getStatusPageUrl())
                    .setASGName(config.getASGName())
                    .setHealthCheckUrls(config.getHealthCheckUrlPath(),
                            config.getHealthCheckUrl(), config.getSecureHealthCheckUrl());


            // Start off with the STARTING state to avoid traffic 应用初始化后是否开启流量接入。有时，应用程序可能需要在准备好接收流量之前进行一些预热
            if (!config.isInstanceEnabledOnit()) { // 应用初始化后不开启，应用实例处于 STARTING 状态。后续可以通过调用 ApplicationInfoManager#setInstanceStatus(...) 方法改变应用实例状态
                InstanceStatus initialStatus = InstanceStatus.STARTING;
                LOG.info("Setting initial instance status as: {}", initialStatus);
                builder.setStatus(initialStatus);
            } else { // 应用初始化后开启，应用实例处于 UP 状态。
                LOG.info("Setting initial instance status as: {}. This may be too early for the instance to advertise "
                         + "itself as available. You would instead want to control this via a healthcheck handler.",
                         InstanceStatus.UP);
            }
            // 设置 应用实例信息构建器 的 元数据( Metadata )集合
            // Add any user-specific metadata information
            for (Map.Entry<String, String> mapEntry : config.getMetadataMap().entrySet()) {
                String key = mapEntry.getKey();
                String value = mapEntry.getValue();
                // only add the metadata if the value is present
                if (value != null && !value.isEmpty()) {
                    builder.add(key, value);
                }
            }
            // 创建 应用实例信息
            instanceInfo = builder.build();
            instanceInfo.setLeaseInfo(leaseInfoBuilder.build()); // 设置 应用实例信息 的 租约信息
        }
        return instanceInfo;
    }

}
