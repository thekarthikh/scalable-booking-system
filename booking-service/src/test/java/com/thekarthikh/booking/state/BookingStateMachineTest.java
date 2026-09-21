package com.thekarthikh.booking.state;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class BookingStateMachineTest {
    @Test
    void allowsOnlyForwardBookingTransitions() {
        assertThat(BookingStateMachine.canTransition("PENDING", "CONFIRMED")).isTrue();
        assertThat(BookingStateMachine.canTransition("PENDING", "CANCELLED")).isTrue();
        assertThat(BookingStateMachine.canTransition("CONFIRMED", "CANCELLED")).isTrue();
        assertThat(BookingStateMachine.canTransition("CANCELLED", "CONFIRMED")).isFalse();
        assertThat(BookingStateMachine.canTransition("CONFIRMED", "PENDING")).isFalse();
        assertThat(BookingStateMachine.canTransition("CONFIRMED", "CONFIRMED")).isFalse();
        assertThat(BookingStateMachine.canTransition("CANCELLED", "CANCELLED")).isFalse();
    }
}
