package com.thekarthikh.booking.controller;

import com.thekarthikh.booking.dto.BookingResponse;
import com.thekarthikh.booking.dto.CreateBookingRequest;
import com.thekarthikh.booking.service.BookingService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.*;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/bookings")
@RequiredArgsConstructor
public class BookingController {

    private final BookingService bookingService;

    /**
     * Create a new booking.
     * The idempotency key in the request body guarantees exactly-once semantics.
     */
    @PostMapping
    public ResponseEntity<BookingResponse> createBooking(
            @Valid @RequestBody CreateBookingRequest req,
            Authentication auth) {
        UUID userId = UUID.fromString((String) auth.getCredentials());
        BookingResponse response = bookingService.createBooking(userId, req);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    /** Get a specific booking (owner only). */
    @GetMapping("/{bookingId}")
    public ResponseEntity<BookingResponse> getBooking(
            @PathVariable UUID bookingId,
            Authentication auth) {
        UUID userId = UUID.fromString((String) auth.getCredentials());
        return ResponseEntity.ok(bookingService.getBooking(bookingId, userId));
    }

    /** Get all bookings for the authenticated user. */
    @GetMapping
    public ResponseEntity<List<BookingResponse>> getMyBookings(Authentication auth) {
        UUID userId = UUID.fromString((String) auth.getCredentials());
        return ResponseEntity.ok(bookingService.getUserBookings(userId));
    }

    /** Cancel a booking (owner only). */
    @DeleteMapping("/{bookingId}")
    public ResponseEntity<BookingResponse> cancelBooking(
            @PathVariable UUID bookingId,
            Authentication auth) {
        UUID userId = UUID.fromString((String) auth.getCredentials());
        return ResponseEntity.ok(bookingService.cancelBooking(bookingId, userId));
    }
}
