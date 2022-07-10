package com.netflix.eureka.util.batcher;

import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingDeque;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import com.netflix.eureka.util.batcher.TaskProcessor.ProcessingResult;
import com.netflix.servo.annotations.DataSourceType;
import com.netflix.servo.annotations.Monitor;
import com.netflix.servo.monitor.MonitorConfig;
import com.netflix.servo.monitor.Monitors;
import com.netflix.servo.monitor.StatsTimer;
import com.netflix.servo.monitor.Timer;
import com.netflix.servo.stats.StatsConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static com.netflix.eureka.Names.METRIC_REPLICATION_PREFIX;

/**
 * An active object with an internal thread accepting tasks from clients, and dispatching them to
 * workers in a pull based manner. Workers explicitly request an item or a batch of items whenever they are
 * available. This guarantees that data to be processed are always up to date, and no stale data processing is done.
 *
 * <h3>Task identification</h3>
 * Each task passed for processing has a corresponding task id. This id is used to remove duplicates (replace
 * older copies with newer ones).
 *
 * <h3>Re-processing</h3>
 * If data processing by a worker failed, and the failure is transient in nature, the worker will put back the
 * task(s) back to the {@link AcceptorExecutor}. This data will be merged with current workload, possibly discarded if
 * a newer version has been already received.
 *
 * @author Tomasz Bak
 */
class AcceptorExecutor<ID, T> { // 任务接收执行器

    private static final Logger logger = LoggerFactory.getLogger(AcceptorExecutor.class);

    private final String id;
    private final int maxBufferSize; // 待执行队列最大数量 {@link #processingOrder}
    private final int maxBatchingSize; // 单个批量任务包含任务最大数量
    private final long maxBatchingDelay; // 批量任务等待最大延迟时长，单位：毫秒
    // 是否关闭
    private final AtomicBoolean isShutdown = new AtomicBoolean(false);
    // 接收任务队列
    private final BlockingQueue<TaskHolder<ID, T>> acceptorQueue = new LinkedBlockingQueue<>();
    private final BlockingDeque<TaskHolder<ID, T>> reprocessQueue = new LinkedBlockingDeque<>(); // 重新执行任务队列
    private final Thread acceptorThread; // 接收任务线程
    // 待执行任务映射
    private final Map<ID, TaskHolder<ID, T>> pendingTasks = new HashMap<>();
    private final Deque<ID> processingOrder = new LinkedList<>(); // 待执行队列
    // 单任务工作请求信号量
    private final Semaphore singleItemWorkRequests = new Semaphore(0);
    private final BlockingQueue<TaskHolder<ID, T>> singleItemWorkQueue = new LinkedBlockingQueue<>(); // 单任务工作队列
    // 批量任务工作请求信号量
    private final Semaphore batchWorkRequests = new Semaphore(0);
    private final BlockingQueue<List<TaskHolder<ID, T>>> batchWorkQueue = new LinkedBlockingQueue<>(); // 批量任务工作队列
    // 网络通信整形器
    private final TrafficShaper trafficShaper;

    /*
     * Metrics
     */
    @Monitor(name = METRIC_REPLICATION_PREFIX + "acceptedTasks", description = "Number of accepted tasks", type = DataSourceType.COUNTER)
    volatile long acceptedTasks;

    @Monitor(name = METRIC_REPLICATION_PREFIX + "replayedTasks", description = "Number of replayedTasks tasks", type = DataSourceType.COUNTER)
    volatile long replayedTasks;

    @Monitor(name = METRIC_REPLICATION_PREFIX + "expiredTasks", description = "Number of expired tasks", type = DataSourceType.COUNTER)
    volatile long expiredTasks;

    @Monitor(name = METRIC_REPLICATION_PREFIX + "overriddenTasks", description = "Number of overridden tasks", type = DataSourceType.COUNTER)
    volatile long overriddenTasks;

    @Monitor(name = METRIC_REPLICATION_PREFIX + "queueOverflows", description = "Number of queue overflows", type = DataSourceType.COUNTER)
    volatile long queueOverflows;

    private final Timer batchSizeMetric;

    AcceptorExecutor(String id,
                     int maxBufferSize,
                     int maxBatchingSize,
                     long maxBatchingDelay,  // 批量任务等待最大延迟时长，单位：毫秒
                     long congestionRetryDelayMs,  // 请求限流延迟重试时间，单位：毫秒
                     long networkFailureRetryMs) { // 网络失败延迟重试时长，单位：毫秒
        this.id = id;
        this.maxBufferSize = maxBufferSize;
        this.maxBatchingSize = maxBatchingSize;
        this.maxBatchingDelay = maxBatchingDelay;
        this.trafficShaper = new TrafficShaper(congestionRetryDelayMs, networkFailureRetryMs); // 创建 网络通信整形器
        // 创建 接收任务线程
        ThreadGroup threadGroup = new ThreadGroup("eurekaTaskExecutors");
        this.acceptorThread = new Thread(threadGroup, new AcceptorRunner(), "TaskAcceptor-" + id);
        this.acceptorThread.setDaemon(true);
        this.acceptorThread.start();

        final double[] percentiles = {50.0, 95.0, 99.0, 99.5};
        final StatsConfig statsConfig = new StatsConfig.Builder()
                .withSampleSize(1000)
                .withPercentiles(percentiles)
                .withPublishStdDev(true)
                .build();
        final MonitorConfig config = MonitorConfig.builder(METRIC_REPLICATION_PREFIX + "batchSize").build();
        this.batchSizeMetric = new StatsTimer(config, statsConfig);
        try {
            Monitors.registerObject(id, this);
        } catch (Throwable e) {
            logger.warn("Cannot register servo monitor for this object", e);
        }
    }
    // 添加任务到 接收任务队列 acceptorQueue
    void process(ID id, T task, long expiryTime) {
        acceptorQueue.add(new TaskHolder<ID, T>(id, task, expiryTime));
        acceptedTasks++;
    }
    // 添加到 重新执行队列 reprocessQueue
    void reprocess(List<TaskHolder<ID, T>> holders, ProcessingResult processingResult) {
        reprocessQueue.addAll(holders); // 添加到 重新执行队列 reprocessQueue
        replayedTasks += holders.size();
        trafficShaper.registerFailure(processingResult); // 提交任务结果给 TrafficShaper
    }

    void reprocess(TaskHolder<ID, T> taskHolder, ProcessingResult processingResult) {
        reprocessQueue.add(taskHolder);
        replayedTasks++;
        trafficShaper.registerFailure(processingResult);
    }

    BlockingQueue<TaskHolder<ID, T>> requestWorkItem() {
        singleItemWorkRequests.release();
        return singleItemWorkQueue;
    }
    // 发起请求信号量，并获得批量任务的工作队列
    BlockingQueue<List<TaskHolder<ID, T>>> requestWorkItems() {
        batchWorkRequests.release(); // 发起请求信号量
        return batchWorkQueue; // 获得批量任务的工作队列
    }

    void shutdown() {
        if (isShutdown.compareAndSet(false, true)) {
            Monitors.unregisterObject(id, this);
            acceptorThread.interrupt();
        }
    }

    @Monitor(name = METRIC_REPLICATION_PREFIX + "acceptorQueueSize", description = "Number of tasks waiting in the acceptor queue", type = DataSourceType.GAUGE)
    public long getAcceptorQueueSize() {
        return acceptorQueue.size();
    }

    @Monitor(name = METRIC_REPLICATION_PREFIX + "reprocessQueueSize", description = "Number of tasks waiting in the reprocess queue", type = DataSourceType.GAUGE)
    public long getReprocessQueueSize() {
        return reprocessQueue.size();
    }

    @Monitor(name = METRIC_REPLICATION_PREFIX + "queueSize", description = "Task queue size", type = DataSourceType.GAUGE)
    public long getQueueSize() {
        return pendingTasks.size();
    }

    @Monitor(name = METRIC_REPLICATION_PREFIX + "pendingJobRequests", description = "Number of worker threads awaiting job assignment", type = DataSourceType.GAUGE)
    public long getPendingJobRequests() {
        return singleItemWorkRequests.availablePermits() + batchWorkRequests.availablePermits();
    }

    @Monitor(name = METRIC_REPLICATION_PREFIX + "availableJobs", description = "Number of jobs ready to be taken by the workers", type = DataSourceType.GAUGE)
    public long workerTaskQueueSize() {
        return singleItemWorkQueue.size() + batchWorkQueue.size();
    }
    // 调度任务
    class AcceptorRunner implements Runnable {
        @Override
        public void run() {
            long scheduleTime = 0;
            while (!isShutdown.get()) { // 无限循环执行调度，直到关闭
                try {
                    drainInputQueues(); // 处理完输入队列 (接收队列 acceptorQueue + 重新执行队列 reprocessQueue)，直到有待执行的任务

                    int totalItems = processingOrder.size(); // 待执行任务数量
                    // 计算可调度任务的最小时间
                    long now = System.currentTimeMillis();
                    if (scheduleTime < now) { // 小于当前时间，配合 TrafficShaper#transmissionDelay(...) 重新计算
                        scheduleTime = now + trafficShaper.transmissionDelay();
                    }
                    if (scheduleTime <= now) { // 小于当前时间，执行任务的调度
                        assignBatchWork(); // 调度批量任务
                        assignSingleItemWork(); // 调度单任务
                    }

                    // If no worker is requesting data or there is a delay injected by the traffic shaper,
                    // sleep for some time to avoid tight loop.
                    if (totalItems == processingOrder.size()) { // 1）任务执行器没有处理任何任务请求，正在忙碌处理之前的任务，processingOrder 还没空间；或者 2）任务延迟调度。睡眠 10 秒，避免资源浪费
                        Thread.sleep(10);
                    }
                } catch (InterruptedException ex) {
                    // Ignore
                } catch (Throwable e) {
                    // Safe-guard, so we never exit this loop in an uncontrolled way.
                    logger.warn("Discovery AcceptorThread error", e);
                }
            }
        }

        private boolean isFull() {
            return pendingTasks.size() >= maxBufferSize;
        }

        private void drainInputQueues() throws InterruptedException {
            do {
                drainReprocessQueue(); // 处理完 重新执行队列 reprocessQueue
                drainAcceptorQueue(); // 处理完 接收队列 acceptorQueue

                if (isShutdown.get()) {
                    break;
                }
                // If all queues are empty, block for a while on the acceptor queue
                if (reprocessQueue.isEmpty() && acceptorQueue.isEmpty() && pendingTasks.isEmpty()) { // 所有队列为空，等待 10 ms，看接收队列是否有新任务
                    TaskHolder<ID, T> taskHolder = acceptorQueue.poll(10, TimeUnit.MILLISECONDS);
                    if (taskHolder != null) {
                        appendTaskHolder(taskHolder);
                    }
                }
            } while (!reprocessQueue.isEmpty() || !acceptorQueue.isEmpty() || pendingTasks.isEmpty()); // 结束条件：重新执行队列(reprocessQueue) 或者 接收队列(acceptorQueue)为空 或者 待执行任务映射(pendingTasks)不为空
        }
        // 处理完 接收队列 acceptorQueue
        private void drainAcceptorQueue() {
            while (!acceptorQueue.isEmpty()) {
                appendTaskHolder(acceptorQueue.poll());
            }
        }
        // 处理完 重新执行队列 reprocessQueue
        private void drainReprocessQueue() {
            long now = System.currentTimeMillis();
            while (!reprocessQueue.isEmpty() && !isFull()) {
                TaskHolder<ID, T> taskHolder = reprocessQueue.pollLast();  // 优先拿较新的任务
                ID id = taskHolder.getId();
                if (taskHolder.getExpiryTime() <= now) {  // 过期
                    expiredTasks++;
                } else if (pendingTasks.containsKey(id)) { // 已存在
                    overriddenTasks++;
                } else {
                    pendingTasks.put(id, taskHolder);
                    processingOrder.addFirst(id);  // 提交到 待执行队列 processingOrder 队头
                }
            }
            if (isFull()) { // 如果待执行队列已满，清空重新执行队列，放弃较早的任务
                queueOverflows += reprocessQueue.size();
                reprocessQueue.clear();
            }
        }

        private void appendTaskHolder(TaskHolder<ID, T> taskHolder) {
            if (isFull()) { // 如果待执行队列已满，移除待处理队列，放弃较早的任务
                pendingTasks.remove(processingOrder.poll());
                queueOverflows++;
            }
            TaskHolder<ID, T> previousTask = pendingTasks.put(taskHolder.getId(), taskHolder); // 添加到待执行队列
            if (previousTask == null) {
                processingOrder.add(taskHolder.getId());
            } else {
                overriddenTasks++;
            }
        }
        // 调度单任务
        void assignSingleItemWork() {
            if (!processingOrder.isEmpty()) { // 待执行任队列不为空
                if (singleItemWorkRequests.tryAcquire(1)) { // 获取 单任务工作请求信号量
                    long now = System.currentTimeMillis();
                    while (!processingOrder.isEmpty()) {
                        ID id = processingOrder.poll(); // 一定不为空
                        TaskHolder<ID, T> holder = pendingTasks.remove(id);
                        if (holder.getExpiryTime() > now) {
                            singleItemWorkQueue.add(holder);
                            return;
                        }
                        expiredTasks++;
                    }
                    singleItemWorkRequests.release(); // 获取不到单任务，释放请求信号量
                }
            }
        }
        // 调度批量任务
        void assignBatchWork() {
            if (hasEnoughTasksForNextBatch()) { // 判断是否有足够任务进行下一次批量任务调度：1）待执行任务(processingOrder)映射已满；或者 2）到达批量任务处理最大等待延迟
                if (batchWorkRequests.tryAcquire(1)) { // 获取 批量任务工作请求信号量。每次执行时，发出 batchWorkRequests 。每一个信号量需要保证获取到一个批量任务
                    long now = System.currentTimeMillis(); // 获取批量任务
                    int len = Math.min(maxBatchingSize, processingOrder.size());
                    List<TaskHolder<ID, T>> holders = new ArrayList<>(len); // 批量任务，实际是 List<TaskHolder<ID, T>>
                    while (holders.size() < len && !processingOrder.isEmpty()) {
                        ID id = processingOrder.poll();
                        TaskHolder<ID, T> holder = pendingTasks.remove(id);
                        if (holder.getExpiryTime() > now) {  // 过期
                            holders.add(holder);
                        } else {
                            expiredTasks++;
                        }
                    }
                    if (holders.isEmpty()) { // 未调度到批量任务，释放请求信号量，代表请求实际未完成，每一个信号量需要保证获取到一个批量任务
                        batchWorkRequests.release();
                    } else { // 添加批量任务到批量任务工作队列
                        batchSizeMetric.record(holders.size(), TimeUnit.MILLISECONDS);
                        batchWorkQueue.add(holders);
                    }
                }
            }
        }
        // 判断是否有足够任务进行下一次批量任务调度：1）待执行任务(processingOrder)映射已满；或者 2）到达批量任务处理最大等待延迟
        private boolean hasEnoughTasksForNextBatch() {
            if (processingOrder.isEmpty()) { // 待执行队列为空
                return false;
            }
            if (pendingTasks.size() >= maxBufferSize) { // 待执行任务映射已满
                return true;
            }
            // 到达批量任务处理最大等待延迟(通过待处理队列的头部任务判断)
            TaskHolder<ID, T> nextHolder = pendingTasks.get(processingOrder.peek());
            long delay = System.currentTimeMillis() - nextHolder.getSubmitTimestamp();
            return delay >= maxBatchingDelay;
        }
    }
}
