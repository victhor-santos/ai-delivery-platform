package com.victhor.delivery.order.infrastructure.persistence;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.function.UnaryOperator;

import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import com.victhor.delivery.order.application.OrderRepository;
import com.victhor.delivery.order.domain.Order;

@Repository
@Transactional(readOnly = true)
public class JpaOrderRepository implements OrderRepository {

    private final SpringDataOrderRepository repository;

    public JpaOrderRepository(SpringDataOrderRepository repository) {
        this.repository = repository;
    }

    @Override
    @Transactional
    public Order save(Order order) {
        return repository.save(OrderEntity.fromDomain(order)).toDomain();
    }

    @Override
    public Optional<Order> findById(UUID id) {
        return repository.findById(id).map(OrderEntity::toDomain);
    }

    @Override
    @Transactional
    public Optional<Order> cancel(UUID id, Instant cancelledAt) {
        return update(id, order -> order.cancel(cancelledAt));
    }

    private Optional<Order> update(UUID id, UnaryOperator<Order> transition) {
        return repository.findById(id).map(entity -> {
            Order updated = transition.apply(entity.toDomain());
            entity.applyState(updated);
            return updated;
        });
    }
}
