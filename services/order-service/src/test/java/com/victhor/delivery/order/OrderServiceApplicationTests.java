package com.victhor.delivery.order;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.stream.Stream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "ORDER_DB_PASSWORD=testcontainers-only")
@Testcontainers
class OrderServiceApplicationTests {

    private static final UUID RESTAURANT_ID = UUID.randomUUID();
    private static final String VALID_REQUEST = """
            {"restaurantId":"%s","destination":{
              "address":"  Rua das Flores, 42  ","latitude":-23.55,"longitude":-46.63}}
            """.formatted(RESTAURANT_ID);

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
    private Environment environment;

    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

    @BeforeEach
    void clearOrders() {
        jdbc.update("DELETE FROM orders");
    }

    @Test
    void loadsContextWithFlywayAndHibernateValidation() {
        assertThat(environment.getProperty("spring.jpa.hibernate.ddl-auto")).isEqualTo("validate");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM flyway_schema_history WHERE success", Integer.class))
                .isEqualTo(1);
    }

    @Test
    void createsPersistsAndRetrievesOrder() throws Exception {
        var response = send("POST", "/api/orders", VALID_REQUEST);
        assertThat(response.statusCode()).isEqualTo(201);
        var order = json(response);
        var id = UUID.fromString(order.path("id").asText());
        String location = "/api/orders/" + id;
        assertThat(response.headers().firstValue("Location")).contains(location);
        assertThat(order.path("restaurantId").asText()).isEqualTo(RESTAURANT_ID.toString());
        assertThat(order.path("destination").path("address").asText()).isEqualTo("Rua das Flores, 42");
        assertThat(order.path("destination").path("latitude").asDouble()).isEqualTo(-23.55);
        assertThat(order.path("status").asText()).isEqualTo("CREATED");
        assertThat(order.path("updatedAt")).isEqualTo(order.path("createdAt"));
        assertThat(Instant.parse(order.path("createdAt").asText())).isNotNull();
        assertThat(order.path("confirmedAt").isNull()).isTrue();
        assertThat(order.path("cancelledAt").isNull()).isTrue();
        var retrieved = send("GET", location, null);
        assertThat(retrieved.statusCode()).isEqualTo(200);
        assertThat(json(retrieved)).isEqualTo(order);
        assertThat(jdbc.queryForObject("SELECT restaurant_id FROM orders WHERE id = ?", UUID.class, id))
                .isEqualTo(RESTAURANT_ID);
        assertThat(jdbc.queryForObject("SELECT destination_address FROM orders WHERE id = ?", String.class, id))
                .isEqualTo("Rua das Flores, 42");
    }

    @Test
    void ignoresClientSuppliedIdentityStatusAndTimestamps() throws Exception {
        UUID suppliedId = UUID.randomUUID();
        String request = VALID_REQUEST.strip().replaceFirst("\\{", """
                {"id":"%s","status":"CONFIRMED","createdAt":"2000-01-01T00:00:00Z",
                """.formatted(suppliedId));
        var response = send("POST", "/api/orders", request);
        assertThat(response.statusCode()).isEqualTo(201);
        assertThat(json(response).path("id").asText()).isNotEqualTo(suppliedId.toString());
        assertThat(json(response).path("status").asText()).isEqualTo("CREATED");
        assertThat(json(response).path("createdAt").asText()).doesNotStartWith("2000-");
    }

    @Test
    void confirmsAndCancelsWithoutChangingTimestampsOnRepeatedCommands() throws Exception {
        String location = send("POST", "/api/orders", VALID_REQUEST).headers().firstValue("Location").orElseThrow();
        var confirmation = send("POST", location + "/confirm", null);
        assertThat(confirmation.statusCode()).isEqualTo(200);
        var confirmed = json(confirmation);
        assertThat(confirmed.path("status").asText()).isEqualTo("CONFIRMED");
        assertThat(confirmed.path("confirmedAt").isTextual()).isTrue();
        assertThat(json(send("POST", location + "/confirm", null))).isEqualTo(confirmed);
        assertThat(json(send("GET", location, null))).isEqualTo(confirmed);
        var cancellation = send("POST", location + "/cancel", null);
        assertThat(cancellation.statusCode()).isEqualTo(200);
        var cancelled = json(cancellation);
        assertThat(cancelled.path("status").asText()).isEqualTo("CANCELLED");
        assertThat(cancelled.path("confirmedAt")).isEqualTo(confirmed.path("confirmedAt"));
        assertThat(cancelled.path("cancelledAt").isTextual()).isTrue();
        assertThat(json(send("POST", location + "/cancel", null))).isEqualTo(cancelled);
        assertProblem(send("POST", location + "/confirm", null), 409);
        assertThat(json(send("GET", location, null))).isEqualTo(cancelled);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM orders", Integer.class)).isEqualTo(1);
    }

    @Test
    void cancelsBeforeConfirmation() throws Exception {
        String location = send("POST", "/api/orders", VALID_REQUEST).headers().firstValue("Location").orElseThrow();
        var response = send("POST", location + "/cancel", null);
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(json(response).path("status").asText()).isEqualTo("CANCELLED");
        assertThat(json(response).path("confirmedAt").isNull()).isTrue();
    }

    @ParameterizedTest
    @MethodSource("invalidRequests")
    void rejectsInvalidInputWithoutPersisting(String body) throws Exception {
        assertProblem(send("POST", "/api/orders", body), 400);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM orders", Integer.class)).isZero();
    }

    static Stream<String> invalidRequests() {
        return Stream.of("", "{", "null", "[]", "{}", "{\"restaurantId\":\"invalid\"}",
                VALID_REQUEST.replace(RESTAURANT_ID.toString(), ""),
                VALID_REQUEST.replace("\"  Rua das Flores, 42  \"", "\"   \""),
                VALID_REQUEST.replace("\"  Rua das Flores, 42  \"", "123"),
                VALID_REQUEST.replace("\"  Rua das Flores, 42  \"", "null"),
                VALID_REQUEST.replace("Rua das Flores, 42", "x".repeat(256)),
                VALID_REQUEST.replace("-23.55", "91"), VALID_REQUEST.replace("-46.63", "-181"),
                VALID_REQUEST.replace("-23.55", "\"-23.55\""), VALID_REQUEST.replace("-23.55", "true"),
                VALID_REQUEST.replace("-23.55", "null"), VALID_REQUEST.replace("-23.55", "1e309"),
                "{\"restaurantId\":\"" + RESTAURANT_ID + "\",\"destination\":null}",
                "{\"restaurantId\":\"" + RESTAURANT_ID + "\",\"destination\":{}}");
    }

    @Test
    void handlesMissingOrdersAndMalformedIdentifiers() throws Exception {
        for (String suffix : new String[] {"", "/confirm", "/cancel"}) {
            String method = suffix.isEmpty() ? "GET" : "POST";
            assertProblem(send(method, "/api/orders/" + UUID.randomUUID() + suffix, null), 404);
            assertProblem(send(method, "/api/orders/invalid" + suffix, null), 400);
        }
    }

    @Test
    void preservesPingAndActuator() throws Exception {
        assertThat(send("GET", "/api/orders/ping", null).statusCode()).isEqualTo(200);
        var health = send("GET", "/actuator/health", null);
        assertThat(health.statusCode()).isEqualTo(200);
        assertThat(json(health).path("status").asText()).isEqualTo("UP");
    }

    private HttpResponse<String> send(String method, String path, String body) throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .timeout(Duration.ofSeconds(10)).header("Content-Type", "application/json")
                .method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body))
                .build();
        return client.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private JsonNode json(HttpResponse<String> response) {
        return mapper.readTree(response.body());
    }

    private void assertProblem(HttpResponse<String> response, int status) {
        assertThat(response.statusCode()).isEqualTo(status);
        assertThat(response.headers().firstValue("Content-Type").orElseThrow()).contains("application/problem+json");
        assertThat(json(response).path("status").asInt()).isEqualTo(status);
        assertThat(json(response).path("detail").asText()).isNotBlank();
        assertThat(response.body()).doesNotContain("org.hibernate", "SQLException", "stackTrace", "com.victhor");
    }
}
