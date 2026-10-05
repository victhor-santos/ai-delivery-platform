package com.victhor.delivery.order.api;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.victhor.delivery.order.domain.Order;
import com.victhor.delivery.order.domain.OrderPricing;

public record OrderResponse(UUID id, UUID restaurantId, DestinationResponse destination, String status,
        Instant createdAt, Instant updatedAt, Instant confirmedAt, Instant cancelledAt, Instant deliveryRequestedAt,
        List<OrderItemResponse> items, BigDecimal total, String currency) {

    static OrderResponse from(Order order) {
        var pricing = order.pricing();
        var items = pricing == null ? List.<OrderItemResponse>of()
                : pricing.items().stream().map(OrderItemResponse::from).toList();
        return new OrderResponse(order.id(), order.restaurantId(), DestinationResponse.from(order.destination()),
                order.status().name(), order.createdAt(), order.updatedAt(), order.confirmedAt(), order.cancelledAt(),
                order.deliveryRequestedAt(), items, pricing == null ? null : pricing.total(),
                pricing == null ? null : OrderPricing.CURRENCY);
    }
}
