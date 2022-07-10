package com.netflix.discovery;

import com.netflix.appinfo.ApplicationInfoManager;

/**
 * A handler that can be registered with an {@link EurekaClient} at creation time to execute
 * pre registration logic. The pre registration logic need to be synchronous to be guaranteed
 * to execute before registration.
 */
public interface PreRegistrationHandler { // 向 Eureka-Server 注册之前的处理器接口，目前暂未提供默认实现。通过实现该接口，可以在注册前做一些自定义的处理
    void beforeRegistration();
}
