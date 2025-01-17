package com.netflix.eureka;

import javax.inject.Inject;
import javax.inject.Singleton;
import javax.servlet.Filter;
import javax.servlet.FilterChain;
import javax.servlet.FilterConfig;
import javax.servlet.ServletException;
import javax.servlet.ServletRequest;
import javax.servlet.ServletResponse;
import javax.servlet.http.HttpServletRequest;
import java.io.IOException;

import com.google.common.base.Strings;
import com.netflix.appinfo.AbstractEurekaIdentity;
import com.netflix.servo.monitor.DynamicCounter;
import com.netflix.servo.monitor.MonitorConfig;

/**
 * An auth filter for client requests. For now, it only logs supported client identification data from header info
 */
@Singleton
public class ServerRequestAuthFilter implements Filter { // Eureka-Server 请求认证过滤器。Eureka-Server 未实现认证。目前打印访问的客户端名和版本号，配合 Netflix Servo 实现监控信息采集
    public static final String UNKNOWN = "unknown";

    private static final String NAME_PREFIX = "DiscoveryServerRequestAuth_Name_";

    private EurekaServerConfig serverConfig;

    @Inject
    public ServerRequestAuthFilter(EurekaServerContext server) {
        this.serverConfig = server.getServerConfig();
    }

    // for non-DI use
    public ServerRequestAuthFilter() {
    }

    @Override
    public void init(FilterConfig filterConfig) throws ServletException {
        if (serverConfig == null) {
            EurekaServerContext serverContext = (EurekaServerContext) filterConfig.getServletContext()
                    .getAttribute(EurekaServerContext.class.getName());
            serverConfig = serverContext.getServerConfig();
        }
    }

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        logAuth(request); // Eureka-Server 未实现认证。目前打印访问的客户端名和版本号
        chain.doFilter(request, response);
    }

    @Override
    public void destroy() {
        // nothing to do here
    }
    // 打印访问的客户端名和版本号
    protected void logAuth(ServletRequest request) {
        if (serverConfig.shouldLogIdentityHeaders()) {
            if (request instanceof HttpServletRequest) {
                HttpServletRequest httpRequest = (HttpServletRequest) request;

                String clientName = getHeader(httpRequest, AbstractEurekaIdentity.AUTH_NAME_HEADER_KEY); // 客户端名
                String clientVersion = getHeader(httpRequest, AbstractEurekaIdentity.AUTH_VERSION_HEADER_KEY); // 客户端版本号

                DynamicCounter.increment(MonitorConfig.builder(NAME_PREFIX + clientName + "-" + clientVersion).build());
            }
        }
    }

    protected String getHeader(HttpServletRequest request, String headerKey) {
        String value = request.getHeader(headerKey);
        return Strings.isNullOrEmpty(value) ? UNKNOWN : value;
    }
}
