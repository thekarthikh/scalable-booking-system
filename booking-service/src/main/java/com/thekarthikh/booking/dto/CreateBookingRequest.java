package com.thekarthikh.booking.dto;

import jakarta.validation.constraints.*;
import lombok.Data;

import java.util.UUID;

@Data
public class CreateBookingRequest {

    @NotNull(message = "Item ID is required")
    private UUID itemId;

    @NotNull(message = "Quantity is required")
    @Min(value = 1, message = "Quantity must be at least 1")
    @Max(value = 100, message = "Quantity cannot exceed 100")
    private Integer quantity;

    /**
     * UUID v4 idempotency key — clients must generate this and include it on
     * every request submission and any retries.  Ensures exactly-once booking
     * semantics even under retry-storm conditions.
     */
    @NotBlank(message = "Idempotency key is required (UUID v4)")
    @Pattern(
        regexp = "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-4[0-9a-fA-F]{3}-[89abAB][0-9a-fA-F]{3}-[0-9a-fA-F]{12}$",
        message = "Idempotency key must be a valid UUID v4"
    )
    private String idempotencyKey;
}
