package com.victhor.delivery.order.domain;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OrderPricingTests {

    @Test
    void derivesTheTotalFromTheSnapshotsUsingCents() {
        var first = item(3, "0.10");
        var second = item(1, "0.20");
        var pricing = new OrderPricing(List.of(first, second));

        assertThat(pricing.items()).containsExactly(first, second);
        assertThat(pricing.total()).isEqualTo(new BigDecimal("0.50"));
    }

    @Test
    void copiesTheInputAndExposesAnImmutableComposition() {
        var first = item(1, "15.00");
        var source = new ArrayList<>(List.of(first));
        var pricing = new OrderPricing(source);
        source.add(item(1, "5.00"));

        assertThat(pricing.items()).containsExactly(first);
        assertThat(pricing.total()).isEqualTo(new BigDecimal("15.00"));
        assertThatThrownBy(() -> pricing.items().clear()).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void restoresMatchingTotalsToCanonicalCents() {
        var pricing = new OrderPricing(List.of(item(2, "12.50")), new BigDecimal("25.000"));

        assertThat(pricing.total()).isEqualTo(new BigDecimal("25.00"));
    }

    @ParameterizedTest
    @MethodSource("inconsistentTotals")
    void rejectsRestoringAnInconsistentTotal(BigDecimal total) {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new OrderPricing(List.of(item(2, "12.50")), total));
    }

    static Stream<BigDecimal> inconsistentTotals() {
        return Stream.of(null, BigDecimal.ZERO, new BigDecimal("24.99"), new BigDecimal("25.01"),
                new BigDecimal("25.001"), OrderPricing.MAX_TOTAL.add(new BigDecimal("0.01")));
    }

    @Test
    void supportsTheMaximumCompositionAndRejectsOneAdditionalLine() {
        var items = IntStream.range(0, OrderPricing.MAX_ITEMS)
                .mapToObj(index -> item(OrderItem.MAX_QUANTITY, OrderItem.MAX_UNIT_PRICE.toPlainString())).toList();

        assertThat(new OrderPricing(items).total()).isEqualTo(OrderPricing.MAX_TOTAL);
        var oversized = new ArrayList<>(items);
        oversized.add(item(1, "0.01"));
        assertThatIllegalArgumentException().isThrownBy(() -> new OrderPricing(oversized));
    }

    @Test
    void rejectsMissingEmptyOrNullCompositions() {
        assertThatNullPointerException().isThrownBy(() -> new OrderPricing(null));
        assertThatIllegalArgumentException().isThrownBy(() -> new OrderPricing(List.of()));
        assertThatNullPointerException().isThrownBy(() -> new OrderPricing(Arrays.asList(item(1, "1.00"), null)));
    }

    @Test
    void rejectsDuplicateMenuItemsEvenWithDifferentSnapshots() {
        var first = item(1, "10.00");
        var duplicate = new OrderItem(first.menuItemId(), "Outro nome", 2, new BigDecimal("20.00"));

        assertThatIllegalArgumentException().isThrownBy(() -> new OrderPricing(List.of(first, duplicate)));
    }

    private static OrderItem item(int quantity, String price) {
        return new OrderItem(UUID.randomUUID(), "Prato", quantity, new BigDecimal(price));
    }
}
