package com.thekarthikh.booking.exception;

public class DuplicateBookingException extends RuntimeException {
    public DuplicateBookingException(String message) { super(message); }
}
