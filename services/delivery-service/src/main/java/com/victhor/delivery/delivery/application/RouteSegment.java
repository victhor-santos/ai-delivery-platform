package com.victhor.delivery.delivery.application;

public record RouteSegment(String segmentId, double distanceKm, double predictedTravelTimeMinutes,
        SegmentPredictionContext predictionContext) {

    public RouteSegment(String segmentId, double distanceKm, double predictedTravelTimeMinutes) {
        this(segmentId, distanceKm, predictedTravelTimeMinutes, null);
    }

    public RouteSegment {
        if (segmentId == null || !segmentId.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,63}")) {
            throw new IllegalArgumentException("Invalid segment identifier");
        }
        if (!Double.isFinite(distanceKm) || distanceKm <= 0
                || !Double.isFinite(predictedTravelTimeMinutes) || predictedTravelTimeMinutes <= 0) {
            throw new IllegalArgumentException("Segment costs must be finite and positive");
        }
    }
}
