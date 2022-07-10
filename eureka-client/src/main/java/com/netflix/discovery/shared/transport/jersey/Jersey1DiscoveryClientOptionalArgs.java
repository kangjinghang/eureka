package com.netflix.discovery.shared.transport.jersey;

import com.netflix.discovery.AbstractDiscoveryClientOptionalArgs;
import com.sun.jersey.api.client.filter.ClientFilter;

/**
 * Jersey1 implementation of DiscoveryClientOptionalArg.
 *  Jersey 1.X 使用 ClientFilter 。ClientFilter 目前有两个过滤器实现：EurekaIdentityHeaderFilter 、DynamicGZIPContentEncodingFilter
 * @author Matt Nelson
 */
public class Jersey1DiscoveryClientOptionalArgs extends AbstractDiscoveryClientOptionalArgs<ClientFilter> {

}
