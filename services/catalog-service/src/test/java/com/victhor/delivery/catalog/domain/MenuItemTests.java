package com.victhor.delivery.catalog.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import java.math.BigDecimal;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

class MenuItemTests {

    private static final UUID RESTAURANT_ID = UUID.randomUUID();

    @Test
    void createsAnAvailableItemWithNewIdentityAndNormalizedDetails() {
        var menuItem = MenuItem.create(RESTAURANT_ID, "  Pizza de queijo  ", "  Molho de tomate e queijo  ",
                new BigDecimal("25"));

        assertThat(menuItem.id()).isNotNull();
        assertThat(menuItem.restaurantId()).isEqualTo(RESTAURANT_ID);
        assertThat(menuItem.name()).isEqualTo("Pizza de queijo");
        assertThat(menuItem.description()).isEqualTo("Molho de tomate e queijo");
        assertThat(menuItem.price()).isEqualTo(new BigDecimal("25.00"));
        assertThat(menuItem.available()).isTrue();
        assertThat(MenuItem.create(RESTAURANT_ID, menuItem.name(), null, menuItem.price()).id())
                .isNotEqualTo(menuItem.id());
    }

    @Test
    void restoresIdentityOwnershipAndUnavailableState() {
        UUID id = UUID.randomUUID();

        var menuItem = new MenuItem(id, RESTAURANT_ID, "Pizza", null, new BigDecimal("25.50"), false);

        assertThat(menuItem.id()).isEqualTo(id);
        assertThat(menuItem.restaurantId()).isEqualTo(RESTAURANT_ID);
        assertThat(menuItem.available()).isFalse();
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = { " ", "\t\n", "\u2003" })
    void rejectsMissingOrBlankNames(String name) {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> MenuItem.create(RESTAURANT_ID, name, null, new BigDecimal("25.50")));
    }

    @Test
    void validatesTheNameLengthAfterRemovingSurroundingWhitespace() {
        String maximumName = "a".repeat(MenuItem.MAX_NAME_LENGTH);

        assertThat(MenuItem.create(RESTAURANT_ID, "  " + maximumName + "  ", null,
                new BigDecimal("25.50")).name()).isEqualTo(maximumName);
        assertThatIllegalArgumentException().isThrownBy(() -> MenuItem.create(RESTAURANT_ID,
                maximumName + "a", null, new BigDecimal("25.50")));
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = { " ", "\t\n", "\u2003" })
    void normalizesAnAbsentOrBlankDescriptionToNull(String description) {
        assertThat(MenuItem.create(RESTAURANT_ID, "Pizza", description, new BigDecimal("25.50"))
                .description()).isNull();
    }

    @Test
    void validatesTheDescriptionLengthAfterRemovingSurroundingWhitespace() {
        String maximumDescription = "a".repeat(MenuItem.MAX_DESCRIPTION_LENGTH);

        assertThat(MenuItem.create(RESTAURANT_ID, "Pizza", "  " + maximumDescription + "  ",
                new BigDecimal("25.50")).description()).isEqualTo(maximumDescription);
        assertThatIllegalArgumentException().isThrownBy(() -> MenuItem.create(RESTAURANT_ID, "Pizza",
                maximumDescription + "a", new BigDecimal("25.50")));
    }

    @ParameterizedTest
    @CsvSource({ "0.01,0.01", "25,25.00", "25.5,25.50", "25.5000,25.50", "1e2,100.00",
            "1e-2,0.01", "99999999.99,99999999.99", "99999999.99000,99999999.99" })
    void preservesTheExactValueAndNormalizesAcceptedPricesToCents(String supplied, String expected) {
        var menuItem = MenuItem.create(RESTAURANT_ID, "Pizza", null, new BigDecimal(supplied));

        assertThat(menuItem.price()).isEqualTo(new BigDecimal(expected));
        assertThat(menuItem.price().scale()).isEqualTo(2);
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = { "0", "0.00", "-0.01", "-25", "0.001", "25.501", "99999999.991",
            "100000000", "1e-1000000000", "1e1000000000" })
    void rejectsMissingNonPositiveOverlargeAndFractionalCentPrices(String supplied) {
        BigDecimal price = supplied == null ? null : new BigDecimal(supplied);

        assertThatIllegalArgumentException()
                .isThrownBy(() -> MenuItem.create(RESTAURANT_ID, "Pizza", null, price));
    }

    @Test
    void rejectsMissingIdentityOrRestaurantOwnership() {
        var price = new BigDecimal("25.50");

        assertThatNullPointerException()
                .isThrownBy(() -> new MenuItem(null, RESTAURANT_ID, "Pizza", null, price, true));
        assertThatNullPointerException()
                .isThrownBy(() -> new MenuItem(UUID.randomUUID(), null, "Pizza", null, price, true));
    }
}
