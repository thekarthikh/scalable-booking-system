package com.thekarthikh.booking.saga;

import com.thekarthikh.booking.entity.SagaEvent;
import com.thekarthikh.booking.repository.SagaEventRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.beans.factory.annotation.Value;

import java.time.Duration;

import java.util.List;

/**
 * Transactional Outbox relay.
 *
 * Saga events are first written to the {@code saga_events} table inside the
 * same DB transaction as the booking write — making the database change and
 * the durable publication intent atomic. Kafka delivery is still fallible.
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
    @Value("${saga.outbox.send-timeout:5s}")
    private Duration sendTimeout;

    /**
     * Publishes un-sent outbox events every 500 ms.
     * In production this would use a Debezium CDC connector instead.
     */
    @Scheduled(fixedDelay = 500)
    @Transactional
    public void relayEvents() {
        List<SagaEvent> pending = sagaEventRepository.findUnpublishedEventsForUpdate();
        for (SagaEvent event : pending) {
            try {
                kafkaTemplate.send(BOOKING_TOPIC, event.getBookingId().toString(), event.getPayload())
                        .get(sendTimeout.toMillis(), java.util.concurrent.TimeUnit.MILLISECONDS);
                event.setPublished(true);
                sagaEventRepository.save(event);
            } catch (Exception e) {
                log.error("Error relaying saga event id={}: {}", event.getId(), e.getMessage());
                break;
            }
        }
    }
}
