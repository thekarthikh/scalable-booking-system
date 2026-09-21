package com.thekarthikh.booking.idempotency;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

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
    public Optional<String> getResponse(String idempotencyKey, String requestFingerprint) {
        String value;
        try {
            value = redisTemplate.opsForValue().get(KEY_PREFIX + idempotencyKey);
        } catch (DataAccessException ex) {
            log.warn("Redis unavailable during idempotency lookup; relying on database uniqueness", ex);
            return Optional.empty();
        }
        if (value != null) {
            int separator = value.indexOf('|');
            if (separator > 0) {
                String cachedFingerprint = value.substring(0, separator);
                if (!MessageDigest.isEqual(
                        cachedFingerprint.getBytes(StandardCharsets.UTF_8),
                        requestFingerprint.getBytes(StandardCharsets.UTF_8))) {
                    throw new com.thekarthikh.booking.exception.IdempotencyConflictException(
                            "Idempotency key is already bound to a different request");
                }
                try {
                    return Optional.of(new String(
                            Base64.getUrlDecoder().decode(value.substring(separator + 1)),
                            StandardCharsets.UTF_8));
                } catch (IllegalArgumentException malformedCache) {
                    log.warn("Ignoring malformed idempotency cache entry for key={}", idempotencyKey);
                }
            }
            // Legacy cache values are intentionally ignored. The database remains
            // the source of truth and will validate the request binding.
            log.debug("Idempotency cache hit for key={}", idempotencyKey);
        }
        return Optional.empty();
    }

    /**
     * Store the response for future duplicate requests.
     */
    public void saveResponse(String idempotencyKey, String requestFingerprint, String responseJson) {
        try {
            String encoded = requestFingerprint + "|" + Base64.getUrlEncoder().withoutPadding()
                    .encodeToString(responseJson.getBytes(StandardCharsets.UTF_8));
            redisTemplate.opsForValue().set(KEY_PREFIX + idempotencyKey, encoded, Duration.ofHours(ttlHours));
        } catch (DataAccessException ex) {
            log.warn("Redis unavailable while caching idempotency response", ex);
        }
        log.debug("Idempotency cached key={} ttl={}h", idempotencyKey, ttlHours);
    }

    /**
     * Reserve an idempotency key as "in-flight" (SET NX) so that concurrent
     * requests with the same key are not processed simultaneously.
     *
     * @return a request-owned nonce, a Redis-bypass marker, or null when another
     * request currently owns the key
     */
    public String reserveKey(String idempotencyKey) {
        String nonce = UUID.randomUUID().toString();
        try {
            Boolean reserved = redisTemplate.opsForValue().setIfAbsent(
                    KEY_PREFIX + idempotencyKey + ":lock", nonce, Duration.ofSeconds(30));
            return Boolean.TRUE.equals(reserved) ? nonce : null;
        } catch (DataAccessException ex) {
            log.warn("Redis unavailable while reserving idempotency key", ex);
            return "redis-bypass:" + nonce;
        }
    }

    /** Remove the in-flight lock only when this request still owns it. */
    public void releaseKeyLock(String idempotencyKey, String nonce) {
        if (nonce == null || nonce.startsWith("redis-bypass:")) {
            return;
        }
        String lua = """
                if redis.call('get', KEYS[1]) == ARGV[1] then
                    return redis.call('del', KEYS[1])
                else
                    return 0
                end
                """;
        try {
            redisTemplate.execute(new org.springframework.data.redis.core.script.DefaultRedisScript<>(lua, Long.class),
                    List.of(KEY_PREFIX + idempotencyKey + ":lock"), nonce);
        } catch (DataAccessException ex) {
            log.warn("Redis unavailable while releasing idempotency key", ex);
        }
    }

    public static String fingerprint(UUID userId, UUID itemId, int quantity) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] value = (userId + "|" + itemId + "|" + quantity).getBytes(StandardCharsets.UTF_8);
            return Base64.getUrlEncoder().withoutPadding().encodeToString(digest.digest(value));
        } catch (Exception ex) {
            throw new IllegalStateException("Unable to create idempotency fingerprint", ex);
        }
    }
}
