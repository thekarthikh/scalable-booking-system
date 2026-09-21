package com.thekarthikh.booking.security;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JwtTokenProviderTest {

    @Test
    void missingProductionKeyFailsFast() {
        assertThatThrownBy(() -> new JwtTokenProvider(null))
                .isInstanceOf(IllegalStateException.class);
    }
}
