package com.thekarthikh.booking.repository;

import com.thekarthikh.booking.entity.SagaEvent;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface SagaEventRepository extends JpaRepository<SagaEvent, UUID> {

    List<SagaEvent> findByBookingId(UUID bookingId);

    /** Used by the outbox relay scheduler. */
    @Query("SELECT e FROM SagaEvent e WHERE e.published = false ORDER BY e.createdAt ASC")
    List<SagaEvent> findUnpublishedEvents();
}
