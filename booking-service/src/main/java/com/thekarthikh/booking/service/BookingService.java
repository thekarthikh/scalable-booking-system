package com.thekarthikh.booking.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.thekarthikh.booking.client.InventoryClient;
import com.thekarthikh.booking.client.InventoryItemDto;
import com.thekarthikh.booking.dto.BookingResponse;
import com.thekarthikh.booking.dto.CreateBookingRequest;
import com.thekarthikh.booking.entity.Booking;
import com.thekarthikh.booking.entity.SagaEvent;
import com.thekarthikh.booking.exception.*;
import com.thekarthikh.booking.idempotency.IdempotencyService;
import com.thekarthikh.booking.lock.OptimisticLockGuard;
import com.thekarthikh.booking.lock.RedisDistributedLock;
import com.thekarthikh.booking.lock.RedisRateLimiter;
import com.thekarthikh.booking.repository.BookingRepository;
import com.thekarthikh.booking.repository.SagaEventRepository;
import com.thekarthikh.booking.saga.SagaEventTypes;
import com.thekarthikh.booking.saga.SagaMessage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Core booking business logic.
 *
 * ══════════════════════════════════════════════════════════════════════
 * ANTI-DOUBLE-BOOKING GUARANTEE — createBooking critical path:
 *
 * Step 1 │ Idempotency check (Redis)
 *         │ ➜ Replay cached response if idempotency key already exists.
 *         │   Prevents double-booking from client retries / retry storms.
 *
 * Step 2 │ Rate limiter (Redis token-bucket Lua)
 *         │ ➜ Rejects requests beyond 1000 burst / 200 rps per user.
 *         │   Prevents thundering-herd overload.
 *
 * Step 3 │ Layer 2 — Redis distributed lock
 *         │ ➜ Serialises concurrent requests for the same itemId across
 *         │   all service instances in the cluster.
 *
 * Step 4 │ Layer 1 — DB row lock (SELECT FOR UPDATE inside @Transactional)
 *         │ ➜ Serialises within a single PostgreSQL node.
 *         │   Eliminates race between two threads in the same JVM.
 *
 * Step 5 │ Layer 3 — Optimistic lock retry wrapper
 *         │ ➜ Catches any residual version conflicts (e.g. lock TTL expiry
 *         │   during a GC pause) and retries with exponential back-off.
 *
 * Step 6 │ Saga choreography — BOOKING_CREATED event → InventoryService
 *         │ ➜ Published via transactional outbox so the event is guaranteed
 *         │   to reach Kafka only after the DB transaction commits.
 * ══════════════════════════════════════════════════════════════════════
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BookingService {

    private final BookingRepository    bookingRepository;
    private final SagaEventRepository  sagaEventRepository;
    private final IdempotencyService   idempotencyService;
    private final RedisDistributedLock redisDistributedLock;
    private final OptimisticLockGuard  optimisticLockGuard;
    private final RedisRateLimiter     rateLimiter;
    private final InventoryClient      inventoryClient;
    private final ObjectMapper         objectMapper;

    // ─────────────────────────────────────────────────────────────────
    // PUBLIC API
    // ─────────────────────────────────────────────────────────────────

    public BookingResponse createBooking(UUID userId, CreateBookingRequest req) {
        // ── Step 1: Idempotency ──────────────────────────────────────
        var cached = idempotencyService.getResponse(req.getIdempotencyKey());
        if (cached.isPresent()) {
            log.info("Returning cached response for idempotency key={}", req.getIdempotencyKey());
            return deserialize(cached.get());
        }

        // ── Step 2: Rate limiter ─────────────────────────────────────
        if (!rateLimiter.tryAcquire(userId.toString())) {
            throw new RateLimitExceededException("Rate limit exceeded. Please slow down.");
        }

        // ── Step 3: Redis distributed lock ───────────────────────────
        String lockNonce = redisDistributedLock.acquireLock(req.getItemId().toString());
        if (lockNonce == null) {
            throw new OptimisticLockConflictException(
                    "System is busy processing another booking for this item. Please retry.");
        }

        try {
            // ── Steps 4 & 5 wrapped in optimistic-lock retry ─────────
            BookingResponse response = optimisticLockGuard.executeWithRetry(
                    () -> executeCreateBooking(userId, req),
                    "createBooking:item=" + req.getItemId()
            );

            // ── Cache response for idempotency ────────────────────────
            idempotencyService.saveResponse(req.getIdempotencyKey(), serialize(response));
            return response;

        } finally {
            redisDistributedLock.releaseLock(req.getItemId().toString(), lockNonce);
        }
    }

    @Transactional(readOnly = true)
    public BookingResponse getBooking(UUID bookingId, UUID requestingUserId) {
        Booking booking = bookingRepository.findById(bookingId)
                .orElseThrow(() -> new BookingNotFoundException("Booking not found: " + bookingId));
        if (!booking.getUserId().equals(requestingUserId)) {
            throw new BookingNotFoundException("Booking not found: " + bookingId);
        }
        return toResponse(booking);
    }

    @Transactional(readOnly = true)
    public List<BookingResponse> getUserBookings(UUID userId) {
        return bookingRepository.findByUserIdOrderByCreatedAtDesc(userId)
                .stream().map(this::toResponse).collect(Collectors.toList());
    }

    @Transactional
    public BookingResponse cancelBooking(UUID bookingId, UUID requestingUserId) {
        Booking booking = bookingRepository.findById(bookingId)
                .orElseThrow(() -> new BookingNotFoundException("Booking not found: " + bookingId));
        if (!booking.getUserId().equals(requestingUserId)) {
            throw new BookingNotFoundException("Booking not found: " + bookingId);
        }
        if ("CANCELLED".equals(booking.getStatus())) {
            return toResponse(booking);
        }
        booking.setStatus("CANCELLED");
        booking.setSagaStatus("CANCELLED_BY_USER");
        bookingRepository.save(booking);

        try {
            SagaMessage msg = SagaMessage.builder()
                    .eventType(SagaEventTypes.BOOKING_CANCELLED)
                    .bookingId(booking.getId())
                    .userId(booking.getUserId())
                    .itemId(booking.getItemId())
                    .quantity(booking.getQuantity())
                    .failureReason("Cancelled by user")
                    .timestamp(System.currentTimeMillis())
                    .build();
            sagaEventRepository.save(SagaEvent.builder()
                    .bookingId(booking.getId())
                    .eventType(SagaEventTypes.BOOKING_CANCELLED)
                    .payload(objectMapper.writeValueAsString(msg))
                    .build());
        } catch (Exception e) {
            log.error("Failed to enqueue cancellation saga event", e);
        }
        return toResponse(booking);
    }

    // ─────────────────────────────────────────────────────────────────
    // PRIVATE HELPERS
    // ─────────────────────────────────────────────────────────────────

    /**
     * Layers 1 & 4: DB transaction  +  core business logic.
     * Called inside the optimistic-lock retry loop (Layer 3).
     */
    @Transactional
    protected BookingResponse executeCreateBooking(UUID userId, CreateBookingRequest req) {
        // Layer 1 — check idempotency key uniqueness at DB level
        if (bookingRepository.findByIdempotencyKey(req.getIdempotencyKey()).isPresent()) {
            throw new DuplicateBookingException(
                    "Booking with idempotency key " + req.getIdempotencyKey() + " already exists");
        }

        // Fetch item price from InventoryService
        InventoryItemDto item = inventoryClient.getItem(req.getItemId());
        if (item == null) {
            throw new RuntimeException("InventoryService unavailable — please retry");
        }

        BigDecimal totalPrice = item.getPrice().multiply(BigDecimal.valueOf(req.getQuantity()));

        Booking booking = Booking.builder()
                .idempotencyKey(req.getIdempotencyKey())
                .userId(userId)
                .itemId(req.getItemId())
                .quantity(req.getQuantity())
                .totalPrice(totalPrice)
                .status("PENDING")
                .sagaStatus("STARTED")
                .build();
        booking = bookingRepository.save(booking);

        // Publish BOOKING_CREATED to Saga outbox (same transaction)
        try {
            SagaMessage msg = SagaMessage.builder()
                    .eventType(SagaEventTypes.BOOKING_CREATED)
                    .bookingId(booking.getId())
                    .userId(userId)
                    .itemId(req.getItemId())
                    .quantity(req.getQuantity())
                    .totalPrice(totalPrice)
                    .timestamp(System.currentTimeMillis())
                    .build();
            sagaEventRepository.save(SagaEvent.builder()
                    .bookingId(booking.getId())
                    .eventType(SagaEventTypes.BOOKING_CREATED)
                    .payload(objectMapper.writeValueAsString(msg))
                    .build());
        } catch (Exception e) {
            throw new RuntimeException("Failed to enqueue saga event", e);
        }

        log.info("Booking created id={} userId={} itemId={}", booking.getId(), userId, req.getItemId());
        return toResponse(booking);
    }

    private BookingResponse toResponse(Booking b) {
        return BookingResponse.builder()
                .id(b.getId())
                .idempotencyKey(b.getIdempotencyKey())
                .userId(b.getUserId())
                .itemId(b.getItemId())
                .quantity(b.getQuantity())
                .totalPrice(b.getTotalPrice())
                .status(b.getStatus())
                .sagaStatus(b.getSagaStatus())
                .failureReason(b.getFailureReason())
                .createdAt(b.getCreatedAt())
                .updatedAt(b.getUpdatedAt())
                .build();
    }

    private String serialize(BookingResponse resp) {
        try {
            return objectMapper.writeValueAsString(resp);
        } catch (Exception e) {
            throw new RuntimeException("Serialization error", e);
        }
    }

    private BookingResponse deserialize(String json) {
        try {
            return objectMapper.readValue(json, BookingResponse.class);
        } catch (Exception e) {
            throw new RuntimeException("Deserialization error", e);
        }
    }
}
