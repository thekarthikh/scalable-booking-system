package com.thekarthikh.booking.lock;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.UUID;

/**
 * Layer 2 of the 3-layer locking strategy: Redis Distributed Lock.
 *
 * Uses SET NX PX (atomic set-if-not-exists with millisecond expiry) as a
 * best-effort single-Redis lease. The lock value is a unique nonce so that
 * only the owner can release it while the lease is still present.
 *
 * Ordering of guarantees:
 *   Layer 1 (DB row lock)     – serialises within a single DB transaction.
 *   Layer 2 (Redis dist lock) – coordinates multiple service instances when
 *                               the lease and Redis instance are healthy.
 *   Layer 3 (Optimistic lock) – catches any edge cases the redis lock misses
 *                               (e.g. lock TTL expiry during long transaction).
 */
@Slf4j
@Component
public class RedisDistributedLock {

    private static final String KEY_PREFIX = "booking:lock:";

    private final StringRedisTemplate redisTemplate;
    private final long lockTtlMs;
    private final long retryIntervalMs;
    private final int maxRetries;

    public RedisDistributedLock(
            StringRedisTemplate redisTemplate,
            @Value("${redis.distributed-lock.ttl-ms:30000}") long lockTtlMs,
            @Value("${redis.distributed-lock.retry-interval-ms:50}") long retryIntervalMs,
            @Value("${redis.distributed-lock.max-retries:10}") int maxRetries) {
        this.redisTemplate = redisTemplate;
        this.lockTtlMs = lockTtlMs;
        this.retryIntervalMs = retryIntervalMs;
        this.maxRetries = maxRetries;
    }

    /**
     * Acquire the lock for {@code resourceId}.
     *
     * @return the lock nonce (must be passed to {@link #releaseLock}) or
     *         {@code null} if the lock could not be acquired within the retry budget.
     */
    public String acquireLock(String resourceId) {
        String key   = KEY_PREFIX + resourceId;
        String nonce = UUID.randomUUID().toString();

        for (int attempt = 0; attempt <= maxRetries; attempt++) {
            Boolean acquired;
            try {
                acquired = redisTemplate.opsForValue().setIfAbsent(key, nonce, Duration.ofMillis(lockTtlMs));
            } catch (DataAccessException ex) {
                log.warn("Redis unavailable for booking lock; continuing with database concurrency controls", ex);
                return "redis-bypass:" + nonce;
            }

            if (Boolean.TRUE.equals(acquired)) {
                log.debug("Acquired Redis lock for resource={} nonce={} attempt={}", resourceId, nonce, attempt);
                return nonce;
            }

            if (attempt < maxRetries) {
                try {
                    Thread.sleep(retryIntervalMs);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    return null;
                }
            }
        }

        log.warn("Failed to acquire Redis lock for resource={} after {} retries", resourceId, maxRetries);
        return null;
    }

    /**
     * Release the lock only if we still own it (compare-and-delete via Lua).
     */
    public void releaseLock(String resourceId, String nonce) {
        if (nonce == null) return;
        String key = KEY_PREFIX + resourceId;
        // Atomic compare-and-delete: only delete if value equals our nonce
        String lua = """
                if redis.call('get', KEYS[1]) == ARGV[1] then
                    return redis.call('del', KEYS[1])
                else
                    return 0
                end
                """;
        try {
            redisTemplate.execute(new org.springframework.data.redis.core.script.DefaultRedisScript<>(lua, Long.class),
                    java.util.List.of(key), nonce);
        } catch (DataAccessException ex) {
            log.warn("Redis unavailable while releasing booking lock for resource={}", resourceId);
        }
        log.debug("Released Redis lock for resource={}", resourceId);
    }
}
