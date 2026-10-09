package com.victhor.delivery.delivery.application;

import java.util.List;

import com.victhor.delivery.delivery.domain.Delivery;

public record DeliveryPage(List<Delivery> items, int page, int size, long totalElements) {

    public DeliveryPage {
        items = List.copyOf(items);
    }
}
