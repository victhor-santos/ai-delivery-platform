package com.victhor.delivery.order.infrastructure.persistence;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import com.victhor.delivery.order.domain.DeliveryDestination;
import com.victhor.delivery.order.domain.Order;
import com.victhor.delivery.order.domain.OrderStatus;

@Entity
@Table(name = "orders")
public class OrderEntity {

    @Id
    private UUID id;

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

    @Version
    @Column(nullable = false)
    private Long version;

    protected OrderEntity() {
    }

    private OrderEntity(Order order) {
        id = order.id();
        restaurantId = order.restaurantId();
        destinationAddress = order.destination().address();
        destinationLatitude = order.destination().latitude();
        destinationLongitude = order.destination().longitude();
        createdAt = order.createdAt();
        applyState(order);
    }

    static OrderEntity fromDomain(Order order) {
        return new OrderEntity(order);
    }

    Order toDomain() {
        var destination = new DeliveryDestination(destinationAddress, destinationLatitude, destinationLongitude);
        return new Order(id, restaurantId, destination, status, createdAt, updatedAt, confirmedAt, cancelledAt,
                deliveryRequestedAt);
    }

    void applyState(Order order) {
        status = order.status();
        updatedAt = order.updatedAt();
        confirmedAt = order.confirmedAt();
        cancelledAt = order.cancelledAt();
        deliveryRequestedAt = order.deliveryRequestedAt();
    }
}
