package com.victhor.delivery.order.infrastructure;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Set;
import java.util.UUID;

import com.victhor.delivery.order.application.DeliveryGateway;
import com.victhor.delivery.order.application.DeliveryIntegrationConflictException;
import com.victhor.delivery.order.application.RemoteServiceUnavailableException;
import com.victhor.delivery.order.application.RestaurantLookup;
import com.victhor.delivery.order.domain.DeliveryDestination;
import com.victhor.delivery.order.domain.DeliveryRequest;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

public class HttpDeliveryIntegration implements RestaurantLookup, DeliveryGateway {

    private static final Set<String> DELIVERY_STATUSES = Set.of("CREATED", "ASSIGNED", "PICKED_UP", "IN_TRANSIT",
            "DELIVERED", "CANCELLED");

    private final HttpClient client;
    private final ObjectMapper mapper;
    private final String catalogUrl;
    private final String deliveryUrl;
    private final Duration timeout;

    public HttpDeliveryIntegration(HttpClient client, ObjectMapper mapper, String catalogUrl,
            String deliveryUrl, Duration timeout) {
        this.client = client;
        this.mapper = mapper;
        this.catalogUrl = catalogUrl.replaceAll("/+$", "");
        this.deliveryUrl = deliveryUrl.replaceAll("/+$", "");
        this.timeout = timeout;
    }

    @Override
    public RestaurantPickup findById(UUID restaurantId) {
        var response = send("GET", catalogUrl + "/api/catalog/restaurants/" + restaurantId, null);
        if (response.statusCode() == 404) {
            throw new DeliveryIntegrationConflictException();
        }
        if (response.statusCode() != 200) {
            throw new RemoteServiceUnavailableException();
        }
        try {
            JsonNode json = mapper.readTree(response.body());
            if (!restaurantId.equals(UUID.fromString(json.path("id").asString())) || !json.path("active").isBoolean()) {
                throw new IllegalArgumentException("Invalid restaurant response");
            }
            JsonNode pickup = json.path("pickupLocation");
            if (pickup.isMissingNode()) {
                throw new IllegalArgumentException("Missing pickup location field");
            }
            DeliveryDestination location = pickup.isNull() ? null : new DeliveryDestination(
                    requiredText(json.path("name")), coordinate(pickup.path("latitude")), coordinate(pickup.path("longitude")));
            return new RestaurantPickup(json.path("active").asBoolean(), location);
        } catch (RuntimeException exception) {
            throw new RemoteServiceUnavailableException(exception);
        }
    }

    @Override
    public DeliveryReceipt createForOrder(DeliveryRequest request) {
        ObjectNode body = mapper.createObjectNode();
        body.set("origin", location(request.origin()));
        body.set("destination", location(request.destination()));
        var response = send("PUT", deliveryUrl + "/api/deliveries/by-order/" + request.orderId(), body.toString());
        if (response.statusCode() == 409) {
            throw new DeliveryIntegrationConflictException();
        }
        if (response.statusCode() != 200 && response.statusCode() != 201) {
            throw new RemoteServiceUnavailableException();
        }
        try {
            JsonNode json = mapper.readTree(response.body());
            UUID orderId = UUID.fromString(json.path("orderId").asString());
            UUID deliveryId = UUID.fromString(json.path("id").asString());
            String status = requiredText(json.path("status"));
            if (!orderId.equals(request.orderId()) || !DELIVERY_STATUSES.contains(status)
                    || !request.origin().equals(readLocation(json.path("origin")))
                    || !request.destination().equals(readLocation(json.path("destination")))) {
                throw new IllegalArgumentException("Delivery response does not match request");
            }
            return new DeliveryReceipt(orderId, deliveryId, status);
        } catch (RuntimeException exception) {
            throw new RemoteServiceUnavailableException(exception);
        }
    }

    private ObjectNode location(DeliveryDestination location) {
        return mapper.createObjectNode().put("description", location.address())
                .put("latitude", location.latitude()).put("longitude", location.longitude());
    }

    private DeliveryDestination readLocation(JsonNode json) {
        return new DeliveryDestination(requiredText(json.path("description")),
                coordinate(json.path("latitude")), coordinate(json.path("longitude")));
    }

    private String requiredText(JsonNode node) {
        if (!node.isString() || node.asString().isBlank()) {
            throw new IllegalArgumentException("Missing textual field");
        }
        return node.asString();
    }

    private double coordinate(JsonNode node) {
        if (!node.isNumber()) {
            throw new IllegalArgumentException("Missing numeric coordinate");
        }
        return node.asDouble();
    }

    private HttpResponse<String> send(String method, String url, String body) {
        try {
            var request = HttpRequest.newBuilder(URI.create(url)).timeout(timeout).header("Accept", "application/json");
            if (body != null) {
                request.header("Content-Type", "application/json");
            }
            return client.send(request.method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                    : HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new RemoteServiceUnavailableException(exception);
        } catch (IOException | IllegalArgumentException exception) {
            throw new RemoteServiceUnavailableException(exception);
        }
    }
}
