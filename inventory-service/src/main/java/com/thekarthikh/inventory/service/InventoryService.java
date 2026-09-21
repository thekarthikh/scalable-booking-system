package com.thekarthikh.inventory.service;

import com.thekarthikh.inventory.dto.CreateInventoryItemRequest;
import com.thekarthikh.inventory.dto.InventoryItemResponse;
import com.thekarthikh.inventory.entity.InventoryItem;
import com.thekarthikh.inventory.repository.InventoryRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class InventoryService {

    private final InventoryRepository inventoryRepository;

    @Transactional
    public InventoryItemResponse createItem(CreateInventoryItemRequest req) {
        InventoryItem item = InventoryItem.builder()
                .name(req.getName())
                .description(req.getDescription())
                .totalCapacity(req.getTotalCapacity())
                .available(req.getTotalCapacity())
                .price(req.getPrice())
                .build();
        item = inventoryRepository.save(item);
        log.info("Created inventory item: {}", item.getId());
        return mapToResponse(item);
    }

    @Transactional(readOnly = true)
    public List<InventoryItemResponse> getAllItems() {
        return inventoryRepository.findAll().stream()
                .map(this::mapToResponse)
                .collect(Collectors.toList());
    }

    @Transactional(readOnly = true)
    public InventoryItemResponse getItem(UUID id) {
        InventoryItem item = inventoryRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("Item not found: " + id));
        return mapToResponse(item);
    }

    private InventoryItemResponse mapToResponse(InventoryItem item) {
        return InventoryItemResponse.builder()
                .id(item.getId())
                .name(item.getName())
                .description(item.getDescription())
                .totalCapacity(item.getTotalCapacity())
                .available(item.getAvailable())
                .price(item.getPrice())
                .version(item.getVersion())
                .createdAt(item.getCreatedAt())
                .updatedAt(item.getUpdatedAt())
                .build();
    }
}
