package com.victhor.delivery.order.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.IntStream;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import com.victhor.delivery.order.application.PaymentGateway.PaymentOutcome;
import com.victhor.delivery.order.application.PaymentRejectedException;
import com.victhor.delivery.order.application.RemoteServiceUnavailableException;
import com.victhor.delivery.order.domain.DeliveryDestination;
import com.victhor.delivery.order.domain.IdempotencyKey;
import com.victhor.delivery.order.domain.Order;
import com.victhor.delivery.order.domain.OrderItem;
import com.victhor.delivery.order.domain.OrderPayment;
import com.victhor.delivery.order.domain.OrderPricing;
import com.victhor.delivery.order.domain.SimulatedPaymentMethod;

class HttpPaymentGatewayTests {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String TOKEN = "header.payload.signature";
    private static final UUID PAYMENT_ID = UUID.fromString("5c0f9a4e-2a4f-4c5b-9d7e-0e6b1a2c3d4e");

    private final AtomicReference<String> responseBody = new AtomicReference<>("{}");
    private final AtomicInteger responseStatus = new AtomicInteger(201);
    private final AtomicReference<String> requestMethod = new AtomicReference<>();
    private final AtomicReference<String> requestUri = new AtomicReference<>();
    private final AtomicReference<String> requestBody = new AtomicReference<>();
    private final AtomicReference<String> authorization = new AtomicReference<>();
    private final AtomicReference<String> idempotencyKey = new AtomicReference<>();
    private HttpServer server;
    private HttpClient client;
    private HttpPaymentGateway gateway;
    private OrderPayment pending;

    @BeforeEach
    void startPaymentServer() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            requestMethod.set(exchange.getRequestMethod());
            requestUri.set(exchange.getRequestURI().toString());
            requestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            idempotencyKey.set(exchange.getRequestHeaders().getFirst("Idempotency-Key"));
            byte[] body = responseBody.get().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(responseStatus.get(), body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        client = HttpClient.newHttpClient();
        gateway = new HttpPaymentGateway(client, JSON, "http://127.0.0.1:" + server.getAddress().getPort() + "/",
                Duration.ofSeconds(2));
        pending = pendingFor(new BigDecimal("51.80"));
    }

    @AfterEach
    void stopPaymentServer() {
        server.stop(0);
        client.close();
    }

    @Test
    void chargesTheOrderTotalUnderTheIntentKeyOnBehalfOfTheCustomer() {
        responseBody.set(attempt(pending, "APPROVED", null).toString());

        assertThat(gateway.charge(pending, TOKEN)).isEqualTo(new PaymentOutcome(PAYMENT_ID, true, null));
        assertThat(requestMethod.get()).isEqualTo("POST");
        assertThat(requestUri.get()).isEqualTo("/api/payments");
        assertThat(authorization.get()).isEqualTo("Bearer " + TOKEN);
        assertThat(idempotencyKey.get()).isEqualTo("checkout-0001");
        JsonNode body = JSON.readTree(requestBody.get());
        assertThat(body.path("orderId").asString()).isEqualTo(pending.orderId().toString());
        assertThat(body.path("amount").decimalValue()).isEqualByComparingTo("51.80");
        assertThat(body.path("method").asString()).isEqualTo("sim-card-approved");
    }

    @Test
    void keepsTheExactAmountOfTheLargestOrderTotal() {
        pending = pendingFor(OrderPricing.MAX_TOTAL);
        responseBody.set(attempt(pending, "APPROVED", null).toString());

        assertThat(gateway.charge(pending, TOKEN).approved()).isTrue();
        assertThat(requestBody.get()).contains("\"amount\":494999999950.50");
    }

    @Test
    void readsADeclineWithItsReason() {
        responseStatus.set(200);
        responseBody.set(attempt(pending, "DECLINED", "INSUFFICIENT_FUNDS").toString());

        assertThat(gateway.charge(pending, TOKEN))
                .isEqualTo(new PaymentOutcome(PAYMENT_ID, false, "INSUFFICIENT_FUNDS"));
    }

    @ParameterizedTest
    @ValueSource(ints = { 400, 404, 422 })
    void reportsARefusalThatChargedNothing(int status) {
        responseStatus.set(status);

        assertThatThrownBy(() -> gateway.charge(pending, TOKEN)).isInstanceOfSatisfying(PaymentRejectedException.class,
                rejection -> assertThat(rejection.alreadyCharged()).isFalse());
    }

    @Test
    void reportsAConflictAsAPossibleChargeUnderAnotherKey() {
        responseStatus.set(409);

        assertThatThrownBy(() -> gateway.charge(pending, TOKEN)).isInstanceOfSatisfying(PaymentRejectedException.class,
                rejection -> assertThat(rejection.alreadyCharged()).isTrue());
    }

    @ParameterizedTest
    @ValueSource(ints = { 401, 500, 503 })
    void treatsOtherStatusesAsAnUnknownOutcome(int status) {
        responseStatus.set(status);

        assertThatThrownBy(() -> gateway.charge(pending, TOKEN)).isInstanceOf(RemoteServiceUnavailableException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = { "orderId", "amount", "method", "currency", "simulated", "status", "declineReason", "id" })
    void rejectsAResponseThatDoesNotDescribeTheIntent(String field) {
        ObjectNode response = attempt(pending, "DECLINED", "CARD_DECLINED");
        switch (field) {
            case "orderId" -> response.put(field, UUID.randomUUID().toString());
            case "amount" -> response.put(field, new BigDecimal("51.81"));
            case "method" -> response.put(field, "sim-card-declined");
            case "currency" -> response.put(field, "USD");
            case "simulated" -> response.put(field, false);
            case "status" -> response.put(field, "PENDING");
            case "declineReason" -> response.putNull(field);
            default -> response.put(field, "not-a-uuid");
        }
        responseBody.set(response.toString());

        assertThatThrownBy(() -> gateway.charge(pending, TOKEN)).isInstanceOf(RemoteServiceUnavailableException.class);
    }

    @Test
    void findsOnlyAnApprovalForTheWholeTotalOfThisOrder() {
        ObjectNode page = JSON.createObjectNode();
        page.set("items", JSON.valueToTree(List.of(
                attempt(pending, "DECLINED", "CARD_DECLINED").put("id", UUID.randomUUID().toString()),
                attempt(pending, "APPROVED", null).put("amount", new BigDecimal("10.00")),
                attempt(pending, "APPROVED", null))));
        responseStatus.set(200);
        responseBody.set(page.toString());

        assertThat(gateway.findApproved(pending, TOKEN)).contains(new PaymentOutcome(PAYMENT_ID, true, null));
        assertThat(requestMethod.get()).isEqualTo("GET");
        assertThat(requestUri.get()).isEqualTo("/api/payments?orderId=" + pending.orderId() + "&page=0&size=100");
        assertThat(authorization.get()).isEqualTo("Bearer " + TOKEN);

        page.set("items", JSON.valueToTree(List.of(attempt(pending, "DECLINED", "CARD_DECLINED"))));
        responseBody.set(page.toString());
        assertThat(gateway.findApproved(pending, TOKEN)).isEmpty();
    }

    @Test
    void treatsAnUnreadableAttemptListAsAnUnknownOutcome() {
        responseStatus.set(200);
        responseBody.set("{\"items\":{}}");
        assertThatThrownBy(() -> gateway.findApproved(pending, TOKEN))
                .isInstanceOf(RemoteServiceUnavailableException.class);
        responseStatus.set(503);
        assertThatThrownBy(() -> gateway.findApproved(pending, TOKEN))
                .isInstanceOf(RemoteServiceUnavailableException.class);
    }

    @Test
    void treatsAnUnreachableServiceAsAnUnknownOutcome() {
        server.stop(0);

        assertThatThrownBy(() -> gateway.charge(pending, TOKEN)).isInstanceOf(RemoteServiceUnavailableException.class);
    }

    private static OrderPayment pendingFor(BigDecimal total) {
        var pricing = total.compareTo(OrderPricing.MAX_TOTAL) == 0
                ? new OrderPricing(IntStream.range(0, OrderPricing.MAX_ITEMS).mapToObj(index -> new OrderItem(
                        UUID.randomUUID(), "Prato", OrderItem.MAX_QUANTITY, OrderItem.MAX_UNIT_PRICE)).toList())
                : new OrderPricing(List.of(new OrderItem(UUID.randomUUID(), "Prato", 2, new BigDecimal("25.90"))));
        assertThat(pricing.total()).isEqualByComparingTo(total);
        Order order = Order.create(UUID.randomUUID(), UUID.randomUUID(), new DeliveryDestination("Rua", 0, 0), pricing,
                Instant.parse("2026-10-07T12:00:00Z"));
        return OrderPayment.request(order, new IdempotencyKey("checkout-0001"), SimulatedPaymentMethod.APPROVED_CARD,
                Instant.parse("2026-10-07T12:00:01Z"));
    }

    private static ObjectNode attempt(OrderPayment payment, String status, String declineReason) {
        ObjectNode attempt = JSON.createObjectNode().put("id", PAYMENT_ID.toString())
                .put("orderId", payment.orderId().toString()).put("amount", payment.amount()).put("currency", "BRL")
                .put("method", payment.method().code()).put("status", status).put("simulated", true)
                .put("createdAt", "2026-10-07T12:00:02Z");
        return declineReason == null ? attempt.putNull("declineReason") : attempt.put("declineReason", declineReason);
    }
}
