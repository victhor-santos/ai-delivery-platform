package com.victhor.delivery.user.application;

import java.util.UUID;

import com.victhor.delivery.user.domain.UserAddress;

public class UserAddressService {

    public static final int MAX_PAGE_SIZE = 100;

    private final UserRepository users;
    private final UserAddressRepository addresses;

    public UserAddressService(UserRepository users, UserAddressRepository addresses) {
        this.users = users;
        this.addresses = addresses;
    }

    public UserAddress create(UUID userId, String label, String address, double latitude, double longitude) {
        var created = UserAddress.create(userId, label, address, latitude, longitude);
        requireUser(userId);
        return addresses.save(created);
    }

    public UserAddress findById(UUID userId, UUID addressId) {
        return addresses.findById(userId, addressId).orElseThrow(UserAddressNotFoundException::new);
    }

    public UserAddress update(UUID userId, UUID addressId, String label, String address,
            double latitude, double longitude) {
        var replacement = new UserAddress(addressId, userId, label, address, latitude, longitude);
        return addresses.update(userId, addressId, replacement.label(), replacement.address(),
                replacement.latitude(), replacement.longitude()).orElseThrow(UserAddressNotFoundException::new);
    }

    public UserAddressPage findAll(UUID userId, int page, int size) {
        long offset = (long) page * size;
        if (page < 0 || size < 1 || size > MAX_PAGE_SIZE || offset > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("Invalid pagination");
        }
        requireUser(userId);
        return addresses.findAll(userId, page, size);
    }

    private void requireUser(UUID userId) {
        users.findById(userId).orElseThrow(UserNotFoundException::new);
    }
}
