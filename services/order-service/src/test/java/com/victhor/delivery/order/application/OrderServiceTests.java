package com.victhor.delivery.order.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.victhor.delivery.order.domain.DeliveryDestination;
import com.victhor.delivery.order.domain.Order;

@ExtendWith(MockitoExtension.class)
class OrderServiceTests {

    private static final Instant NOW = Instant.parse("2026-09-30T12:00:00.123456789Z");
    private static final Instant DATABASE_TIME = Instant.parse("2026-09-30T12:00:00.123456Z");

    @Mock
    private OrderRepository orders;

    private OrderService service;

    @BeforeEach
    void setUp() {
        service = new OrderService(orders, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void createsUsingTheInjectedClockAtPostgresPrecision() {
        when(orders.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        UUID restaurantId = UUID.randomUUID();
        var destination = new DeliveryDestination("Rua Central, 10", 0, 0);

        Order created = service.create(restaurantId, destination);

        assertThat(created.restaurantId()).isEqualTo(restaurantId);
        assertThat(created.destination()).isEqualTo(destination);
        assertThat(created.createdAt()).isEqualTo(DATABASE_TIME);
        verify(orders).save(created);
    }

    @Test
    void reportsMissingOrdersOnAllOperations() {
        UUID id = UUID.randomUUID();

        assertThatThrownBy(() -> service.findById(id)).isInstanceOf(OrderNotFoundException.class);
        assertThatThrownBy(() -> service.confirm(id)).isInstanceOf(OrderNotFoundException.class);
        assertThatThrownBy(() -> service.cancel(id)).isInstanceOf(OrderNotFoundException.class);
        verify(orders).confirm(id, DATABASE_TIME);
        verify(orders).cancel(id, DATABASE_TIME);
    }
}
