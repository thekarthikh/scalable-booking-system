package com.thekarthikh.notification.saga;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.*;

import java.math.BigDecimal;
import java.util.UUID;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
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
