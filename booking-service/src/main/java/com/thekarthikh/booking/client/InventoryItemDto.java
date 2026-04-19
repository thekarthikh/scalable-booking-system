package com.thekarthikh.booking.client;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.util.UUID;

/** Inventory item payload returned by InventoryService REST API. */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class InventoryItemDto {
    private UUID       id;
    private String     name;
    private Integer    available;
    private BigDecimal price;
}
