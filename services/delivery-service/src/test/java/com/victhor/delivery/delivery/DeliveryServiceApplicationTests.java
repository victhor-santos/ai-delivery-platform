package com.victhor.delivery.delivery;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "DELIVERY_DB_PASSWORD=testcontainers-only")
@Testcontainers
class DeliveryServiceApplicationTests {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17-alpine");

    @LocalServerPort
    private int port;

    @Autowired
    private ObjectMapper mapper;

    @Test
    void servesPingAndActuatorWithPostgresqlConfigured() throws Exception {
        try (var client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build()) {
            JsonNode ping = get(client, "/api/deliveries/ping");
            assertThat(ping.path("service").asText()).isEqualTo("delivery-service");
            assertThat(ping.path("status").asText()).isEqualTo("ok");
            assertThat(get(client, "/actuator/health").path("status").asText()).isEqualTo("UP");
            assertThat(get(client, "/actuator/info").isObject()).isTrue();
        }
    }

    private JsonNode get(HttpClient client, String path) throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .timeout(Duration.ofSeconds(10)).GET().build();
        var response = client.send(request, HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).as("GET %s", path).isEqualTo(200);
        return mapper.readTree(response.body());
    }
}
