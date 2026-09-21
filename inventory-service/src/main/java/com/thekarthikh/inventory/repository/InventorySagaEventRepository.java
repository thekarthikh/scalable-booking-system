package com.thekarthikh.inventory.repository;

import com.thekarthikh.inventory.entity.InventorySagaEvent;
import org.springframework.data.jpa.repository.*;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface InventorySagaEventRepository extends JpaRepository<InventorySagaEvent, UUID> {

    @Query(value = "SELECT * FROM inventory_saga_events WHERE published = false ORDER BY created_at ASC LIMIT 100 FOR UPDATE SKIP LOCKED",
            nativeQuery = true)
    List<InventorySagaEvent> findUnpublishedEventsForUpdate();

    @Modifying
    @Query(value = """
            INSERT INTO inventory_saga_events (id, booking_id, event_type, payload, published, created_at)
            VALUES (gen_random_uuid(), :bookingId, :eventType, :payload, false, now())
            ON CONFLICT (booking_id, event_type) DO NOTHING
            """, nativeQuery = true)
    int insertIfAbsent(UUID bookingId, String eventType, String payload);
}
