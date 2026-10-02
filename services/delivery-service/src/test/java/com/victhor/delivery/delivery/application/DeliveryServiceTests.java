package com.victhor.delivery.delivery.application;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.victhor.delivery.delivery.domain.Delivery;
import com.victhor.delivery.delivery.domain.DeliveryLocation;
import com.victhor.delivery.delivery.domain.GeoPoint;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DeliveryServiceTests {

    private static final Instant NOW = Instant.parse("2026-10-01T12:00:00.123456789Z");
    private static final Instant DATABASE_TIME = Instant.parse("2026-10-01T12:00:00.123456Z");

    @Mock
    private DeliveryRepository deliveries;

    private DeliveryService service;

    @BeforeEach
    void setUp() {
        service = new DeliveryService(deliveries, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void createsWithInjectedClockAndImmutableLocationSnapshots() {
        when(deliveries.create(any())).thenAnswer(invocation -> invocation.getArgument(0));
        UUID orderId = UUID.randomUUID();
        var origin = new DeliveryLocation("Restaurante", new GeoPoint(0, 0));
        var destination = new DeliveryLocation("Rua Central, 42", new GeoPoint(-23.55, -46.63));

        Delivery created = service.create(orderId, origin, destination);

        assertThat(created.orderId()).isEqualTo(orderId);
        assertThat(created.origin()).isEqualTo(origin);
        assertThat(created.destination()).isEqualTo(destination);
        assertThat(created.createdAt()).isEqualTo(DATABASE_TIME);
        verify(deliveries).create(created);
    }

    @Test
    void reportsMissingDeliveryAndUsesServerTimeForAllTransitions() {
        UUID id = UUID.randomUUID();
        UUID courierId = UUID.randomUUID();

        assertThatThrownBy(() -> service.findById(id)).isInstanceOf(DeliveryNotFoundException.class);
        assertThatThrownBy(() -> service.findByOrderId(id)).isInstanceOf(DeliveryNotFoundException.class);
        assertThatThrownBy(() -> service.assign(id, courierId)).isInstanceOf(DeliveryNotFoundException.class);
        assertThatThrownBy(() -> service.pickUp(id)).isInstanceOf(DeliveryNotFoundException.class);
        assertThatThrownBy(() -> service.startTransit(id)).isInstanceOf(DeliveryNotFoundException.class);
        assertThatThrownBy(() -> service.arrive(id)).isInstanceOf(DeliveryNotFoundException.class);
        assertThatThrownBy(() -> service.complete(id)).isInstanceOf(DeliveryNotFoundException.class);
        assertThatThrownBy(() -> service.cancel(id)).isInstanceOf(DeliveryNotFoundException.class);

        verify(deliveries).assign(id, courierId, DATABASE_TIME);
        verify(deliveries).pickUp(id, DATABASE_TIME);
        verify(deliveries).startTransit(id, DATABASE_TIME);
        verify(deliveries).arrive(id, DATABASE_TIME);
        verify(deliveries).complete(id, DATABASE_TIME);
        verify(deliveries).cancel(id, DATABASE_TIME);
    }
}
