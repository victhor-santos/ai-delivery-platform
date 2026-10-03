package com.victhor.delivery.delivery.api;

import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

import com.victhor.delivery.delivery.application.SegmentObservation;

final class SegmentObservationCsv {

    private static final String HEADER = "schema_version,traversal_id,delivery_id,route_plan_id,sequence,data_origin,"
            + "prediction_data_origin,segment_id,from_node,to_node,graph_version,model_version,feature_schema_version,"
            + "distance_km,road_type,reference_speed_kmh,traffic_level,hour,day_of_week,timezone,traffic_source,"
            + "traffic_observed_at,traffic_available_at,features_available_at,prediction_at,planned_departure_at,"
            + "context_as_of,predicted_travel_time_minutes,entered_at,entry_recorded_at,exited_at,recorded_at,"
            + "label_available_at,actual_travel_time_minutes";

    private SegmentObservationCsv() {
    }

    static String write(List<SegmentObservation> observations) {
        StringBuilder csv = new StringBuilder(HEADER).append('\n');
        for (var observation : observations) {
            var traversal = observation.traversal();
            var prediction = observation.prediction();
            var segment = prediction.segment();
            var context = segment.predictionContext();
            if (traversal.exitedAt() == null) {
                throw new IllegalArgumentException("Incomplete traversals cannot be exported as labeled samples");
            }
            Object[] values = {"delivery-segment-observation-v1", traversal.id(), traversal.deliveryId(), traversal.routePlanId(),
                    traversal.sequence(), traversal.dataOrigin(), prediction.dataOrigin(), segment.segmentId(), context.fromNode(),
                    context.toNode(), prediction.graphVersion(), prediction.modelVersion(), context.featureSchemaVersion(),
                    segment.distanceKm(), context.roadType(), context.referenceSpeedKmh(), context.trafficLevel(), context.hour(),
                    context.dayOfWeek(), context.timezone(), context.trafficSource(), context.trafficObservedAt(),
                    context.trafficAvailableAt(), context.featuresAvailableAt(), prediction.predictionAt(), prediction.plannedDepartureAt(),
                    prediction.contextAsOf(), segment.predictedTravelTimeMinutes(), traversal.enteredAt(), traversal.entryRecordedAt(),
                    traversal.exitedAt(), traversal.labelAvailableAt(), traversal.labelAvailableAt(), traversal.actualTravelTimeMinutes()};
            csv.append(Arrays.stream(values).map(value -> "\"" + value.toString().replace("\"", "\"\"") + "\"")
                    .collect(Collectors.joining(","))).append('\n');
        }
        return csv.toString();
    }
}
