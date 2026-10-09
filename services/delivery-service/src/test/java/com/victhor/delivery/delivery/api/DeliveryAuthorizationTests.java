package com.victhor.delivery.delivery.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import com.victhor.delivery.delivery.application.DeliveryService;
import com.victhor.delivery.delivery.domain.Delivery;
import com.victhor.delivery.delivery.domain.DeliveryLocation;
import com.victhor.delivery.delivery.domain.GeoPoint;
import com.victhor.delivery.delivery.infrastructure.auth.TestAccessTokens;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** The delivery API seen by its customer, by another customer, by the operator and by anonymous callers. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"DELIVERY_DB_PASSWORD=testcontainers-only",
                "RABBITMQ_PASSWORD=unused", "spring.rabbitmq.listener.simple.auto-startup=false",
                "management.health.rabbit.enabled=false"})
@Testcontainers
@ActiveProfiles("test")
class DeliveryAuthorizationTests {

    private static final UUID CUSTOMER = UUID.randomUUID();
    private static final UUID STRANGER = UUID.randomUUID();

    @Container
    @ServiceConnection
    static final PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17-alpine");

    @LocalServerPort
    private int port;
    @Autowired
    private DeliveryService deliveries;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private ObjectMapper mapper;

    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    private Delivery delivery;

    @BeforeEach
    void createACustomersDelivery() {
        jdbc.update("DELETE FROM deliveries");
        delivery = deliveries.createForOrder(UUID.randomUUID(), CUSTOMER,
                new DeliveryLocation("Restaurante", new GeoPoint(-23.55, -46.63)),
                new DeliveryLocation("Rua Central, 42", new GeoPoint(-23.56, -46.64))).delivery();
    }

    @Test
    void showsTheDeliveryToItsCustomerAndTheOperatorOnly() throws Exception {
        String byId = "/api/deliveries/" + delivery.id();
        String byOrder = "/api/deliveries/by-order/" + delivery.orderId();
        for (String path : List.of(byId, byOrder)) {
            assertThat(send("GET", path, customer(CUSTOMER)).statusCode()).isEqualTo(200);
            assertThat(send("GET", path, operator()).statusCode()).isEqualTo(200);
            assertProblem(send("GET", path, customer(STRANGER)), 404);
            assertProblem(send("GET", path, null), 401);
        }
        var foreign = send("GET", byId, customer(STRANGER));
        var missing = send("GET", "/api/deliveries/" + UUID.randomUUID(), customer(STRANGER));
        assertThat(mapper.readTree(foreign.body()).path("detail"))
                .isEqualTo(mapper.readTree(missing.body()).path("detail"));
        assertProblem(send("GET", byId + "/route", customer(STRANGER)), 404);
        assertProblem(send("GET", byId + "/segments", customer(STRANGER)), 404);
        assertThat(send("GET", byId + "/segments", customer(CUSTOMER)).statusCode()).isEqualTo(200);
    }

    @Test
    void reservesListingCouriersExportsAndEveryCommandForTheOperator() throws Exception {
        String path = "/api/deliveries/" + delivery.id();
        List<String[]> operatorOnly = List.of(
                new String[] { "GET", "/api/deliveries", null },
                new String[] { "POST", "/api/deliveries/couriers", null },
                new String[] { "GET", "/api/deliveries/couriers/" + UUID.randomUUID(), null },
                new String[] { "POST", path + "/assign", "{\"courierId\":\"" + UUID.randomUUID() + "\"}" },
                new String[] { "POST", path + "/pick-up", null }, new String[] { "POST", path + "/start-transit", null },
                new String[] { "POST", path + "/arrive", null }, new String[] { "POST", path + "/complete", null },
                new String[] { "POST", path + "/cancel", null },
                new String[] { "POST", path + "/route", "{\"departureAt\":\"2026-10-03T19:00:00-03:00\"}" },
                new String[] { "PUT", path + "/segments/0/entry", "{\"occurredAt\":\"2026-10-03T19:00:00-03:00\"}" },
                new String[] { "GET", path + "/segments/export?availableAtCutoff=2026-10-03T19:00:00Z", null },
                new String[] { "POST", "/api/deliveries", "{}" });
        for (String[] call : operatorOnly) {
            var denied = send(call[0], call[1], call[2], customer(CUSTOMER));
            assertProblem(denied, 403);
            assertThat(mapper.readTree(denied.body()).path("detail").asString())
                    .isEqualTo("Operação permitida apenas a operadores.");
            assertProblem(send(call[0], call[1], call[2], null), 401);
        }
        assertThat(jdbc.queryForObject("SELECT status FROM deliveries WHERE id = ?", String.class, delivery.id()))
                .isEqualTo("CREATED");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM couriers", Integer.class)).isZero();
    }

    @Test
    void listsDeliveriesForTheOperatorNewestFirstAndFiltersByStatus() throws Exception {
        var newer = deliveries.createForOrder(UUID.randomUUID(), STRANGER,
                new DeliveryLocation("Restaurante", new GeoPoint(-23.55, -46.63)),
                new DeliveryLocation("Rua Lateral, 7", new GeoPoint(-23.57, -46.65))).delivery();
        deliveries.cancel(newer.id());

        JsonNode all = mapper.readTree(send("GET", "/api/deliveries?size=10", operator()).body());
        assertThat(all.path("totalElements").asLong()).isEqualTo(2);
        assertThat(all.path("items").get(0).path("id").asString()).isEqualTo(newer.id().toString());
        JsonNode created = mapper.readTree(send("GET", "/api/deliveries?status=CREATED", operator()).body());
        assertThat(created.path("items")).hasSize(1);
        assertThat(created.path("items").get(0).path("id").asString()).isEqualTo(delivery.id().toString());
        assertProblem(send("GET", "/api/deliveries?size=101", operator()), 400);
        assertProblem(send("GET", "/api/deliveries?status=LOST", operator()), 400);
    }

    @Test
    void rejectsTokensSignedByAnotherKey() throws Exception {
        String forged = "Bearer " + TestAccessTokens.issue(TestAccessTokens.FOREIGN_PRIVATE_KEY, Instant.now(),
                claims -> claims.claim("roles", List.of("OPERATOR")));
        assertProblem(send("GET", "/api/deliveries", forged), 401);
    }

    private HttpResponse<String> send(String method, String path, String authorization) throws Exception {
        return send(method, path, null, authorization);
    }

    private HttpResponse<String> send(String method, String path, String body, String authorization) throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .timeout(Duration.ofSeconds(10)).header("Content-Type", "application/json")
                .method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
        if (authorization != null) {
            request.header("Authorization", authorization);
        }
        return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private void assertProblem(HttpResponse<String> response, int status) {
        assertThat(response.statusCode()).isEqualTo(status);
        assertThat(response.headers().firstValue("Content-Type").orElse("")).startsWith("application/problem+json");
    }

    private static String customer(UUID id) {
        return "Bearer " + TestAccessTokens.issue(id);
    }

    private static String operator() {
        return "Bearer " + TestAccessTokens.issueOperator(UUID.randomUUID());
    }
}
