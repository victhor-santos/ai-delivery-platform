package com.victhor.delivery.order.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class OrderPaymentTests {

    private static final Instant CREATED = Instant.parse("2026-10-07T12:00:00Z");
    private static final IdempotencyKey KEY = new IdempotencyKey("checkout-0001");
    private static final UUID PAYMENT_ID = UUID.randomUUID();
    private static final OrderPricing PRICING = new OrderPricing(List.of(
            new OrderItem(UUID.randomUUID(), "Prato executivo", 2, new BigDecimal("25.90"))));

    private final Order order = Order.create(UUID.randomUUID(), UUID.randomUUID(),
            new DeliveryDestination("Rua Central, 10", -23.55, -46.63), PRICING, CREATED);

    @Test
    void awaitingPaymentBlocksCancellationWithoutChangingTheLifecycle() {
        Order awaiting = order.requestPayment(CREATED.plusSeconds(5));

        assertThat(awaiting.status()).isEqualTo(OrderStatus.CREATED);
        assertThat(awaiting.updatedAt()).isEqualTo(CREATED);
        assertThat(awaiting.paymentRequestedAt()).isEqualTo(CREATED.plusSeconds(5));
        assertThat(awaiting.requestPayment(CREATED.plusSeconds(9))).isSameAs(awaiting);
        assertThatThrownBy(() -> awaiting.cancel(CREATED.plusSeconds(10)))
                .isInstanceOf(OrderStateConflictException.class);
    }

    @Test
    void approvedPaymentConfirmsTheOrderAndKeepsItFromBeingCancelled() {
        Order paid = order.requestPayment(CREATED.plusSeconds(5)).confirmPayment(PAYMENT_ID, CREATED.plusSeconds(6));

        assertThat(paid.status()).isEqualTo(OrderStatus.CONFIRMED);
        assertThat(paid.confirmedAt()).isEqualTo(CREATED.plusSeconds(6));
        assertThat(paid.updatedAt()).isEqualTo(paid.confirmedAt());
        assertThat(paid.paymentId()).isEqualTo(PAYMENT_ID);
        assertThat(paid.paymentRequestedAt()).isNull();
        assertThat(paid.pricing()).isSameAs(PRICING);
        assertThat(paid.confirmPayment(PAYMENT_ID, CREATED.plusSeconds(9))).isSameAs(paid);
        assertThatThrownBy(() -> paid.confirmPayment(UUID.randomUUID(), CREATED.plusSeconds(9)))
                .isInstanceOf(OrderStateConflictException.class);
        assertThatThrownBy(() -> paid.cancel(CREATED.plusSeconds(10))).isInstanceOf(OrderStateConflictException.class);
        assertThatThrownBy(() -> paid.requestPayment(CREATED.plusSeconds(10)))
                .isInstanceOf(OrderStateConflictException.class);
        Order delivering = paid.requestDelivery(CREATED.plusSeconds(10));
        assertThat(delivering.paymentId()).isEqualTo(PAYMENT_ID);
    }

    @Test
    void onlyAPendingPaymentCanConfirmTheOrder() {
        assertThatThrownBy(() -> order.confirmPayment(PAYMENT_ID, CREATED.plusSeconds(1)))
                .isInstanceOf(OrderStateConflictException.class);
        Order cancelled = order.cancel(CREATED.plusSeconds(1));
        assertThatThrownBy(() -> cancelled.requestPayment(CREATED.plusSeconds(2)))
                .isInstanceOf(OrderStateConflictException.class);
        assertThatThrownBy(() -> cancelled.confirmPayment(PAYMENT_ID, CREATED.plusSeconds(2)))
                .isInstanceOf(OrderStateConflictException.class);
    }

    @Test
    void releasedPaymentAllowsAnotherAttemptOrCancellation() {
        Order released = order.requestPayment(CREATED.plusSeconds(5)).releasePayment();

        assertThat(released).isEqualTo(order);
        assertThat(released.releasePayment()).isSameAs(released);
        assertThat(released.cancel(CREATED.plusSeconds(6)).status()).isEqualTo(OrderStatus.CANCELLED);
        assertThat(released.requestPayment(CREATED.plusSeconds(7)).paymentRequestedAt())
                .isEqualTo(CREATED.plusSeconds(7));
    }

    @Test
    void ordersWithoutTotalCannotBePaid() {
        var legacy = new Order(UUID.randomUUID(), UUID.randomUUID(), order.destination(), OrderStatus.CREATED,
                CREATED, CREATED, null, null);

        assertThatThrownBy(() -> legacy.requestPayment(CREATED.plusSeconds(1)))
                .isInstanceOf(OrderStateConflictException.class);
        assertThatThrownBy(() -> OrderPayment.request(legacy, KEY, SimulatedPaymentMethod.APPROVED_CARD, CREATED))
                .isInstanceOf(OrderStateConflictException.class);
    }

    @Test
    void rejectsRestoringInconsistentPaymentState() {
        assertThatIllegalArgumentException().isThrownBy(() -> new Order(order.id(), order.restaurantId(),
                order.destination(), OrderStatus.CREATED, CREATED, CREATED, null, null, null, PRICING,
                order.customerId(), CREATED.minusSeconds(1), null));
        assertThatIllegalArgumentException().isThrownBy(() -> new Order(order.id(), order.restaurantId(),
                order.destination(), OrderStatus.CREATED, CREATED, CREATED, null, null, null, PRICING,
                order.customerId(), null, PAYMENT_ID));
        assertThatIllegalArgumentException().isThrownBy(() -> new Order(order.id(), order.restaurantId(),
                order.destination(), OrderStatus.CREATED, CREATED, CREATED, null, null, null, null,
                order.customerId(), CREATED, null));
        Instant confirmed = CREATED.plusSeconds(1);
        assertThatIllegalArgumentException().isThrownBy(() -> new Order(order.id(), order.restaurantId(),
                order.destination(), OrderStatus.CONFIRMED, CREATED, confirmed, confirmed, null, null, PRICING,
                order.customerId(), confirmed, PAYMENT_ID));
    }

    @Test
    void recordsAPendingIntentForTheOrderTotal() {
        OrderPayment pending = OrderPayment.request(order, KEY, SimulatedPaymentMethod.DECLINED_CARD, CREATED);

        assertThat(pending.orderId()).isEqualTo(order.id());
        assertThat(pending.amount()).isEqualByComparingTo("51.80");
        assertThat(pending.isPending()).isTrue();
        assertThat(pending.paymentId()).isNull();
        assertThat(pending.completedAt()).isNull();
        assertThat(pending.sameIntentAs(SimulatedPaymentMethod.DECLINED_CARD)).isTrue();
        assertThat(pending.sameIntentAs(SimulatedPaymentMethod.APPROVED_CARD)).isFalse();
    }

    @Test
    void completesAPendingIntentOnlyOnce() {
        OrderPayment pending = OrderPayment.request(order, KEY, SimulatedPaymentMethod.DECLINED_CARD, CREATED);

        OrderPayment declined = pending.decline(PAYMENT_ID, "CARD_DECLINED", CREATED.plusSeconds(1));
        assertThat(declined.status()).isEqualTo(OrderPaymentStatus.DECLINED);
        assertThat(declined.declineReason()).isEqualTo("CARD_DECLINED");
        assertThat(pending.approve(PAYMENT_ID, CREATED.plusSeconds(1)).status()).isEqualTo(OrderPaymentStatus.APPROVED);
        OrderPayment rejected = pending.reject(CREATED.plusSeconds(1));
        assertThat(rejected.status()).isEqualTo(OrderPaymentStatus.REJECTED);
        assertThat(rejected.paymentId()).isNull();
        assertThatIllegalStateException().isThrownBy(() -> declined.approve(PAYMENT_ID, CREATED.plusSeconds(2)));
        assertThatIllegalStateException().isThrownBy(() -> rejected.reject(CREATED.plusSeconds(2)));
        assertThatIllegalArgumentException().isThrownBy(() -> pending.approve(PAYMENT_ID, CREATED.minusSeconds(1)));
    }

    @Test
    void rejectsPaymentDataThatContradictsItsStatus() {
        BigDecimal amount = PRICING.total();
        var method = SimulatedPaymentMethod.APPROVED_CARD;
        assertThatIllegalArgumentException().isThrownBy(() -> new OrderPayment(order.id(), KEY, method, amount,
                OrderPaymentStatus.PENDING, PAYMENT_ID, null, CREATED, null));
        assertThatIllegalArgumentException().isThrownBy(() -> new OrderPayment(order.id(), KEY, method, amount,
                OrderPaymentStatus.APPROVED, null, null, CREATED, CREATED));
        assertThatIllegalArgumentException().isThrownBy(() -> new OrderPayment(order.id(), KEY, method, amount,
                OrderPaymentStatus.DECLINED, PAYMENT_ID, null, CREATED, CREATED));
        assertThatIllegalArgumentException().isThrownBy(() -> new OrderPayment(order.id(), KEY, method, amount,
                OrderPaymentStatus.REJECTED, null, null, CREATED, null));
        assertThatIllegalArgumentException().isThrownBy(() -> new OrderPayment(order.id(), KEY, method,
                BigDecimal.ZERO, OrderPaymentStatus.PENDING, null, null, CREATED, null));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "short", "has space in-it", "acentuação-1234", "4111 1111 1111 1111"})
    void rejectsMalformedIdempotencyKeys(String value) {
        assertThatIllegalArgumentException().isThrownBy(() -> new IdempotencyKey(value));
    }

    @Test
    void acceptsOnlyTheSimulatedMethodCodes() {
        assertThat(SimulatedPaymentMethod.fromCode("sim-card-insufficient-funds"))
                .isEqualTo(SimulatedPaymentMethod.INSUFFICIENT_FUNDS_CARD);
        assertThatIllegalArgumentException().isThrownBy(() -> SimulatedPaymentMethod.fromCode("4111111111111111"));
        assertThatIllegalArgumentException().isThrownBy(() -> SimulatedPaymentMethod.fromCode(null));
    }
}
