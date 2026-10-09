package com.victhor.delivery.order.infrastructure;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.UUID;

import com.victhor.delivery.order.application.DeliveryIntegrationConflictException;
import com.victhor.delivery.order.application.RemoteServiceUnavailableException;
import com.victhor.delivery.order.application.RestaurantLookup;
import com.victhor.delivery.order.domain.DeliveryDestination;
import com.victhor.delivery.order.observability.RequestIds;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Reads the restaurant pickup location that becomes the origin of a delivery. */
public class HttpRestaurantLookup implements RestaurantLookup {

    private final HttpClient client;
    private final ObjectMapper mapper;
    private final String catalogUrl;
    private final Duration timeout;

    public HttpRestaurantLookup(HttpClient client, ObjectMapper mapper, String catalogUrl, Duration timeout) {
        this.client = client;
        this.mapper = mapper;
        this.catalogUrl = catalogUrl.replaceAll("/+$", "");
        this.timeout = timeout;
    }

    @Override
    public RestaurantPickup findById(UUID restaurantId) {
        var response = get(catalogUrl + "/api/catalog/restaurants/" + restaurantId);
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

    private HttpResponse<String> get(String url) {
        try {
            var request = RequestIds.propagate(HttpRequest.newBuilder(URI.create(url))).timeout(timeout)
                    .header("Accept", "application/json").GET().build();
            return client.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new RemoteServiceUnavailableException(exception);
        } catch (IOException | IllegalArgumentException exception) {
            throw new RemoteServiceUnavailableException(exception);
        }
    }
}
