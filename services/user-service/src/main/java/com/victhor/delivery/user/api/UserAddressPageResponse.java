package com.victhor.delivery.user.api;

import java.util.List;

import com.victhor.delivery.user.application.UserAddressPage;

public record UserAddressPageResponse(List<UserAddressResponse> items, int page, int size,
        long totalElements, long totalPages) {

    static UserAddressPageResponse from(UserAddressPage result) {
        return new UserAddressPageResponse(result.items().stream().map(UserAddressResponse::from).toList(),
                result.page(), result.size(), result.totalElements(), result.totalPages());
    }
}
