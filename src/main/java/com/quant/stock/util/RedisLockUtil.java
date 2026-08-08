package com.quant.stock.util;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;

/**
 * 分布式锁：优先 Redis（token 校验解锁），不可用时降级本地可重入锁。
 */
@Slf4j
@Component
public class RedisLockUtil {

    private static final String LOCK_PREFIX = "quant:lock:";
    private static final String LOCAL_TOKEN = "local";

    @Autowired(required = false)
    private StringRedisTemplate stringRedisTemplate;

    private final Map<String, ReentrantLock> localLocks = new ConcurrentHashMap<String, ReentrantLock>();
    private final ThreadLocal<Map<String, Hold>> heldByThread =
            new ThreadLocal<Map<String, Hold>>() {
                @Override
                protected Map<String, Hold> initialValue() {
                    return new HashMap<String, Hold>();
                }
            };

    /** 尝试获取分布式锁（可重入）；Redis 不可用时降级本地锁。 */
    public boolean tryLock(String key, long expireSeconds) {
        String lockKey = LOCK_PREFIX + key;
        Map<String, Hold> held = heldByThread.get();
        Hold existing = held.get(lockKey);
        if (existing != null) {
            existing.depth++;
            return true;
        }
        long ttl = Math.max(1L, expireSeconds);
        if (stringRedisTemplate != null) {
            try {
                String token = UUID.randomUUID().toString().replace("-", "");
                Boolean ok = stringRedisTemplate.opsForValue()
                        .setIfAbsent(lockKey, token, ttl, TimeUnit.SECONDS);
                if (Boolean.TRUE.equals(ok)) {
                    held.put(lockKey, new Hold(token, false));
                    return true;
                }
                return false;
            } catch (Exception e) {
                log.error("Redis锁失败，降级本地锁: {}", e.getMessage(), e);
            }
        }
        ReentrantLock lock = localLocks.computeIfAbsent(lockKey, k -> new ReentrantLock());
        if (lock.tryLock()) {
            held.put(lockKey, new Hold(LOCAL_TOKEN, true));
            return true;
        }
        return false;
    }

    /** 释放当前线程持有的锁（token 校验后删 Redis 键或解本地锁）。 */
    public void unlock(String key) {
        String lockKey = LOCK_PREFIX + key;
        Map<String, Hold> held = heldByThread.get();
        Hold h = held.get(lockKey);
        if (h == null) {
            return;
        }
        h.depth--;
        if (h.depth > 0) {
            return;
        }
        held.remove(lockKey);
        if (h.local) {
            ReentrantLock local = localLocks.get(lockKey);
            if (local != null && local.isHeldByCurrentThread()) {
                local.unlock();
            }
            return;
        }
        if (stringRedisTemplate != null && h.token != null) {
            try {
                String cur = stringRedisTemplate.opsForValue().get(lockKey);
                if (h.token.equals(cur)) {
                    stringRedisTemplate.delete(lockKey);
                }
            } catch (Exception e) {
                log.error("Redis解锁失败: {}", e.getMessage(), e);
            }
        }
    }

    /** 在持锁状态下执行 supplier，失败则抛 IllegalStateException。 */
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

    private static final class Hold {
        final String token;
        final boolean local;
        int depth = 1;

        Hold(String token, boolean local) {
            this.token = token;
            this.local = local;
        }
    }
}
