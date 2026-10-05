package com.victhor.delivery.order.api;

import java.math.BigDecimal;
import java.util.UUID;

import com.victhor.delivery.order.domain.OrderItem;

public record OrderItemResponse(UUID menuItemId, String name, int quantity, BigDecimal unitPrice,
        BigDecimal lineTotal) {

    static OrderItemResponse from(OrderItem item) {
        return new OrderItemResponse(item.menuItemId(), item.name(), item.quantity(), item.unitPrice(), item.lineTotal());
    }
}
