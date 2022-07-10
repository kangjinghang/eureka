package com.netflix.discovery.shared.resolver;

/**
 * @author David Liu
 */
public interface ClosableResolver<T extends EurekaEndpoint> extends ClusterResolver<T> {  // 可关闭的解析器接口
    void shutdown(); // 关闭
}
