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

    public abstract String getTaskName(); // 定义获取任务名的抽象方法

    public Action getAction() {
        return action;
    }

    public abstract EurekaHttpResponse<?> execute() throws Throwable; // 定义执行同步任务的抽象方法

    public void handleSuccess() { // 同步任务处理成功
    }

    public void handleFailure(int statusCode, Object responseEntity) throws Throwable { // 同步任务处理失败
        logger.warn("The replication of task {} failed with response code {}", getTaskName(), statusCode);
    }
}
