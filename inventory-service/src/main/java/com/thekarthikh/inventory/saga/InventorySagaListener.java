package com.thekarthikh.inventory.saga;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.thekarthikh.inventory.entity.InventoryItem;
import com.thekarthikh.inventory.entity.InventoryReservation;
import com.thekarthikh.inventory.entity.InventorySagaEvent;
import com.thekarthikh.inventory.repository.InventoryRepository;
import com.thekarthikh.inventory.repository.InventoryReservationRepository;
import com.thekarthikh.inventory.repository.InventorySagaEventRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;
import java.util.UUID;

/**
 * Inventory Saga Listener.
 *
 * Listens for BOOKING_CREATED events and atomically reserves stock.
 * Publishes INVENTORY_RESERVED or INVENTORY_FAILED back to BookingService.
 *
 * Uses SELECT FOR UPDATE (Layer 1) on the inventory row to ensure that
 * concurrent Saga messages for the same item are serialized at DB level,
 * preventing any double-deduction of stock.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class InventorySagaListener {

    private static final String BOOKING_TOPIC   = "booking-events";
    private static final String INVENTORY_TOPIC = "inventory-events";

    private final InventoryRepository          inventoryRepository;
    private final InventoryReservationRepository reservationRepository;
    private final InventorySagaEventRepository eventRepository;
    private final ObjectMapper                 objectMapper;

    @KafkaListener(
            topics = BOOKING_TOPIC,
            groupId = "inventory-service-group",
            containerFactory = "kafkaListenerContainerFactory"
    )
    @Transactional
    public void handleBookingEvent(ConsumerRecord<String, String> record) {
        try {
            SagaMessage message = objectMapper.readValue(record.value(), SagaMessage.class);
            log.info("Inventory received event type={} bookingId={}", message.getEventType(), message.getBookingId());

            if ("BOOKING_CREATED".equals(message.getEventType())) {
                handleBookingCreated(message);
            } else if ("BOOKING_CANCELLED".equals(message.getEventType())) {
                handleBookingCancelled(message);
            }
        } catch (Exception e) {
            throw new IllegalStateException("Unable to process booking event", e);
        }
    }

    private void handleBookingCreated(SagaMessage message) throws Exception {
        UUID itemId   = message.getItemId();
        int quantity  = message.getQuantity();

        Optional<InventoryReservation> existing = reservationRepository.findByBookingIdWithLock(message.getBookingId());
        if (existing.isPresent()) {
            if ("RESERVED".equals(existing.get().getStatus())) {
                enqueueEvent("INVENTORY_RESERVED", message);
            }
            return;
        }

        // Layer 1: DB row lock — SELECT FOR UPDATE
        Optional<InventoryItem> opt = inventoryRepository.findByIdWithLock(itemId);
        if (opt.isEmpty()) {
            publishFailure(message, "Item not found: " + itemId);
            return;
        }

        // A duplicate can have waited on the item row lock while the first
        // transaction created the ledger record. Re-check after the lock so
        // the duplicate is acknowledged without a second stock deduction.
        Optional<InventoryReservation> afterItemLock =
                reservationRepository.findByBookingIdWithLock(message.getBookingId());
        if (afterItemLock.isPresent()) {
            if ("RESERVED".equals(afterItemLock.get().getStatus())) {
                enqueueEvent("INVENTORY_RESERVED", message);
            }
            return;
        }

        InventoryItem item = opt.get();
        if (item.getAvailable() < quantity) {
            log.warn("Insufficient inventory for itemId={} available={} requested={}",
                    itemId, item.getAvailable(), quantity);
            publishFailure(message, "Insufficient inventory: only " + item.getAvailable() + " available");
            return;
        }

        // Atomically deduct stock (version bump triggers optimistic lock on concurrent writers)
        item.setAvailable(item.getAvailable() - quantity);
        inventoryRepository.save(item);

        reservationRepository.save(InventoryReservation.builder()
                .bookingId(message.getBookingId())
                .itemId(itemId)
                .quantity(quantity)
                .status("RESERVED")
                .build());

        // Publish INVENTORY_RESERVED
        SagaMessage response = SagaMessage.builder()
                .eventType("INVENTORY_RESERVED")
                .bookingId(message.getBookingId())
                .userId(message.getUserId())
                .itemId(itemId)
                .quantity(quantity)
                .totalPrice(message.getTotalPrice())
                .timestamp(System.currentTimeMillis())
                .build();

        saveEventIfAbsent(response);

        log.info("Inventory reserved itemId={} quantity={} for bookingId={}",
                itemId, quantity, message.getBookingId());
    }

    private void handleBookingCancelled(SagaMessage message) throws Exception {
        UUID itemId = message.getItemId();
        Optional<InventoryReservation> reservation = reservationRepository.findByBookingIdWithLock(message.getBookingId());
        if (reservation.isEmpty()) {
            // Keep a tombstone so a stale BOOKING_CREATED event cannot reserve
            // stock after cancellation was already observed out of order.
            reservationRepository.save(InventoryReservation.builder()
                    .bookingId(message.getBookingId())
                    .itemId(itemId)
                    .quantity(message.getQuantity())
                    .status("CANCELLED")
                    .build());
            return;
        }
        if (!"RESERVED".equals(reservation.get().getStatus())) {
            return;
        }
        inventoryRepository.findByIdWithLock(itemId).ifPresent(item -> {
            item.setAvailable(Math.min(item.getAvailable() + reservation.get().getQuantity(), item.getTotalCapacity()));
            inventoryRepository.save(item);
            reservation.get().setStatus("RELEASED");
            reservationRepository.save(reservation.get());
            log.info("Inventory released itemId={} quantity={} for cancelled booking={}",
                    itemId, reservation.get().getQuantity(), message.getBookingId());
        });
    }

    private void publishFailure(SagaMessage original, String reason) throws Exception {
        SagaMessage failure = SagaMessage.builder()
                .eventType("INVENTORY_FAILED")
                .bookingId(original.getBookingId())
                .userId(original.getUserId())
                .itemId(original.getItemId())
                .quantity(original.getQuantity())
                .failureReason(reason)
                .timestamp(System.currentTimeMillis())
                .build();
        saveEventIfAbsent(failure);
        log.warn("Published INVENTORY_FAILED for bookingId={}: {}", original.getBookingId(), reason);
    }

    private void enqueueEvent(String eventType, SagaMessage original) throws Exception {
        SagaMessage response = SagaMessage.builder()
                .eventType(eventType)
                .bookingId(original.getBookingId())
                .userId(original.getUserId())
                .itemId(original.getItemId())
                .quantity(original.getQuantity())
                .totalPrice(original.getTotalPrice())
                .timestamp(System.currentTimeMillis())
                .build();
        saveEventIfAbsent(response);
    }

    private void saveEventIfAbsent(SagaMessage message) throws Exception {
        eventRepository.insertIfAbsent(
                message.getBookingId(),
                message.getEventType(),
                objectMapper.writeValueAsString(message));
    }
}
