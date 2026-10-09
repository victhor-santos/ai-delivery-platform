package com.victhor.delivery.order;

import java.net.URI;
import java.math.BigDecimal;
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
import org.springframework.test.context.ActiveProfiles;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import com.victhor.delivery.order.application.CatalogLookup;
import com.victhor.delivery.order.application.CatalogSelectionConflictException;
import com.victhor.delivery.order.application.RemoteServiceUnavailableException;
import com.victhor.delivery.order.infrastructure.auth.TestAccessTokens;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ActiveProfiles("test")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "ORDER_DB_PASSWORD=testcontainers-only")
@Testcontainers
class OrderServiceApplicationTests {

    private static final UUID RESTAURANT_ID = UUID.randomUUID();
    private static final UUID MENU_ITEM_ID = UUID.randomUUID();
    private static final UUID CUSTOMER_ID = UUID.randomUUID();
    private static final String VALID_REQUEST = """
            {"restaurantId":"%s","items":[{"menuItemId":"%s","quantity":2}],"destination":{
              "address":"  Rua das Flores, 42  ","latitude":-23.55,"longitude":-46.63}}
            """.formatted(RESTAURANT_ID, MENU_ITEM_ID);

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

    @MockitoBean
    private CatalogLookup catalog;

    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

    @BeforeEach
    void clearOrders() {
        jdbc.update("DELETE FROM orders");
        when(catalog.isRestaurantActive(RESTAURANT_ID)).thenReturn(true);
        when(catalog.findMenuItem(RESTAURANT_ID, MENU_ITEM_ID))
                .thenReturn(new CatalogLookup.CatalogMenuItem("Prato do dia", new BigDecimal("25.90"), true));
    }

    @Test
    void loadsContextWithFlywayAndHibernateValidation() {
        assertThat(environment.getProperty("spring.jpa.hibernate.ddl-auto")).isEqualTo("validate");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM flyway_schema_history WHERE success", Integer.class))
                .isEqualTo(6);
    }

    @Test
    void createsPersistsAndRetrievesOrder() throws Exception {
        var response = send("POST", "/api/orders", VALID_REQUEST);
        assertThat(response.statusCode()).isEqualTo(201);
        var order = json(response);
        var id = UUID.fromString(order.path("id").asString());
        String location = "/api/orders/" + id;
        assertThat(response.headers().firstValue("Location")).contains(location);
        assertThat(order.path("customerId").asString()).isEqualTo(CUSTOMER_ID.toString());
        assertThat(order.path("restaurantId").asString()).isEqualTo(RESTAURANT_ID.toString());
        assertThat(order.path("destination").path("address").asString()).isEqualTo("Rua das Flores, 42");
        assertThat(order.path("destination").path("latitude").asDouble()).isEqualTo(-23.55);
        assertThat(order.path("status").asString()).isEqualTo("CREATED");
        assertThat(order.path("updatedAt")).isEqualTo(order.path("createdAt"));
        assertThat(Instant.parse(order.path("createdAt").asString())).isNotNull();
        assertThat(order.path("confirmedAt").isNull()).isTrue();
        assertThat(order.path("cancelledAt").isNull()).isTrue();
        assertThat(order.path("currency").asString()).isEqualTo("BRL");
        assertThat(order.path("total").asDouble()).isEqualTo(51.80);
        assertThat(order.path("items").size()).isEqualTo(1);
        var item = order.path("items").get(0);
        assertThat(item.path("menuItemId").asString()).isEqualTo(MENU_ITEM_ID.toString());
        assertThat(item.path("name").asString()).isEqualTo("Prato do dia");
        assertThat(item.path("quantity").asInt()).isEqualTo(2);
        assertThat(item.path("unitPrice").asDouble()).isEqualTo(25.90);
        assertThat(item.path("lineTotal").asDouble()).isEqualTo(51.80);
        var retrieved = send("GET", location, null);
        assertThat(retrieved.statusCode()).isEqualTo(200);
        assertThat(json(retrieved)).isEqualTo(order);
        assertThat(jdbc.queryForObject("SELECT restaurant_id FROM orders WHERE id = ?", UUID.class, id))
                .isEqualTo(RESTAURANT_ID);
        assertThat(jdbc.queryForObject("SELECT customer_id FROM orders WHERE id = ?", UUID.class, id))
                .isEqualTo(CUSTOMER_ID);
        assertThat(jdbc.queryForObject("SELECT destination_address FROM orders WHERE id = ?", String.class, id))
                .isEqualTo("Rua das Flores, 42");
    }

    @Test
    void ignoresClientSuppliedIdentityCustomerStatusAndTimestamps() throws Exception {
        UUID suppliedId = UUID.randomUUID();
        UUID suppliedCustomer = UUID.randomUUID();
        String request = VALID_REQUEST.strip().replaceFirst("\\{", """
                {"id":"%s","customerId":"%s","status":"CONFIRMED","createdAt":"2000-01-01T00:00:00Z","total":0.01,
                "currency":"USD",
                """.formatted(suppliedId, suppliedCustomer)).replace("\"quantity\":2", "\"quantity\":2,\"name\":\"Falso\",\"unitPrice\":0.01");
        var response = send("POST", "/api/orders", request);
        assertThat(response.statusCode()).isEqualTo(201);
        assertThat(json(response).path("id").asString()).isNotEqualTo(suppliedId.toString());
        assertThat(json(response).path("customerId").asString()).isEqualTo(CUSTOMER_ID.toString());
        assertThat(json(response).path("status").asString()).isEqualTo("CREATED");
        assertThat(json(response).path("createdAt").asString()).doesNotStartWith("2000-");
        assertThat(json(response).path("total").asDouble()).isEqualTo(51.80);
        assertThat(json(response).path("currency").asString()).isEqualTo("BRL");
        assertThat(json(response).path("items").get(0).path("name").asString()).isEqualTo("Prato do dia");
    }

    @Test
    void cancelsWithoutChangingTimestampsOnRepeatedCommandsAndOffersNoManualConfirmation() throws Exception {
        String location = send("POST", "/api/orders", VALID_REQUEST).headers().firstValue("Location").orElseThrow();
        var created = json(send("GET", location, null));
        assertThat(created.path("paymentRequestedAt").isNull()).isTrue();
        assertThat(created.path("paymentId").isNull()).isTrue();
        assertProblem(send("POST", location + "/confirm", null), 404);
        assertThat(json(send("GET", location, null))).isEqualTo(created);
        var cancellation = send("POST", location + "/cancel", null);
        assertThat(cancellation.statusCode()).isEqualTo(200);
        var cancelled = json(cancellation);
        assertThat(cancelled.path("status").asString()).isEqualTo("CANCELLED");
        assertThat(cancelled.path("confirmedAt").isNull()).isTrue();
        assertThat(cancelled.path("cancelledAt").isString()).isTrue();
        assertThat(json(send("POST", location + "/cancel", null))).isEqualTo(cancelled);
        assertThat(json(send("GET", location, null))).isEqualTo(cancelled);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM orders", Integer.class)).isEqualTo(1);
    }

    @Test
    void cancelsBeforeConfirmation() throws Exception {
        String location = send("POST", "/api/orders", VALID_REQUEST).headers().firstValue("Location").orElseThrow();
        var response = send("POST", location + "/cancel", null);
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(json(response).path("status").asString()).isEqualTo("CANCELLED");
        assertThat(json(response).path("confirmedAt").isNull()).isTrue();
    }

    @ParameterizedTest
    @MethodSource("invalidRequests")
    void rejectsInvalidInputWithoutPersisting(String body) throws Exception {
        assertProblem(send("POST", "/api/orders", body), 400);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM orders", Integer.class)).isZero();
        verifyNoInteractions(catalog);
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

    @ParameterizedTest
    @MethodSource("invalidItemRequests")
    void rejectsMissingInvalidOrDuplicateSelectionsBeforeCallingCatalog(String body) throws Exception {
        assertProblem(send("POST", "/api/orders", body), 400);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM orders", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM order_items", Integer.class)).isZero();
        verifyNoInteractions(catalog);
    }

    static Stream<String> invalidItemRequests() {
        String item = "{\"menuItemId\":\"" + MENU_ITEM_ID + "\",\"quantity\":2}";
        String items = "[" + item + "]";
        String manyItems = "[" + String.join(",", java.util.Collections.nCopies(51, item)) + "]";
        return Stream.of(VALID_REQUEST.replace("\"items\":" + items + ",", ""),
                VALID_REQUEST.replace(items, "null"), VALID_REQUEST.replace(items, "[]"),
                VALID_REQUEST.replace(items, "{}"), VALID_REQUEST.replace(items, "[null]"),
                VALID_REQUEST.replace(items, "[{}]"), VALID_REQUEST.replace(MENU_ITEM_ID.toString(), "invalid"),
                VALID_REQUEST.replace(items, "[" + item + "," + item + "]"), VALID_REQUEST.replace(items, manyItems),
                VALID_REQUEST.replace("\"quantity\":2", "\"quantity\":0"),
                VALID_REQUEST.replace("\"quantity\":2", "\"quantity\":100"),
                VALID_REQUEST.replace("\"quantity\":2", "\"quantity\":null"),
                VALID_REQUEST.replace("\"quantity\":2", "\"quantity\":\"2\""),
                VALID_REQUEST.replace("\"quantity\":2", "\"quantity\":true"),
                VALID_REQUEST.replace("\"quantity\":2", "\"quantity\":2.0"),
                VALID_REQUEST.replace("\"quantity\":2", "\"quantity\":2.5"),
                VALID_REQUEST.replace("\"quantity\":2", "\"quantity\":2147483648"));
    }

    @Test
    void preservesSnapshotsWhenCatalogChangesOrBecomesUnavailable() throws Exception {
        var created = send("POST", "/api/orders", VALID_REQUEST);
        assertThat(created.statusCode()).isEqualTo(201);
        String path = created.headers().firstValue("Location").orElseThrow();
        JsonNode original = json(created);
        when(catalog.findMenuItem(RESTAURANT_ID, MENU_ITEM_ID))
                .thenReturn(new CatalogLookup.CatalogMenuItem("Novo nome", new BigDecimal("100.00"), false));
        when(catalog.isRestaurantActive(RESTAURANT_ID)).thenThrow(new RemoteServiceUnavailableException());
        clearInvocations(catalog);

        for (var response : new HttpResponse<?>[] { send("GET", path, null), send("POST", path + "/cancel", null) }) {
            assertThat(response.statusCode()).isEqualTo(200);
            JsonNode order = mapper.readTree((String) response.body());
            assertThat(order.path("items")).isEqualTo(original.path("items"));
            assertThat(order.path("total")).isEqualTo(original.path("total"));
        }
        verifyNoInteractions(catalog);
    }

    @Test
    void rejectsInactiveRestaurantUnavailableItemAndCatalogFailuresWithoutWriting() throws Exception {
        when(catalog.isRestaurantActive(RESTAURANT_ID)).thenReturn(false);
        assertProblem(send("POST", "/api/orders", VALID_REQUEST), 409);
        when(catalog.isRestaurantActive(RESTAURANT_ID)).thenReturn(true);
        when(catalog.findMenuItem(RESTAURANT_ID, MENU_ITEM_ID))
                .thenReturn(new CatalogLookup.CatalogMenuItem("Prato", new BigDecimal("25.90"), false));
        assertProblem(send("POST", "/api/orders", VALID_REQUEST), 409);
        when(catalog.findMenuItem(RESTAURANT_ID, MENU_ITEM_ID)).thenThrow(new CatalogSelectionConflictException());
        assertProblem(send("POST", "/api/orders", VALID_REQUEST), 409);
        when(catalog.isRestaurantActive(RESTAURANT_ID)).thenThrow(new RemoteServiceUnavailableException());
        assertProblem(send("POST", "/api/orders", VALID_REQUEST), 503);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM orders", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM order_items", Integer.class)).isZero();
    }

    @Test
    void readsAndTransitionsAPricelessLegacyOrderAssignedToTheCustomerWithoutInventingPrices() throws Exception {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO orders (id,customer_id,restaurant_id,destination_address,destination_latitude,
                    destination_longitude,status,created_at,updated_at)
                VALUES (?,?,?,'Legacy',0,0,'CREATED',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)
                """, id, CUSTOMER_ID, RESTAURANT_ID);

        for (String suffix : new String[] { "", "/cancel" }) {
            var response = send(suffix.isEmpty() ? "GET" : "POST", "/api/orders/" + id + suffix, null);
            assertThat(response.statusCode()).isEqualTo(200);
            assertThat(json(response).path("items").size()).isZero();
            assertThat(json(response).path("total").isNull()).isTrue();
            assertThat(json(response).path("currency").isNull()).isTrue();
        }
        verifyNoInteractions(catalog);
    }

    @Test
    void hidesUnownedLegacyOrdersFromEveryCustomerWithoutChangingThem() throws Exception {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO orders (id,restaurant_id,destination_address,destination_latitude,destination_longitude,
                    status,created_at,updated_at) VALUES (?,?,'Legacy',0,0,'CREATED',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)
                """, id, RESTAURANT_ID);

        for (String suffix : new String[] { "", "/cancel", "/delivery" }) {
            assertProblem(send(suffix.isEmpty() ? "GET" : "POST", "/api/orders/" + id + suffix, null), 404);
        }
        assertThat(jdbc.queryForObject("SELECT status FROM orders WHERE id = ?", String.class, id)).isEqualTo("CREATED");
        assertThat(jdbc.queryForObject("SELECT customer_id FROM orders WHERE id = ?", UUID.class, id)).isNull();
    }

    @Test
    void reportsAnotherCustomersOrderAsMissingAndLeavesItUnchanged() throws Exception {
        String location = send("POST", "/api/orders", VALID_REQUEST).headers().firstValue("Location").orElseThrow();
        var original = json(send("GET", location, null));
        String intruder = TestAccessTokens.issue(UUID.randomUUID());
        clearInvocations(catalog);

        for (String suffix : new String[] { "", "/cancel", "/delivery" }) {
            var response = send(suffix.isEmpty() ? "GET" : "POST", location + suffix, null, intruder);
            assertProblem(response, 404);
            assertThat(json(response).path("detail").asString()).isEqualTo("Pedido não encontrado.");
            assertThat(response.body()).doesNotContain(CUSTOMER_ID.toString(), RESTAURANT_ID.toString());
        }

        assertThat(json(send("GET", location, null))).isEqualTo(original);
        UUID id = UUID.fromString(original.path("id").asString());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM order_delivery_requests WHERE order_id = ?",
                Integer.class, id)).isZero();
        verifyNoInteractions(catalog);
    }

    @Test
    void handlesMissingOrdersAndMalformedIdentifiers() throws Exception {
        for (String suffix : new String[] {"", "/cancel"}) {
            String method = suffix.isEmpty() ? "GET" : "POST";
            assertProblem(send(method, "/api/orders/" + UUID.randomUUID() + suffix, null), 404);
            assertProblem(send(method, "/api/orders/invalid" + suffix, null), 400);
        }
    }

    @Test
    void preservesPingAndActuatorWithoutCredentials() throws Exception {
        assertThat(send("GET", "/api/orders/ping", null, null).statusCode()).isEqualTo(200);
        var health = send("GET", "/actuator/health", null, null);
        assertThat(health.statusCode()).isEqualTo(200);
        assertThat(json(health).path("status").asString()).isEqualTo("UP");
        assertThat(send("GET", "/actuator/info", null, null).statusCode()).isEqualTo(200);
    }

    @ParameterizedTest
    @MethodSource("invalidCredentials")
    void rejectsOrderRequestsWithoutAValidAccessTokenBeforeTouchingData(String token) throws Exception {
        String location = send("POST", "/api/orders", VALID_REQUEST).headers().firstValue("Location").orElseThrow();
        clearInvocations(catalog);

        for (String[] call : new String[][] { { "POST", "/api/orders" }, { "GET", location },
                { "POST", location + "/payment" }, { "POST", location + "/cancel" },
                { "POST", location + "/delivery" } }) {
            var response = send(call[0], call[1], call[1].equals("/api/orders") ? VALID_REQUEST : null, token);
            assertProblem(response, 401);
            assertThat(response.headers().firstValue("WWW-Authenticate")).hasValueSatisfying(
                    challenge -> assertThat(challenge).startsWith("Bearer"));
        }
        verifyNoInteractions(catalog);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM orders", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT status FROM orders", String.class)).isEqualTo("CREATED");
    }

    static Stream<String> invalidCredentials() {
        return Stream.of(null, "not-a-token", TestAccessTokens.issue(
                TestAccessTokens.FOREIGN_PRIVATE_KEY, Instant.now(), claims -> { }),
                TestAccessTokens.issue(TestAccessTokens.PRIVATE_KEY, Instant.now().minusSeconds(1000), claims -> { }));
    }

    private HttpResponse<String> send(String method, String path, String body) throws Exception {
        return send(method, path, body, TestAccessTokens.issue(CUSTOMER_ID));
    }

    private HttpResponse<String> send(String method, String path, String body, String token) throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .timeout(Duration.ofSeconds(10)).header("Content-Type", "application/json")
                .method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
        if (token != null) {
            request.header("Authorization", "Bearer " + token);
        }
        return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private JsonNode json(HttpResponse<String> response) {
        return mapper.readTree(response.body());
    }

    private void assertProblem(HttpResponse<String> response, int status) {
        assertThat(response.statusCode()).isEqualTo(status);
        assertThat(response.headers().firstValue("Content-Type").orElseThrow()).contains("application/problem+json");
        assertThat(json(response).path("status").asInt()).isEqualTo(status);
        assertThat(json(response).path("detail").asString()).isNotBlank();
        assertThat(response.body()).doesNotContain("org.hibernate", "SQLException", "stackTrace", "com.victhor");
    }
}
