package com.thekarthikh.booking.exception;

public class OptimisticLockConflictException extends RuntimeException {
    public OptimisticLockConflictException(String message) { super(message); }
}
