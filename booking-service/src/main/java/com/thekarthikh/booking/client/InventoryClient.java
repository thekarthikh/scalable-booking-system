package com.thekarthikh.booking.client;

import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.retry.annotation.Retry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.util.UUID;

/**
 * HTTP client for InventoryService.
 * Protected by Resilience4j CircuitBreaker + Retry so that transient
 * inventory-service failures don't cascade into BookingService.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class InventoryClient {

    private final RestTemplate restTemplate;

    @Value("${services.inventory-service.url:http://localhost:8083}")
    private String inventoryServiceUrl;

    @CircuitBreaker(name = "inventory-service", fallbackMethod = "getItemFallback")
    @Retry(name = "inventory-service")
    public InventoryItemDto getItem(UUID itemId) {
        String url = inventoryServiceUrl + "/api/v1/inventory/" + itemId;
        ResponseEntity<InventoryItemDto> response =
                restTemplate.getForEntity(url, InventoryItemDto.class);
        return response.getBody();
    }

    public InventoryItemDto getItemFallback(UUID itemId, Exception ex) {
        log.warn("InventoryService circuit open for itemId={}: {}", itemId, ex.getMessage());
        return null;   // caller must handle null as service unavailable
    }
}
