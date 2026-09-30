package com.victhor.delivery.catalog.api;

import java.util.List;

import com.victhor.delivery.catalog.application.RestaurantPage;

public record RestaurantPageResponse(List<RestaurantResponse> content, int page, int size,
        long totalElements, long totalPages) {

    static RestaurantPageResponse from(RestaurantPage result) {
        return new RestaurantPageResponse(result.content().stream().map(RestaurantResponse::from).toList(),
                result.page(), result.size(), result.totalElements(), result.totalPages());
    }
}
