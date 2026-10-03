package com.victhor.delivery.delivery.api;

import java.time.Instant;
import java.util.UUID;

import com.victhor.delivery.delivery.application.SegmentObservation;

public record SegmentObservationResponse(UUID id, UUID deliveryId, UUID routePlanId, int sequence,
        String dataOrigin, Instant enteredAt, Instant entryRecordedAt, Instant exitedAt, Instant labelAvailableAt,
        Double actualTravelTimeMinutes, Prediction prediction) {

    static SegmentObservationResponse from(SegmentObservation row) {
        var traversal = row.traversal();
        var prediction = row.prediction();
        var segment = prediction.segment();
        var context = segment.predictionContext();
        var features = new PredictionContext(context.featureSchemaVersion(), context.fromNode(), context.toNode(),
                context.roadType(), context.referenceSpeedKmh(), context.trafficLevel(), context.hour(), context.dayOfWeek(),
                context.timezone(), context.trafficSource(), context.trafficObservedAt(), context.trafficAvailableAt(),
                context.featuresAvailableAt());
        var snapshot = new Prediction(new Segment(segment.segmentId(), segment.distanceKm(),
                segment.predictedTravelTimeMinutes(), features), prediction.plannedDepartureAt(), prediction.predictionAt(),
                prediction.contextAsOf(), prediction.modelVersion(), prediction.graphVersion(), prediction.dataOrigin());
        return new SegmentObservationResponse(traversal.id(), traversal.deliveryId(), traversal.routePlanId(),
                traversal.sequence(), traversal.dataOrigin(), traversal.enteredAt(), traversal.entryRecordedAt(),
                traversal.exitedAt(), traversal.labelAvailableAt(), traversal.actualTravelTimeMinutes(), snapshot);
    }

    public record Prediction(Segment segment, Instant plannedDepartureAt, Instant predictionAt,
            Instant contextAsOf, String modelVersion, String graphVersion, String dataOrigin) {
    }

    public record Segment(String segmentId, double distanceKm, double predictedTravelTimeMinutes,
            PredictionContext predictionContext) {
    }

    public record PredictionContext(String featureSchemaVersion, String fromNode, String toNode,
            String roadType, double referenceSpeedKmh, String trafficLevel, int hour, int dayOfWeek,
            String timezone, String trafficSource, Instant trafficObservedAt, Instant trafficAvailableAt,
            Instant featuresAvailableAt) {
    }
}
