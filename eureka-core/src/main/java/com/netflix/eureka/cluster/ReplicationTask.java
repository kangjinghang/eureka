package com.netflix.eureka.cluster;

import com.netflix.discovery.shared.transport.EurekaHttpResponse;
import com.netflix.eureka.registry.PeerAwareInstanceRegistryImpl.Action;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Base class for all replication tasks.
 */
abstract class ReplicationTask { // 同步任务抽象类

    private static final Logger logger = LoggerFactory.getLogger(ReplicationTask.class);

    protected final String peerNodeName;
    protected final Action action;

    ReplicationTask(String peerNodeName, Action action) {
        this.peerNodeName = peerNodeName;
        this.action = action;
    }

    public abstract String getTaskName();

    public Action getAction() {
        return action;
    }
    // 执行同步任务
    public abstract EurekaHttpResponse<?> execute() throws Throwable;
    // 处理成功执行同步结果
    public void handleSuccess() {
    }
    // 处理失败执行同步结果
    public void handleFailure(int statusCode, Object responseEntity) throws Throwable {
        logger.warn("The replication of task {} failed with response code {}", getTaskName(), statusCode);
    }
}
