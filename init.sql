-- ============================================================
-- Scalable Booking System – Database Schema
-- ============================================================

CREATE EXTENSION IF NOT EXISTS pgcrypto;

-- ─── Users ──────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS users (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    username    VARCHAR(100) UNIQUE NOT NULL,
    email       VARCHAR(255) UNIQUE NOT NULL,
    password    VARCHAR(255) NOT NULL,
    role        VARCHAR(50) NOT NULL DEFAULT 'USER',
    created_at  TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at  TIMESTAMP NOT NULL DEFAULT NOW()
);

-- ─── Inventory Items ────────────────────────────────────────
CREATE TABLE IF NOT EXISTS inventory_items (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    name            VARCHAR(255) NOT NULL,
    description     TEXT,
    total_capacity  INT NOT NULL CHECK (total_capacity >= 0),
    available       INT NOT NULL CHECK (available >= 0),
    price           NUMERIC(12,2) NOT NULL,
    version         BIGINT NOT NULL DEFAULT 0,   -- for optimistic locking
    created_at      TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMP NOT NULL DEFAULT NOW(),
    CONSTRAINT chk_available_lte_total CHECK (available <= total_capacity)
);

-- ─── Bookings ────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS bookings (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    idempotency_key VARCHAR(36) UNIQUE NOT NULL,  -- UUID v4
    user_id         UUID NOT NULL REFERENCES users(id),
    item_id         UUID NOT NULL REFERENCES inventory_items(id),
    quantity        INT NOT NULL CHECK (quantity > 0),
    total_price     NUMERIC(12,2) NOT NULL,
    status          VARCHAR(50) NOT NULL DEFAULT 'PENDING',
    saga_status     VARCHAR(50) NOT NULL DEFAULT 'STARTED',
    failure_reason  TEXT,
    created_at      TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMP NOT NULL DEFAULT NOW(),
    CONSTRAINT chk_booking_status CHECK (status IN ('PENDING', 'CONFIRMED', 'CANCELLED')),
    CONSTRAINT chk_booking_saga_status CHECK (saga_status IN ('STARTED', 'INVENTORY_RESERVED', 'INVENTORY_FAILED', 'CANCELLED_BY_USER'))
);

-- ─── Saga Events (outbox pattern) ────────────────────────────
CREATE TABLE IF NOT EXISTS saga_events (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    booking_id  UUID NOT NULL,
    event_type  VARCHAR(100) NOT NULL,
    payload     TEXT NOT NULL,
    published   BOOLEAN NOT NULL DEFAULT FALSE,
    created_at  TIMESTAMP NOT NULL DEFAULT NOW()
);

-- Inventory reservation ledger.  The booking id is unique so Kafka redelivery
-- cannot deduct the same reservation twice.
CREATE TABLE IF NOT EXISTS inventory_reservations (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    booking_id  UUID NOT NULL UNIQUE,
    item_id     UUID NOT NULL REFERENCES inventory_items(id),
    quantity    INT NOT NULL CHECK (quantity > 0),
    status      VARCHAR(20) NOT NULL,
    created_at  TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at  TIMESTAMP NOT NULL DEFAULT NOW()
);

-- Inventory's own outbox keeps the reservation write and response event
-- durable without pretending that a database transaction and Kafka are one
-- distributed transaction.
CREATE TABLE IF NOT EXISTS inventory_saga_events (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    booking_id  UUID NOT NULL,
    event_type  VARCHAR(100) NOT NULL,
    payload     TEXT NOT NULL,
    published   BOOLEAN NOT NULL DEFAULT FALSE,
    created_at  TIMESTAMP NOT NULL DEFAULT NOW()
);

-- ─── Notifications ───────────────────────────────────────────
CREATE TABLE IF NOT EXISTS notifications (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id     UUID NOT NULL,
    booking_id  UUID,
    type        VARCHAR(100) NOT NULL,
    message     TEXT NOT NULL,
    sent        BOOLEAN NOT NULL DEFAULT FALSE,
    retry_count INT NOT NULL DEFAULT 0,
    created_at  TIMESTAMP NOT NULL DEFAULT NOW()
);

-- ─── Indexes ─────────────────────────────────────────────────
CREATE INDEX IF NOT EXISTS idx_bookings_user_id    ON bookings(user_id);
CREATE INDEX IF NOT EXISTS idx_bookings_item_id    ON bookings(item_id);
CREATE INDEX IF NOT EXISTS idx_bookings_status     ON bookings(status);
CREATE INDEX IF NOT EXISTS idx_bookings_idempkey   ON bookings(idempotency_key);
CREATE INDEX IF NOT EXISTS idx_saga_events_booking ON saga_events(booking_id);
CREATE INDEX IF NOT EXISTS idx_saga_events_pending ON saga_events(published, created_at);
CREATE INDEX IF NOT EXISTS idx_inventory_saga_events_pending ON inventory_saga_events(published, created_at);
CREATE UNIQUE INDEX IF NOT EXISTS uq_inventory_saga_events_booking_type
    ON inventory_saga_events(booking_id, event_type);
CREATE INDEX IF NOT EXISTS idx_notifications_user  ON notifications(user_id);
CREATE UNIQUE INDEX IF NOT EXISTS uq_notifications_booking_type
    ON notifications(booking_id, type)
    WHERE booking_id IS NOT NULL;

-- ─── Seed Data ───────────────────────────────────────────────
INSERT INTO inventory_items (id, name, description, total_capacity, available, price)
VALUES
  ('a1b2c3d4-e5f6-7890-abcd-ef1234567890', 'Concert Ticket - Front Row',   'Front row seat for the grand concert', 500,  500,  299.99),
  ('b2c3d4e5-f6a7-8901-bcde-f01234567891', 'Concert Ticket - General',      'General admission concert ticket',     2000, 2000, 99.99),
  ('c3d4e5f6-a7b8-9012-cdef-012345678902', 'Flight SFO→NYC Economy',         'Economy class seat',                   180,  180,  349.00),
  ('d4e5f6a7-b8c9-0123-def0-123456789013', 'Hotel Room - Deluxe',            'Deluxe double room, sea view',         50,   50,   189.00),
  ('e5f6a7b8-c9d0-1234-ef01-234567890124', 'Workshop Slot - Spring Boot',    'Advanced Spring Boot masterclass',     40,   40,   49.99)
ON CONFLICT DO NOTHING;
