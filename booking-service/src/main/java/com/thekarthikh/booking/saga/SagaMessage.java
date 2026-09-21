package com.thekarthikh.booking.saga;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.util.UUID;

/** Payload carried by every Saga Kafka message. */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SagaMessage {
    private String    eventType;
    private UUID      bookingId;
    private UUID      userId;
    private UUID      itemId;
    private Integer   quantity;
    private BigDecimal totalPrice;
    private String    failureReason;
    private long      timestamp;
}
