package com.victhor.delivery.order.api;

import com.victhor.delivery.order.domain.DeliveryDestination;

public record DestinationResponse(String address, double latitude, double longitude) {

    static DestinationResponse from(DeliveryDestination destination) {
        return new DestinationResponse(destination.address(), destination.latitude(), destination.longitude());
    }
}
