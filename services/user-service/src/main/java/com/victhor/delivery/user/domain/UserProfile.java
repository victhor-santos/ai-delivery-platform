package com.victhor.delivery.user.domain;

import java.util.Objects;
import java.util.UUID;

public record UserProfile(UUID id, String name, EmailAddress email) {

    public static final int MAX_NAME_LENGTH = 120;

    public UserProfile {
        Objects.requireNonNull(id, "User id is required");
        Objects.requireNonNull(email, "User email is required");
        name = normalizeName(name);
    }

    public static UserProfile create(String name, EmailAddress email) {
        return new UserProfile(UUID.randomUUID(), name, email);
    }

    public UserProfile updateName(String name) {
        return new UserProfile(id, name, email);
    }

    public static String normalizeName(String name) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("User name is required");
        }
        String normalized = name.strip();
        if (normalized.length() > MAX_NAME_LENGTH) {
            throw new IllegalArgumentException("User name must have at most 120 characters");
        }
        return normalized;
    }
}
