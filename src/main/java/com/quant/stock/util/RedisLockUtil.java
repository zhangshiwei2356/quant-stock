package com.quant.stock.util;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;

/**
 * 分布式锁：优先 Redis，不可用时降级本地锁
 */
@Slf4j
@Component
public class RedisLockUtil {

    private static final String LOCK_PREFIX = "quant:lock:";

    @Autowired(required = false)
    private StringRedisTemplate stringRedisTemplate;

    private final Map<String, ReentrantLock> localLocks = new ConcurrentHashMap<String, ReentrantLock>();

    public boolean tryLock(String key, long expireSeconds) {
        String lockKey = LOCK_PREFIX + key;
        if (stringRedisTemplate != null) {
            try {
                Boolean ok = stringRedisTemplate.opsForValue()
                        .setIfAbsent(lockKey, "1", expireSeconds, TimeUnit.SECONDS);
                return Boolean.TRUE.equals(ok);
            } catch (Exception e) {
                log.warn("Redis锁失败，降级本地锁: {}", e.getMessage());
            }
        }
        ReentrantLock lock = localLocks.computeIfAbsent(lockKey, k -> new ReentrantLock());
        return lock.tryLock();
    }

    public void unlock(String key) {
        String lockKey = LOCK_PREFIX + key;
        if (stringRedisTemplate != null) {
            try {
                stringRedisTemplate.delete(lockKey);
                return;
            } catch (Exception e) {
                log.debug("Redis解锁失败: {}", e.getMessage());
            }
        }
        ReentrantLock lock = localLocks.get(lockKey);
        if (lock != null && lock.isHeldByCurrentThread()) {
            lock.unlock();
        }
    }

    public <T> T executeWithLock(String key, long expireSeconds, Supplier<T> supplier) {
        boolean locked = tryLock(key, expireSeconds);
        if (!locked) {
            throw new IllegalStateException("获取锁失败: " + key);
        }
        try {
            return supplier.get();
        } finally {
            unlock(key);
        }
    }
}
