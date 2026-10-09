package com.victhor.delivery.delivery.application;

import java.time.Instant;
import java.util.Objects;

public record TraversalPrediction(RouteSegment segment, Instant plannedDepartureAt, Instant predictionAt,
        Instant contextAsOf, String modelVersion, String graphVersion, String dataOrigin) {

    public TraversalPrediction {
        Objects.requireNonNull(segment);
        Objects.requireNonNull(segment.predictionContext(), "Segment features were not recorded in this plan");
        segment.predictionContext().validateFor(plannedDepartureAt, predictionAt, contextAsOf);
        if (!OptimizedRoute.DATA_ORIGINS.contains(dataOrigin) || modelVersion == null || graphVersion == null) {
            throw new IllegalArgumentException("Invalid prediction provenance");
        }
    }

    public static TraversalPrediction from(DeliveryRoutePlan plan, int sequence) {
        var route = plan.optimizedRoute();
        return new TraversalPrediction(route.segments().get(sequence), plan.departureAt(), route.predictedAt(),
                route.contextAsOf(), route.modelVersion(), route.graphVersion(), route.dataOrigin());
    }
}
