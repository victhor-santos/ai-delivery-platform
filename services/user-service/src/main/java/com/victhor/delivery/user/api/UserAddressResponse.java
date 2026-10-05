package com.victhor.delivery.user.api;

import java.util.UUID;

import com.victhor.delivery.user.domain.UserAddress;

public record UserAddressResponse(UUID id, UUID userId, String label, String address,
        double latitude, double longitude) {

    static UserAddressResponse from(UserAddress address) {
        return new UserAddressResponse(address.id(), address.userId(), address.label(), address.address(),
                address.latitude(), address.longitude());
    }
}
