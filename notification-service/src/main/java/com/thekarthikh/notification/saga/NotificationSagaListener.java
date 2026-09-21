package com.thekarthikh.notification.saga;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.thekarthikh.notification.entity.Notification;
import com.thekarthikh.notification.repository.NotificationRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Component
@RequiredArgsConstructor
public class NotificationSagaListener {

    private final NotificationRepository notificationRepository;
    private final ObjectMapper objectMapper;

    @KafkaListener(
            topics = "booking-events",
            groupId = "notification-service-group",
            containerFactory = "kafkaListenerContainerFactory"
    )
    @Transactional
    public void handleBookingEvent(ConsumerRecord<String, String> record) {
        try {
            SagaMessage message = objectMapper.readValue(record.value(), SagaMessage.class);
            log.info("Notification received event type={} bookingId={}", message.getEventType(), message.getBookingId());

            String content = "";
            String type = message.getEventType();

            if ("BOOKING_CONFIRMED".equals(type)) {
                content = "Your booking " + message.getBookingId() + " has been confirmed!";
            } else if ("BOOKING_CANCELLED".equals(type)) {
                content = "Your booking " + message.getBookingId() + " has been cancelled. Reason: " + message.getFailureReason();
            } else {
                return; // Ignore other events
            }

            if (notificationRepository.existsByBookingIdAndType(message.getBookingId(), type)) {
                log.debug("Notification already exists for bookingId={} type={}", message.getBookingId(), type);
                return;
            }

            Notification notification = Notification.builder()
                    .userId(message.getUserId())
                    .bookingId(message.getBookingId())
                    .type(type)
                    .message(content)
                    .sent(true)
                    .build();

            notificationRepository.save(notification);
            log.info("Saved notification for booking {}: {}", message.getBookingId(), content);

        } catch (Exception e) {
            throw new IllegalStateException("Unable to process notification event", e);
        }
    }
}
