/*
 * Copyright 2015 Netflix, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.netflix.discovery.shared.transport.jersey;

import com.netflix.appinfo.AbstractEurekaIdentity;
import com.netflix.appinfo.EurekaAccept;
import com.netflix.appinfo.EurekaClientIdentity;
import com.netflix.appinfo.InstanceInfo;
import com.netflix.discovery.EurekaClientConfig;
import com.netflix.discovery.EurekaIdentityHeaderFilter;
import com.netflix.discovery.provider.DiscoveryJerseyProvider;
import com.netflix.discovery.shared.resolver.EurekaEndpoint;
import com.netflix.discovery.shared.transport.EurekaClientFactoryBuilder;
import com.netflix.discovery.shared.transport.EurekaHttpClient;
import com.netflix.discovery.shared.transport.TransportClientFactory;
import com.netflix.discovery.shared.transport.jersey.EurekaJerseyClientImpl.EurekaJerseyClientBuilder;
import com.sun.jersey.api.client.config.ClientConfig;
import com.sun.jersey.api.client.filter.ClientFilter;
import com.sun.jersey.api.client.filter.GZIPContentEncodingFilter;
import com.sun.jersey.client.apache4.ApacheHttpClient4;
import com.sun.jersey.client.apache4.config.ApacheHttpClient4Config;
import com.sun.jersey.client.apache4.config.DefaultApacheHttpClient4Config;
import org.apache.http.client.params.ClientPNames;
import org.apache.http.conn.scheme.Scheme;
import org.apache.http.conn.scheme.SchemeRegistry;
import org.apache.http.conn.scheme.SchemeSocketFactory;
import org.apache.http.conn.ssl.AllowAllHostnameVerifier;
import org.apache.http.conn.ssl.SSLSocketFactory;
import org.apache.http.impl.conn.tsccm.ThreadSafeClientConnManager;
import org.apache.http.params.CoreProtocolPNames;

import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.SSLContext;

import static com.netflix.discovery.util.DiscoveryBuildInfo.buildVersion;

/**
 * @author Tomasz Bak
 */
public class JerseyEurekaHttpClientFactory implements TransportClientFactory { // 创建 JerseyApplicationClient 的工厂类

    public static final String HTTP_X_DISCOVERY_ALLOW_REDIRECT = "X-Discovery-AllowRedirect";

    private final EurekaJerseyClient jerseyClient;
    private final ApacheHttpClient4 apacheClient;
    private final ApacheHttpClientConnectionCleaner cleaner;
    private final Map<String, String> additionalHeaders;

    /**
     * @deprecated {@link EurekaJerseyClient} is deprecated and will be removed
     */
    @Deprecated
    public JerseyEurekaHttpClientFactory(EurekaJerseyClient jerseyClient, boolean allowRedirects) {
        this(
                jerseyClient,
                null,
                -1,
                Collections.singletonMap(HTTP_X_DISCOVERY_ALLOW_REDIRECT, allowRedirects ? "true" : "false")
        );
    }

    @Deprecated
    public JerseyEurekaHttpClientFactory(EurekaJerseyClient jerseyClient, Map<String, String> additionalHeaders) {
        this(jerseyClient, null, -1, additionalHeaders);
    }

    public JerseyEurekaHttpClientFactory(ApacheHttpClient4 apacheClient, long connectionIdleTimeout, Map<String, String> additionalHeaders) {
        this(null, apacheClient, connectionIdleTimeout, additionalHeaders);
    }

    private JerseyEurekaHttpClientFactory(EurekaJerseyClient jerseyClient,
                                          ApacheHttpClient4 apacheClient,
                                          long connectionIdleTimeout,
                                          Map<String, String> additionalHeaders) {
        this.jerseyClient = jerseyClient;
        this.apacheClient = jerseyClient != null ? jerseyClient.getClient() : apacheClient;
        this.additionalHeaders = additionalHeaders;
        if (jerseyClient == null) {
            // the jersey client contains a cleaner already so only create this cleaner if we don't have a jersey client
            this.cleaner = new ApacheHttpClientConnectionCleaner(this.apacheClient, connectionIdleTimeout);
        } else {
            this.cleaner = null;
        }
    }

    @Override
    public EurekaHttpClient newClient(EurekaEndpoint endpoint) {
        return new JerseyApplicationClient(apacheClient, endpoint.getServiceUrl(), additionalHeaders);
    }

    @Override
    public void shutdown() {
        if (cleaner != null) {
            cleaner.shutdown();
        }

        if (jerseyClient != null) {
            jerseyClient.destroyResources();
        } else {
            apacheClient.destroy();
        }
    }
    
    public static JerseyEurekaHttpClientFactory create(EurekaClientConfig clientConfig,
            Collection<ClientFilter> additionalFilters,
            InstanceInfo myInstanceInfo,
            AbstractEurekaIdentity clientIdentity) {
        return create(clientConfig, additionalFilters, myInstanceInfo, clientIdentity, Optional.empty(), Optional.empty());
    }

    public static JerseyEurekaHttpClientFactory create(EurekaClientConfig clientConfig,
                                                       Collection<ClientFilter> additionalFilters,
                                                       InstanceInfo myInstanceInfo,
                                                       AbstractEurekaIdentity clientIdentity,
                                                       Optional<SSLContext> sslContext,
                                                       Optional<HostnameVerifier> hostnameVerifier) {
        boolean useExperimental = "true".equals(clientConfig.getExperimental("JerseyEurekaHttpClientFactory.useNewBuilder"));

        JerseyEurekaHttpClientFactoryBuilder clientBuilder = (useExperimental ? experimentalBuilder() : newBuilder())
                .withAdditionalFilters(additionalFilters)
                .withMyInstanceInfo(myInstanceInfo)
                .withUserAgent("Java-EurekaClient")
                .withClientConfig(clientConfig)
                .withClientIdentity(clientIdentity);
        
        sslContext.ifPresent(clientBuilder::withSSLContext);
        hostnameVerifier.ifPresent(clientBuilder::withHostnameVerifier);

        if ("true".equals(System.getProperty("com.netflix.eureka.shouldSSLConnectionsUseSystemSocketFactory"))) {
            clientBuilder.withClientName("DiscoveryClient-HTTPClient-System").withSystemSSLConfiguration();
        } else if (clientConfig.getProxyHost() != null && clientConfig.getProxyPort() != null) {
            clientBuilder.withClientName("Proxy-DiscoveryClient-HTTPClient")
                    .withProxy(
                            clientConfig.getProxyHost(), Integer.parseInt(clientConfig.getProxyPort()),
                            clientConfig.getProxyUserName(), clientConfig.getProxyPassword()
                    );
        } else {
            clientBuilder.withClientName("DiscoveryClient-HTTPClient");
        }

        return clientBuilder.build();
    }

    public static JerseyEurekaHttpClientFactoryBuilder newBuilder() {
        return new JerseyEurekaHttpClientFactoryBuilder().withExperimental(false);
    }

    public static JerseyEurekaHttpClientFactoryBuilder experimentalBuilder() {
        return new JerseyEurekaHttpClientFactoryBuilder().withExperimental(true);
    }

    /**
     * Currently use EurekaJerseyClientBuilder. Once old transport in DiscoveryClient is removed, incorporate
     * EurekaJerseyClientBuilder here, and remove it.
     */ // 内部类，用于创建 JerseyEurekaHttpClientFactory
    public static class JerseyEurekaHttpClientFactoryBuilder extends EurekaClientFactoryBuilder<JerseyEurekaHttpClientFactory, JerseyEurekaHttpClientFactoryBuilder> {
        // 客户端过滤器
        private Collection<ClientFilter> additionalFilters = Collections.emptyList();
        private boolean experimental = false;

        public JerseyEurekaHttpClientFactoryBuilder withAdditionalFilters(Collection<ClientFilter> additionalFilters) {
            this.additionalFilters = additionalFilters;
            return this;
        }

        public JerseyEurekaHttpClientFactoryBuilder withExperimental(boolean experimental) {
            this.experimental = experimental;
            return this;
        }

        @Override
        public JerseyEurekaHttpClientFactory build() {
            Map<String, String> additionalHeaders = new HashMap<>();
            if (allowRedirect) {  // 是否允许重定向
                additionalHeaders.put(HTTP_X_DISCOVERY_ALLOW_REDIRECT, "true");
            }
            if (EurekaAccept.compact == eurekaAccept) { // 是否紧凑的请求的数据结构，{@link EurekaAccept}
                additionalHeaders.put(EurekaAccept.HTTP_X_EUREKA_ACCEPT, eurekaAccept.name());
            }

            if (experimental) {
                return buildExperimental(additionalHeaders);
            }
            return buildLegacy(additionalHeaders, systemSSL);
        }
        // 创建 JerseyEurekaHttpClientFactory。使用 {@link EurekaJerseyClientBuilder} 创建 EurekaJerseyClient，使用高版本的 Apache HttpClient 4.3.4 的 SSL 配置，不使用 Http Proxy
        private JerseyEurekaHttpClientFactory buildLegacy(Map<String, String> additionalHeaders, boolean systemSSL) {
            EurekaJerseyClientBuilder clientBuilder = new EurekaJerseyClientBuilder()
                    .withClientName(clientName)
                    .withUserAgent("Java-EurekaClient") // UserAgent （不同）
                    .withConnectionTimeout(connectionTimeout) // 连接时间参数
                    .withReadTimeout(readTimeout)
                    .withMaxConnectionsPerHost(maxConnectionsPerHost) // 连接数量参数
                    .withMaxTotalConnections(maxTotalConnections)
                    .withConnectionIdleTimeout((int) connectionIdleTimeout)
                    .withEncoderWrapper(encoderWrapper) // 编解码
                    .withDecoderWrapper(decoderWrapper)
                    .withProxy(proxyHost,String.valueOf(proxyPort),proxyUserName,proxyPassword);

            if (systemSSL) {
                clientBuilder.withSystemSSLConfiguration();
            } else if (sslContext != null) {
                clientBuilder.withCustomSSL(sslContext);
            }
            
            if (hostnameVerifier != null) {
                clientBuilder.withHostnameVerifier(hostnameVerifier);
            }

            EurekaJerseyClient jerseyClient = clientBuilder.build();
            ApacheHttpClient4 discoveryApacheClient = jerseyClient.getClient();
            addFilters(discoveryApacheClient); // 添加过滤器

            return new JerseyEurekaHttpClientFactory(jerseyClient, additionalHeaders);
        }
        // 创建 JerseyEurekaHttpClientFactory
        private JerseyEurekaHttpClientFactory buildExperimental(Map<String, String> additionalHeaders) {
            ThreadSafeClientConnManager cm = createConnectionManager();
            ClientConfig clientConfig = new DefaultApacheHttpClient4Config();
            // Proxy
            if (proxyHost != null) {
                addProxyConfiguration(clientConfig);
            }
            // 编解码
            DiscoveryJerseyProvider discoveryJerseyProvider = new DiscoveryJerseyProvider(encoderWrapper, decoderWrapper);
            clientConfig.getSingletons().add(discoveryJerseyProvider);
            // 连接数量参数
            // Common properties to all clients
            cm.setDefaultMaxPerRoute(maxConnectionsPerHost);
            cm.setMaxTotal(maxTotalConnections);
            clientConfig.getProperties().put(ApacheHttpClient4Config.PROPERTY_CONNECTION_MANAGER, cm);
            // UserAgent
            String fullUserAgentName = (userAgent == null ? clientName : userAgent) + "/v" + buildVersion();
            clientConfig.getProperties().put(CoreProtocolPNames.USER_AGENT, fullUserAgentName);
            // 禁用重定向
            // To pin a client to specific server in case redirect happens, we handle redirects directly
            // (see DiscoveryClient.makeRemoteCall methods).
            clientConfig.getProperties().put(ClientConfig.PROPERTY_FOLLOW_REDIRECTS, Boolean.FALSE);
            clientConfig.getProperties().put(ClientPNames.HANDLE_REDIRECTS, Boolean.FALSE);
            // 添加过滤器
            ApacheHttpClient4 apacheClient = ApacheHttpClient4.create(clientConfig);
            addFilters(apacheClient);

            return new JerseyEurekaHttpClientFactory(apacheClient, connectionIdleTimeout, additionalHeaders);
        }

        /**
         * Since Jersey 1.19 depends on legacy apache http-client API, we have to as well.
         */
        private ThreadSafeClientConnManager createConnectionManager() { // 使用低版本的 Apache HttpClient 4.1.1 的 SSL 配置
            try {
                ThreadSafeClientConnManager connectionManager;
                if (sslContext != null) {
                    SchemeSocketFactory socketFactory = new SSLSocketFactory(sslContext, new AllowAllHostnameVerifier());
                    SchemeRegistry sslSchemeRegistry = new SchemeRegistry();
                    sslSchemeRegistry.register(new Scheme("https", 443, socketFactory));
                    connectionManager = new ThreadSafeClientConnManager(sslSchemeRegistry);
                } else {
                    connectionManager = new ThreadSafeClientConnManager();
                }
                return connectionManager;
            } catch (Exception e) {
                throw new IllegalStateException("Cannot initialize Apache connection manager", e);
            }
        }

        private void addProxyConfiguration(ClientConfig clientConfig) {
            if (proxyUserName != null && proxyPassword != null) {
                clientConfig.getProperties().put(ApacheHttpClient4Config.PROPERTY_PROXY_USERNAME, proxyUserName);
                clientConfig.getProperties().put(ApacheHttpClient4Config.PROPERTY_PROXY_PASSWORD, proxyPassword);
            } else {
                // Due to bug in apache client, user name/password must always be set.
                // Otherwise proxy configuration is ignored.
                clientConfig.getProperties().put(ApacheHttpClient4Config.PROPERTY_PROXY_USERNAME, "guest");
                clientConfig.getProperties().put(ApacheHttpClient4Config.PROPERTY_PROXY_PASSWORD, "guest");
            }
            clientConfig.getProperties().put(DefaultApacheHttpClient4Config.PROPERTY_PROXY_URI, "http://" + proxyHost + ':' + proxyPort);
        }

        private void addFilters(ApacheHttpClient4 discoveryApacheClient) {
            // Add gzip content encoding support
            discoveryApacheClient.addFilter(new GZIPContentEncodingFilter(false));

            // always enable client identity headers
            String ip = myInstanceInfo == null ? null : myInstanceInfo.getIPAddr();
            AbstractEurekaIdentity identity = clientIdentity == null ? new EurekaClientIdentity(ip) : clientIdentity;
            discoveryApacheClient.addFilter(new EurekaIdentityHeaderFilter(identity));

            if (additionalFilters != null) {
                for (ClientFilter filter : additionalFilters) {
                    if (filter != null) {
                        discoveryApacheClient.addFilter(filter);
                    }
                }
            }
        }
    }
}