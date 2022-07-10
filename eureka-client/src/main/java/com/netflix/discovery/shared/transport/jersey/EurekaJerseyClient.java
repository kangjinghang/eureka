package com.netflix.discovery.shared.transport.jersey;

import com.sun.jersey.client.apache4.ApacheHttpClient4;

/**
 * @author David Liu
 */
public interface EurekaJerseyClient { // EurekaJerseyClient 接口
    // ApacheHttpClient4 基于 Apache HttpClient4 实现的 Jersey Client
    ApacheHttpClient4 getClient();

    /**
     * Clean up resources.
     */
    void destroyResources();
}
