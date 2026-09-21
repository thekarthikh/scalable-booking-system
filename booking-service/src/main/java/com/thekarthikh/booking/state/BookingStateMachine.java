package com.thekarthikh.booking.state;

import com.thekarthikh.booking.exception.OptimisticLockConflictException;

import java.util.Map;
import java.util.Set;

public final class BookingStateMachine {
    private static final Map<String, Set<String>> ALLOWED = Map.of(
            "PENDING", Set.of("CONFIRMED", "CANCELLED"),
            "CONFIRMED", Set.of("CANCELLED"),
            "CANCELLED", Set.of());

    private BookingStateMachine() { }

    public static boolean canTransition(String current, String target) {
        return ALLOWED.getOrDefault(current, Set.of()).contains(target);
    }

    public static void requireTransition(String current, String target) {
        if (!canTransition(current, target)) {
            throw new OptimisticLockConflictException(
                    "Invalid booking state transition: " + current + " -> " + target);
        }
    }
}
