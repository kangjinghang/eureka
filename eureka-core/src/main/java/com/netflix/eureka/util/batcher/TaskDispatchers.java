package com.netflix.eureka.util.batcher;

/**
 * See {@link TaskDispatcher} for an overview.
 *
 * @author Tomasz Bak
 */
public class TaskDispatchers { // 任务分发器工厂类，用于创建任务分发器
    // 创建单任务执行的分发器，<ID> 任务编号泛型，<T> 任务泛型
    public static <ID, T> TaskDispatcher<ID, T> createNonBatchingTaskDispatcher(String id,  // 任务执行器编号
                                                                                int maxBufferSize,  // 待执行队列最大数量
                                                                                int workerCount, // 任务执行器工作线程数
                                                                                long maxBatchingDelay,  // 批量任务等待最大延迟时长，单位：毫秒
                                                                                long congestionRetryDelayMs,  // 请求限流延迟重试时间，单位：毫秒
                                                                                long networkFailureRetryMs,  // 网络失败延迟重试时长，单位：毫秒
                                                                                TaskProcessor<T> taskProcessor) {  // 任务处理器
        final AcceptorExecutor<ID, T> acceptorExecutor = new AcceptorExecutor<>(  // 创建 任务接收执行器
                id, maxBufferSize, 1, maxBatchingDelay, congestionRetryDelayMs, networkFailureRetryMs
        );
        final TaskExecutors<ID, T> taskExecutor = TaskExecutors.singleItemExecutors(id, workerCount, taskProcessor, acceptorExecutor); // 创建单任务执行器
        return new TaskDispatcher<ID, T>() {  // 创建 单任务分发器
            @Override
            public void process(ID id, T task, long expiryTime) {
                acceptorExecutor.process(id, task, expiryTime);  // 提交 [任务编号 , 任务 , 任务过期时间] 给任务分发器处理
            }

            @Override
            public void shutdown() {
                acceptorExecutor.shutdown();
                taskExecutor.shutdown();
            }
        };
    }
    // 创建批量任务执行的分发器，<ID> 任务编号泛型，<T> 任务泛型
    public static <ID, T> TaskDispatcher<ID, T> createBatchingTaskDispatcher(String id, // 任务执行器编号
                                                                             int maxBufferSize, // 待执行队列最大数量
                                                                             int workloadSize, // 单个批量任务包含任务最大数量
                                                                             int workerCount, // 任务执行器工作线程数
                                                                             long maxBatchingDelay, // 批量任务等待最大延迟时长，单位：毫秒
                                                                             long congestionRetryDelayMs, // 请求限流延迟重试时间，单位：毫秒
                                                                             long networkFailureRetryMs, // 网络失败延迟重试时长，单位：毫秒
                                                                             TaskProcessor<T> taskProcessor) { // 任务处理器
        final AcceptorExecutor<ID, T> acceptorExecutor = new AcceptorExecutor<>( // 创建 任务接收执行器
                id, maxBufferSize, workloadSize, maxBatchingDelay, congestionRetryDelayMs, networkFailureRetryMs
        );
        final TaskExecutors<ID, T> taskExecutor = TaskExecutors.batchExecutors(id, workerCount, taskProcessor, acceptorExecutor); // 创建 批量任务执行器
        return new TaskDispatcher<ID, T>() {  // 创建 批量任务分发器
            @Override
            public void process(ID id, T task, long expiryTime) {
                acceptorExecutor.process(id, task, expiryTime); // 提交 [任务编号 , 任务 , 任务过期时间] 给任务分发器处理
            }

            @Override
            public void shutdown() {
                acceptorExecutor.shutdown();
                taskExecutor.shutdown();
            }
        };
    }
}
