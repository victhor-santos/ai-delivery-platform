package com.victhor.delivery.catalog.domain;

import java.util.Objects;
import java.util.UUID;

public record Restaurant(UUID id, String name, boolean active) {

    public static final int MAX_NAME_LENGTH = 120;

    public Restaurant {
        Objects.requireNonNull(id, "Restaurant id is required");
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("Restaurant name is required");
        }
        name = name.strip();
        if (name.length() > MAX_NAME_LENGTH) {
            throw new IllegalArgumentException("Restaurant name must have at most 120 characters");
        }
    }

    public static Restaurant create(String name) {
        return new Restaurant(UUID.randomUUID(), name, true);
    }
}
