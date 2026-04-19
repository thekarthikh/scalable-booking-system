package com.thekarthikh.inventory.dto;

import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class InventoryItemResponse {
    private UUID          id;
    private String        name;
    private String        description;
    private Integer       totalCapacity;
    private Integer       available;
    private BigDecimal    price;
    private Long          version;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
