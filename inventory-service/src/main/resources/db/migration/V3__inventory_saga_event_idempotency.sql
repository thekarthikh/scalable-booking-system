-- Keep the first copy of any duplicate event created before the unique key
-- existed, then make duplicate outbox insertion a database no-op.
DELETE FROM inventory_saga_events a
USING inventory_saga_events b
WHERE a.booking_id = b.booking_id
  AND a.event_type = b.event_type
  AND a.id > b.id;

CREATE UNIQUE INDEX IF NOT EXISTS uq_inventory_saga_events_booking_type
    ON inventory_saga_events (booking_id, event_type);
