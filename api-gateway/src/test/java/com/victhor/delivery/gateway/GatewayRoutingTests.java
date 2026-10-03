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

    private static HttpServer startBackend() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/api/", exchange -> {
                byte[] body = exchange.getRequestURI().getPath().getBytes(StandardCharsets.UTF_8);
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
