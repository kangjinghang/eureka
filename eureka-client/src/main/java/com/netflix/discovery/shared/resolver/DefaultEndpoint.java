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

package com.netflix.discovery.shared.resolver;

import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * @author Tomasz Bak
 */
public class DefaultEndpoint implements EurekaEndpoint { // 默认 Eureka 服务端点实现类

    protected final String networkAddress; // 网络地址
    protected final int port; // 端口
    protected final boolean isSecure; // 是否安全(https)
    protected final String relativeUri; // 相对地址
    protected final String serviceUrl; // 完整的服务 URL

    public DefaultEndpoint(String serviceUrl) {
        this.serviceUrl = serviceUrl;

        try {
            URL url = new URL(serviceUrl); // 将 serviceUrl 分解成 几个属性
            this.networkAddress = url.getHost();
            this.port = url.getPort();
            this.isSecure = "https".equals(url.getProtocol());
            this.relativeUri = url.getPath();
        } catch (Exception e) {
            throw new IllegalArgumentException("Malformed serviceUrl: " + serviceUrl);
        }
    }

    public DefaultEndpoint(String networkAddress, int port, boolean isSecure, String relativeUri) {
        this.networkAddress = networkAddress;
        this.port = port;
        this.isSecure = isSecure;
        this.relativeUri = relativeUri;
        // 几个属性 拼接成 serviceUrl
        StringBuilder sb = new StringBuilder()
                .append(isSecure ? "https" : "http")
                .append("://")
                .append(networkAddress);
		if (port >= 0) {
			sb.append(':')
				.append(port);
		}
        if (relativeUri != null) {
            if (!relativeUri.startsWith("/")) {
                sb.append('/');
            }
            sb.append(relativeUri);
        }
        this.serviceUrl = sb.toString();
    }

    @Override
    public String getServiceUrl() {
        return serviceUrl;
    }

    @Deprecated
    @Override
    public String getHostName() {
        return networkAddress;
    }

    @Override
    public String getNetworkAddress() {
        return networkAddress;
    }

    @Override
    public int getPort() {
        return port;
    }

    @Override
    public boolean isSecure() {
        return isSecure;
    }

    @Override
    public String getRelativeUri() {
        return relativeUri;
    }

    public static List<EurekaEndpoint> createForServerList(
            List<String> hostNames, int port, boolean isSecure, String relativeUri) {
        if (hostNames.isEmpty()) {
            return Collections.emptyList();
        }
        List<EurekaEndpoint> eurekaEndpoints = new ArrayList<>(hostNames.size());
        for (String hostName : hostNames) {
            eurekaEndpoints.add(new DefaultEndpoint(hostName, port, isSecure, relativeUri));
        }
        return eurekaEndpoints;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof DefaultEndpoint)) return false;

        DefaultEndpoint that = (DefaultEndpoint) o;

        if (isSecure != that.isSecure) return false;
        if (port != that.port) return false;
        if (networkAddress != null ? !networkAddress.equals(that.networkAddress) : that.networkAddress != null) return false;
        if (relativeUri != null ? !relativeUri.equals(that.relativeUri) : that.relativeUri != null) return false;
        if (serviceUrl != null ? !serviceUrl.equals(that.serviceUrl) : that.serviceUrl != null) return false;

        return true;
    }

    @Override
    public int hashCode() {
        int result = networkAddress != null ? networkAddress.hashCode() : 0;
        result = 31 * result + port;
        result = 31 * result + (isSecure ? 1 : 0);
        result = 31 * result + (relativeUri != null ? relativeUri.hashCode() : 0);
        result = 31 * result + (serviceUrl != null ? serviceUrl.hashCode() : 0);
        return result;
    }
    // 基于 serviceUrl 属性做比较
    @Override
    public int compareTo(Object that) {
        return serviceUrl.compareTo(((DefaultEndpoint) that).getServiceUrl());
    }

    @Override
    public String toString() {
        return "DefaultEndpoint{ serviceUrl='" + serviceUrl + '}';
    }
}
