package com.victhor.delivery.order.infrastructure.persistence;

import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import com.victhor.delivery.order.domain.DeliveryDestination;
import com.victhor.delivery.order.domain.DeliveryRequest;

@Entity
@Table(name = "order_delivery_requests")
class DeliveryRequestEntity {

    @Id
    private UUID orderId;
    @Column(nullable = false, length = DeliveryDestination.MAX_ADDRESS_LENGTH)
    private String originDescription;
    @Column(nullable = false)
    private double originLatitude;
    @Column(nullable = false)
    private double originLongitude;
    @Column(nullable = false, length = DeliveryDestination.MAX_ADDRESS_LENGTH)
    private String destinationAddress;
    @Column(nullable = false)
    private double destinationLatitude;
    @Column(nullable = false)
    private double destinationLongitude;

    protected DeliveryRequestEntity() {
    }

    static DeliveryRequestEntity from(DeliveryRequest request) {
        var entity = new DeliveryRequestEntity();
        entity.orderId = request.orderId();
        entity.originDescription = request.origin().address();
        entity.originLatitude = request.origin().latitude();
        entity.originLongitude = request.origin().longitude();
        entity.destinationAddress = request.destination().address();
        entity.destinationLatitude = request.destination().latitude();
        entity.destinationLongitude = request.destination().longitude();
        return entity;
    }

    DeliveryRequest toDomain() {
        return new DeliveryRequest(orderId, new DeliveryDestination(originDescription, originLatitude, originLongitude),
                new DeliveryDestination(destinationAddress, destinationLatitude, destinationLongitude));
    }
}
