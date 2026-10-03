package com.victhor.delivery.delivery.application;

import java.time.Instant;
import java.time.ZoneId;
import java.util.Objects;
import java.util.Set;

public record SegmentPredictionContext(String featureSchemaVersion, String fromNode, String toNode,
        String roadType, double referenceSpeedKmh, String trafficLevel, int hour, int dayOfWeek,
        String timezone, String trafficSource, Instant trafficObservedAt, Instant trafficAvailableAt,
        Instant featuresAvailableAt) {

    public SegmentPredictionContext {
        if (!"segment-features-v1".equals(featureSchemaVersion) || !identifier(fromNode) || !identifier(toNode)
                || fromNode.equals(toNode) || roadType == null || !Set.of("residential", "primary", "highway").contains(roadType)
                || !Double.isFinite(referenceSpeedKmh) || referenceSpeedKmh <= 0
                || trafficLevel == null || !Set.of("low", "medium", "high").contains(trafficLevel)
                || hour < 0 || hour > 23 || dayOfWeek < 0 || dayOfWeek > 6
                || !"America/Sao_Paulo".equals(timezone) || !identifier(trafficSource)) {
            throw new IllegalArgumentException("Invalid segment prediction context");
        }
        Objects.requireNonNull(trafficObservedAt);
        Objects.requireNonNull(trafficAvailableAt);
        Objects.requireNonNull(featuresAvailableAt);
        if (trafficObservedAt.isAfter(trafficAvailableAt) || trafficAvailableAt.isAfter(featuresAvailableAt)) {
            throw new IllegalArgumentException("Invalid feature availability order");
        }
    }

    public void validateFor(Instant departureAt, Instant predictedAt, Instant contextAsOf) {
        var local = departureAt.atZone(ZoneId.of(timezone));
        if (featuresAvailableAt.isAfter(contextAsOf) || contextAsOf.isAfter(predictedAt)
                || hour != local.getHour() || dayOfWeek != local.getDayOfWeek().getValue() - 1) {
            throw new IllegalArgumentException("Features do not match prediction context");
        }
    }

    private static boolean identifier(String value) {
        return value != null && value.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,63}");
    }
}
