package com.victhor.delivery.order.api;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import com.victhor.delivery.order.application.OrderPaymentService;
import com.victhor.delivery.order.domain.IdempotencyKey;
import com.victhor.delivery.order.domain.OrderPayment;
import com.victhor.delivery.order.domain.OrderPricing;
import com.victhor.delivery.order.domain.SimulatedPaymentMethod;

@RestController
public class OrderPaymentController {

    static final String IDEMPOTENCY_KEY_HEADER = "Idempotency-Key";

    private final OrderPaymentService payments;

    public OrderPaymentController(OrderPaymentService payments) {
        this.payments = payments;
    }

    /** Approval, decline and a repeated key all answer 200 with the settled intent. */
    @PostMapping("/api/orders/{id}/payment")
    public OrderPaymentResponse pay(@AuthenticationPrincipal Jwt principal, @PathVariable UUID id,
            @RequestHeader(IDEMPOTENCY_KEY_HEADER) String idempotencyKey,
            @Valid @RequestBody PayOrderRequest request) {
        return OrderPaymentResponse.from(payments.pay(id, CurrentCustomer.id(principal),
                new IdempotencyKey(idempotencyKey), SimulatedPaymentMethod.fromCode(request.method()),
                principal.getTokenValue()));
    }

    /** The amount is always the order total; any amount or status in the body is ignored. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record PayOrderRequest(@NotBlank String method) {
    }

    public record OrderPaymentResponse(UUID orderId, UUID paymentId, String status, String declineReason,
            String method, BigDecimal amount, String currency, Instant requestedAt, Instant completedAt) {

        static OrderPaymentResponse from(OrderPayment payment) {
            return new OrderPaymentResponse(payment.orderId(), payment.paymentId(), payment.status().name(),
                    payment.declineReason(), payment.method().code(), payment.amount(), OrderPricing.CURRENCY,
                    payment.requestedAt(), payment.completedAt());
        }
    }
}
