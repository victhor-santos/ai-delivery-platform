package com.victhor.delivery.gateway;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class GatewayRoutingTests {

    private static final HttpServer BACKEND = startBackend();

    @LocalServerPort
    private int port;

    @DynamicPropertySource
    static void configureBackends(DynamicPropertyRegistry registry) {
        for (String service : new String[] {"USER", "CATALOG", "ORDER", "PAYMENT", "DELIVERY"}) {
            registry.add(service + "_SERVICE_URL",
                    () -> "http://127.0.0.1:" + BACKEND.getAddress().getPort());
        }
    }

    @AfterAll
    static void stopBackend() {
        BACKEND.stop(0);
    }

    @ParameterizedTest
    @ValueSource(strings = {"/api/users/ping", "/api/catalog/ping", "/api/orders/ping",
            "/api/payments/ping", "/api/deliveries/ping"})
    void forwardsRequestsToConfiguredBackendWithoutChangingPath(String path) throws Exception {
        try (HttpClient client = HttpClient.newHttpClient()) {
            HttpRequest request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                    .timeout(Duration.ofSeconds(5)).GET().build();
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

            assertThat(response.statusCode()).isEqualTo(200);
            assertThat(response.body()).isEqualTo(path);
        }
    }

    @Test
    void forwardsAndReturnsAValidClientRequestId() throws Exception {
        HttpResponse<String> response = get("/api/orders/ping", "web-0123456789abcdef");

        assertThat(response.headers().firstValue("X-Request-Id")).hasValue("web-0123456789abcdef");
        assertThat(response.headers().firstValue("X-Seen-Request-Id")).hasValue("web-0123456789abcdef");
    }

    @ParameterizedTest
    @ValueSource(strings = {"short", "has space inside", "semicolon;injected-value", "xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx"})
    void replacesInvalidRequestIdsWithAGeneratedOne(String invalid) throws Exception {
        HttpResponse<String> response = get("/api/orders/ping", invalid);

        String requestId = response.headers().firstValue("X-Request-Id").orElseThrow();
        assertThat(requestId).isNotEqualTo(invalid).matches("[0-9a-f-]{36}");
        assertThat(response.headers().firstValue("X-Seen-Request-Id")).hasValue(requestId);
    }

    @Test
    void generatesARequestIdEvenWhenNoRouteMatches() throws Exception {
        HttpResponse<String> response = get("/api/unknown", null);

        assertThat(response.statusCode()).isEqualTo(404);
        assertThat(response.headers().firstValue("X-Request-Id")).hasValueSatisfying(
                value -> assertThat(value).matches("[0-9a-f-]{36}"));
    }

    private HttpResponse<String> get(String path, String requestId) throws Exception {
        try (HttpClient client = HttpClient.newHttpClient()) {
            var builder = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                    .timeout(Duration.ofSeconds(5)).GET();
            if (requestId != null) {
                builder.header("X-Request-Id", requestId);
            }
            return client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
        }
    }

    private static HttpServer startBackend() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/api/", exchange -> {
                byte[] body = exchange.getRequestURI().getPath().getBytes(StandardCharsets.UTF_8);
                String requestId = exchange.getRequestHeaders().getFirst("X-Request-Id");
                if (requestId != null) {
                    exchange.getResponseHeaders().set("X-Seen-Request-Id", requestId);
                }
                exchange.sendResponseHeaders(200, body.length);
                try (var output = exchange.getResponseBody()) {
                    output.write(body);
                }
            });
            server.start();
            return server;
        } catch (IOException exception) {
            throw new IllegalStateException("Cannot start test backend", exception);
        }
    }
}
