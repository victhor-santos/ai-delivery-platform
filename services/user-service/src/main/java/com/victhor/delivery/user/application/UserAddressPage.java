package com.victhor.delivery.user.application;

import java.util.List;

import com.victhor.delivery.user.domain.UserAddress;

public record UserAddressPage(List<UserAddress> items, int page, int size, long totalElements) {

    public UserAddressPage {
        items = List.copyOf(items);
    }

    public long totalPages() {
        return totalElements / size + (totalElements % size == 0 ? 0 : 1);
    }
}
