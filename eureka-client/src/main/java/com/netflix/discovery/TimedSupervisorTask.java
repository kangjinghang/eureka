package com.netflix.discovery;

import java.util.TimerTask;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;

import com.netflix.servo.monitor.Counter;
import com.netflix.servo.monitor.LongGauge;
import com.netflix.servo.monitor.MonitorConfig;
import com.netflix.servo.monitor.Monitors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A supervisor task that schedules subtasks while enforce a timeout.
 * Wrapped subtasks must be thread safe.
 *
 * @author David Qiang Liu
 */
public class TimedSupervisorTask extends TimerTask { // 监管定时任务的任务
    private static final Logger logger = LoggerFactory.getLogger(TimedSupervisorTask.class);

    private final Counter successCounter;
    private final Counter timeoutCounter;
    private final Counter rejectedCounter;
    private final Counter throwableCounter;
    private final LongGauge threadPoolLevelGauge;

    private final String name;
    private final ScheduledExecutorService scheduler; // 定时任务服务，用于定时【发起】子任务。
    private final ThreadPoolExecutor executor; // 执行子任务线程池，用于【提交】子任务执行。
    private final long timeoutMillis; // 子任务执行超时时间，单位：毫秒
    private final Runnable task; // 子任务

    private final AtomicLong delay; // 当前任子务执行频率，单位：毫秒。值等于 timeout 参数
    private final long maxDelay; // 最大子任务执行频率，子任务执行超时情况下使用，单位：毫秒。值等于 timeout * expBackOffBound 参数

    public TimedSupervisorTask(String name, ScheduledExecutorService scheduler, ThreadPoolExecutor executor,
                               int timeout, TimeUnit timeUnit, int expBackOffBound, Runnable task) {
        this.name = name;
        this.scheduler = scheduler;
        this.executor = executor;
        this.timeoutMillis = timeUnit.toMillis(timeout);
        this.task = task;
        this.delay = new AtomicLong(timeoutMillis);
        this.maxDelay = timeoutMillis * expBackOffBound;

        // Initialize the counters and register.
        successCounter = Monitors.newCounter("success");
        timeoutCounter = Monitors.newCounter("timeouts");
        rejectedCounter = Monitors.newCounter("rejectedExecutions");
        throwableCounter = Monitors.newCounter("throwables");
        threadPoolLevelGauge = new LongGauge(MonitorConfig.builder("threadPoolUsed").build());
        Monitors.registerObject(name, this);
    }

    @Override
    public void run() {
        Future<?> future = null;
        try {
            future = executor.submit(task); // 提交 task 到 executor 执行任务
            threadPoolLevelGauge.set((long) executor.getActiveCount());
            future.get(timeoutMillis, TimeUnit.MILLISECONDS);  // block until done or timeout 等待任务 执行完成 或 超时
            delay.set(timeoutMillis);  // 设置 下一次任务执行频率
            threadPoolLevelGauge.set((long) executor.getActiveCount());
            successCounter.increment();
        } catch (TimeoutException e) { // task 执行超时，重新计算延迟时间(不允许超过 maxDelay)，再次提交自己到scheduler 延迟执行。
            logger.warn("task supervisor timed out", e);
            timeoutCounter.increment();

            long currentDelay = delay.get();
            long newDelay = Math.min(maxDelay, currentDelay * 2); // 重新计算延迟时间(不允许超过 maxDelay)
            delay.compareAndSet(currentDelay, newDelay); // 设置 下一次任务执行频率

        } catch (RejectedExecutionException e) {
            if (executor.isShutdown() || scheduler.isShutdown()) {
                logger.warn("task supervisor shutting down, reject the task", e);
            } else {
                logger.warn("task supervisor rejected the task", e);
            }

            rejectedCounter.increment();
        } catch (Throwable e) {
            if (executor.isShutdown() || scheduler.isShutdown()) {
                logger.warn("task supervisor shutting down, can't accept the task");
            } else {
                logger.warn("task supervisor threw an exception", e);
            }

            throwableCounter.increment();
        } finally {
            if (future != null) { // 取消 未完成的任务
                future.cancel(true);
            }

            if (!scheduler.isShutdown()) { // 调度 下次任务
                scheduler.schedule(this, delay.get(), TimeUnit.MILLISECONDS);
            }
        }
    }

    @Override
    public boolean cancel() {
        Monitors.unregisterObject(name, this);
        return super.cancel();
    }
}