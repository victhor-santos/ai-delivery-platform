package com.victhor.delivery.order.domain;

public record DeliveryDestination(String address, double latitude, double longitude) {

    public static final int MAX_ADDRESS_LENGTH = 255;

    public DeliveryDestination {
        if (address == null || address.isBlank()) {
            throw new IllegalArgumentException("Destination address is required");
        }
        address = address.strip();
        if (address.length() > MAX_ADDRESS_LENGTH) {
            throw new IllegalArgumentException("Destination address must have at most 255 characters");
        }
        if (!Double.isFinite(latitude) || latitude < -90 || latitude > 90) {
            throw new IllegalArgumentException("Latitude must be finite and between -90 and 90");
        }
        if (!Double.isFinite(longitude) || longitude < -180 || longitude > 180) {
            throw new IllegalArgumentException("Longitude must be finite and between -180 and 180");
        }
    }
}
