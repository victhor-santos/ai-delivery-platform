package com.victhor.delivery.delivery.application;

import java.util.List;
import java.util.UUID;

public interface SegmentObservationRepository {

    SegmentObservation enter(UUID deliveryId, int sequence, TraversalEvent event);

    SegmentObservation exit(UUID deliveryId, int sequence, TraversalEvent event);

    List<SegmentObservation> findByDelivery(UUID deliveryId);
}
