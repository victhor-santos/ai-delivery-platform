package com.victhor.delivery.order.application;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import com.victhor.delivery.order.domain.DeliveryRequest;

public interface DeliveryRequestRepository {

    Optional<DeliveryRequest> findByOrderId(UUID orderId);

    DeliveryRequest prepare(DeliveryRequest request, Instant now);
}
