package com.victhor.delivery.catalog.application;

import java.util.List;

import com.victhor.delivery.catalog.domain.Restaurant;

public record RestaurantPage(List<Restaurant> content, int page, int size, long totalElements) {

    public RestaurantPage {
        content = List.copyOf(content);
    }

    public long totalPages() {
        return totalElements / size + (totalElements % size == 0 ? 0 : 1);
    }
}
