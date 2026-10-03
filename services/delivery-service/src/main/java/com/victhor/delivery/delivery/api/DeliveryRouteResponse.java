package com.victhor.delivery.delivery.api;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.victhor.delivery.delivery.application.DeliveryRoutePlan;

public record DeliveryRouteResponse(UUID id, UUID deliveryId, Instant departureAt, Instant plannedAt,
        List<Point> route, List<Segment> segments, double distanceKm, double predictedTravelTimeMinutes,
        Instant predictedAt, Instant contextAsOf, String modelVersion, String graphVersion, String dataOrigin) {

    static DeliveryRouteResponse from(DeliveryRoutePlan plan) {
        var route = plan.optimizedRoute();
        return new DeliveryRouteResponse(plan.id(), plan.deliveryId(), plan.departureAt(), plan.plannedAt(),
                route.route().stream().map(point -> new Point(point.latitude(), point.longitude())).toList(),
                route.segments().stream().map(segment -> new Segment(segment.segmentId(), segment.distanceKm(),
                        segment.predictedTravelTimeMinutes())).toList(), route.distanceKm(), route.predictedTravelTimeMinutes(),
                route.predictedAt(), route.contextAsOf(), route.modelVersion(), route.graphVersion(), route.dataOrigin());
    }

    public record Point(double latitude, double longitude) {
    }

    public record Segment(String segmentId, double distanceKm, double predictedTravelTimeMinutes) {
    }
}
