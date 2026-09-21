package com.thekarthikh.booking.saga;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.thekarthikh.booking.entity.Booking;
import com.thekarthikh.booking.entity.SagaEvent;
import com.thekarthikh.booking.repository.BookingRepository;
import com.thekarthikh.booking.repository.SagaEventRepository;
import com.thekarthikh.booking.state.BookingStateMachine;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;
import java.util.UUID;

/**
 * Saga Choreography Coordinator (Booking Service side).
 *
 * Listens for events from InventoryService and reacts:
 *
 * INVENTORY_RESERVED → mark booking CONFIRMED, publish BOOKING_CONFIRMED
 * INVENTORY_FAILED   → mark booking CANCELLED, publish BOOKING_CANCELLED (compensation)
 *
 * Each handler is idempotent: if the booking is already in the target state
 * (e.g. duplicate Kafka delivery), the operation is skipped.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SagaChoreographyCoordinator {

    private final BookingRepository   bookingRepository;
    private final SagaEventRepository sagaEventRepository;
    private final ObjectMapper        objectMapper;

    @KafkaListener(
            topics = "inventory-events",
            groupId = "booking-saga-group",
            containerFactory = "kafkaListenerContainerFactory"
    )
    @Transactional
    public void handleInventoryEvent(ConsumerRecord<String, String> record) {
        try {
            SagaMessage message = objectMapper.readValue(record.value(), SagaMessage.class);
            log.info("Received saga event type={} bookingId={}", message.getEventType(), message.getBookingId());

            switch (message.getEventType()) {
                case SagaEventTypes.INVENTORY_RESERVED -> handleInventoryReserved(message);
                case SagaEventTypes.INVENTORY_FAILED   -> handleInventoryFailed(message);
                default -> log.warn("Unknown saga event type={}", message.getEventType());
            }
        } catch (Exception e) {
            throw new IllegalStateException("Unable to process inventory saga event", e);
        }
    }

    private void handleInventoryReserved(SagaMessage message) throws Exception {
        Optional<Booking> opt = bookingRepository.findByIdWithPessimisticLock(message.getBookingId());
        if (opt.isEmpty()) {
            log.error("Booking not found for INVENTORY_RESERVED: {}", message.getBookingId());
            return;
        }
        Booking booking = opt.get();
        if (!BookingStateMachine.canTransition(booking.getStatus(), "CONFIRMED")) {
            log.info("Booking {} is already terminal ({}) — ignoring duplicate/out-of-order reservation event",
                    booking.getId(), booking.getStatus());
            return;
        }

        booking.setStatus("CONFIRMED");
        booking.setSagaStatus("INVENTORY_RESERVED");
        bookingRepository.save(booking);

        // Publish BOOKING_CONFIRMED → NotificationService
        SagaMessage confirmed = SagaMessage.builder()
                .eventType(SagaEventTypes.BOOKING_CONFIRMED)
                .bookingId(booking.getId())
                .userId(booking.getUserId())
                .itemId(booking.getItemId())
                .quantity(booking.getQuantity())
                .totalPrice(booking.getTotalPrice())
                .timestamp(System.currentTimeMillis())
                .build();

        sagaEventRepository.save(SagaEvent.builder()
                .bookingId(booking.getId())
                .eventType(SagaEventTypes.BOOKING_CONFIRMED)
                .payload(objectMapper.writeValueAsString(confirmed))
                .build());

        log.info("Booking {} CONFIRMED via Saga choreography", booking.getId());
    }

    private void handleInventoryFailed(SagaMessage message) throws Exception {
        Optional<Booking> opt = bookingRepository.findByIdWithPessimisticLock(message.getBookingId());
        if (opt.isEmpty()) {
            log.error("Booking not found for INVENTORY_FAILED: {}", message.getBookingId());
            return;
        }
        Booking booking = opt.get();
        if (!BookingStateMachine.canTransition(booking.getStatus(), "CANCELLED")) {
            log.info("Booking {} is already terminal ({}) — skipping stale failure event",
                    booking.getId(), booking.getStatus());
            return;
        }

        BookingStateMachine.requireTransition(booking.getStatus(), "CANCELLED");
        booking.setStatus("CANCELLED");
        booking.setSagaStatus("INVENTORY_FAILED");
        booking.setFailureReason(message.getFailureReason());
        bookingRepository.save(booking);

        // Publish BOOKING_CANCELLED → NotificationService (compensation)
        SagaMessage cancelled = SagaMessage.builder()
                .eventType(SagaEventTypes.BOOKING_CANCELLED)
                .bookingId(booking.getId())
                .userId(booking.getUserId())
                .itemId(booking.getItemId())
                .quantity(booking.getQuantity())
                .failureReason(message.getFailureReason())
                .timestamp(System.currentTimeMillis())
                .build();

        sagaEventRepository.save(SagaEvent.builder()
                .bookingId(booking.getId())
                .eventType(SagaEventTypes.BOOKING_CANCELLED)
                .payload(objectMapper.writeValueAsString(cancelled))
                .build());

        log.warn("Booking {} CANCELLED via Saga compensation. Reason: {}",
                booking.getId(), message.getFailureReason());
    }
}
