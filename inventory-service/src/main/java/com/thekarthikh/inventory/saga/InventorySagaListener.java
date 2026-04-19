package com.thekarthikh.inventory.saga;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.thekarthikh.inventory.entity.InventoryItem;
import com.thekarthikh.inventory.repository.InventoryRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.core.KafkaTemplate;
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
    private final KafkaTemplate<String, String> kafkaTemplate;
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
            log.error("Error processing booking event in inventory: {}", e.getMessage(), e);
        }
    }

    private void handleBookingCreated(SagaMessage message) throws Exception {
        UUID itemId   = message.getItemId();
        int quantity  = message.getQuantity();

        // Layer 1: DB row lock — SELECT FOR UPDATE
        Optional<InventoryItem> opt = inventoryRepository.findByIdWithLock(itemId);
        if (opt.isEmpty()) {
            publishFailure(message, "Item not found: " + itemId);
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

        kafkaTemplate.send(INVENTORY_TOPIC, message.getBookingId().toString(),
                objectMapper.writeValueAsString(response));

        log.info("Inventory reserved itemId={} quantity={} for bookingId={}",
                itemId, quantity, message.getBookingId());
    }

    private void handleBookingCancelled(SagaMessage message) throws Exception {
        UUID itemId = message.getItemId();
        inventoryRepository.findByIdWithLock(itemId).ifPresent(item -> {
            item.setAvailable(Math.min(item.getAvailable() + message.getQuantity(), item.getTotalCapacity()));
            inventoryRepository.save(item);
            log.info("Inventory released itemId={} quantity={} for cancelled booking={}",
                    itemId, message.getQuantity(), message.getBookingId());
        });

        SagaMessage response = SagaMessage.builder()
                .eventType("INVENTORY_RELEASED")
                .bookingId(message.getBookingId())
                .itemId(itemId)
                .quantity(message.getQuantity())
                .timestamp(System.currentTimeMillis())
                .build();
        try {
            kafkaTemplate.send(INVENTORY_TOPIC, message.getBookingId().toString(),
                    objectMapper.writeValueAsString(response));
        } catch (Exception e) {
            log.error("Failed to publish INVENTORY_RELEASED", e);
        }
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
        kafkaTemplate.send(INVENTORY_TOPIC, original.getBookingId().toString(),
                objectMapper.writeValueAsString(failure));
        log.warn("Published INVENTORY_FAILED for bookingId={}: {}", original.getBookingId(), reason);
    }
}
