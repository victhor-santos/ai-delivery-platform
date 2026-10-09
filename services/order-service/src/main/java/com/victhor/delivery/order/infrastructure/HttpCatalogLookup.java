package com.victhor.delivery.order.infrastructure;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.UUID;

import com.victhor.delivery.order.application.CatalogLookup;
import com.victhor.delivery.order.application.CatalogSelectionConflictException;
import com.victhor.delivery.order.application.RemoteServiceUnavailableException;
import com.victhor.delivery.order.domain.OrderItem;
import com.victhor.delivery.order.observability.RequestIds;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.ObjectReader;
import tools.jackson.databind.cfg.JsonNodeFeature;

public class HttpCatalogLookup implements CatalogLookup {

    private final HttpClient client;
    private final ObjectReader reader;
    private final String catalogUrl;
    private final Duration timeout;

    public HttpCatalogLookup(HttpClient client, ObjectMapper mapper, String catalogUrl, Duration timeout) {
        this.client = client;
        this.reader = mapper.reader().with(JsonNodeFeature.USE_BIG_DECIMAL_FOR_FLOATS)
                .with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
        this.catalogUrl = catalogUrl.replaceAll("/+$", "");
        this.timeout = timeout;
    }

    @Override
    public boolean isRestaurantActive(UUID restaurantId) {
        JsonNode restaurant = get("/api/catalog/restaurants/" + restaurantId);
        try {
            requireId(restaurant.path("id"), restaurantId);
            return requiredBoolean(restaurant.path("active"));
        } catch (RuntimeException exception) {
            throw new RemoteServiceUnavailableException(exception);
        }
    }

    @Override
    public CatalogMenuItem findMenuItem(UUID restaurantId, UUID menuItemId) {
        JsonNode menuItem = get("/api/catalog/restaurants/" + restaurantId + "/menu-items/" + menuItemId);
        try {
            requireId(menuItem.path("id"), menuItemId);
            requireId(menuItem.path("restaurantId"), restaurantId);
            if (!"BRL".equals(requiredText(menuItem.path("currency"))) || !menuItem.path("price").isNumber()) {
                throw new IllegalArgumentException("Invalid menu item price or currency");
            }
            var snapshot = new OrderItem(menuItemId, requiredText(menuItem.path("name")), 1,
                    menuItem.path("price").decimalValue());
            return new CatalogMenuItem(snapshot.name(), snapshot.unitPrice(),
                    requiredBoolean(menuItem.path("available")));
        } catch (RuntimeException exception) {
            throw new RemoteServiceUnavailableException(exception);
        }
    }

    private JsonNode get(String path) {
        HttpResponse<String> response;
        try {
            var request = RequestIds.propagate(HttpRequest.newBuilder(URI.create(catalogUrl + path)))
                    .timeout(timeout).header("Accept", "application/json").GET().build();
            response = client.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new RemoteServiceUnavailableException(exception);
        } catch (IOException | IllegalArgumentException exception) {
            throw new RemoteServiceUnavailableException(exception);
        }
        if (response.statusCode() == 404) {
            throw new CatalogSelectionConflictException();
        }
        if (response.statusCode() != 200) {
            throw new RemoteServiceUnavailableException();
        }
        try {
            JsonNode result = reader.readTree(response.body());
            if (result == null || !result.isObject()) {
                throw new IllegalArgumentException("Expected a catalog response object");
            }
            return result;
        } catch (RuntimeException exception) {
            throw new RemoteServiceUnavailableException(exception);
        }
    }

    private void requireId(JsonNode node, UUID expected) {
        if (!expected.equals(UUID.fromString(requiredText(node)))) {
            throw new IllegalArgumentException("Catalog response does not match the requested resource");
        }
    }

    private String requiredText(JsonNode node) {
        if (!node.isString() || node.asString().isBlank()) {
            throw new IllegalArgumentException("Missing textual catalog field");
        }
        return node.asString();
    }

    private boolean requiredBoolean(JsonNode node) {
        if (!node.isBoolean()) {
            throw new IllegalArgumentException("Missing boolean catalog field");
        }
        return node.asBoolean();
    }
}
