package com.thekarthikh.inventory.dto;

import lombok.Builder;
import lombok.Value;

import java.time.Instant;

@Value
@Builder
public class ApiError {
    int status;
    String error;
    String message;
    Instant timestamp;
}
