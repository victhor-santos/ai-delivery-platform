package com.victhor.delivery.order.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OrderDeliveryTests {

    private static final Instant TIME = Instant.parse("2026-10-02T10:00:00Z");
    private static final OrderPricing PRICING = new OrderPricing(List.of(
            new OrderItem(UUID.randomUUID(), "Prato executivo", 2, new BigDecimal("25.00"))));
    private static final UUID PAYMENT_ID = UUID.randomUUID();

    private Order created() {
        return Order.create(UUID.randomUUID(), UUID.randomUUID(), new DeliveryDestination("Rua Central", 0, 0), PRICING, TIME);
    }

    @Test
    void onlyConfirmedOrdersCanRequestDelivery() {
        assertThatThrownBy(() -> created().requestDelivery(TIME)).isInstanceOf(OrderStateConflictException.class);
        assertThatThrownBy(() -> created().cancel(TIME).requestDelivery(TIME))
                .isInstanceOf(OrderStateConflictException.class);
    }

    @Test
    void deliveryRequestPreservesConfirmationAndBlocksCancellation() {
        Order confirmed = paid(TIME.plusSeconds(1));
        Order requested = confirmed.requestDelivery(TIME.plusSeconds(2));
        assertThat(requested.confirmedAt()).isEqualTo(confirmed.confirmedAt());
        assertThat(requested.deliveryRequestedAt()).isEqualTo(TIME.plusSeconds(2));
        assertThat(requested.updatedAt()).isEqualTo(requested.deliveryRequestedAt());
        assertThat(requested.pricing()).isSameAs(confirmed.pricing());
        assertThat(requested.requestDelivery(TIME.plusSeconds(3))).isSameAs(requested);
        assertThat(requested.paymentId()).isEqualTo(PAYMENT_ID);
        assertThat(requested.confirmPayment(PAYMENT_ID, TIME.plusSeconds(3))).isSameAs(requested);
        assertThatThrownBy(() -> requested.cancel(TIME.plusSeconds(3))).isInstanceOf(OrderStateConflictException.class);
    }

    @Test
    void rejectsDeliveryRequestBeforeConfirmationTime() {
        assertThatThrownBy(() -> paid(TIME.plusSeconds(1)).requestDelivery(TIME))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private Order paid(Instant at) {
        return created().requestPayment(TIME).confirmPayment(PAYMENT_ID, at);
    }
}
