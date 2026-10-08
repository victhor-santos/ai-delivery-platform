package com.victhor.delivery.payment.api;

import java.net.URI;
import java.util.UUID;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.victhor.delivery.payment.application.PaymentService;
import com.victhor.delivery.payment.domain.IdempotencyKey;
import com.victhor.delivery.payment.domain.SimulatedPaymentMethod;

@RestController
@RequestMapping("/api/payments")
public class PaymentController {

    static final String IDEMPOTENCY_KEY_HEADER = "Idempotency-Key";

    private final PaymentService payments;

    public PaymentController(PaymentService payments) {
        this.payments = payments;
    }

    /** A new attempt returns 201; repeating the same key and intent returns the stored attempt with 200. */
    @PostMapping
    public ResponseEntity<PaymentResponse> attempt(@AuthenticationPrincipal Jwt principal,
            @RequestHeader(IDEMPOTENCY_KEY_HEADER) String idempotencyKey,
            @Valid @RequestBody CreatePaymentRequest request) {
        var result = payments.attempt(CurrentCustomer.id(principal), new IdempotencyKey(idempotencyKey),
                request.orderId(), request.amount(), SimulatedPaymentMethod.fromCode(request.method()),
                principal.getTokenValue());
        var response = PaymentResponse.from(result.attempt());
        var location = URI.create("/api/payments/" + response.id());
        return ResponseEntity.status(result.replayed() ? HttpStatus.OK : HttpStatus.CREATED).location(location)
                .body(response);
    }

    @GetMapping("/{id}")
    public PaymentResponse findById(@AuthenticationPrincipal Jwt principal, @PathVariable UUID id) {
        return PaymentResponse.from(payments.findById(id, CurrentCustomer.id(principal)));
    }

    @GetMapping
    public PaymentPageResponse findByOrder(@AuthenticationPrincipal Jwt principal, @RequestParam UUID orderId,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(PaymentService.MAX_PAGE_SIZE) int size) {
        return PaymentPageResponse.from(payments.findByOrder(CurrentCustomer.id(principal), orderId, page, size));
    }
}
