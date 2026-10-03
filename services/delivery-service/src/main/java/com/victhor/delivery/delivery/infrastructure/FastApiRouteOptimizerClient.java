package com.victhor.delivery.delivery.infrastructure;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import com.victhor.delivery.delivery.application.OptimizedRoute;
import com.victhor.delivery.delivery.application.RouteContext;
import com.victhor.delivery.delivery.application.RouteNotFoundException;
import com.victhor.delivery.delivery.application.RouteOptimizer;
import com.victhor.delivery.delivery.application.RouteSegment;
import com.victhor.delivery.delivery.application.RouteServiceUnavailableException;
import com.victhor.delivery.delivery.application.UnsupportedRouteLocationException;
import com.victhor.delivery.delivery.domain.GeoPoint;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

public class FastApiRouteOptimizerClient implements RouteOptimizer {

    private final HttpClient client;
    private final ObjectMapper mapper;
    private final URI endpoint;
    private final Duration timeout;

    public FastApiRouteOptimizerClient(HttpClient client, ObjectMapper mapper, String baseUrl, Duration timeout) {
        URI base = URI.create(baseUrl);
        if (!("http".equals(base.getScheme()) || "https".equals(base.getScheme())) || base.getHost() == null
                || base.getUserInfo() != null || base.getQuery() != null || base.getFragment() != null
                || !(base.getPath().isEmpty() || "/".equals(base.getPath())) || timeout.toMillis() <= 0) {
            throw new IllegalArgumentException("Invalid route service configuration");
        }
        this.client = client;
        this.mapper = mapper;
        this.endpoint = base.resolve("/api/routes/fastest");
        this.timeout = timeout;
    }

    @Override
    public OptimizedRoute optimizeRoute(GeoPoint origin, GeoPoint destination, RouteContext context) {
        ObjectNode body = mapper.createObjectNode();
        body.set("origin", point(origin));
        body.set("destination", point(destination));
        body.put("departure_at", context.departureAt().toString());
        HttpResponse<String> response = send(body.toString());
        if (response.statusCode() != 200) {
            throwRemoteFailure(response);
        }
        try {
            requireMediaType(response, "application/json");
            JsonNode json = readBody(response);
            JsonNode points = json.path("route");
            JsonNode segments = json.path("segments");
            if (!points.isArray() || points.isEmpty() || points.size() > 200
                    || !segments.isArray() || segments.size() > 199) {
                throw new IllegalArgumentException("Invalid route collections");
            }
            var coordinates = new ArrayList<GeoPoint>();
            for (JsonNode point : points) {
                coordinates.add(new GeoPoint(number(point.path("lat")), number(point.path("lon"))));
            }
            var costs = new ArrayList<RouteSegment>();
            for (JsonNode segment : segments) {
                costs.add(new RouteSegment(text(segment.path("segment_id")), number(segment.path("distance_km")),
                        number(segment.path("predicted_travel_time_minutes"))));
            }
            var route = new OptimizedRoute(coordinates, costs, number(json.path("distance_km")),
                    number(json.path("predicted_travel_time_minutes")), instant(json.path("predicted_at")),
                    instant(json.path("context_as_of")), text(json.path("model_version")),
                    text(json.path("graph_version")), text(json.path("data_origin")));
            route.validateEndpoints(origin, destination);
            return route;
        } catch (RuntimeException exception) {
            throw new RouteServiceUnavailableException(exception);
        }
    }

    private void throwRemoteFailure(HttpResponse<String> response) {
        String code;
        try {
            requireMediaType(response, "application/problem+json");
            JsonNode problem = readBody(response);
            if (!problem.path("status").isIntegralNumber() || problem.path("status").asInt() != response.statusCode()
                    || !"about:blank".equals(text(problem.path("type")))) {
                throw new IllegalArgumentException("Invalid remote problem");
            }
            text(problem.path("title"));
            text(problem.path("detail"));
            code = text(problem.path("code"));
        } catch (RuntimeException exception) {
            throw new RouteServiceUnavailableException(exception);
        }
        if (response.statusCode() == 422 && "OUTSIDE_GRAPH_COVERAGE".equals(code)) {
            throw new UnsupportedRouteLocationException();
        }
        if (response.statusCode() == 404 && "ROUTE_NOT_FOUND".equals(code)) {
            throw new RouteNotFoundException();
        }
        // Invalid requests generated by this adapter are integration failures, not user input errors.
        throw new RouteServiceUnavailableException();
    }

    private HttpResponse<String> send(String body) {
        var request = HttpRequest.newBuilder(endpoint).timeout(timeout)
                .header("Accept", "application/json, application/problem+json")
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body)).build();
        var pending = client.sendAsync(request, HttpResponse.BodyHandlers.ofString());
        try {
            return pending.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException exception) {
            pending.cancel(true);
            Thread.currentThread().interrupt();
            throw new RouteServiceUnavailableException(exception);
        } catch (TimeoutException | ExecutionException exception) {
            pending.cancel(true);
            throw new RouteServiceUnavailableException(exception);
        }
    }

    private ObjectNode point(GeoPoint point) {
        return mapper.createObjectNode().put("lat", point.latitude()).put("lon", point.longitude());
    }

    private JsonNode readBody(HttpResponse<String> response) {
        if (response.body().length() > 1_048_576) {
            throw new IllegalArgumentException("Route response exceeds supported size");
        }
        JsonNode json = mapper.readTree(response.body());
        if (json == null || !json.isObject()) {
            throw new IllegalArgumentException("Invalid route response");
        }
        return json;
    }

    private static void requireMediaType(HttpResponse<String> response, String expected) {
        String type = response.headers().firstValue("Content-Type").orElse("").split(";", 2)[0].trim();
        if (!expected.equalsIgnoreCase(type)) {
            throw new IllegalArgumentException("Unexpected route response media type");
        }
    }

    private static String text(JsonNode value) {
        if (!value.isString() || value.asString().isBlank()) {
            throw new IllegalArgumentException("Missing route text");
        }
        return value.asString();
    }

    private static double number(JsonNode value) {
        if (!value.isNumber() || !Double.isFinite(value.asDouble())) {
            throw new IllegalArgumentException("Missing finite route number");
        }
        return value.asDouble();
    }

    private static Instant instant(JsonNode value) {
        return OffsetDateTime.parse(text(value)).toInstant();
    }
}
