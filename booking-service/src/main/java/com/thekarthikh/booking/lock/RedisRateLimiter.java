package com.thekarthikh.booking.lock;

import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Redis Token-Bucket Rate Limiter using a Lua script.
 *
 * Algorithm (executed atomically on the Redis server):
 *  1. Read current tokens and last refill timestamp.
 *  2. Compute elapsed time since last refill.
 *  3. Add (elapsed_seconds × refill_rate) tokens, capped at capacity.
 *  4. If tokens ≥ 1, decrement and allow the request.
 *  5. Otherwise, reject.
 *
 * Running inside a single Lua script guarantees atomicity — no two clients
 * can race on the same key.  This satisfies the 10K-concurrent-user target
 * without a DB round-trip for every request.
 */
@Slf4j
@Component
public class RedisRateLimiter {

    private static final String KEY_PREFIX = "rate:";

    /** Lua script: token-bucket, returns 1 (allowed) or 0 (rejected). */
    private static final DefaultRedisScript<Long> TOKEN_BUCKET_SCRIPT;

    static {
        TOKEN_BUCKET_SCRIPT = new DefaultRedisScript<>();
        TOKEN_BUCKET_SCRIPT.setResultType(Long.class);
        TOKEN_BUCKET_SCRIPT.setScriptText("""
                local key          = KEYS[1]
                local capacity     = tonumber(ARGV[1])
                local refill_rate  = tonumber(ARGV[2])
                local now          = tonumber(ARGV[3])

                -- Read bucket state
                local data         = redis.call('HMGET', key, 'tokens', 'last_refill')
                local tokens       = tonumber(data[1]) or capacity
                local last_refill  = tonumber(data[2]) or now

                -- Refill tokens based on elapsed time
                local elapsed      = math.max(0, now - last_refill)
                local new_tokens   = math.min(capacity, tokens + elapsed * refill_rate)

                -- Attempt to consume one token
                if new_tokens >= 1 then
                    new_tokens = new_tokens - 1
                    redis.call('HMSET', key, 'tokens', new_tokens, 'last_refill', now)
                    redis.call('EXPIRE', key, 3600)
                    return 1
                else
                    redis.call('HMSET', key, 'tokens', new_tokens, 'last_refill', now)
                    redis.call('EXPIRE', key, 3600)
                    return 0
                end
                """);
    }

    private final StringRedisTemplate redisTemplate;
    private final long capacity;
    private final long refillRate;

    public RedisRateLimiter(
            StringRedisTemplate redisTemplate,
            @org.springframework.beans.factory.annotation.Value("${redis.rate-limiter.capacity:1000}") long capacity,
            @org.springframework.beans.factory.annotation.Value("${redis.rate-limiter.refill-rate:200}") long refillRate) {
        this.redisTemplate = redisTemplate;
        this.capacity = capacity;
        this.refillRate = refillRate;
    }

    /**
     * Check and consume one token for the given client identifier.
     *
     * @param clientId  per-user or per-IP identifier
     * @return {@code true} if the request is allowed; {@code false} if rate-limited
     */
    public boolean tryAcquire(String clientId) {
        String key    = KEY_PREFIX + clientId;
        long   nowSec = System.currentTimeMillis() / 1000;

        Long result = redisTemplate.execute(
                TOKEN_BUCKET_SCRIPT,
                List.of(key),
                String.valueOf(capacity),
                String.valueOf(refillRate),
                String.valueOf(nowSec)
        );

        boolean allowed = Long.valueOf(1L).equals(result);
        if (!allowed) {
            log.warn("Rate limit exceeded for clientId={}", clientId);
        }
        return allowed;
    }
}
