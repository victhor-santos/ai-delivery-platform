package com.victhor.delivery.payment.api;

import java.math.BigDecimal;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/** The customer, status and identity are never taken from the body. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record CreatePaymentRequest(@NotNull UUID orderId, @NotNull BigDecimal amount, @NotBlank String method) {
}
