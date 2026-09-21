package com.thekarthikh.inventory.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "inventory_items")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class InventoryItem {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false, length = 255)
    private String name;

    @Column(columnDefinition = "TEXT")
    private String description;

    @Column(name = "total_capacity", nullable = false)
    private Integer totalCapacity;

    @Column(nullable = false)
    private Integer available;

    @Column(nullable = false, precision = 12, scale = 2)
    private BigDecimal price;

    /**
     * JPA optimistic locking version field.
     * Spring Data JPA will automatically append WHERE version=? to UPDATE
     * statements, throwing OptimisticLockingFailureException on conflict.
     * This is picked up by the OptimisticLockGuard in BookingService (Layer 3).
     */
    @Version
    @Column(nullable = false)
    private Long version;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;
}
