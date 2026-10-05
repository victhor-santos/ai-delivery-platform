package com.victhor.delivery.user.api;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.victhor.delivery.user.domain.UserAddress;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

@JsonIgnoreProperties(ignoreUnknown = true)
public record UserAddressRequest(
        @NotBlank @Size(max = UserAddress.MAX_LABEL_LENGTH) String label,
        @NotBlank @Size(max = UserAddress.MAX_ADDRESS_LENGTH) String address,
        @NotNull Double latitude,
        @NotNull Double longitude) {

    public UserAddressRequest {
        if (label != null) {
            label = label.strip();
        }
        if (address != null) {
            address = address.strip();
        }
    }
}
