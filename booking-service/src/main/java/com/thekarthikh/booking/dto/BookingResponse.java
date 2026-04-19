package com.thekarthikh.booking.dto;

import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BookingResponse {
    private UUID          id;
    private String        idempotencyKey;
    private UUID          userId;
    private UUID          itemId;
    private Integer       quantity;
    private BigDecimal    totalPrice;
    private String        status;
    private String        sagaStatus;
    private String        failureReason;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
