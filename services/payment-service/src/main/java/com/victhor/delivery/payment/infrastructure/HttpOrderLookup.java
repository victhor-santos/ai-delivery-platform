package com.victhor.delivery.payment.infrastructure;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Set;
import java.util.UUID;

import com.victhor.delivery.payment.application.OrderLookup;
import com.victhor.delivery.payment.application.PaymentOrderNotFoundException;
import com.victhor.delivery.payment.application.RemoteServiceUnavailableException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

public class HttpOrderLookup implements OrderLookup {

    private static final Set<String> ORDER_STATUSES = Set.of("CREATED", "CONFIRMED", "CANCELLED");

    private final HttpClient client;
    private final ObjectMapper mapper;
    private final String orderUrl;
    private final Duration timeout;

    public HttpOrderLookup(HttpClient client, ObjectMapper mapper, String orderUrl, Duration timeout) {
        this.client = client;
        this.mapper = mapper;
        this.orderUrl = orderUrl.replaceAll("/+$", "");
        this.timeout = timeout;
    }

    @Override
    public OrderSnapshot findById(UUID orderId, String accessToken) {
        var request = HttpRequest.newBuilder(URI.create(orderUrl + "/api/orders/" + orderId)).timeout(timeout)
                .header("Accept", "application/json").header("Authorization", "Bearer " + accessToken).GET().build();
        HttpResponse<String> response;
        try {
            response = client.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new RemoteServiceUnavailableException(exception);
        } catch (IOException | IllegalArgumentException exception) {
            throw new RemoteServiceUnavailableException(exception);
        }
        if (response.statusCode() == 404) {
            throw new PaymentOrderNotFoundException();
        }
        if (response.statusCode() != 200) {
            throw new RemoteServiceUnavailableException();
        }
        try {
            JsonNode json = mapper.readTree(response.body());
            String status = json.path("status").asString();
            JsonNode total = json.path("total");
            JsonNode requestedAt = json.path("paymentRequestedAt");
            if (!orderId.equals(UUID.fromString(json.path("id").asString())) || !ORDER_STATUSES.contains(status)
                    || !(total.isNumber() || total.isNull()) || !(requestedAt.isString() || requestedAt.isNull())) {
                throw new IllegalArgumentException("Invalid order response");
            }
            return new OrderSnapshot(status, total.isNull() ? null : total.decimalValue(), requestedAt.isString());
        } catch (RuntimeException exception) {
            throw new RemoteServiceUnavailableException(exception);
        }
    }
}
