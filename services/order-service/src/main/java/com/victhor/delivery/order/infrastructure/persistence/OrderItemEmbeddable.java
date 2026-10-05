package com.victhor.delivery.order.infrastructure.persistence;

import java.math.BigDecimal;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;

import com.victhor.delivery.order.domain.OrderItem;

@Embeddable
class OrderItemEmbeddable {

    @Column(nullable = false)
    private UUID menuItemId;

    @Column(nullable = false, length = OrderItem.MAX_NAME_LENGTH)
    private String name;

    @Column(nullable = false)
    private int quantity;

    @Column(nullable = false, precision = 10, scale = 2)
    private BigDecimal unitPrice;

    protected OrderItemEmbeddable() {
    }

    static OrderItemEmbeddable fromDomain(OrderItem item) {
        var snapshot = new OrderItemEmbeddable();
        snapshot.menuItemId = item.menuItemId();
        snapshot.name = item.name();
        snapshot.quantity = item.quantity();
        snapshot.unitPrice = item.unitPrice();
        return snapshot;
    }

    OrderItem toDomain() {
        return new OrderItem(menuItemId, name, quantity, unitPrice);
    }
}
