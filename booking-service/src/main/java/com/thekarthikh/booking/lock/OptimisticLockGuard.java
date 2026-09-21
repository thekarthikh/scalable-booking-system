package com.thekarthikh.booking.lock;

import com.thekarthikh.booking.exception.OptimisticLockConflictException;
import com.thekarthikh.booking.repository.BookingRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.stereotype.Component;

import java.util.UUID;
import java.util.function.Supplier;

/**
 * Layer 3 of the 3-layer locking strategy: Optimistic Locking guard.
 *
 * Wraps any business operation in a retry loop that catches Spring's
 * OptimisticLockingFailureException and retries up to {@code MAX_RETRIES}
 * times with exponential back-off.
 *
 * This layer catches residual races that slip through Layers 1 & 2:
 *  - Redis lock expired mid-transaction (network partition / GC pause).
 *  - Two reads of the same entity version before either write commits.
 *
 * Combined contract of all three layers:
 * ┌──────────────────────────────────────────────────────────────────┐
 * │ Layer 1 (DB row lock)     – single DB node serialisation         │
 * │ Layer 2 (Redis dist lock) – cross-instance serialisation         │
 * │ Layer 3 (Optimistic lock) – last-resort safety net               │
 * └──────────────────────────────────────────────────────────────────┘
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OptimisticLockGuard {

    private static final int MAX_RETRIES = 5;
    private static final long BASE_BACKOFF_MS = 20;

    /**
     * Execute {@code operation}, retrying on optimistic lock conflicts up to
     * {@code MAX_RETRIES} times with exponential back-off.
     */
    public <T> T executeWithRetry(Supplier<T> operation, String context) {
        int attempt = 0;
        while (true) {
            try {
                return operation.get();
            } catch (OptimisticLockingFailureException ex) {
                attempt++;
                if (attempt > MAX_RETRIES) {
                    log.error("Optimistic lock conflict unresolved after {} attempts for context={}",
                            MAX_RETRIES, context);
                    throw new OptimisticLockConflictException(
                            "Concurrent modification detected — please retry: " + context);
                }
                long backoff = BASE_BACKOFF_MS * (1L << (attempt - 1)); // 20, 40, 80, 160, 320 ms
                log.warn("Optimistic lock conflict on attempt={} context={}, backing off {}ms",
                        attempt, context, backoff);
                try {
                    Thread.sleep(backoff);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw new OptimisticLockConflictException("Interrupted during optimistic lock retry");
                }
            }
        }
    }
}
