package com.thekarthikh.inventory.repository;

import com.thekarthikh.inventory.entity.InventoryReservation;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface InventoryReservationRepository extends JpaRepository<InventoryReservation, UUID> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT r FROM InventoryReservation r WHERE r.bookingId = :bookingId")
    Optional<InventoryReservation> findByBookingIdWithLock(@Param("bookingId") UUID bookingId);
}
