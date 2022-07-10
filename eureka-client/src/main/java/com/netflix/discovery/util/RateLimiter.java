/*
 * Copyright 2014 Netflix, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.netflix.discovery.util;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Rate limiter implementation is based on token bucket algorithm. There are two parameters:
 * <ul>
 * <li>
 *     burst size - maximum number of requests allowed into the system as a burst
 * </li>
 * <li>
 *     average rate - expected number of requests per second (RateLimiters using MINUTES is also supported)
 * </li>
 * </ul>
 *
 * @author Tomasz Bak
 */
public class RateLimiter { // 基于 Token Bucket Algorithm (令牌桶算法) 的速率限制器。
    // 速率单位转换成毫秒
    private final long rateToMsConversion;
    // 消耗令牌数
    private final AtomicInteger consumedTokens = new AtomicInteger();
    private final AtomicLong lastRefillTime = new AtomicLong(0); // 最后填充令牌的时间

    @Deprecated
    public RateLimiter() {
        this(TimeUnit.SECONDS);
    }

    public RateLimiter(TimeUnit averageRateUnit) {
        switch (averageRateUnit) {
            case SECONDS:  // 秒级
                rateToMsConversion = 1000;
                break;
            case MINUTES: // 分钟级
                rateToMsConversion = 60 * 1000;
                break;
            default:
                throw new IllegalArgumentException("TimeUnit of " + averageRateUnit + " is not supported");
        }
    }
    // 获取令牌，并返回是否获取成功。burstSize 令牌桶上限。averageRate 令牌再装平均速率
    public boolean acquire(int burstSize, long averageRate) {
        return acquire(burstSize, averageRate, System.currentTimeMillis());
    }

    public boolean acquire(int burstSize, long averageRate, long currentTimeMillis) {
        if (burstSize <= 0 || averageRate <= 0) { // Instead of throwing exception, we just let all the traffic go
            return true;
        }
        // 填充 令牌
        refillToken(burstSize, averageRate, currentTimeMillis);
        return consumeToken(burstSize); // 消费 令牌
    }
    // 填充 令牌
    private void refillToken(int burstSize, long averageRate, long currentTimeMillis) {
        long refillTime = lastRefillTime.get(); // 获得 最后填充令牌的时间
        long timeDelta = currentTimeMillis - refillTime; // 获得 过去多少毫秒
        // 计算 可填充最大令牌数量
        long newTokens = timeDelta * averageRate / rateToMsConversion; // 可填充的最大令牌数量
        if (newTokens > 0) {
            long newRefillTime = refillTime == 0 // 计算 新的填充令牌的时间
                    ? currentTimeMillis
                    : refillTime + newTokens * rateToMsConversion / averageRate;
            if (lastRefillTime.compareAndSet(refillTime, newRefillTime)) { // CAS 保证有且仅有一个线程进入填充
                while (true) {  // 死循环，直到成功
                    int currentLevel = consumedTokens.get();
                    int adjustedLevel = Math.min(currentLevel, burstSize); // In case burstSize decreased . 令牌桶上限：burstSize 可能调小，例如，系统接入分布式配置中心，可以远程调整该数值。如果此时 burstSize 更小，以它作为已消耗的令牌数量
                    int newLevel = (int) Math.max(0, adjustedLevel - newTokens);  // 计算 填充令牌后的已消耗令牌数量 = 令牌桶上限 - 可填充的最大令牌数量
                    if (consumedTokens.compareAndSet(currentLevel, newLevel)) { // CAS 避免和正在消费令牌的线程冲突
                        return;
                    }
                }
            }
        }
    }
    // 消费 令牌
    private boolean consumeToken(int burstSize) {
        while (true) {  // 死循环，直到没有令牌，或者获取令牌成功
            int currentLevel = consumedTokens.get(); // 没有令牌
            if (currentLevel >= burstSize) {
                return false;
            }
            if (consumedTokens.compareAndSet(currentLevel, currentLevel + 1)) {
                return true;
            }
        }
    }

    public void reset() {
        consumedTokens.set(0);
        lastRefillTime.set(0);
    }
}
