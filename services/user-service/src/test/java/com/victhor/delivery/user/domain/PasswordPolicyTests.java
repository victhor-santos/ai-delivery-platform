package com.victhor.delivery.user.domain;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.stream.Stream;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

class PasswordPolicyTests {

    @ParameterizedTest
    @MethodSource("validPasswords")
    void acceptsUnicodeCharactersAndTheExactByteLimitWithoutTrimming(String password) {
        assertThatCode(() -> PasswordPolicy.validateRegistration(password)).doesNotThrowAnyException();
    }

    static Stream<String> validPasswords() {
        return Stream.of("correct-horse-battery", "a".repeat(72), "é".repeat(36), "😀".repeat(12), "  password  ");
    }

    @ParameterizedTest
    @MethodSource("invalidPasswords")
    void rejectsMissingShortBlankOrOversizedPasswords(String password) {
        assertThatThrownBy(() -> PasswordPolicy.validateRegistration(password)).isInstanceOf(IllegalArgumentException.class);
    }

    static Stream<String> invalidPasswords() {
        return Stream.of(null, "", " ".repeat(12), "a".repeat(11), "a".repeat(73), "é".repeat(37), "😀".repeat(11));
    }
}
