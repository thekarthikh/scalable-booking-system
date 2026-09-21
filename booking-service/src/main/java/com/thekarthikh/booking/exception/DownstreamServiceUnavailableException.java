package com.thekarthikh.booking.exception;

public class DownstreamServiceUnavailableException extends RuntimeException {
    public DownstreamServiceUnavailableException(String message) {
        super(message);
    }
}
