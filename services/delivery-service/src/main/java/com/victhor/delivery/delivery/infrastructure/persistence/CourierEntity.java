package com.victhor.delivery.delivery.infrastructure.persistence;

import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import com.victhor.delivery.delivery.domain.Courier;

@Entity
@Table(name = "couriers")
public class CourierEntity {

    @Id
    private UUID id;

    @Column(nullable = false)
    private boolean active;

    @Version
    @Column(nullable = false)
    private Long version;

    protected CourierEntity() {
    }

    static CourierEntity fromDomain(Courier courier) {
        var entity = new CourierEntity();
        entity.id = courier.id();
        entity.active = courier.active();
        return entity;
    }

    Courier toDomain() {
        return new Courier(id, active);
    }
}
