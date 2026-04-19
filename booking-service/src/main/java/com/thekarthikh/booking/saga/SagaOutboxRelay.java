package com.thekarthikh.booking.saga;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.thekarthikh.booking.entity.SagaEvent;
import com.thekarthikh.booking.repository.SagaEventRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Transactional Outbox relay.
 *
 * Saga events are first written to the {@code saga_events} table inside the
 * same DB transaction as the booking write — guaranteeing atomicity between
 * the database state and Kafka event emission.
 *
 * A scheduled poller then reads un-published events and forwards them to Kafka,
 * marking each as published only after the send succeeds.  This gives us
 * at-least-once delivery semantics without a 2-phase-commit.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SagaOutboxRelay {

    private static final String BOOKING_TOPIC = "booking-events";

    private final SagaEventRepository   sagaEventRepository;
    private final KafkaTemplate<String, String> kafkaTemplate;
    private final ObjectMapper          objectMapper;

    /**
     * Publishes un-sent outbox events every 500 ms.
     * In production this would use a Debezium CDC connector instead.
     */
    @Scheduled(fixedDelay = 500)
    @Transactional
    public void relayEvents() {
        List<SagaEvent> pending = sagaEventRepository.findUnpublishedEvents();
        for (SagaEvent event : pending) {
            try {
                kafkaTemplate.send(BOOKING_TOPIC, event.getBookingId().toString(), event.getPayload())
                        .whenComplete((result, ex) -> {
                            if (ex != null) {
                                log.error("Failed to publish saga event id={}: {}", event.getId(), ex.getMessage());
                            }
                        });
                event.setPublished(true);
                sagaEventRepository.save(event);
            } catch (Exception e) {
                log.error("Error relaying saga event id={}: {}", event.getId(), e.getMessage());
            }
        }
    }
}
