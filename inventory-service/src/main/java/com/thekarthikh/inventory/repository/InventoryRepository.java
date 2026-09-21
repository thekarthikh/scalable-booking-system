package com.thekarthikh.inventory.repository;

import com.thekarthikh.inventory.entity.InventoryItem;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface InventoryRepository extends JpaRepository<InventoryItem, UUID> {

    /**
     * SELECT FOR UPDATE — acquires a row-level exclusive lock for the duration
     * of the calling @Transactional method.  This is Layer 1 of the 3-layer
     * locking strategy executed on the inventory side.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT i FROM InventoryItem i WHERE i.id = :id")
    Optional<InventoryItem> findByIdWithLock(@Param("id") UUID id);
}
