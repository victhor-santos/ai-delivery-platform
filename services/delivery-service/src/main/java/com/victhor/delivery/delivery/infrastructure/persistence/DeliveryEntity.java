package com.victhor.delivery.delivery.infrastructure.persistence;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import com.victhor.delivery.delivery.domain.Delivery;
import com.victhor.delivery.delivery.domain.DeliveryLocation;
import com.victhor.delivery.delivery.domain.DeliveryStatus;
import com.victhor.delivery.delivery.domain.GeoPoint;

@Entity
@Table(name = "deliveries")
public class DeliveryEntity {

    @Id
    private UUID id;

    @Column(nullable = false)
    private UUID orderId;

    @Column(nullable = false, length = DeliveryLocation.MAX_DESCRIPTION_LENGTH)
    private String originDescription;

    @Column(nullable = false)
    private double originLatitude;

    @Column(nullable = false)
    private double originLongitude;

    @Column(nullable = false, length = DeliveryLocation.MAX_DESCRIPTION_LENGTH)
    private String destinationDescription;

    @Column(nullable = false)
    private double destinationLatitude;

    @Column(nullable = false)
    private double destinationLongitude;

    private UUID courierId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private DeliveryStatus status;

    @Column(nullable = false)
    private Instant createdAt;

    @Column(nullable = false)
    private Instant updatedAt;

    private Instant assignedAt;
    private Instant pickedUpAt;
    private Instant departedAt;
    private Instant arrivedAt;
    private Instant deliveredAt;
    private Instant cancelledAt;

    @Version
    @Column(nullable = false)
    private Long version;

    protected DeliveryEntity() {
    }

    static DeliveryEntity fromDomain(Delivery delivery) {
        var entity = new DeliveryEntity();
        entity.id = delivery.id();
        entity.orderId = delivery.orderId();
        entity.originDescription = delivery.origin().description();
        entity.originLatitude = delivery.origin().point().latitude();
        entity.originLongitude = delivery.origin().point().longitude();
        entity.destinationDescription = delivery.destination().description();
        entity.destinationLatitude = delivery.destination().point().latitude();
        entity.destinationLongitude = delivery.destination().point().longitude();
        entity.createdAt = microseconds(delivery.createdAt());
        entity.applyState(delivery);
        return entity;
    }

    Delivery toDomain() {
        var origin = new DeliveryLocation(originDescription, new GeoPoint(originLatitude, originLongitude));
        var destination = new DeliveryLocation(destinationDescription,
                new GeoPoint(destinationLatitude, destinationLongitude));
        return Delivery.restore(id, orderId, origin, destination, courierId, status, createdAt, updatedAt,
                assignedAt, pickedUpAt, departedAt, arrivedAt, deliveredAt, cancelledAt);
    }

    void applyState(Delivery delivery) {
        courierId = delivery.courierId();
        status = delivery.status();
        updatedAt = microseconds(delivery.updatedAt());
        assignedAt = microseconds(delivery.assignedAt());
        pickedUpAt = microseconds(delivery.pickedUpAt());
        departedAt = microseconds(delivery.departedAt());
        arrivedAt = microseconds(delivery.arrivedAt());
        deliveredAt = microseconds(delivery.deliveredAt());
        cancelledAt = microseconds(delivery.cancelledAt());
    }

    private static Instant microseconds(Instant instant) {
        return instant == null ? null : instant.truncatedTo(ChronoUnit.MICROS);
    }
}
