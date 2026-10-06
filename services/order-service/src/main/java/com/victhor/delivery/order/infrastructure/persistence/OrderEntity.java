package com.victhor.delivery.order.infrastructure.persistence;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.CollectionTable;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OrderColumn;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import com.victhor.delivery.order.domain.DeliveryDestination;
import com.victhor.delivery.order.domain.Order;
import com.victhor.delivery.order.domain.OrderPricing;
import com.victhor.delivery.order.domain.OrderStatus;

@Entity
@Table(name = "orders")
public class OrderEntity {

    @Id
    private UUID id;

    private UUID customerId;

    @Column(nullable = false)
    private UUID restaurantId;

    @Column(nullable = false, length = DeliveryDestination.MAX_ADDRESS_LENGTH)
    private String destinationAddress;

    @Column(nullable = false)
    private double destinationLatitude;

    @Column(nullable = false)
    private double destinationLongitude;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private OrderStatus status;

    @Column(nullable = false)
    private Instant createdAt;

    @Column(nullable = false)
    private Instant updatedAt;

    private Instant confirmedAt;

    private Instant cancelledAt;

    private Instant deliveryRequestedAt;

    @Column(precision = 14, scale = 2)
    private BigDecimal total;

    @ElementCollection
    @CollectionTable(name = "order_items", joinColumns = @JoinColumn(name = "order_id"))
    @OrderColumn(name = "item_position", nullable = false)
    private List<OrderItemEmbeddable> items = new ArrayList<>();

    @Version
    @Column(nullable = false)
    private Long version;

    protected OrderEntity() {
    }

    private OrderEntity(Order order) {
        id = order.id();
        customerId = order.customerId();
        restaurantId = order.restaurantId();
        destinationAddress = order.destination().address();
        destinationLatitude = order.destination().latitude();
        destinationLongitude = order.destination().longitude();
        createdAt = order.createdAt();
        if (order.pricing() != null) {
            total = order.pricing().total();
            items = new ArrayList<>(order.pricing().items().stream().map(OrderItemEmbeddable::fromDomain).toList());
        }
        applyState(order);
    }

    static OrderEntity fromDomain(Order order) {
        return new OrderEntity(order);
    }

    Order toDomain() {
        var destination = new DeliveryDestination(destinationAddress, destinationLatitude, destinationLongitude);
        var pricing = total == null && items.isEmpty() ? null
                : new OrderPricing(items.stream().map(OrderItemEmbeddable::toDomain).toList(), total);
        return new Order(id, restaurantId, destination, status, createdAt, updatedAt, confirmedAt, cancelledAt,
                deliveryRequestedAt, pricing, customerId);
    }

    void applyState(Order order) {
        status = order.status();
        updatedAt = order.updatedAt();
        confirmedAt = order.confirmedAt();
        cancelledAt = order.cancelledAt();
        deliveryRequestedAt = order.deliveryRequestedAt();
    }
}
