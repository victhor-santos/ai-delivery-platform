package com.victhor.delivery.delivery;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import com.victhor.delivery.delivery.application.CourierRepository;
import com.victhor.delivery.delivery.domain.Courier;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "DELIVERY_DB_PASSWORD=testcontainers-only")
@Testcontainers
class DeliveryServiceApplicationTests {

    private static final String DELIVERIES = "/api/deliveries";
    private static final String COURIERS = DELIVERIES + "/couriers";
    private static final UUID ORDER_ID = UUID.randomUUID();
    private static final String VALID_REQUEST = """
            {"orderId":"%s","origin":{"description":"  Restaurante Central  ","latitude":-23.55,"longitude":-46.63},
             "destination":{"description":"Rua Central, 42","latitude":-23.56,"longitude":-46.64}}
            """.formatted(ORDER_ID);

    @Container
    @ServiceConnection
    static final PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17-alpine");

    @LocalServerPort
    private int port;

    @Autowired
    private ObjectMapper mapper;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private CourierRepository couriers;

    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

    @BeforeEach
    void clearTestDatabase() {
        jdbc.update("DELETE FROM deliveries");
        jdbc.update("DELETE FROM couriers");
    }

    @Test
    void servesPingAndActuatorWithPostgresqlConfigured() throws Exception {
        JsonNode ping = get(DELIVERIES + "/ping");
        assertThat(ping.path("service").asText()).isEqualTo("delivery-service");
        assertThat(ping.path("status").asText()).isEqualTo("ok");
        assertThat(get("/actuator/health").path("status").asText()).isEqualTo("UP");
        assertThat(get("/actuator/info").isObject()).isTrue();
    }

    @Test
    void createsPersistsAndQueriesDeliveryWithServerOwnedIdentityAndState() throws Exception {
        var request = mapper.readTree(VALID_REQUEST).deepCopy();
        ((tools.jackson.databind.node.ObjectNode) request).put("id", UUID.randomUUID().toString())
                .put("status", "DELIVERED").put("createdAt", "2000-01-01T00:00:00Z");
        var response = send("POST", DELIVERIES, request.toString());
        assertThat(response.statusCode()).isEqualTo(201);
        JsonNode delivery = json(response);
        UUID id = UUID.fromString(delivery.path("id").asText());
        assertThat(id.toString()).isNotEqualTo(request.path("id").asText());
        assertThat(response.headers().firstValue("Location")).contains(DELIVERIES + "/" + id);
        assertThat(delivery.path("orderId").asText()).isEqualTo(ORDER_ID.toString());
        assertThat(delivery.path("origin").path("description").asText()).isEqualTo("Restaurante Central");
        assertThat(delivery.path("status").asText()).isEqualTo("CREATED");
        assertThat(delivery.path("courierId").isNull()).isTrue();
        assertThat(delivery.path("updatedAt")).isEqualTo(delivery.path("createdAt"));
        assertThat(Instant.parse(delivery.path("createdAt").asText()).getNano() % 1000).isZero();
        assertThat(get(DELIVERIES + "/" + id)).isEqualTo(delivery);
        assertThat(get(DELIVERIES + "/by-order/" + ORDER_ID)).isEqualTo(delivery);
        assertThat(jdbc.queryForObject("SELECT origin_description FROM deliveries WHERE id = ?", String.class, id))
                .isEqualTo("Restaurante Central");
        assertThat(delivery.has("version")).isFalse();
    }

    @Test
    void completesLifecycleWithControlledConflictsAndPreservedHistory() throws Exception {
        JsonNode courier = createCourier();
        String path = DELIVERIES + "/" + createDelivery(ORDER_ID).path("id").asText();
        JsonNode assigned = assign(path, courier.path("id").asText());
        assertThat(assigned.path("status").asText()).isEqualTo("ASSIGNED");
        assertProblem(send("POST", path + "/assign", assignment(courier.path("id").asText())), 409);
        assertThat(get(path)).isEqualTo(assigned);
        assertThat(command(path, "pick-up").path("status").asText()).isEqualTo("PICKED_UP");
        assertProblem(send("POST", path + "/cancel", null), 409);
        JsonNode inTransit = command(path, "start-transit");
        assertThat(inTransit.path("status").asText()).isEqualTo("IN_TRANSIT");
        assertProblem(send("POST", path + "/complete", null), 409);
        assertThat(get(path)).isEqualTo(inTransit);
        JsonNode arrived = command(path, "arrive");
        assertThat(arrived.path("status").asText()).isEqualTo("IN_TRANSIT");
        assertProblem(send("POST", path + "/arrive", null), 409);
        JsonNode completed = command(path, "complete");
        assertThat(completed.path("status").asText()).isEqualTo("DELIVERED");
        assertThat(completed.path("courierId")).isEqualTo(courier.path("id"));
        Instant previous = Instant.parse(completed.path("createdAt").asText());
        for (String event : new String[]{"assignedAt", "pickedUpAt", "departedAt", "arrivedAt", "deliveredAt"}) {
            Instant time = Instant.parse(completed.path(event).asText());
            assertThat(time).isAfterOrEqualTo(previous);
            previous = time;
        }
        assertThat(completed.path("updatedAt")).isEqualTo(completed.path("deliveredAt"));
        assertThat(get(path)).isEqualTo(completed);
        assertProblem(send("POST", path + "/complete", null), 409);
        String next = DELIVERIES + "/" + createDelivery(UUID.randomUUID()).path("id").asText();
        assertThat(assign(next, courier.path("id").asText()).path("status").asText()).isEqualTo("ASSIGNED");
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void cancelsBeforePickupPreservingHistoryAndReleasingCourier(boolean assigned) throws Exception {
        String courierId = createCourier().path("id").asText();
        String path = DELIVERIES + "/" + createDelivery(ORDER_ID).path("id").asText();
        if (assigned) {
            assign(path, courierId);
        }
        JsonNode cancelled = command(path, "cancel");
        assertThat(cancelled.path("status").asText()).isEqualTo("CANCELLED");
        assertThat(cancelled.path("courierId").isNull()).isEqualTo(!assigned);
        assertThat(cancelled.path("assignedAt").isNull()).isEqualTo(!assigned);
        assertThat(get(path)).isEqualTo(cancelled);
        assertProblem(send("POST", path + "/cancel", null), 409);
        assertProblem(send("POST", DELIVERIES, VALID_REQUEST), 409);
        String next = DELIVERIES + "/" + createDelivery(UUID.randomUUID()).path("id").asText();
        assertThat(assign(next, courierId).path("status").asText()).isEqualTo("ASSIGNED");
    }

    @Test
    void rejectsMissingInactiveAndBusyCouriersWithoutChangingDelivery() throws Exception {
        String path = DELIVERIES + "/" + createDelivery(ORDER_ID).path("id").asText();
        JsonNode original = get(path);
        assertProblem(send("POST", path + "/assign", assignment(UUID.randomUUID().toString())), 404);
        Courier inactive = couriers.create(new Courier(UUID.randomUUID(), false));
        assertProblem(send("POST", path + "/assign", assignment(inactive.id().toString())), 409);
        assertThat(get(path)).isEqualTo(original);
        String courierId = createCourier().path("id").asText();
        assign(path, courierId);
        String otherPath = DELIVERIES + "/" + createDelivery(UUID.randomUUID()).path("id").asText();
        JsonNode other = get(otherPath);
        assertProblem(send("POST", otherPath + "/assign", assignment(courierId)), 409);
        assertThat(get(otherPath)).isEqualTo(other);
        assertProblem(send("POST", DELIVERIES, VALID_REQUEST), 409);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM deliveries", Integer.class)).isEqualTo(2);
    }

    @ParameterizedTest
    @MethodSource("invalidDeliveries")
    void rejectsInvalidDeliveryInputWithoutWritingData(String body) throws Exception {
        assertProblem(send("POST", DELIVERIES, body), 400);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM deliveries", Integer.class)).isZero();
    }

    static Stream<String> invalidDeliveries() {
        return Stream.of("null", "{}", "{", "[]",
                VALID_REQUEST.replace(ORDER_ID.toString(), "invalid-uuid"),
                VALID_REQUEST.replace("\"orderId\":\"" + ORDER_ID + "\"", "\"orderId\":null"),
                VALID_REQUEST.replace("  Restaurante Central  ", " "),
                VALID_REQUEST.replace("  Restaurante Central  ", "x".repeat(256)),
                VALID_REQUEST.replace("  Restaurante Central  ", ""),
                VALID_REQUEST.replace("-23.55", "91"), VALID_REQUEST.replace("-46.63", "-181"),
                VALID_REQUEST.replace("-23.56", "-91"), VALID_REQUEST.replace("-46.64", "181"),
                VALID_REQUEST.replace("-23.55", "null"), VALID_REQUEST.replace("-46.64", "null"),
                VALID_REQUEST.replace("-23.55", "\"-23.55\""),
                VALID_REQUEST.replace("-23.55", "true"), VALID_REQUEST.replace("-23.55", "1e999"),
                VALID_REQUEST.replace("\"description\":\"  Restaurante Central  \"", "\"description\":42"),
                "{\"orderId\":\"" + ORDER_ID + "\",\"origin\":null,\"destination\":{}}");
    }

    @ParameterizedTest
    @ValueSource(strings = {"{}", "null", "{\"courierId\":null}", "{\"courierId\":\"invalid\"}"})
    void rejectsInvalidAssignmentInput(String body) throws Exception {
        String path = DELIVERIES + "/" + createDelivery(ORDER_ID).path("id").asText();
        assertProblem(send("POST", path + "/assign", body), 400);
        assertThat(get(path).path("status").asText()).isEqualTo("CREATED");
    }

    @Test
    void reportsMissingResourcesAndMalformedIdentifiers() throws Exception {
        String missing = UUID.randomUUID().toString();
        assertProblem(send("GET", DELIVERIES + "/" + missing, null), 404);
        assertProblem(send("GET", DELIVERIES + "/by-order/" + missing, null), 404);
        assertProblem(send("GET", COURIERS + "/" + missing, null), 404);
        assertProblem(send("POST", DELIVERIES + "/" + missing + "/assign", assignment(missing)), 404);
        for (String action : new String[]{"pick-up", "start-transit", "arrive", "complete", "cancel"}) {
            assertProblem(send("POST", DELIVERIES + "/" + missing + "/" + action, null), 404);
        }
        assertProblem(send("GET", DELIVERIES + "/invalid-uuid", null), 400);
        assertProblem(send("GET", DELIVERIES + "/by-order/invalid-uuid", null), 400);
        assertProblem(send("GET", COURIERS + "/invalid-uuid", null), 400);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void concurrentHttpRequestsCannotDuplicateOrdersOrReserveTheSameCourier(boolean assigning) throws Exception {
        String firstPath = DELIVERIES;
        String secondPath = DELIVERIES;
        String body = VALID_REQUEST;
        if (assigning) {
            String courierId = createCourier().path("id").asText();
            firstPath += "/" + createDelivery(ORDER_ID).path("id").asText() + "/assign";
            secondPath += "/" + createDelivery(UUID.randomUUID()).path("id").asText() + "/assign";
            body = assignment(courierId);
        }

        var first = client.sendAsync(request("POST", firstPath, body), HttpResponse.BodyHandlers.ofString());
        var second = client.sendAsync(request("POST", secondPath, body), HttpResponse.BodyHandlers.ofString());
        var firstResponse = first.get(15, TimeUnit.SECONDS);
        var secondResponse = second.get(15, TimeUnit.SECONDS);
        assertThat(new int[]{firstResponse.statusCode(), secondResponse.statusCode()})
                .containsExactlyInAnyOrder(assigning ? 200 : 201, 409);
        assertProblem(firstResponse.statusCode() == 409 ? firstResponse : secondResponse, 409);
        String countSql = assigning ? "SELECT count(*) FROM deliveries WHERE status = 'ASSIGNED'"
                : "SELECT count(*) FROM deliveries";
        assertThat(jdbc.queryForObject(countSql, Integer.class)).isEqualTo(1);
    }

    private JsonNode createCourier() throws Exception {
        var response = send("POST", COURIERS, null);
        assertThat(response.statusCode()).isEqualTo(201);
        JsonNode courier = json(response);
        UUID id = UUID.fromString(courier.path("id").asText());
        assertThat(response.headers().firstValue("Location")).contains(COURIERS + "/" + id);
        assertThat(courier.path("active").asBoolean()).isTrue();
        assertThat(courier.size()).isEqualTo(2);
        assertThat(get(COURIERS + "/" + id)).isEqualTo(courier);
        return courier;
    }

    private JsonNode createDelivery(UUID orderId) throws Exception {
        var response = send("POST", DELIVERIES, VALID_REQUEST.replace(ORDER_ID.toString(), orderId.toString()));
        assertThat(response.statusCode()).isEqualTo(201);
        return json(response);
    }

    private JsonNode assign(String path, String courierId) throws Exception {
        var response = send("POST", path + "/assign", assignment(courierId));
        assertThat(response.statusCode()).isEqualTo(200);
        return json(response);
    }

    private String assignment(String courierId) {
        return "{\"courierId\":\"" + courierId + "\"}";
    }

    private JsonNode command(String path, String action) throws Exception {
        var response = send("POST", path + "/" + action, null);
        assertThat(response.statusCode()).isEqualTo(200);
        return json(response);
    }

    private JsonNode get(String path) throws Exception {
        var response = send("GET", path, null);
        assertThat(response.statusCode()).as("GET %s", path).isEqualTo(200);
        return json(response);
    }

    private HttpResponse<String> send(String method, String path, String body) throws Exception {
        return client.send(request(method, path, body), HttpResponse.BodyHandlers.ofString());
    }

    private HttpRequest request(String method, String path, String body) {
        var request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .timeout(Duration.ofSeconds(10));
        if (body != null) {
            request.header("Content-Type", "application/json");
        }
        return request.method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(body)).build();
    }

    private JsonNode json(HttpResponse<String> response) {
        return mapper.readTree(response.body());
    }

    private void assertProblem(HttpResponse<String> response, int status) {
        assertThat(response.statusCode()).as("Response: %s", response.body()).isEqualTo(status);
        assertThat(response.headers().firstValue("Content-Type").orElseThrow()).startsWith("application/problem+json");
        assertThat(json(response).path("status").asInt()).isEqualTo(status);
        assertThat(response.body()).doesNotContain("org.hibernate", "SQLException", "constraint", "stackTrace");
    }
}
