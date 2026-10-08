package com.victhor.delivery.order.infrastructure;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Optional;
import java.util.UUID;

import com.victhor.delivery.order.application.PaymentGateway;
import com.victhor.delivery.order.application.PaymentRejectedException;
import com.victhor.delivery.order.application.RemoteServiceUnavailableException;
import com.victhor.delivery.order.domain.OrderPayment;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

public class HttpPaymentGateway implements PaymentGateway {

    /** Payment caps a page at 100 attempts; an order with more attempts than that is not reconciled. */
    private static final int RECONCILIATION_PAGE_SIZE = 100;

    private final HttpClient client;
    private final ObjectMapper mapper;
    private final String paymentUrl;
    private final Duration timeout;

    public HttpPaymentGateway(HttpClient client, ObjectMapper mapper, String paymentUrl, Duration timeout) {
        this.client = client;
        this.mapper = mapper;
        this.paymentUrl = paymentUrl.replaceAll("/+$", "");
        this.timeout = timeout;
    }

    @Override
    public PaymentOutcome charge(OrderPayment pending, String accessToken) {
        String body = mapper.createObjectNode().put("orderId", pending.orderId().toString())
                .put("amount", pending.amount()).put("method", pending.method().code()).toString();
        var request = request(paymentUrl + "/api/payments", accessToken)
                .header("Content-Type", "application/json")
                .header("Idempotency-Key", pending.idempotencyKey().value())
                .POST(HttpRequest.BodyPublishers.ofString(body)).build();
        var response = send(request);
        switch (response.statusCode()) {
            case 200, 201 -> {
                // handled below
            }
            case 409 -> throw new PaymentRejectedException(true);
            case 400, 404, 422 -> throw new PaymentRejectedException(false);
            default -> throw new RemoteServiceUnavailableException();
        }
        try {
            JsonNode json = mapper.readTree(response.body());
            if (!matches(json, pending) || !pending.method().code().equals(json.path("method").asString())) {
                throw new IllegalArgumentException("Payment response does not match the intent");
            }
            return outcome(json);
        } catch (RuntimeException exception) {
            throw new RemoteServiceUnavailableException(exception);
        }
    }

    @Override
    public Optional<PaymentOutcome> findApproved(OrderPayment pending, String accessToken) {
        var request = request(paymentUrl + "/api/payments?orderId=" + pending.orderId() + "&page=0&size="
                + RECONCILIATION_PAGE_SIZE, accessToken).GET().build();
        var response = send(request);
        if (response.statusCode() != 200) {
            throw new RemoteServiceUnavailableException();
        }
        try {
            JsonNode items = mapper.readTree(response.body()).path("items");
            if (!items.isArray()) {
                throw new IllegalArgumentException("Missing payment page items");
            }
            for (JsonNode attempt : items) {
                if (matches(attempt, pending) && "APPROVED".equals(attempt.path("status").asString())) {
                    return Optional.of(outcome(attempt));
                }
            }
            return Optional.empty();
        } catch (RuntimeException exception) {
            throw new RemoteServiceUnavailableException(exception);
        }
    }

    /** Only an attempt for this order and its full total settles the intent. */
    private static boolean matches(JsonNode attempt, OrderPayment pending) {
        return pending.orderId().equals(UUID.fromString(attempt.path("orderId").asString()))
                && attempt.path("amount").isNumber()
                && pending.amount().compareTo(attempt.path("amount").decimalValue()) == 0
                && "BRL".equals(attempt.path("currency").asString())
                && attempt.path("simulated").asBoolean(false);
    }

    private static PaymentOutcome outcome(JsonNode attempt) {
        UUID paymentId = UUID.fromString(attempt.path("id").asString());
        return switch (attempt.path("status").asString()) {
            case "APPROVED" -> new PaymentOutcome(paymentId, true, null);
            case "DECLINED" -> {
                JsonNode reason = attempt.path("declineReason");
                if (!reason.isString() || reason.asString().isBlank()
                        || reason.asString().length() > OrderPayment.MAX_DECLINE_REASON_LENGTH) {
                    throw new IllegalArgumentException("Missing decline reason");
                }
                yield new PaymentOutcome(paymentId, false, reason.asString());
            }
            default -> throw new IllegalArgumentException("Unknown payment status");
        };
    }

    private HttpRequest.Builder request(String url, String accessToken) {
        return HttpRequest.newBuilder(URI.create(url)).timeout(timeout).header("Accept", "application/json")
                .header("Authorization", "Bearer " + accessToken);
    }

    private HttpResponse<String> send(HttpRequest request) {
        try {
            return client.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new RemoteServiceUnavailableException(exception);
        } catch (IOException | IllegalArgumentException exception) {
            throw new RemoteServiceUnavailableException(exception);
        }
    }
}
