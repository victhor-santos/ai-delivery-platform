package com.victhor.delivery.delivery.api;

import java.util.List;

import com.victhor.delivery.delivery.application.DeliveryPage;

public record DeliveryPageResponse(List<DeliveryResponse> items, int page, int size, long totalElements) {

    static DeliveryPageResponse from(DeliveryPage page) {
        return new DeliveryPageResponse(page.items().stream().map(DeliveryResponse::from).toList(), page.page(),
                page.size(), page.totalElements());
    }
}
