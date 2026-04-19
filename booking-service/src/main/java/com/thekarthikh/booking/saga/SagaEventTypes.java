package com.thekarthikh.booking.saga;

/**
 * Canonical event type constants shared between Saga producers and consumers.
 *
 * Choreography flow (happy path):
 *  BookingService  ──BOOKING_CREATED──►  InventoryService
 *  InventoryService ─INVENTORY_RESERVED─► BookingService  (updates status to CONFIRMED)
 *  BookingService  ──BOOKING_CONFIRMED──► NotificationService
 *
 * Compensation flow (failure):
 *  InventoryService ─INVENTORY_FAILED──► BookingService   (cancels booking)
 *  BookingService  ──BOOKING_CANCELLED─► NotificationService
 */
public final class SagaEventTypes {

    private SagaEventTypes() {}

    // ─── Forward events ───────────────────────────────────────────
    public static final String BOOKING_CREATED      = "BOOKING_CREATED";
    public static final String INVENTORY_RESERVED   = "INVENTORY_RESERVED";
    public static final String BOOKING_CONFIRMED    = "BOOKING_CONFIRMED";

    // ─── Compensation events ──────────────────────────────────────
    public static final String INVENTORY_FAILED     = "INVENTORY_FAILED";
    public static final String BOOKING_CANCELLED    = "BOOKING_CANCELLED";
    public static final String INVENTORY_RELEASED   = "INVENTORY_RELEASED";
}
