package com.victhor.delivery.user;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import com.victhor.delivery.user.application.AuthenticationService;
import com.victhor.delivery.user.application.OperatorAccountConflictException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "USER_DB_PASSWORD=testcontainers-only", "user.operator.email=operator@example.test",
        "user.operator.password=operator-password-123" })
@Testcontainers
@ActiveProfiles("test")
class OperatorAccountTests {

    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

    @Container
    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17-alpine");

    @LocalServerPort
    private int port;
    @Autowired
    private ObjectMapper mapper;
    @Autowired
    private AuthenticationService authentication;

    @Test
    void provisionsTheConfiguredOperatorAtStartupAndGrantsTheRoleOnlyThroughIt() throws Exception {
        var login = post("/login", Map.of("email", "operator@example.test", "password", "operator-password-123"));
        assertThat(login.statusCode()).isEqualTo(200);
        var me = HTTP.send(HttpRequest.newBuilder(uri("/me")).timeout(Duration.ofSeconds(20))
                .header("Authorization", "Bearer " + body(login).path("accessToken").asString()).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(body(me).path("role").asString()).isEqualTo("OPERATOR");
        assertThat(body(me).path("email").asString()).isEqualTo("operator@example.test");

        assertThat(post("/register", Map.of("name", "Intruso", "email", "operator@example.test",
                "password", "operator-password-123")).statusCode()).isEqualTo(409);
    }

    @Test
    void refusesToHandTheOperatorRoleToAnExistingCustomer() throws Exception {
        assertThat(post("/register", Map.of("name", "Cliente", "email", "early@example.test",
                "password", "customer-password-123")).statusCode()).isEqualTo(201);
        org.assertj.core.api.Assertions.assertThatThrownBy(
                () -> authentication.ensureOperator("early@example.test", "operator-password-123"))
                .isInstanceOf(OperatorAccountConflictException.class);
    }

    private HttpResponse<String> post(String path, Map<String, ?> value) throws Exception {
        return HTTP.send(HttpRequest.newBuilder(uri(path)).timeout(Duration.ofSeconds(20))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(value))).build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private URI uri(String path) {
        return URI.create("http://localhost:" + port + "/api/users/auth" + path);
    }

    private JsonNode body(HttpResponse<String> response) {
        return mapper.readTree(response.body());
    }
}
