package com.victhor.delivery.delivery.application;

import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;

import com.victhor.delivery.delivery.domain.GeoPoint;

public record OptimizedRoute(List<GeoPoint> route, List<RouteSegment> segments, double distanceKm,
        double predictedTravelTimeMinutes, Instant predictedAt, Instant contextAsOf, String modelVersion,
        String graphVersion, String dataOrigin) {

    public OptimizedRoute {
        route = List.copyOf(route);
        segments = List.copyOf(segments);
        Objects.requireNonNull(predictedAt, "Prediction time is required");
        Objects.requireNonNull(contextAsOf, "Context time is required");
        if (route.isEmpty() || route.size() > 200 || route.size() != segments.size() + 1) {
            throw new IllegalArgumentException("Route coordinates do not match the segments");
        }
        var identifiers = new HashSet<String>();
        for (RouteSegment segment : segments) {
            if (!identifiers.add(segment.segmentId())) {
                throw new IllegalArgumentException("Repeated route segment");
            }
        }
        if (!Double.isFinite(distanceKm) || distanceKm < 0
                || !Double.isFinite(predictedTravelTimeMinutes) || predictedTravelTimeMinutes < 0
                || (segments.isEmpty() && (distanceKm != 0 || predictedTravelTimeMinutes != 0))
                || (!segments.isEmpty() && (distanceKm == 0 || predictedTravelTimeMinutes == 0))
                || !sameTotal(distanceKm, segments.stream().mapToDouble(RouteSegment::distanceKm).sum())
                || !sameTotal(predictedTravelTimeMinutes,
                        segments.stream().mapToDouble(RouteSegment::predictedTravelTimeMinutes).sum())) {
            throw new IllegalArgumentException("Invalid route totals");
        }
        if (contextAsOf.isAfter(predictedAt) || modelVersion == null
                || !modelVersion.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,127}") || graphVersion == null
                || !graphVersion.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,63}") || !"synthetic".equals(dataOrigin)) {
            throw new IllegalArgumentException("Invalid route provenance");
        }
    }

    public void validateEndpoints(GeoPoint origin, GeoPoint destination) {
        if (metersBetween(origin, route.getFirst()) > 1.0 || metersBetween(destination, route.getLast()) > 1.0) {
            throw new IllegalArgumentException("Route endpoints do not match the delivery");
        }
    }

    private static boolean sameTotal(double actual, double expected) {
        return Double.isFinite(expected) && Math.abs(actual - expected) <= 1e-9 * Math.max(1.0, expected);
    }

    private static double metersBetween(GeoPoint first, GeoPoint second) {
        double firstLatitude = Math.toRadians(first.latitude());
        double secondLatitude = Math.toRadians(second.latitude());
        double latitudeDifference = secondLatitude - firstLatitude;
        double longitudeDifference = Math.toRadians(second.longitude() - first.longitude());
        double haversine = Math.pow(Math.sin(latitudeDifference / 2), 2)
                + Math.cos(firstLatitude) * Math.cos(secondLatitude) * Math.pow(Math.sin(longitudeDifference / 2), 2);
        return 2 * 6_371_000 * Math.asin(Math.sqrt(Math.clamp(haversine, 0.0, 1.0)));
    }
}
