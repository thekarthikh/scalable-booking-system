package com.thekarthikh.booking.idempotency;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class IdempotencyFingerprintTest {
    @Test
    void fingerprintChangesWhenUserOrRequestChanges() {
        UUID user = UUID.randomUUID();
        UUID otherUser = UUID.randomUUID();
        UUID item = UUID.randomUUID();

        assertThat(IdempotencyService.fingerprint(user, item, 1))
                .isEqualTo(IdempotencyService.fingerprint(user, item, 1));
        assertThat(IdempotencyService.fingerprint(user, item, 1))
                .isNotEqualTo(IdempotencyService.fingerprint(otherUser, item, 1));
        assertThat(IdempotencyService.fingerprint(user, item, 1))
                .isNotEqualTo(IdempotencyService.fingerprint(user, item, 2));
    }
}
