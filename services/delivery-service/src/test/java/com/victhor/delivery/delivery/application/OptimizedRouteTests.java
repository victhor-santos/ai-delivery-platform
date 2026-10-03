package com.victhor.delivery.delivery.application;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import com.victhor.delivery.delivery.domain.GeoPoint;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OptimizedRouteTests {

    private static final Instant NOW = Instant.parse("2026-10-03T12:00:00Z");
    private static final GeoPoint ORIGIN = new GeoPoint(-23.5505, -46.6333);
    private static final GeoPoint DESTINATION = new GeoPoint(-23.561, -46.656);

    @Test
    void preservesOrderAndCopiesCollectionsWithFloatingPointTolerance() {
        var points = new ArrayList<>(List.of(ORIGIN, new GeoPoint(-23.554, -46.64), DESTINATION));
        var segments = new ArrayList<>(List.of(new RouteSegment("A-B", 0.1, 0.1), new RouteSegment("B-C", 0.2, 0.2)));
        var route = route(points, segments, 0.3, 0.3, NOW);
        points.clear();
        segments.clear();
        assertThat(route.route()).hasSize(3);
        assertThat(route.segments()).extracting(RouteSegment::segmentId).containsExactly("A-B", "B-C");
        assertThatThrownBy(() -> route.route().clear()).isInstanceOf(UnsupportedOperationException.class);
        route.validateEndpoints(ORIGIN, DESTINATION);
    }

    @Test
    void acceptsSameNodeAndSubmeterSnappingButRejectsUnrelatedEndpoints() {
        var zero = route(List.of(ORIGIN), List.of(), 0, 0, NOW);
        zero.validateEndpoints(ORIGIN, new GeoPoint(ORIGIN.latitude() + 0.000001, ORIGIN.longitude()));
        assertThatThrownBy(() -> zero.validateEndpoints(ORIGIN, DESTINATION)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> route(List.of(ORIGIN), List.of(), 1, 0, NOW)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> route(List.of(ORIGIN), List.of(), 1e-10, 0, NOW)).isInstanceOf(IllegalArgumentException.class);
    }

    @ParameterizedTest
    @MethodSource("invalidCosts")
    void rejectsInvalidSegmentCosts(double value) {
        assertThatThrownBy(() -> new RouteSegment("A-B", value, 1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new RouteSegment("A-B", 1, value)).isInstanceOf(IllegalArgumentException.class);
    }

    static Stream<Double> invalidCosts() {
        return Stream.of(0.0, -1.0, Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY);
    }

    @Test
    void rejectsMalformedRoutesAndFutureContext() {
        var segment = new RouteSegment("A-B", 1, 2);
        assertThatThrownBy(() -> route(List.of(), List.of(), 0, 0, NOW)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> route(List.of(ORIGIN), List.of(segment), 1, 2, NOW)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> route(List.of(ORIGIN, DESTINATION), List.of(segment), 2, 2, NOW)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> route(List.of(ORIGIN, DESTINATION), List.of(segment), 1, Double.NaN, NOW)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> route(List.of(ORIGIN), List.of(), 0, 0, NOW.plusNanos(1))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> route(List.of(ORIGIN, DESTINATION, ORIGIN), List.of(segment, segment), 2, 4, NOW)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new RouteSegment("invalid id", 1, 2)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new OptimizedRoute(List.of(ORIGIN), List.of(), 0, 0, NOW, NOW, " ", "v1", "synthetic"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new OptimizedRoute(List.of(ORIGIN), List.of(), 0, 0, NOW, NOW, "v1", "v1", "real"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void normalizesDeparturePrecisionAndChecksPythonCalendarRange() {
        assertThat(new RouteContext(NOW.plusNanos(123456789)).departureAt()).isEqualTo(NOW.plusNanos(123456000));
        assertThatThrownBy(() -> new RouteContext(Instant.parse("+10000-01-01T00:00:00Z")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new RouteContext(Instant.parse("0001-01-01T00:00:00Z")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private OptimizedRoute route(List<GeoPoint> points, List<RouteSegment> segments, double distance, double time,
            Instant context) {
        return new OptimizedRoute(points, segments, distance, time, NOW, context, "segment-model-v1-0123456789abcdef",
                "synthetic-city-v1", "synthetic");
    }

    @Test
    void requiresAllSnapshotsToDescribeAConnectedPathAvailableAtPredictionTime() {
        var points = List.of(ORIGIN, new GeoPoint(-23.554, -46.64), DESTINATION);
        var first = snapshotSegment("A", "B", NOW);
        var second = snapshotSegment("B", "C", NOW);
        assertThat(route(points, List.of(first, second), 2, 4, NOW).segments()).hasSize(2);
        assertThatThrownBy(() -> route(points, List.of(first, new RouteSegment("B-C", 1, 2)), 2, 4, NOW))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> route(points, List.of(first, snapshotSegment("C", "D", NOW)), 2, 4, NOW))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> route(points, List.of(first, snapshotSegment("B", "C", NOW.plusSeconds(1))), 2, 4, NOW))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private RouteSegment snapshotSegment(String from, String to, Instant availableAt) {
        return new RouteSegment(from + "-" + to, 1, 2, new SegmentPredictionContext("segment-features-v1", from, to,
                "primary", 30, "low", 9, 5, "America/Sao_Paulo", "synthetic-traffic-v1", NOW, NOW, availableAt));
    }
}
