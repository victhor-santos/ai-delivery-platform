package com.victhor.delivery.delivery.domain;

import java.util.Objects;

public record DeliveryLocation(String description, GeoPoint point) {

    public static final int MAX_DESCRIPTION_LENGTH = 255;

    public DeliveryLocation {
        if (description == null || description.isBlank()) {
            throw new IllegalArgumentException("Location description is required");
        }
        description = description.strip();
        if (description.length() > MAX_DESCRIPTION_LENGTH) {
            throw new IllegalArgumentException("Location description must have at most 255 characters");
        }
        Objects.requireNonNull(point, "Location coordinates are required");
    }
}
