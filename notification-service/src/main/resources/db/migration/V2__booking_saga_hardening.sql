CREATE TABLE IF NOT EXISTS inventory_reservations (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    booking_id UUID NOT NULL UNIQUE,
    item_id UUID NOT NULL REFERENCES inventory_items(id),
    quantity INT NOT NULL CHECK (quantity > 0),
    status VARCHAR(20) NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMP NOT NULL DEFAULT NOW()
);

CREATE TABLE IF NOT EXISTS inventory_saga_events (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    booking_id UUID NOT NULL,
    event_type VARCHAR(100) NOT NULL,
    payload TEXT NOT NULL,
    published BOOLEAN NOT NULL DEFAULT FALSE,
    created_at TIMESTAMP NOT NULL DEFAULT NOW()
);

CREATE UNIQUE INDEX IF NOT EXISTS uq_notifications_booking_type
    ON notifications (booking_id, type)
    WHERE booking_id IS NOT NULL;
CREATE INDEX IF NOT EXISTS idx_inventory_saga_events_pending
    ON inventory_saga_events (published, created_at);

DO $$ BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'chk_booking_status') THEN
        ALTER TABLE bookings ADD CONSTRAINT chk_booking_status
            CHECK (status IN ('PENDING', 'CONFIRMED', 'CANCELLED'));
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'chk_booking_saga_status') THEN
        ALTER TABLE bookings ADD CONSTRAINT chk_booking_saga_status
            CHECK (saga_status IN ('STARTED', 'INVENTORY_RESERVED', 'INVENTORY_FAILED', 'CANCELLED_BY_USER'));
    END IF;
END $$;
