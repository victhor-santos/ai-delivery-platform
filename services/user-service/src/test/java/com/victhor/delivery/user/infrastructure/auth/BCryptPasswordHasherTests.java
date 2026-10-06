package com.victhor.delivery.user.infrastructure.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class BCryptPasswordHasherTests {

    private final BCryptPasswordHasher passwords = new BCryptPasswordHasher();

    @Test
    void hashesWithAnIndependentSaltAndChecksExactUntrimmedValues() {
        String raw = "  correct-password  ";
        String first = passwords.hash(raw);
        String second = passwords.hash(raw);
        assertThat(first).startsWith("$2a$12$").isNotEqualTo(raw).isNotEqualTo(second);
        assertThat(passwords.matches(raw, first)).isTrue();
        assertThat(passwords.matches(raw.strip(), first)).isFalse();
    }

    @Test
    void rejectsByteOverflowInsteadOfTreatingTruncatedPasswordsAsEquivalent() {
        String raw = "é".repeat(36);
        String hash = passwords.hash(raw);
        assertThat(passwords.matches(raw, hash)).isTrue();
        assertThat(passwords.matches(raw + "x", hash)).isFalse();
        assertThatThrownBy(() -> passwords.hash(raw + "x")).isInstanceOf(IllegalArgumentException.class);
    }
}
