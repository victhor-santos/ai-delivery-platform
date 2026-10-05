package com.victhor.delivery.user.infrastructure.persistence;

import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import com.victhor.delivery.user.domain.UserAddress;

@Entity
@Table(name = "user_addresses")
public class UserAddressEntity {

    @Id
    private UUID id;

    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    @Column(nullable = false, length = UserAddress.MAX_LABEL_LENGTH)
    private String label;

    @Column(nullable = false, length = UserAddress.MAX_ADDRESS_LENGTH)
    private String address;

    @Column(nullable = false)
    private double latitude;

    @Column(nullable = false)
    private double longitude;

    protected UserAddressEntity() {
    }

    private UserAddressEntity(UserAddress address) {
        id = address.id();
        userId = address.userId();
        updateDetails(address);
    }

    static UserAddressEntity fromDomain(UserAddress address) {
        return new UserAddressEntity(address);
    }

    UserAddress toDomain() {
        return new UserAddress(id, userId, label, address, latitude, longitude);
    }

    void updateDetails(UserAddress address) {
        label = address.label();
        this.address = address.address();
        latitude = address.latitude();
        longitude = address.longitude();
    }
}
