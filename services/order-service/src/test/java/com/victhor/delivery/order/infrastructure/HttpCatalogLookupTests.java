package com.victhor.delivery.order.infrastructure;

import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.ObjectMapper;

import com.victhor.delivery.order.application.CatalogSelectionConflictException;
import com.victhor.delivery.order.application.RemoteServiceUnavailableException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class HttpCatalogLookupTests {

    private static final UUID RESTAURANT_ID = UUID.fromString("39d29e97-e413-4cd2-bec4-c7686802e71a");
    private static final UUID ITEM_ID = UUID.fromString("d16a0e63-30f6-4fdd-835e-9c1dc8afeb35");
    private static final UUID OTHER_ID = UUID.fromString("93105132-1d8d-4331-87b0-85c4f862a0b0");

    private final AtomicReference<String> responseBody = new AtomicReference<>();
    private final AtomicInteger responseStatus = new AtomicInteger(200);
    private final AtomicReference<String> requestPath = new AtomicReference<>();
    private final AtomicReference<String> requestAccept = new AtomicReference<>();
    private final AtomicInteger delayMs = new AtomicInteger();
    private HttpServer server;
    private HttpClient client;
    private HttpCatalogLookup adapter;
    private String url;

    @BeforeEach
    void startCatalogServer() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            requestPath.set(exchange.getRequestURI().getPath());
            requestAccept.set(exchange.getRequestHeaders().getFirst("Accept"));
            try {
                if (delayMs.get() > 0) {
                    Thread.sleep(delayMs.get());
                }
                byte[] body = responseBody.get().getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(responseStatus.get(), body.length);
                exchange.getResponseBody().write(body);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
            } finally {
                exchange.close();
            }
        });
        server.start();
        client = HttpClient.newHttpClient();
        url = "http://127.0.0.1:" + server.getAddress().getPort();
        adapter = new HttpCatalogLookup(client, new ObjectMapper(), url + "/", Duration.ofSeconds(2));
    }

    @AfterEach
    void stopCatalogServer() {
        server.stop(0);
        client.close();
    }

    @ParameterizedTest
    @ValueSource(booleans = { true, false })
    void readsRestaurantAvailabilityWithoutRequiringPickupLocation(boolean active) {
        responseBody.set("{\"id\":\"" + RESTAURANT_ID + "\",\"active\":" + active + "}");

        assertThat(adapter.isRestaurantActive(RESTAURANT_ID)).isEqualTo(active);
        assertThat(requestPath).hasValue("/api/catalog/restaurants/" + RESTAURANT_ID);
        assertThat(requestAccept).hasValue("application/json");
    }

    @ParameterizedTest
    @MethodSource("invalidRestaurants")
    void rejectsMalformedOrUnrelatedRestaurantResponses(String body) {
        responseBody.set(body);

        assertThatThrownBy(() -> adapter.isRestaurantActive(RESTAURANT_ID))
                .isInstanceOf(RemoteServiceUnavailableException.class);
    }

    static Stream<String> invalidRestaurants() {
        return Stream.of("", "not-json", "null", "[]", "true", "{}",
                "{\"id\":\"" + OTHER_ID + "\",\"active\":true}",
                "{\"id\":\"bad-id\",\"active\":true}",
                "{\"id\":123,\"active\":true}",
                "{\"id\":\"" + RESTAURANT_ID + "\",\"active\":\"true\"}",
                "{\"id\":\"" + RESTAURANT_ID + "\",\"active\":1}",
                "{\"id\":\"" + RESTAURANT_ID + "\",\"active\":null}",
                "{\"id\":\"" + RESTAURANT_ID + "\"}",
                "{\"id\":\"" + RESTAURANT_ID + "\",\"active\":true} {}");
    }

    @ParameterizedTest
    @ValueSource(booleans = { true, false })
    void readsMenuItemSnapshotAndPreservesAvailability(boolean available) {
        responseBody.set(menuItem("\"  Pizza Margherita  \"", "19.99", "\"BRL\"", String.valueOf(available)));

        var item = adapter.findMenuItem(RESTAURANT_ID, ITEM_ID);

        assertThat(item.name()).isEqualTo("Pizza Margherita");
        assertThat(item.price()).isEqualTo(new BigDecimal("19.99"));
        assertThat(item.available()).isEqualTo(available);
        assertThat(requestPath).hasValue("/api/catalog/restaurants/" + RESTAURANT_ID + "/menu-items/" + ITEM_ID);
        assertThat(requestAccept).hasValue("application/json");
    }

    @ParameterizedTest
    @ValueSource(strings = { "0.01", "99999999.99", "19.9900000000000000000000000000", "1e-2" })
    void readsExactDecimalPricesAndNormalizesCents(String price) {
        responseBody.set(menuItem("\"Pizza\"", price, "\"BRL\"", "true"));

        assertThat(adapter.findMenuItem(RESTAURANT_ID, ITEM_ID).price())
                .isEqualTo(new BigDecimal(price).setScale(2));
    }

    @ParameterizedTest
    @MethodSource("invalidMenuItems")
    void rejectsInvalidMenuItemDataWithoutCoercingTypesOrRoundingPrices(String body) {
        responseBody.set(body);

        assertThatThrownBy(() -> adapter.findMenuItem(RESTAURANT_ID, ITEM_ID))
                .isInstanceOf(RemoteServiceUnavailableException.class);
    }

    static Stream<String> invalidMenuItems() {
        String valid = menuItem("\"Pizza\"", "19.99", "\"BRL\"", "true");
        return Stream.of("", "null", "[]", "{}", "not-json", valid + " {}",
                valid.replace(ITEM_ID.toString(), OTHER_ID.toString()),
                valid.replace(RESTAURANT_ID.toString(), OTHER_ID.toString()),
                valid.replace("\"id\":\"" + ITEM_ID + "\"", "\"id\":null"),
                valid.replace("\"restaurantId\":\"" + RESTAURANT_ID + "\"", "\"restaurantId\":123"),
                menuItem("null", "19.99", "\"BRL\"", "true"),
                menuItem("123", "19.99", "\"BRL\"", "true"),
                menuItem("\"  \"", "19.99", "\"BRL\"", "true"),
                menuItem("\"" + "a".repeat(121) + "\"", "19.99", "\"BRL\"", "true"),
                menuItem("\"Pizza\"", "null", "\"BRL\"", "true"),
                menuItem("\"Pizza\"", "\"19.99\"", "\"BRL\"", "true"),
                menuItem("\"Pizza\"", "true", "\"BRL\"", "true"),
                menuItem("\"Pizza\"", "0", "\"BRL\"", "true"),
                menuItem("\"Pizza\"", "-1", "\"BRL\"", "true"),
                menuItem("\"Pizza\"", "100000000", "\"BRL\"", "true"),
                menuItem("\"Pizza\"", "1.001", "\"BRL\"", "true"),
                menuItem("\"Pizza\"", "19.9900000000000000000000000001", "\"BRL\"", "true"),
                menuItem("\"Pizza\"", "99999999.9900000000000000000001", "\"BRL\"", "true"),
                menuItem("\"Pizza\"", "19.99", "\"USD\"", "true"),
                menuItem("\"Pizza\"", "19.99", "\"brl\"", "true"),
                menuItem("\"Pizza\"", "19.99", "123", "true"),
                menuItem("\"Pizza\"", "19.99", "null", "true"),
                menuItem("\"Pizza\"", "19.99", "\"BRL\"", "\"true\""),
                menuItem("\"Pizza\"", "19.99", "\"BRL\"", "1"),
                menuItem("\"Pizza\"", "19.99", "\"BRL\"", "null"));
    }

    @ParameterizedTest
    @ValueSource(booleans = { true, false })
    void mapsMissingCatalogResourcesToSelectionConflicts(boolean menuItem) {
        responseStatus.set(404);
        responseBody.set("{}");

        assertThatThrownBy(() -> lookup(menuItem)).isInstanceOf(CatalogSelectionConflictException.class);
    }

    @ParameterizedTest
    @MethodSource("unexpectedStatuses")
    void mapsOtherStatusesToServiceUnavailability(int status, boolean menuItem) {
        responseStatus.set(status);
        responseBody.set("{}");

        assertThatThrownBy(() -> lookup(menuItem)).isInstanceOf(RemoteServiceUnavailableException.class);
    }

    static Stream<Arguments> unexpectedStatuses() {
        return Stream.of(201, 301, 400, 409, 429, 500, 503)
                .flatMap(status -> Stream.of(Arguments.of(status, false), Arguments.of(status, true)));
    }

    @Test
    void boundsCatalogCallsWithTheConfiguredTimeout() {
        responseBody.set("{}");
        delayMs.set(250);
        adapter = new HttpCatalogLookup(client, new ObjectMapper(), url, Duration.ofMillis(50));

        assertThatThrownBy(() -> adapter.isRestaurantActive(RESTAURANT_ID))
                .isInstanceOf(RemoteServiceUnavailableException.class).hasCauseInstanceOf(HttpTimeoutException.class);
    }

    @Test
    void mapsConnectionFailuresToServiceUnavailability() {
        server.stop(0);

        assertThatThrownBy(() -> adapter.isRestaurantActive(RESTAURANT_ID))
                .isInstanceOf(RemoteServiceUnavailableException.class).hasCauseInstanceOf(java.io.IOException.class);
    }

    @Test
    void preservesTheInterruptFlagAfterAnInterruptedRequest() {
        responseBody.set("{}");
        Thread.currentThread().interrupt();
        try {
            assertThatThrownBy(() -> adapter.isRestaurantActive(RESTAURANT_ID))
                    .isInstanceOf(RemoteServiceUnavailableException.class).hasCauseInstanceOf(InterruptedException.class);
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
        } finally {
            Thread.interrupted();
        }
    }

    private void lookup(boolean menuItem) {
        if (menuItem) {
            adapter.findMenuItem(RESTAURANT_ID, ITEM_ID);
        } else {
            adapter.isRestaurantActive(RESTAURANT_ID);
        }
    }

    private static String menuItem(String name, String price, String currency, String available) {
        return "{\"id\":\"" + ITEM_ID + "\",\"restaurantId\":\"" + RESTAURANT_ID
                + "\",\"name\":" + name + ",\"price\":" + price + ",\"currency\":" + currency
                + ",\"available\":" + available + "}";
    }
}
