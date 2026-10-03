package com.victhor.delivery.order.infrastructure;

import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.UUID;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import com.victhor.delivery.order.application.RemoteServiceUnavailableException;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

class HttpDeliveryIntegrationTests {

    @Test
    void boundsRemoteCallsWithAnExplicitRequestTimeout() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/catalog/restaurants", exchange -> {
            try {
                Thread.sleep(250);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
            } finally {
                exchange.close();
            }
        });
        server.start();
        try (var client = HttpClient.newHttpClient()) {
            String url = "http://127.0.0.1:" + server.getAddress().getPort();
            var adapter = new HttpDeliveryIntegration(client, new ObjectMapper(), url, url, Duration.ofMillis(50));
            assertThatThrownBy(() -> adapter.findById(UUID.randomUUID()))
                    .isInstanceOf(RemoteServiceUnavailableException.class).hasCauseInstanceOf(HttpTimeoutException.class);
        } finally {
            server.stop(0);
        }
    }
}
