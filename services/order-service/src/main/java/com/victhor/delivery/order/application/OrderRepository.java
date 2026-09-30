package com.victhor.delivery.order.application;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import com.victhor.delivery.order.domain.Order;

public interface OrderRepository {

    Order save(Order order);

    Optional<Order> findById(UUID id);

    Optional<Order> confirm(UUID id, Instant confirmedAt);

    Optional<Order> cancel(UUID id, Instant cancelledAt);
}
