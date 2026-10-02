package com.victhor.delivery.order.domain;

import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OrderDeliveryTests {

    private static final Instant TIME = Instant.parse("2026-10-02T10:00:00Z");

    private Order created() {
        return Order.create(UUID.randomUUID(), new DeliveryDestination("Rua Central", 0, 0), TIME);
    }

    @Test
    void onlyConfirmedOrdersCanRequestDelivery() {
        assertThatThrownBy(() -> created().requestDelivery(TIME)).isInstanceOf(OrderStateConflictException.class);
        assertThatThrownBy(() -> created().cancel(TIME).requestDelivery(TIME))
                .isInstanceOf(OrderStateConflictException.class);
    }

    @Test
    void deliveryRequestPreservesConfirmationAndBlocksCancellation() {
        Order confirmed = created().confirm(TIME.plusSeconds(1));
        Order requested = confirmed.requestDelivery(TIME.plusSeconds(2));
        assertThat(requested.confirmedAt()).isEqualTo(confirmed.confirmedAt());
        assertThat(requested.deliveryRequestedAt()).isEqualTo(TIME.plusSeconds(2));
        assertThat(requested.updatedAt()).isEqualTo(requested.deliveryRequestedAt());
        assertThat(requested.requestDelivery(TIME.plusSeconds(3))).isSameAs(requested);
        assertThat(requested.confirm(TIME.plusSeconds(3))).isSameAs(requested);
        assertThatThrownBy(() -> requested.cancel(TIME.plusSeconds(3))).isInstanceOf(OrderStateConflictException.class);
    }

    @Test
    void rejectsDeliveryRequestBeforeConfirmationTime() {
        assertThatThrownBy(() -> created().confirm(TIME.plusSeconds(1)).requestDelivery(TIME))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
