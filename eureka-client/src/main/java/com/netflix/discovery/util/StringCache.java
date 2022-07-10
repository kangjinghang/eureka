package com.netflix.discovery.util;

import java.lang.ref.WeakReference;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * An alternative to {@link String#intern()} with no capacity constraints.
 *
 * @author Tomasz Bak
 */
public class StringCache { // 字符串缓存

    public static final int LENGTH_LIMIT = 38;
    // 字符串缓存单例
    private static final StringCache INSTANCE = new StringCache();
    // 读写锁，保证读写互斥
    private final ReadWriteLock lock = new ReentrantReadWriteLock();
    private final Map<String, WeakReference<String>> cache = new WeakHashMap<String, WeakReference<String>>(); // 缓存哈希表。使用 WeakHashMap，当 StringCache 被回收时，其对应的值一起被移除
    private final int lengthLimit; // 缓存字符串最大长度。默认值：38

    public StringCache() {
        this(LENGTH_LIMIT);
    }

    public StringCache(int lengthLimit) {
        this.lengthLimit = lengthLimit;
    }
    // 得字符串缓存。若缓存不存在，则进行缓存。和 String#intern() 的逻辑相同，区别在于 cache 支持自动扩容
    public String cachedValueOf(final String str) {
        if (str != null && (lengthLimit < 0 || str.length() <= lengthLimit)) {
            // Return value from cache if available
            lock.readLock().lock(); // 读锁，读取缓存
            try {
                WeakReference<String> ref = cache.get(str);
                if (ref != null) {
                    return ref.get();
                }
            } finally {
                lock.readLock().unlock();
            }
            // 缓存不存在，写锁，写入缓存
            // Update cache with new content
            lock.writeLock().lock();
            try {
                WeakReference<String> ref = cache.get(str);
                if (ref != null) {
                    return ref.get();
                }
                cache.put(str, new WeakReference<>(str));
            } finally {
                lock.writeLock().unlock();
            }
            return str;
        }
        return str;
    }
    // 缓存大小
    public int size() {
        lock.readLock().lock();
        try {
            return cache.size();
        } finally {
            lock.readLock().unlock();
        }
    }
    //  静态方法，使用 INSTANCE 获取缓存字符串
    public static String intern(String original) {
        return INSTANCE.cachedValueOf(original);
    }
}
