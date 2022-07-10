package com.netflix.discovery;

import com.google.common.util.concurrent.ThreadFactoryBuilder;
import com.netflix.appinfo.InstanceInfo;
import com.netflix.discovery.util.RateLimiter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * A task for updating and replicating the local instanceinfo to the remote server. Properties of this task are:
 * - configured with a single update thread to guarantee sequential update to the remote server
 * - update tasks can be scheduled on-demand via onDemandUpdate()
 * - task processing is rate limited by burstSize
 * - a new update task is always scheduled automatically after an earlier update task. However if an on-demand task
 *   is started, the scheduled automatic update task is discarded (and a new one will be scheduled after the new
 *   on-demand update).
 *
 *   @author dliu
 */
class InstanceInfoReplicator implements Runnable { // 应用实例信息复制器
    private static final Logger logger = LoggerFactory.getLogger(InstanceInfoReplicator.class);

    private final DiscoveryClient discoveryClient;
    private final InstanceInfo instanceInfo; // 应用实例信息
    // 定时执行频率，单位：秒
    private final int replicationIntervalSeconds;
    private final ScheduledExecutorService scheduler; // 定时执行器
    private final AtomicReference<Future> scheduledPeriodicRef; // 定时执行任务的 Future
    // 是否开启调度
    private final AtomicBoolean started;
    private final RateLimiter rateLimiter;  // 限流相关，RateLimiter
    private final int burstSize;  // 限流相关。令牌桶上限，默认：2
    private final int allowedRatePerMinute;  // 限流相关，令牌再装平均速率，默认：60 * 2 / 30 = 4

    InstanceInfoReplicator(DiscoveryClient discoveryClient, InstanceInfo instanceInfo, int replicationIntervalSeconds, int burstSize) {
        this.discoveryClient = discoveryClient;
        this.instanceInfo = instanceInfo;
        this.scheduler = Executors.newScheduledThreadPool(1, // 1个线程
                new ThreadFactoryBuilder()
                        .setNameFormat("DiscoveryClient-InstanceInfoReplicator-%d")
                        .setDaemon(true)
                        .build());

        this.scheduledPeriodicRef = new AtomicReference<Future>();

        this.started = new AtomicBoolean(false);
        this.rateLimiter = new RateLimiter(TimeUnit.MINUTES);
        this.replicationIntervalSeconds = replicationIntervalSeconds;
        this.burstSize = burstSize;

        this.allowedRatePerMinute = 60 * this.burstSize / this.replicationIntervalSeconds;
        logger.info("InstanceInfoReplicator onDemand update allowed rate per min is {}", allowedRatePerMinute);
    }
    // 开启 应用实例信息复制器
    public void start(int initialDelayMs) {
        if (started.compareAndSet(false, true)) {
            instanceInfo.setIsDirty();  // for initial register 设置 应用实例信息 数据不一致。因为 InstanceInfo 刚被创建时，在 Eureka-Server 不存在，也会被注册
            Future next = scheduler.schedule(this, initialDelayMs, TimeUnit.SECONDS); // 提交任务，并设置该任务的 Future。延迟 initialDelayMs 毫秒执行一次任务
            scheduledPeriodicRef.set(next);
        }
    }

    public void stop() {
        shutdownAndAwaitTermination(scheduler);
        started.set(false);
    }

    private void shutdownAndAwaitTermination(ExecutorService pool) {
        pool.shutdown();
        try {
            if (!pool.awaitTermination(3, TimeUnit.SECONDS)) {
                pool.shutdownNow();
            }
        } catch (InterruptedException e) {
            logger.warn("InstanceInfoReplicator stop interrupted");
        }
    }
    // InstanceInfo 状态变更后触发，向 Eureka-Server 发起注册，同步应用实例信息。InstanceInfoReplicator 使用 RateLimiter ，避免状态频繁发生变化，向 Eureka-Server 频繁同步
    public boolean onDemandUpdate() {
        if (rateLimiter.acquire(burstSize, allowedRatePerMinute)) { // 限流相关。若获取成功，向 Eureka-Server 发起注册，同步应用实例信息
            if (!scheduler.isShutdown()) {
                scheduler.submit(new Runnable() {
                    @Override
                    public void run() {
                        logger.debug("Executing on-demand update of local InstanceInfo");
    
                        Future latestPeriodic = scheduledPeriodicRef.get();
                        if (latestPeriodic != null && !latestPeriodic.isDone()) { // 实例状态发生了变化，但是已经提交的定时任务还没执行完
                            logger.debug("Canceling the latest scheduled update, it will be rescheduled at the end of on demand update");
                            latestPeriodic.cancel(false);  // 取消任务，避免无用的注册
                        }
    
                        InstanceInfoReplicator.this.run(); // 再次调用，发起注册，新的时间间隔
                    }
                });
                return true;
            } else {
                logger.warn("Ignoring onDemand update due to stopped scheduler");
                return false;
            }
        } else { // 若获取失败，不向 Eureka-Server 发起注册，同步应用实例信息
            logger.warn("Ignoring onDemand update due to rate limiter");
            return false;
        }
    }
    // 定时检查 InstanceInfo 的状态( status ) 属性是否发生变化。若是，发起注册
    public void run() {
        try {
            discoveryClient.refreshInstanceInfo(); // 刷新 应用实例信息，此处可能导致应用实例信息数据不一致
            // 判断 应用实例信息 是否数据不一致
            Long dirtyTimestamp = instanceInfo.isDirtyWithTime();
            if (dirtyTimestamp != null) {
                discoveryClient.register(); // 发起注册，Eureka-Client 向 Eureka-Server 注册应用实例。
                instanceInfo.unsetIsDirty(dirtyTimestamp); // 设置 应用实例信息 数据一致
            }
        } catch (Throwable t) {
            logger.warn("There was a problem with the instance info replicator", t);
        } finally {
            Future next = scheduler.schedule(this, replicationIntervalSeconds, TimeUnit.SECONDS); // 再次延迟执行任务，并设置 scheduledPeriodicRef。通过这样的方式，不断循环定时执行任务
            scheduledPeriodicRef.set(next);
        }
    }

}
