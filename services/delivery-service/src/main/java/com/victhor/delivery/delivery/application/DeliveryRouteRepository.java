package com.victhor.delivery.delivery.application;

import java.util.Optional;
import java.util.UUID;

public interface DeliveryRouteRepository {

    Optional<DeliveryRoutingSnapshot> findSnapshot(UUID deliveryId);

    Optional<DeliveryRoutePlan> findPlan(UUID deliveryId);

    DeliveryRoutePlan save(DeliveryRoutingSnapshot snapshot, DeliveryRoutePlan plan);
}
