package com.thekarthikh.booking.idempotency;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Optional;

/**
 * Redis-backed idempotency layer.
 *
 * Keys format:  idempotency:{uuid-v4-key}
 * TTL:          24 hours (configurable)
 * Value:        serialised JSON response body so identical re-submissions
 *               return a cached result without re-executing business logic.
 *
 * This prevents double-bookings caused by:
 *  - Client retries after a timeout (retry storm scenario).
 *  - Network duplication at the load-balancer level.
 *  - Kafka at-least-once redelivery.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class IdempotencyService {

    private static final String KEY_PREFIX = "idempotency:";

    private final StringRedisTemplate redisTemplate;

    @Value("${redis.idempotency.ttl-hours:24}")
    private long ttlHours;

    /**
     * Look up a previously cached response for this idempotency key.
     *
     * @return the cached JSON string, or empty if this is a new request
     */
    public Optional<String> getResponse(String idempotencyKey) {
        String value = redisTemplate.opsForValue().get(KEY_PREFIX + idempotencyKey);
        if (value != null) {
            log.debug("Idempotency cache hit for key={}", idempotencyKey);
        }
        return Optional.ofNullable(value);
    }

    /**
     * Store the response for future duplicate requests.
     */
    public void saveResponse(String idempotencyKey, String responseJson) {
        redisTemplate.opsForValue().set(
                KEY_PREFIX + idempotencyKey,
                responseJson,
                Duration.ofHours(ttlHours)
        );
        log.debug("Idempotency cached key={} ttl={}h", idempotencyKey, ttlHours);
    }

    /**
     * Reserve an idempotency key as "in-flight" (SET NX) so that concurrent
     * requests with the same key are not processed simultaneously.
     *
     * @return true if the slot was freshly reserved (this request owns it)
     */
    public boolean reserveKey(String idempotencyKey) {
        Boolean reserved = redisTemplate.opsForValue()
                .setIfAbsent(
                        KEY_PREFIX + idempotencyKey + ":lock",
                        "processing",
                        Duration.ofSeconds(30)
                );
        return Boolean.TRUE.equals(reserved);
    }

    /** Remove the in-flight lock so a cached result can be stored. */
    public void releaseKeyLock(String idempotencyKey) {
        redisTemplate.delete(KEY_PREFIX + idempotencyKey + ":lock");
    }
}
