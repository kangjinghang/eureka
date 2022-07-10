package com.netflix.eureka.util.batcher;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import com.netflix.eureka.util.batcher.TaskProcessor.ProcessingResult;
import com.netflix.servo.annotations.DataSourceType;
import com.netflix.servo.annotations.Monitor;
import com.netflix.servo.monitor.MonitorConfig;
import com.netflix.servo.monitor.Monitors;
import com.netflix.servo.monitor.StatsTimer;
import com.netflix.servo.stats.StatsConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static com.netflix.eureka.Names.METRIC_REPLICATION_PREFIX;

/**
 * {@link TaskExecutors} instance holds a number of worker threads that cooperate with {@link AcceptorExecutor}.
 * Each worker sends a job request to {@link AcceptorExecutor} whenever it is available, and processes it once
 * provided with a task(s).
 *
 * @author Tomasz Bak
 */
class TaskExecutors<ID, T> { // 任务执行器。其内部提供创建单任务和批量任务执行器的两种方法

    private static final Logger logger = LoggerFactory.getLogger(TaskExecutors.class);

    private static final Map<String, TaskExecutorMetrics> registeredMonitors = new HashMap<>();
    // 是否关闭
    private final AtomicBoolean isShutdown;
    private final List<Thread> workerThreads; // 工作线程池。工作任务队列会被工作线程池并发拉取，并发执行。

    TaskExecutors(WorkerRunnableFactory<ID, T> workerRunnableFactory, int workerCount, AtomicBoolean isShutdown) {
        this.isShutdown = isShutdown;
        this.workerThreads = new ArrayList<>();
        // 创建 工作线程池
        ThreadGroup threadGroup = new ThreadGroup("eurekaTaskExecutors");
        for (int i = 0; i < workerCount; i++) {
            WorkerRunnable<ID, T> runnable = workerRunnableFactory.create(i);
            Thread workerThread = new Thread(threadGroup, runnable, runnable.getWorkerName());
            workerThreads.add(workerThread);
            workerThread.setDaemon(true);
            workerThread.start();
        }
    }

    void shutdown() {
        if (isShutdown.compareAndSet(false, true)) {
            for (Thread workerThread : workerThreads) {
                workerThread.interrupt();
            }
            registeredMonitors.forEach(Monitors::unregisterObject);
        }
    }
    // 创建批量任务执行器，<ID> 任务编号泛型，<T> 任务泛型
    static <ID, T> TaskExecutors<ID, T> singleItemExecutors(final String name, // 任务执行器名
                                                            int workerCount, // 任务执行器工作线程数
                                                            final TaskProcessor<T> processor, // 任务处理器
                                                            final AcceptorExecutor<ID, T> acceptorExecutor) { // 接收任务执行器
        final AtomicBoolean isShutdown = new AtomicBoolean();
        final TaskExecutorMetrics metrics = new TaskExecutorMetrics(name);
        registeredMonitors.put(name, metrics);
        return new TaskExecutors<>(idx -> new SingleTaskWorkerRunnable<>("TaskNonBatchingWorker-" + name + '-' + idx, isShutdown, metrics, processor, acceptorExecutor), workerCount, isShutdown); // 创建单任务执行器
    }
    // 创建批量任务执行器，<ID> 任务编号泛型，<T> 任务泛型
    static <ID, T> TaskExecutors<ID, T> batchExecutors(final String name, // 任务执行器名
                                                       int workerCount, // 任务执行器工作线程数
                                                       final TaskProcessor<T> processor, // 任务处理器
                                                       final AcceptorExecutor<ID, T> acceptorExecutor) { // 接收任务执行器
        final AtomicBoolean isShutdown = new AtomicBoolean();
        final TaskExecutorMetrics metrics = new TaskExecutorMetrics(name);
        registeredMonitors.put(name, metrics);
        return new TaskExecutors<>(idx -> new BatchWorkerRunnable<>("TaskBatchingWorker-" + name + '-' + idx, isShutdown, metrics, processor, acceptorExecutor), workerCount, isShutdown); // 创建批量任务执行器
    }

    static class TaskExecutorMetrics {

        @Monitor(name = METRIC_REPLICATION_PREFIX + "numberOfSuccessfulExecutions", description = "Number of successful task executions", type = DataSourceType.COUNTER)
        volatile long numberOfSuccessfulExecutions;

        @Monitor(name = METRIC_REPLICATION_PREFIX + "numberOfTransientErrors", description = "Number of transient task execution errors", type = DataSourceType.COUNTER)
        volatile long numberOfTransientError;

        @Monitor(name = METRIC_REPLICATION_PREFIX + "numberOfPermanentErrors", description = "Number of permanent task execution errors", type = DataSourceType.COUNTER)
        volatile long numberOfPermanentError;

        @Monitor(name = METRIC_REPLICATION_PREFIX + "numberOfCongestionIssues", description = "Number of congestion issues during task execution", type = DataSourceType.COUNTER)
        volatile long numberOfCongestionIssues;

        final StatsTimer taskWaitingTimeForProcessing;

        TaskExecutorMetrics(String id) {
            final double[] percentiles = {50.0, 95.0, 99.0, 99.5};
            final StatsConfig statsConfig = new StatsConfig.Builder()
                    .withSampleSize(1000)
                    .withPercentiles(percentiles)
                    .withPublishStdDev(true)
                    .build();
            final MonitorConfig config = MonitorConfig.builder(METRIC_REPLICATION_PREFIX + "executionTime").build();
            taskWaitingTimeForProcessing = new StatsTimer(config, statsConfig);

            try {
                Monitors.registerObject(id, this);
            } catch (Throwable e) {
                logger.warn("Cannot register servo monitor for this object", e);
            }
        }

        void registerTaskResult(ProcessingResult result, int count) {
            switch (result) {
                case Success:
                    numberOfSuccessfulExecutions += count;
                    break;
                case TransientError:
                    numberOfTransientError += count;
                    break;
                case PermanentError:
                    numberOfPermanentError += count;
                    break;
                case Congestion:
                    numberOfCongestionIssues += count;
                    break;
            }
        }

        <ID, T> void registerExpiryTime(TaskHolder<ID, T> holder) {
            taskWaitingTimeForProcessing.record(System.currentTimeMillis() - holder.getSubmitTimestamp(), TimeUnit.MILLISECONDS);
        }

        <ID, T> void registerExpiryTimes(List<TaskHolder<ID, T>> holders) {
            long now = System.currentTimeMillis();
            for (TaskHolder<ID, T> holder : holders) {
                taskWaitingTimeForProcessing.record(now - holder.getSubmitTimestamp(), TimeUnit.MILLISECONDS);
            }
        }
    }
    // 创建工作线程工厂
    interface WorkerRunnableFactory<ID, T> {
        WorkerRunnable<ID, T> create(int idx);
    }
    // 任务工作线程抽象类
    abstract static class WorkerRunnable<ID, T> implements Runnable {
        final String workerName; // 线程名
        final AtomicBoolean isShutdown; // 是否关闭
        final TaskExecutorMetrics metrics;
        final TaskProcessor<T> processor; // 任务处理器
        final AcceptorExecutor<ID, T> taskDispatcher; // 任务接收执行器

        WorkerRunnable(String workerName,
                       AtomicBoolean isShutdown,
                       TaskExecutorMetrics metrics,
                       TaskProcessor<T> processor,
                       AcceptorExecutor<ID, T> taskDispatcher) {
            this.workerName = workerName;
            this.isShutdown = isShutdown;
            this.metrics = metrics;
            this.processor = processor;
            this.taskDispatcher = taskDispatcher;
        }

        String getWorkerName() {
            return workerName;
        }
    }
    // 批量任务工作后台线程任务
    static class BatchWorkerRunnable<ID, T> extends WorkerRunnable<ID, T> {

        BatchWorkerRunnable(String workerName,
                            AtomicBoolean isShutdown,
                            TaskExecutorMetrics metrics,
                            TaskProcessor<T> processor,
                            AcceptorExecutor<ID, T> acceptorExecutor) {
            super(workerName, isShutdown, metrics, processor, acceptorExecutor);
        }

        @Override
        public void run() {
            try {
                while (!isShutdown.get()) { // 无限循环执行调度，直到关闭
                    List<TaskHolder<ID, T>> holders = getWork(); // 获取批量任务
                    metrics.registerExpiryTimes(holders);

                    List<T> tasks = getTasksOf(holders); // 获得实际批量任务
                    ProcessingResult result = processor.process(tasks); // 调用处理器执行任务
                    switch (result) {
                        case Success:
                            break;
                        case Congestion:
                        case TransientError:
                            taskDispatcher.reprocess(holders, result); // 当任务执行结果为 Congestion 或 TransientError，提交重新处理
                            break;
                        case PermanentError:
                            logger.warn("Discarding {} tasks of {} due to permanent error", holders.size(), workerName);
                    }
                    metrics.registerTaskResult(result, tasks.size());
                }
            } catch (InterruptedException e) {
                // Ignore
            } catch (Throwable e) {
                // Safe-guard, so we never exit this loop in an uncontrolled way.
                logger.warn("Discovery WorkerThread error", e);
            }
        }
        // 获取一个批量任务直到成功
        private List<TaskHolder<ID, T>> getWork() throws InterruptedException {
            BlockingQueue<List<TaskHolder<ID, T>>> workQueue = taskDispatcher.requestWorkItems(); // 发起请求信号量，并获得批量任务的工作队列
            List<TaskHolder<ID, T>> result;
            do {
                result = workQueue.poll(1, TimeUnit.SECONDS); // 【循环】获取批量任务，直到成功
            } while (!isShutdown.get() && result == null);
            return (result == null) ? new ArrayList<>() : result;
        }
        // 获得实际批量任务
        private List<T> getTasksOf(List<TaskHolder<ID, T>> holders) {
            List<T> tasks = new ArrayList<>(holders.size());
            for (TaskHolder<ID, T> holder : holders) {
                tasks.add(holder.getTask());
            }
            return tasks;
        }
    }
    // 单任务工作后台线程任务
    static class SingleTaskWorkerRunnable<ID, T> extends WorkerRunnable<ID, T> {

        SingleTaskWorkerRunnable(String workerName,
                                 AtomicBoolean isShutdown,
                                 TaskExecutorMetrics metrics,
                                 TaskProcessor<T> processor,
                                 AcceptorExecutor<ID, T> acceptorExecutor) {
            super(workerName, isShutdown, metrics, processor, acceptorExecutor);
        }

        @Override
        public void run() {
            try {
                while (!isShutdown.get()) {
                    BlockingQueue<TaskHolder<ID, T>> workQueue = taskDispatcher.requestWorkItem(); // 发起请求信号量，并获得单任务的工作队列
                    TaskHolder<ID, T> taskHolder;
                    while ((taskHolder = workQueue.poll(1, TimeUnit.SECONDS)) == null) { // 【循环】获取单任务，直到成功
                        if (isShutdown.get()) {
                            return;
                        }
                    }
                    metrics.registerExpiryTime(taskHolder);
                    if (taskHolder != null) {
                        ProcessingResult result = processor.process(taskHolder.getTask()); // 调用处理器执行任务
                        switch (result) {
                            case Success:
                                break;
                            case Congestion:
                            case TransientError:
                                taskDispatcher.reprocess(taskHolder, result); // 提交重新处理
                                break;
                            case PermanentError:
                                logger.warn("Discarding a task of {} due to permanent error", workerName);
                        }
                        metrics.registerTaskResult(result, 1);
                    }
                }
            } catch (InterruptedException e) {
                // Ignore
            } catch (Throwable e) {
                // Safe-guard, so we never exit this loop in an uncontrolled way.
                logger.warn("Discovery WorkerThread error", e);
            }
        }
    }
}
