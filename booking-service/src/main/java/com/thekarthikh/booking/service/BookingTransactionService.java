package com.thekarthikh.booking.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.thekarthikh.booking.client.InventoryItemDto;
import com.thekarthikh.booking.dto.BookingResponse;
import com.thekarthikh.booking.dto.CreateBookingRequest;
import com.thekarthikh.booking.entity.Booking;
import com.thekarthikh.booking.entity.SagaEvent;
import com.thekarthikh.booking.exception.DuplicateBookingException;
import com.thekarthikh.booking.repository.BookingRepository;
import com.thekarthikh.booking.repository.SagaEventRepository;
import com.thekarthikh.booking.saga.SagaEventTypes;
import com.thekarthikh.booking.saga.SagaMessage;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;

/** Owns the database transaction for creating the booking and its outbox row. */
@Service
@RequiredArgsConstructor
public class BookingTransactionService {

    private final BookingRepository bookingRepository;
    private final SagaEventRepository sagaEventRepository;
    private final ObjectMapper objectMapper;

    @Transactional
    public BookingResponse createPendingBooking(UUID userId, CreateBookingRequest req, InventoryItemDto item) {
        var existing = bookingRepository.findByIdempotencyKey(req.getIdempotencyKey());
        if (existing.isPresent()) {
            Booking booking = existing.get();
            if (!booking.getUserId().equals(userId)
                    || !booking.getItemId().equals(req.getItemId())
                    || !booking.getQuantity().equals(req.getQuantity())) {
                throw new DuplicateBookingException("Idempotency key is already bound to a different request");
            }
            return toResponse(booking);
        }

        BigDecimal totalPrice = item.getPrice().multiply(BigDecimal.valueOf(req.getQuantity()));
        Booking booking = bookingRepository.save(Booking.builder()
                .idempotencyKey(req.getIdempotencyKey())
                .userId(userId)
                .itemId(req.getItemId())
                .quantity(req.getQuantity())
                .totalPrice(totalPrice)
                .status("PENDING")
                .sagaStatus("STARTED")
                .build());

        try {
            SagaMessage message = SagaMessage.builder()
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
                    .payload(objectMapper.writeValueAsString(message))
                    .build());
        } catch (Exception ex) {
            throw new IllegalStateException("Failed to enqueue booking saga event", ex);
        }
        return toResponse(booking);
    }

    @Transactional(readOnly = true)
    public Optional<BookingResponse> findByIdempotencyKey(String idempotencyKey) {
        return bookingRepository.findByIdempotencyKey(idempotencyKey).map(this::toResponse);
    }

    @Transactional(readOnly = true)
    public Optional<BookingResponse> findMatchingByIdempotencyKey(UUID userId, CreateBookingRequest req) {
        return bookingRepository.findByIdempotencyKey(req.getIdempotencyKey())
                .map(existing -> {
                    if (!existing.getUserId().equals(userId)
                            || !existing.getItemId().equals(req.getItemId())
                            || !existing.getQuantity().equals(req.getQuantity())) {
                        throw new DuplicateBookingException(
                                "Idempotency key is already bound to a different request");
                    }
                    return toResponse(existing);
                });
    }

    private BookingResponse toResponse(Booking b) {
        return BookingResponse.builder()
                .id(b.getId()).idempotencyKey(b.getIdempotencyKey()).userId(b.getUserId())
                .itemId(b.getItemId()).quantity(b.getQuantity()).totalPrice(b.getTotalPrice())
                .status(b.getStatus()).sagaStatus(b.getSagaStatus()).failureReason(b.getFailureReason())
                .createdAt(b.getCreatedAt()).updatedAt(b.getUpdatedAt()).build();
    }
}
