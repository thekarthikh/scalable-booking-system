package com.thekarthikh.inventory.controller;

import com.thekarthikh.inventory.dto.CreateInventoryItemRequest;
import com.thekarthikh.inventory.dto.InventoryItemResponse;
import com.thekarthikh.inventory.service.InventoryService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/inventory")
@RequiredArgsConstructor
public class InventoryController {

    private final InventoryService inventoryService;

    @PostMapping
    public ResponseEntity<InventoryItemResponse> createItem(@Valid @RequestBody CreateInventoryItemRequest req) {
        return ResponseEntity.status(HttpStatus.CREATED).body(inventoryService.createItem(req));
    }

    @GetMapping
    public ResponseEntity<List<InventoryItemResponse>> getAllItems() {
        return ResponseEntity.ok(inventoryService.getAllItems());
    }

    @GetMapping("/{id}")
    public ResponseEntity<InventoryItemResponse> getItem(@PathVariable UUID id) {
        return ResponseEntity.ok(inventoryService.getItem(id));
    }
}
