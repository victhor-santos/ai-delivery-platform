package com.victhor.delivery.user.domain;

import java.nio.charset.StandardCharsets;

public final class PasswordPolicy {

    public static final int MIN_LENGTH = 12;
    public static final int MAX_BYTES = 72;

    private PasswordPolicy() {
    }

    public static void validateRegistration(String password) {
        if (!canMatch(password) || password.codePointCount(0, password.length()) < MIN_LENGTH) {
            throw new IllegalArgumentException("Password must have at least 12 characters and at most 72 UTF-8 bytes");
        }
    }

    public static boolean canMatch(String password) {
        return password != null && !password.isBlank() && password.length() <= MAX_BYTES
                && password.getBytes(StandardCharsets.UTF_8).length <= MAX_BYTES;
    }
}
