package com.victhor.delivery.delivery.infrastructure;

import java.time.Instant;
import java.util.function.Supplier;
import java.util.stream.Stream;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import com.victhor.delivery.delivery.application.RouteContext;
import com.victhor.delivery.delivery.application.RouteNotFoundException;
import com.victhor.delivery.delivery.application.RouteServiceUnavailableException;
import com.victhor.delivery.delivery.application.UnsupportedRouteLocationException;
import com.victhor.delivery.delivery.domain.GeoPoint;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MeteredRouteOptimizerTests {

    private static final GeoPoint POINT = new GeoPoint(-23.5505, -46.6333);
    private static final RouteContext CONTEXT = new RouteContext(Instant.parse("2026-10-03T12:00:00Z"));

    static Stream<Arguments> failures() {
        return Stream.of(
                Arguments.of((Supplier<RuntimeException>) UnsupportedRouteLocationException::new, "outside_coverage"),
                Arguments.of((Supplier<RuntimeException>) RouteNotFoundException::new, "route_not_found"),
                Arguments.of((Supplier<RuntimeException>) RouteServiceUnavailableException::new, "unavailable"),
                Arguments.of((Supplier<RuntimeException>) IllegalStateException::new, "error"));
    }

    @ParameterizedTest
    @MethodSource("failures")
    void recordsTheOutcomeOfFailedCalculationsAndRethrows(Supplier<RuntimeException> failure, String outcome) {
        var registry = new SimpleMeterRegistry();
        RuntimeException expected = failure.get();
        var optimizer = new MeteredRouteOptimizer((origin, destination, context) -> {
            throw expected;
        }, registry);

        assertThatThrownBy(() -> optimizer.optimizeRoute(POINT, POINT, CONTEXT)).isSameAs(expected);

        assertThat(registry.get(MeteredRouteOptimizer.METRIC).tag("outcome", outcome).timer().count()).isEqualTo(1);
    }
}
