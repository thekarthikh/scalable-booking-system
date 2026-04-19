package com.thekarthikh.inventory.dto;

import jakarta.validation.constraints.*;
import lombok.Data;

import java.math.BigDecimal;

@Data
public class CreateInventoryItemRequest {

    @NotBlank(message = "Name is required")
    @Size(max = 255)
    private String name;

    private String description;

    @NotNull
    @Min(1)
    private Integer totalCapacity;

    @NotNull
    @DecimalMin("0.01")
    private BigDecimal price;
}
