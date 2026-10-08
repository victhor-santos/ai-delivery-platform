package com.victhor.delivery.payment.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import com.victhor.delivery.payment.application.OrderLookup.OrderSnapshot;
import com.victhor.delivery.payment.application.PaymentOrderNotFoundException;
import com.victhor.delivery.payment.application.RemoteServiceUnavailableException;

class HttpOrderLookupTests {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final UUID ORDER_ID = UUID.fromString("eebf0950-c061-4ba9-9daf-97f68e732f5f");
    private static final String TOKEN = "header.payload.signature";

    private final AtomicReference<String> responseBody = new AtomicReference<>();
    private final AtomicInteger responseStatus = new AtomicInteger(200);
    private final AtomicReference<String> requestPath = new AtomicReference<>();
    private final AtomicReference<String> authorization = new AtomicReference<>();
    private HttpServer server;
    private HttpClient client;
    private HttpOrderLookup lookup;

    @BeforeEach
    void startOrderServer() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            requestPath.set(exchange.getRequestURI().getPath());
            authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            byte[] body = responseBody.get().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(responseStatus.get(), body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        client = HttpClient.newHttpClient();
        lookup = new HttpOrderLookup(client, JSON, "http://127.0.0.1:" + server.getAddress().getPort() + "/",
                Duration.ofSeconds(2));
        responseBody.set(order().toString());
    }

    @AfterEach
    void stopOrderServer() {
        server.stop(0);
        client.close();
    }

    @Test
    void readsTheOrderOnBehalfOfTheCustomer() {
        OrderSnapshot order = lookup.findById(ORDER_ID, TOKEN);

        assertThat(order.status()).isEqualTo("CREATED");
        assertThat(order.total()).isEqualByComparingTo("494999999950.50");
        assertThat(order.awaitingPayment()).isTrue();
        assertThat(order.accepts(new BigDecimal("494999999950.50"))).isTrue();
        assertThat(order.accepts(new BigDecimal("494999999950.49"))).isFalse();
        assertThat(requestPath.get()).isEqualTo("/api/orders/" + ORDER_ID);
        assertThat(authorization.get()).isEqualTo("Bearer " + TOKEN);
    }

    @Test
    void readsAnOrderThatIsNotAwaitingPaymentOrHasNoTotal() {
        responseBody.set(order().put("status", "CONFIRMED").putNull("paymentRequestedAt").putNull("total").toString());

        assertThat(lookup.findById(ORDER_ID, TOKEN)).isEqualTo(new OrderSnapshot("CONFIRMED", null, false));
    }

    @Test
    void reportsAMissingOrAnotherCustomersOrderAsAbsent() {
        responseStatus.set(404);

        assertThatThrownBy(() -> lookup.findById(ORDER_ID, TOKEN)).isInstanceOf(PaymentOrderNotFoundException.class);
    }

    @ParameterizedTest
    @ValueSource(ints = { 400, 401, 409, 500, 503 })
    void treatsOtherStatusesAsUnavailable(int status) {
        responseStatus.set(status);

        assertThatThrownBy(() -> lookup.findById(ORDER_ID, TOKEN))
                .isInstanceOf(RemoteServiceUnavailableException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = { "id", "status", "total", "paymentRequestedAt", "body" })
    void rejectsAnInvalidOrderResponse(String field) {
        ObjectNode order = order();
        switch (field) {
            case "id" -> order.put("id", UUID.randomUUID().toString());
            case "status" -> order.put("status", "PAID");
            case "total" -> order.put("total", "494999999950.50");
            case "paymentRequestedAt" -> order.put("paymentRequestedAt", true);
            default -> order.removeAll();
        }
        responseBody.set(order.toString());

        assertThatThrownBy(() -> lookup.findById(ORDER_ID, TOKEN))
                .isInstanceOf(RemoteServiceUnavailableException.class);
    }

    @Test
    void treatsAnUnreachableServiceAsUnavailable() {
        server.stop(0);

        assertThatThrownBy(() -> lookup.findById(ORDER_ID, TOKEN))
                .isInstanceOf(RemoteServiceUnavailableException.class);
    }

    private static ObjectNode order() {
        return JSON.createObjectNode().put("id", ORDER_ID.toString()).put("status", "CREATED")
                .put("total", new BigDecimal("494999999950.50")).put("currency", "BRL")
                .put("paymentRequestedAt", "2026-10-07T12:00:00Z").putNull("paymentId");
    }
}
