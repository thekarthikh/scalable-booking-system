package com.thekarthikh.booking.repository;

import com.thekarthikh.booking.entity.Booking;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface BookingRepository extends JpaRepository<Booking, UUID> {

    Optional<Booking> findByIdempotencyKey(String idempotencyKey);

    List<Booking> findByUserId(UUID userId);

    /**
     * Layer 1 of 3-layer locking: DB-level PESSIMISTIC_WRITE (SELECT FOR UPDATE).
     * Guarantees serialized access at the database row level — no two threads
     * can hold this lock simultaneously for the same booking row.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT b FROM Booking b WHERE b.id = :id")
    Optional<Booking> findByIdWithPessimisticLock(@Param("id") UUID id);

    @Query("SELECT b FROM Booking b WHERE b.userId = :userId ORDER BY b.createdAt DESC")
    List<Booking> findByUserIdOrderByCreatedAtDesc(@Param("userId") UUID userId);
}
