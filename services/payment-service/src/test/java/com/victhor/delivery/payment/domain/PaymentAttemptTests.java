package com.victhor.delivery.payment.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

class PaymentAttemptTests {

    private static final UUID CUSTOMER = UUID.randomUUID();
    private static final UUID ORDER = UUID.randomUUID();
    private static final IdempotencyKey KEY = new IdempotencyKey("checkout-0001");
    private static final Instant NOW = Instant.parse("2026-10-07T12:00:00Z");

    @ParameterizedTest
    @EnumSource(SimulatedPaymentMethod.class)
    void decidesTheOutcomeFromTheSimulatedMethodWithGeneratedIdentity(SimulatedPaymentMethod method) {
        var attempt = PaymentAttempt.process(CUSTOMER, ORDER, KEY, new BigDecimal("59.8"), method, NOW);

        assertThat(attempt.id()).isNotNull()
                .isNotEqualTo(PaymentAttempt.process(CUSTOMER, ORDER, KEY, BigDecimal.ONE, method, NOW).id());
        assertThat(attempt.customerId()).isEqualTo(CUSTOMER);
        assertThat(attempt.orderId()).isEqualTo(ORDER);
        assertThat(attempt.idempotencyKey()).isEqualTo(KEY);
        assertThat(attempt.amount()).isEqualTo(new BigDecimal("59.80"));
        assertThat(attempt.status()).isEqualTo(method.outcome());
        assertThat(attempt.declineReason()).isEqualTo(method.declineReason());
        assertThat(attempt.isApproved()).isEqualTo(method == SimulatedPaymentMethod.APPROVED_CARD);
        assertThat(attempt.createdAt()).isEqualTo(NOW);
    }

    @Test
    void mapsEachPublicCodeToAFixedOutcome() {
        assertThat(SimulatedPaymentMethod.fromCode("sim-card-approved").outcome()).isEqualTo(PaymentStatus.APPROVED);
        assertThat(SimulatedPaymentMethod.fromCode("sim-card-declined").declineReason())
                .isEqualTo(DeclineReason.CARD_DECLINED);
        assertThat(SimulatedPaymentMethod.fromCode("sim-card-insufficient-funds").declineReason())
                .isEqualTo(DeclineReason.INSUFFICIENT_FUNDS);
        assertThat(SimulatedPaymentMethod.APPROVED_CARD.declineReason()).isNull();
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = { "", "SIM-CARD-APPROVED", "4111111111111111", "card", " sim-card-approved" })
    void rejectsUnknownOrRealLookingMethods(String code) {
        assertThatIllegalArgumentException().isThrownBy(() -> SimulatedPaymentMethod.fromCode(code));
    }

    @ParameterizedTest
    @ValueSource(strings = { "0", "0.00", "-1", "0.001", "10.005", "494999999950.51", "1e12" })
    void rejectsAmountsOutsidePositiveCents(String amount) {
        assertThatIllegalArgumentException().isThrownBy(() -> PaymentAttempt.process(CUSTOMER, ORDER, KEY,
                new BigDecimal(amount), SimulatedPaymentMethod.APPROVED_CARD, NOW));
    }

    @ParameterizedTest
    @ValueSource(strings = { "0.01", "1", "59.80", "59.800", "494999999950.50" })
    void acceptsPositiveAmountsInCentsUpToTheOrderMaximum(String amount) {
        var attempt = PaymentAttempt.process(CUSTOMER, ORDER, KEY, new BigDecimal(amount),
                SimulatedPaymentMethod.APPROVED_CARD, NOW);

        assertThat(attempt.amount()).isEqualByComparingTo(amount).extracting(BigDecimal::scale).isEqualTo(2);
    }

    @Test
    void requiresEveryIdentifierAndTheCreationTime() {
        var method = SimulatedPaymentMethod.APPROVED_CARD;
        assertThatNullPointerException().isThrownBy(
                () -> PaymentAttempt.process(null, ORDER, KEY, BigDecimal.ONE, method, NOW));
        assertThatNullPointerException().isThrownBy(
                () -> PaymentAttempt.process(CUSTOMER, null, KEY, BigDecimal.ONE, method, NOW));
        assertThatNullPointerException().isThrownBy(
                () -> PaymentAttempt.process(CUSTOMER, ORDER, null, BigDecimal.ONE, method, NOW));
        assertThatNullPointerException().isThrownBy(
                () -> PaymentAttempt.process(CUSTOMER, ORDER, KEY, BigDecimal.ONE, null, NOW));
        assertThatNullPointerException().isThrownBy(
                () -> PaymentAttempt.process(CUSTOMER, ORDER, KEY, BigDecimal.ONE, method, null));
    }

    @Test
    void refusesToRestoreAnOutcomeThatContradictsTheMethod() {
        assertThatIllegalArgumentException().isThrownBy(() -> new PaymentAttempt(UUID.randomUUID(), CUSTOMER, ORDER,
                KEY, BigDecimal.ONE, SimulatedPaymentMethod.DECLINED_CARD, PaymentStatus.APPROVED, null, NOW));
        assertThatIllegalArgumentException().isThrownBy(() -> new PaymentAttempt(UUID.randomUUID(), CUSTOMER, ORDER,
                KEY, BigDecimal.ONE, SimulatedPaymentMethod.DECLINED_CARD, PaymentStatus.DECLINED,
                DeclineReason.INSUFFICIENT_FUNDS, NOW));
    }

    @Test
    void comparesRetriesByOrderAmountAndMethod() {
        var attempt = PaymentAttempt.process(CUSTOMER, ORDER, KEY, new BigDecimal("59.80"),
                SimulatedPaymentMethod.APPROVED_CARD, NOW);

        assertThat(attempt.sameIntentAs(ORDER, new BigDecimal("59.8"), SimulatedPaymentMethod.APPROVED_CARD)).isTrue();
        assertThat(attempt.sameIntentAs(UUID.randomUUID(), new BigDecimal("59.80"),
                SimulatedPaymentMethod.APPROVED_CARD)).isFalse();
        assertThat(attempt.sameIntentAs(ORDER, new BigDecimal("59.81"), SimulatedPaymentMethod.APPROVED_CARD)).isFalse();
        assertThat(attempt.sameIntentAs(ORDER, new BigDecimal("59.80"), SimulatedPaymentMethod.DECLINED_CARD)).isFalse();
        assertThat(attempt.isOwnedBy(CUSTOMER)).isTrue();
        assertThat(attempt.isOwnedBy(UUID.randomUUID())).isFalse();
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = { "", "short", "contains space", "acentuação-123", "a/b/c/d/e", "xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx" })
    void rejectsIdempotencyKeysOutsideTheAllowedShape(String value) {
        assertThatIllegalArgumentException().isThrownBy(() -> new IdempotencyKey(value));
    }

    @ParameterizedTest
    @ValueSource(strings = { "8f14e45f-ceea-467a-9a2e-4c4d0c5c7d8a", "checkout.1:retry_2", "12345678" })
    void acceptsUuidsAndSimpleTokensAsIdempotencyKeys(String value) {
        assertThat(new IdempotencyKey(value).value()).isEqualTo(value);
    }
}
