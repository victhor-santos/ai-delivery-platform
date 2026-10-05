package com.victhor.delivery.user.application;

import java.util.Optional;
import java.util.UUID;

import com.victhor.delivery.user.domain.UserAddress;

public interface UserAddressRepository {

    UserAddress save(UserAddress address);

    Optional<UserAddress> findById(UUID userId, UUID addressId);

    Optional<UserAddress> update(UUID userId, UUID addressId, String label, String address,
            double latitude, double longitude);

    UserAddressPage findAll(UUID userId, int page, int size);
}
