package com.victhor.delivery.user.domain;

import java.util.Objects;
import java.util.UUID;

public record UserAddress(UUID id, UUID userId, String label, String address,
        double latitude, double longitude) {

    public static final int MAX_LABEL_LENGTH = 80;
    public static final int MAX_ADDRESS_LENGTH = 255;

    public UserAddress {
        Objects.requireNonNull(id, "Address id is required");
        Objects.requireNonNull(userId, "Address user id is required");
        if (label == null || label.isBlank()) {
            throw new IllegalArgumentException("Address label is required");
        }
        label = label.strip();
        if (label.length() > MAX_LABEL_LENGTH) {
            throw new IllegalArgumentException("Address label must have at most 80 characters");
        }
        if (address == null || address.isBlank()) {
            throw new IllegalArgumentException("Address is required");
        }
        address = address.strip();
        if (address.length() > MAX_ADDRESS_LENGTH) {
            throw new IllegalArgumentException("Address must have at most 255 characters");
        }
        if (!Double.isFinite(latitude) || latitude < -90 || latitude > 90) {
            throw new IllegalArgumentException("Latitude must be finite and between -90 and 90");
        }
        if (!Double.isFinite(longitude) || longitude < -180 || longitude > 180) {
            throw new IllegalArgumentException("Longitude must be finite and between -180 and 180");
        }
    }

    public static UserAddress create(UUID userId, String label, String address,
            double latitude, double longitude) {
        return new UserAddress(UUID.randomUUID(), userId, label, address, latitude, longitude);
    }

    public UserAddress replace(String label, String address, double latitude, double longitude) {
        return new UserAddress(id, userId, label, address, latitude, longitude);
    }
}
