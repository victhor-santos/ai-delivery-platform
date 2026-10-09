package com.victhor.delivery.order.infrastructure.persistence;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import com.victhor.delivery.order.application.DeliveryRequestRepository;
import com.victhor.delivery.order.application.OrderNotFoundException;
import com.victhor.delivery.order.domain.DeliveryRequest;
import com.victhor.delivery.order.infrastructure.messaging.OutboxEvents;

@Repository
@Transactional(readOnly = true)
public class JpaDeliveryRequestRepository implements DeliveryRequestRepository {

    private final SpringDataOrderRepository orders;
    private final SpringDataDeliveryRequestRepository requests;
    private final OutboxEvents outbox;

    public JpaDeliveryRequestRepository(SpringDataOrderRepository orders, SpringDataDeliveryRequestRepository requests,
            OutboxEvents outbox) {
        this.orders = orders;
        this.requests = requests;
        this.outbox = outbox;
    }

    @Override
    public Optional<DeliveryRequest> findByOrderId(UUID orderId) {
        return requests.findById(orderId).map(DeliveryRequestEntity::toDomain);
    }

    @Override
    @Transactional
    public DeliveryRequest prepare(DeliveryRequest request, Instant now) {
        var entity = orders.findById(request.orderId()).orElseThrow(OrderNotFoundException::new);
        var order = entity.toDomain().requestDelivery(now);
        var existing = requests.findById(request.orderId());
        if (existing.isPresent()) {
            return existing.orElseThrow().toDomain();
        }
        if (!order.destination().equals(request.destination())) {
            throw new IllegalArgumentException("Delivery destination must match the order snapshot");
        }
        entity.applyState(order);
        orders.flush();
        var saved = requests.save(DeliveryRequestEntity.from(request)).toDomain();
        outbox.deliveryRequested(saved, order.customerId(), now);
        return saved;
    }
}
