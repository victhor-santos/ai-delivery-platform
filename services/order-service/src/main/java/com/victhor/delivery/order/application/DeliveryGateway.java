package com.victhor.delivery.order.application;

import java.util.UUID;

import com.victhor.delivery.order.domain.DeliveryRequest;

public interface DeliveryGateway {

    DeliveryReceipt createForOrder(DeliveryRequest request);

    record DeliveryReceipt(UUID orderId, UUID deliveryId, String status) {
    }
}
