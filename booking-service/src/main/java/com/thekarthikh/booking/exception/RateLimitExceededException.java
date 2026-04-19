package com.thekarthikh.booking.exception;

public class RateLimitExceededException extends RuntimeException {
    public RateLimitExceededException(String message) { super(message); }
}
