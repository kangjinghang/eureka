package com.netflix.discovery.shared.transport.jersey;

import java.util.Collection;
import java.util.Optional;

import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.SSLContext;

import com.netflix.appinfo.InstanceInfo;
import com.netflix.discovery.EurekaClientConfig;
import com.netflix.discovery.shared.transport.TransportClientFactory;
// 生成 Jersey 客户端工厂的工厂接口。目前有 Jersey1TransportClientFactories 、Jersey2TransportClientFactories 两个实现
public interface TransportClientFactories<F> {
    
    @Deprecated
    public TransportClientFactory newTransportClientFactory(final Collection<F> additionalFilters,
                                                                   final EurekaJerseyClient providedJerseyClient);

    public TransportClientFactory newTransportClientFactory(final EurekaClientConfig clientConfig,
                                                                   final Collection<F> additionalFilters,
                                                                   final InstanceInfo myInstanceInfo);
    // 创建 registrationClient 和 queryClient 公用的委托的 EurekaHttpClientFactory
    public TransportClientFactory newTransportClientFactory(final EurekaClientConfig clientConfig,
            final Collection<F> additionalFilters,
            final InstanceInfo myInstanceInfo,
            final Optional<SSLContext> sslContext,
            final Optional<HostnameVerifier> hostnameVerifier);
}