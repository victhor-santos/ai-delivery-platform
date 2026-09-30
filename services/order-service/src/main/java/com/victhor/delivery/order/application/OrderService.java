package com.victhor.delivery.order.application;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import com.victhor.delivery.order.domain.DeliveryDestination;
import com.victhor.delivery.order.domain.Order;

public class OrderService {

    private final OrderRepository orders;
    private final Clock clock;

    public OrderService(OrderRepository orders, Clock clock) {
        this.orders = orders;
        this.clock = clock;
    }

    public Order create(UUID restaurantId, DeliveryDestination destination) {
        return orders.save(Order.create(restaurantId, destination, now()));
    }

    public Order findById(UUID id) {
        return orders.findById(id).orElseThrow(OrderNotFoundException::new);
    }

    public Order confirm(UUID id) {
        return orders.confirm(id, now()).orElseThrow(OrderNotFoundException::new);
    }

    public Order cancel(UUID id) {
        return orders.cancel(id, now()).orElseThrow(OrderNotFoundException::new);
    }

    private Instant now() {
        return clock.instant().truncatedTo(ChronoUnit.MICROS);
    }
}
