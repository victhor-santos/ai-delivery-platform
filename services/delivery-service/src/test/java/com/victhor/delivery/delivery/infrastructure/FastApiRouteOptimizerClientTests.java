package com.victhor.delivery.delivery.infrastructure;

import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import com.victhor.delivery.delivery.application.RouteContext;
import com.victhor.delivery.delivery.application.OptimizedRoute;
import com.victhor.delivery.delivery.application.RouteNotFoundException;
import com.victhor.delivery.delivery.application.RouteServiceUnavailableException;
import com.victhor.delivery.delivery.application.UnsupportedRouteLocationException;
import com.victhor.delivery.delivery.domain.GeoPoint;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FastApiRouteOptimizerClientTests {

    private static final GeoPoint ORIGIN = new GeoPoint(-23.5505, -46.6333);
    private static final GeoPoint DESTINATION = new GeoPoint(-23.561, -46.656);
    private static final RouteContext CONTEXT = new RouteContext(Instant.parse("2026-10-03T12:00:00Z"));
    private static final String VALID_RESPONSE = """
            {"route":[{"lat":-23.5505,"lon":-46.6333},{"lat":-23.561,"lon":-46.656}],
             "segments":[{"segment_id":"A-C","distance_km":2.9,"predicted_travel_time_minutes":16.0}],
             "distance_km":2.9,"predicted_travel_time_minutes":16.0,
             "predicted_at":"2026-10-03T12:00:00Z","context_as_of":"2026-10-03T11:00:00Z",
             "model_version":"segment-model-v1-0123456789abcdef","graph_version":"synthetic-city-v1",
             "data_origin":"synthetic"}
            """;
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void sendsPythonFieldNamesAndReadsValidatedRouteWithoutLeakingJavaFields() throws Exception {
        var captured = new AtomicReference<String>();
        try (var server = new Remote(200, "application/json; charset=utf-8", VALID_RESPONSE, 0, captured)) {
            var result = optimize(server);
            var request = mapper.readTree(captured.get());
            assertThat(request.size()).isEqualTo(3);
            assertThat(request.path("origin").path("lat").asDouble()).isEqualTo(ORIGIN.latitude());
            assertThat(request.path("destination").path("lon").asDouble()).isEqualTo(DESTINATION.longitude());
            assertThat(request.path("departure_at").asString()).isEqualTo(CONTEXT.departureAt().toString());
            assertThat(request.has("departureAt")).isFalse();
            assertThat(result.distanceKm()).isEqualTo(2.9);
            assertThat(result.contextAsOf()).isBefore(result.predictedAt());
            assertThat(server.calls.get()).isEqualTo(1);
        }
    }

    @ParameterizedTest
    @MethodSource("remoteProblems")
    void distinguishesCoverageAndMissingPathFromIntegrationFailures(int status, String code,
            Class<? extends RuntimeException> expected) throws Exception {
        String problem = """
                {"type":"about:blank","title":"Failure","status":%d,"detail":"private remote detail","code":"%s"}
                """.formatted(status, code);
        try (var server = new Remote(status, "application/problem+json", problem, 0, null)) {
            assertThatThrownBy(() -> optimize(server)).isInstanceOf(expected).hasMessageNotContaining("private remote");
            assertThat(server.calls.get()).isEqualTo(1);
        }
    }

    static Stream<Arguments> remoteProblems() {
        return Stream.of(
                Arguments.of(422, "OUTSIDE_GRAPH_COVERAGE", UnsupportedRouteLocationException.class),
                Arguments.of(404, "ROUTE_NOT_FOUND", RouteNotFoundException.class),
                Arguments.of(422, "INVALID_REQUEST", RouteServiceUnavailableException.class),
                Arguments.of(503, "MODEL_UNAVAILABLE", RouteServiceUnavailableException.class),
                Arguments.of(503, "TRAFFIC_UNAVAILABLE", RouteServiceUnavailableException.class),
                Arguments.of(503, "INVALID_PREDICTION", RouteServiceUnavailableException.class),
                Arguments.of(500, "INTERNAL_ERROR", RouteServiceUnavailableException.class),
                Arguments.of(404, "UNKNOWN", RouteServiceUnavailableException.class),
                Arguments.of(503, "OUTSIDE_GRAPH_COVERAGE", RouteServiceUnavailableException.class));
    }

    @ParameterizedTest
    @MethodSource("invalidResponses")
    void rejectsIncompleteCoercedOrInconsistentSuccessResponses(String response) throws Exception {
        try (var server = new Remote(200, "application/json", response, 0, null)) {
            assertThatThrownBy(() -> optimize(server)).isInstanceOf(RouteServiceUnavailableException.class);
        }
    }

    static Stream<String> invalidResponses() {
        return Stream.of("not json", "null", "[]", "{}",
                VALID_RESPONSE.replace("\"distance_km\":2.9", "\"distance_km\":\"2.9\""),
                ((ObjectNode) new ObjectMapper().readTree(VALID_RESPONSE)).put("distance_km", 3.0).toString(),
                VALID_RESPONSE.replace("\"lat\":-23.5505", "\"lat\":true"),
                VALID_RESPONSE.replace("\"lat\":-23.5505", "\"lat\":0"),
                VALID_RESPONSE.replace("16.0", "-1.0"),
                VALID_RESPONSE.replace("2026-10-03T11:00:00Z", "2026-10-04T11:00:00Z"),
                VALID_RESPONSE.replace("2026-10-03T12:00:00Z", "2026-10-03T12:00:00"),
                VALID_RESPONSE.replace("\"synthetic\"", "\"real\""));
    }

    @Test
    void rejectsMismatchedProblemStatusAndUnexpectedMediaTypes() throws Exception {
        for (String type : new String[] {"text/html", "application/problem+json"}) {
            try (var server = new Remote(200, type, VALID_RESPONSE, 0, null)) {
                assertThatThrownBy(() -> optimize(server)).isInstanceOf(RouteServiceUnavailableException.class);
            }
        }
        try (var server = new Remote(404, "application/problem+json",
                "{\"type\":\"about:blank\",\"title\":\"Failure\",\"status\":422,\"detail\":\"Failure\",\"code\":\"ROUTE_NOT_FOUND\"}", 0, null)) {
            assertThatThrownBy(() -> optimize(server)).isInstanceOf(RouteServiceUnavailableException.class);
        }
    }

    @Test
    void boundsTheEntireResponseEvenIfHeadersHaveAlreadyArrived() throws Exception {
        try (var server = new Remote(200, "application/json", VALID_RESPONSE, 500, null);
                var client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(1)).build()) {
            var adapter = new FastApiRouteOptimizerClient(client, mapper, server.url(), Duration.ofMillis(150));
            long started = System.nanoTime();
            assertThatThrownBy(() -> adapter.optimizeRoute(ORIGIN, DESTINATION, CONTEXT))
                    .isInstanceOf(RouteServiceUnavailableException.class);
            assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(2));
            assertThat(server.calls.get()).isEqualTo(1);
        }
    }

    @Test
    void convertsConnectionFailureAndPreservesInterruption() throws Exception {
        String stoppedUrl;
        try (var server = new Remote(200, "application/json", VALID_RESPONSE, 0, null)) {
            stoppedUrl = server.url();
        }
        try (var client = HttpClient.newHttpClient()) {
            var adapter = new FastApiRouteOptimizerClient(client, mapper, stoppedUrl, Duration.ofMillis(500));
            assertThatThrownBy(() -> adapter.optimizeRoute(ORIGIN, DESTINATION, CONTEXT))
                    .isInstanceOf(RouteServiceUnavailableException.class);
        }
        try (var server = new Remote(200, "application/json", VALID_RESPONSE, 500, null);
                var client = HttpClient.newHttpClient()) {
            Thread.currentThread().interrupt();
            try {
                assertThatThrownBy(() -> new FastApiRouteOptimizerClient(client, mapper, server.url(), Duration.ofSeconds(1))
                        .optimizeRoute(ORIGIN, DESTINATION, CONTEXT)).isInstanceOf(RouteServiceUnavailableException.class);
                assertThat(Thread.currentThread().isInterrupted()).isTrue();
            } finally {
                Thread.interrupted();
            }
        }
    }

    @Test
    void rejectsInvalidConfigurationAtStartup() {
        try (var client = HttpClient.newHttpClient()) {
            for (String url : new String[] {"file:///tmp", "http://localhost:8000?secret=1", "http://user@localhost:8000", "http://localhost:8000/path"}) {
                assertThatThrownBy(() -> new FastApiRouteOptimizerClient(client, mapper, url, Duration.ofSeconds(1)))
                        .isInstanceOf(IllegalArgumentException.class);
            }
            assertThatThrownBy(() -> new FastApiRouteOptimizerClient(client, mapper, "http://localhost:8000", Duration.ZERO))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    private OptimizedRoute optimize(Remote server) {
        try (var client = HttpClient.newHttpClient()) {
            return new FastApiRouteOptimizerClient(client, mapper, server.url(), Duration.ofSeconds(2))
                    .optimizeRoute(ORIGIN, DESTINATION, CONTEXT);
        }
    }

    private static final class Remote implements AutoCloseable {
        private final HttpServer server;
        private final java.util.concurrent.ExecutorService workers = Executors.newVirtualThreadPerTaskExecutor();
        private final AtomicInteger calls = new AtomicInteger();

        Remote(int status, String contentType, String body, long delayAfterHeadersMs, AtomicReference<String> captured)
                throws Exception {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.setExecutor(workers);
            server.createContext("/api/routes/fastest", exchange -> {
                calls.incrementAndGet();
                byte[] request = exchange.getRequestBody().readAllBytes();
                if (captured != null) {
                    captured.set(new String(request, StandardCharsets.UTF_8));
                }
                byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", contentType);
                exchange.sendResponseHeaders(status, bytes.length);
                try {
                    if (delayAfterHeadersMs > 0) {
                        Thread.sleep(delayAfterHeadersMs);
                    }
                    exchange.getResponseBody().write(bytes);
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                } finally {
                    exchange.close();
                }
            });
            server.start();
        }

        String url() {
            return "http://127.0.0.1:" + server.getAddress().getPort();
        }

        @Override
        public void close() {
            server.stop(0);
            workers.shutdownNow();
            workers.close();
        }
    }
}
