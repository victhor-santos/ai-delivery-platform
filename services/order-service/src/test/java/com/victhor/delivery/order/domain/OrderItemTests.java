package com.victhor.delivery.order.domain;

import java.math.BigDecimal;
import java.util.UUID;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

class OrderItemTests {

    private static final UUID ITEM_ID = UUID.randomUUID();

    @Test
    void normalizesTheNameAndCalculatesAnExactLineTotal() {
        var item = new OrderItem(ITEM_ID, "  Prato executivo  ", 3, new BigDecimal("12.30"));

        assertThat(item.menuItemId()).isEqualTo(ITEM_ID);
        assertThat(item.name()).isEqualTo("Prato executivo");
        assertThat(item.quantity()).isEqualTo(3);
        assertThat(item.unitPrice()).isEqualTo(new BigDecimal("12.30"));
        assertThat(item.lineTotal()).isEqualTo(new BigDecimal("36.90"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"25", "25.0", "25.00", "25.000", "2.5E+1"})
    void canonicalizesPricesWithoutChangingTheirValue(String price) {
        var item = new OrderItem(ITEM_ID, "Prato", 1, new BigDecimal(price));

        assertThat(item.unitPrice()).isEqualTo(new BigDecimal("25.00"));
        assertThat(item.lineTotal()).isEqualTo(new BigDecimal("25.00"));
    }

    @ParameterizedTest
    @MethodSource("invalidPrices")
    void rejectsPricesThatWouldRequireRoundingOrExceedTheRange(BigDecimal price) {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new OrderItem(ITEM_ID, "Prato", 1, price));
    }

    static Stream<BigDecimal> invalidPrices() {
        return Stream.of(null, BigDecimal.ZERO, new BigDecimal("-0.01"), new BigDecimal("0.001"),
                new BigDecimal("25.999"), new BigDecimal("99999999.991"), new BigDecimal("100000000.00"));
    }

    @Test
    void supportsTheMaximumPriceAndQuantityWithoutOverflow() {
        var item = new OrderItem(ITEM_ID, "x".repeat(OrderItem.MAX_NAME_LENGTH), OrderItem.MAX_QUANTITY,
                OrderItem.MAX_UNIT_PRICE);

        assertThat(item.lineTotal()).isEqualTo(new BigDecimal("9899999999.01"));
        assertThat(new OrderItem(ITEM_ID, "Prato", 1, new BigDecimal("0.01")).lineTotal())
                .isEqualTo(new BigDecimal("0.01"));
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "\t\n"})
    void rejectsMissingNames(String name) {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new OrderItem(ITEM_ID, name, 1, BigDecimal.ONE));
    }

    @Test
    void rejectsAnOversizedNameAndMissingIdentity() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new OrderItem(ITEM_ID, "x".repeat(121), 1, BigDecimal.ONE));
        assertThatNullPointerException()
                .isThrownBy(() -> new OrderItem(null, "Prato", 1, BigDecimal.ONE));
    }

    @ParameterizedTest
    @ValueSource(ints = {-1, 0, 100, Integer.MAX_VALUE})
    void rejectsInvalidQuantities(int quantity) {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new OrderItem(ITEM_ID, "Prato", quantity, BigDecimal.ONE));
    }
}
