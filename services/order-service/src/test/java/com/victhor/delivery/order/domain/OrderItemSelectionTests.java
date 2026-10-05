package com.victhor.delivery.order.domain;

import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

class OrderItemSelectionTests {

    @Test
    void acceptsTheMinimumAndMaximumQuantity() {
        UUID id = UUID.randomUUID();

        assertThat(new OrderItemSelection(id, 1).menuItemId()).isEqualTo(id);
        assertThat(new OrderItemSelection(id, OrderItem.MAX_QUANTITY).quantity()).isEqualTo(99);
    }

    @ParameterizedTest
    @ValueSource(ints = {-1, 0, 100, Integer.MAX_VALUE})
    void rejectsInvalidQuantities(int quantity) {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new OrderItemSelection(UUID.randomUUID(), quantity));
    }

    @Test
    void requiresMenuItemIdentity() {
        assertThatNullPointerException().isThrownBy(() -> new OrderItemSelection(null, 1));
    }
}
