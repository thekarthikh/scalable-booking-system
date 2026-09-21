package com.thekarthikh.inventory.saga;

import com.thekarthikh.inventory.entity.InventorySagaEvent;
import com.thekarthikh.inventory.repository.InventorySagaEventRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;

@Slf4j
@Component
@RequiredArgsConstructor
public class InventoryOutboxRelay {

    private static final String INVENTORY_TOPIC = "inventory-events";

    private final InventorySagaEventRepository eventRepository;
    private final KafkaTemplate<String, String> kafkaTemplate;

    @Value("${saga.outbox.send-timeout:5s}")
    private Duration sendTimeout;

    @Scheduled(fixedDelayString = "${saga.outbox.poll-interval:500}")
    @Transactional
    public void relayEvents() {
        for (InventorySagaEvent event : eventRepository.findUnpublishedEventsForUpdate()) {
            try {
                kafkaTemplate.send(INVENTORY_TOPIC, event.getBookingId().toString(), event.getPayload())
                        .get(sendTimeout.toMillis(), java.util.concurrent.TimeUnit.MILLISECONDS);
                event.setPublished(true);
                eventRepository.save(event);
            } catch (Exception ex) {
                log.error("Inventory outbox publish failed id={} type={}: {}",
                        event.getId(), event.getEventType(), ex.getMessage());
                break;
            }
        }
    }
}
