package com.netflix.appinfo;

/**
 * This provides a more granular healthcheck contract than the existing {@link HealthCheckCallback}
 *
 * @author Nitesh Kant
 */
public interface HealthCheckHandler { // 健康检查处理器接口，目前暂未提供合适的默认实现，唯一提供的 com.netflix.appinfo.HealthCheckCallbackToHandlerBridge，用于将 HealthCheckCallback 桥接成 HealthCheckHandler

    InstanceInfo.InstanceStatus getStatus(InstanceInfo.InstanceStatus currentStatus);

}
