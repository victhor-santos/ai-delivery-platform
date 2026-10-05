package com.victhor.delivery.order.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class OrderTests {

    private static final Instant CREATED = Instant.parse("2026-09-30T12:00:00Z");
    private static final DeliveryDestination DESTINATION = new DeliveryDestination("Rua Central, 10", -23.55, -46.63);
    private static final OrderPricing PRICING = new OrderPricing(List.of(
            new OrderItem(UUID.randomUUID(), "Prato executivo", 2, new BigDecimal("25.00"))));

    @Test
    void createsAnOrderWithGeneratedIdentityAndNoLifecycleEvents() {
        UUID restaurantId = UUID.randomUUID();
        Order order = Order.create(restaurantId, DESTINATION, PRICING, CREATED);

        assertThat(order.id()).isNotNull().isNotEqualTo(Order.create(restaurantId, DESTINATION, PRICING, CREATED).id());
        assertThat(order.restaurantId()).isEqualTo(restaurantId);
        assertThat(order.destination()).isEqualTo(DESTINATION);
        assertThat(order.status()).isEqualTo(OrderStatus.CREATED);
        assertThat(order.createdAt()).isEqualTo(CREATED);
        assertThat(order.updatedAt()).isEqualTo(CREATED);
        assertThat(order.confirmedAt()).isNull();
        assertThat(order.cancelledAt()).isNull();
        assertThat(order.pricing()).isSameAs(PRICING);
    }

    @Test
    void confirmsWithoutChangingTheOrderOrDestinationIdentity() {
        Order original = Order.create(UUID.randomUUID(), DESTINATION, PRICING, CREATED);
        Order confirmed = original.confirm(CREATED.plusSeconds(30));

        assertThat(confirmed.id()).isEqualTo(original.id());
        assertThat(confirmed.restaurantId()).isEqualTo(original.restaurantId());
        assertThat(confirmed.destination()).isEqualTo(original.destination());
        assertThat(confirmed.pricing()).isSameAs(original.pricing());
        assertThat(confirmed.status()).isEqualTo(OrderStatus.CONFIRMED);
        assertThat(confirmed.createdAt()).isEqualTo(CREATED);
        assertThat(confirmed.confirmedAt()).isEqualTo(CREATED.plusSeconds(30));
        assertThat(confirmed.updatedAt()).isEqualTo(confirmed.confirmedAt());
        assertThat(confirmed.confirm(CREATED.plusSeconds(60))).isEqualTo(confirmed);
        assertThat(original.status()).isEqualTo(OrderStatus.CREATED);
    }

    @Test
    void cancelsBeforeConfirmation() {
        Order cancelled = Order.create(UUID.randomUUID(), DESTINATION, PRICING, CREATED).cancel(CREATED.plusSeconds(10));

        assertThat(cancelled.status()).isEqualTo(OrderStatus.CANCELLED);
        assertThat(cancelled.confirmedAt()).isNull();
        assertThat(cancelled.cancelledAt()).isEqualTo(CREATED.plusSeconds(10));
        assertThat(cancelled.updatedAt()).isEqualTo(cancelled.cancelledAt());
        assertThat(cancelled.cancel(CREATED.plusSeconds(90))).isEqualTo(cancelled);
        assertThat(cancelled.pricing()).isSameAs(PRICING);
    }

    @Test
    void cancelsAfterConfirmationPreservingBothEvents() {
        Order confirmed = Order.create(UUID.randomUUID(), DESTINATION, PRICING, CREATED).confirm(CREATED.plusSeconds(10));
        Order cancelled = confirmed.cancel(CREATED.plusSeconds(20));

        assertThat(cancelled.confirmedAt()).isEqualTo(confirmed.confirmedAt());
        assertThat(cancelled.cancelledAt()).isEqualTo(CREATED.plusSeconds(20));
        assertThat(cancelled.pricing()).isSameAs(confirmed.pricing());
        assertThatThrownBy(() -> cancelled.confirm(CREATED.plusSeconds(30)))
                .isInstanceOf(OrderStateConflictException.class);
    }

    @Test
    void rejectsEventsBeforeThePreviousState() {
        Order order = Order.create(UUID.randomUUID(), DESTINATION, PRICING, CREATED);

        assertThatIllegalArgumentException().isThrownBy(() -> order.confirm(CREATED.minusSeconds(1)));
        assertThatIllegalArgumentException().isThrownBy(() -> order.cancel(CREATED.minusSeconds(1)));
        Order confirmed = order.confirm(CREATED.plusSeconds(20));
        assertThatIllegalArgumentException().isThrownBy(() -> confirmed.cancel(CREATED.plusSeconds(10)));
    }

    @ParameterizedTest
    @MethodSource("inconsistentStates")
    void rejectsRestoringInconsistentState(OrderStatus status, Instant updated, Instant confirmed, Instant cancelled) {
        assertThatIllegalArgumentException().isThrownBy(() -> new Order(UUID.randomUUID(), UUID.randomUUID(),
                DESTINATION, status, CREATED, updated, confirmed, cancelled));
    }

    static Stream<Arguments> inconsistentStates() {
        return Stream.of(
                Arguments.of(OrderStatus.CREATED, CREATED, CREATED, null),
                Arguments.of(OrderStatus.CREATED, CREATED.plusSeconds(1), null, null),
                Arguments.of(OrderStatus.CONFIRMED, CREATED, null, null),
                Arguments.of(OrderStatus.CONFIRMED, CREATED, CREATED, CREATED),
                Arguments.of(OrderStatus.CANCELLED, CREATED, null, null),
                Arguments.of(OrderStatus.CANCELLED, CREATED, CREATED.plusSeconds(1), CREATED));
    }

    @Test
    void requiresRestaurantDestinationPricingAndCreationTime() {
        assertThatNullPointerException().isThrownBy(() -> Order.create(null, DESTINATION, PRICING, CREATED));
        assertThatNullPointerException().isThrownBy(() -> Order.create(UUID.randomUUID(), null, PRICING, CREATED));
        assertThatNullPointerException().isThrownBy(() -> Order.create(UUID.randomUUID(), DESTINATION, null, CREATED));
        assertThatNullPointerException().isThrownBy(() -> Order.create(UUID.randomUUID(), DESTINATION, PRICING, null));
    }

    @Test
    void restoresLegacyOrdersWithoutInventingPrices() {
        var legacy = new Order(UUID.randomUUID(), UUID.randomUUID(), DESTINATION, OrderStatus.CREATED,
                CREATED, CREATED, null, null);

        assertThat(legacy.pricing()).isNull();
        Order confirmed = legacy.confirm(CREATED.plusSeconds(1));
        assertThat(confirmed.pricing()).isNull();
        assertThat(confirmed.cancel(CREATED.plusSeconds(2)).pricing()).isNull();
        assertThat(confirmed.requestDelivery(CREATED.plusSeconds(2)).pricing()).isNull();
    }

    @Test
    void restoresLegacyOrdersWithDeliveryIntents() {
        Instant confirmedAt = CREATED.plusSeconds(1);
        Instant requestedAt = CREATED.plusSeconds(2);
        var legacy = new Order(UUID.randomUUID(), UUID.randomUUID(), DESTINATION, OrderStatus.CONFIRMED,
                CREATED, requestedAt, confirmedAt, null, requestedAt);

        assertThat(legacy.pricing()).isNull();
        assertThat(legacy.requestDelivery(CREATED.plusSeconds(3))).isSameAs(legacy);
        assertThatThrownBy(() -> legacy.cancel(CREATED.plusSeconds(3)))
                .isInstanceOf(OrderStateConflictException.class);
    }
}
