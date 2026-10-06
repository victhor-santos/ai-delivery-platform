package com.victhor.delivery.user.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import java.util.UUID;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

class UserAddressTests {

    private static final UUID USER_ID = UUID.randomUUID();

    @Test
    void createsAnAddressWithANewIdAndNormalizedTexts() {
        UserAddress address = UserAddress.create(USER_ID, "  Casa  ", "  Rua das Flores, 12  ", -23.55, -46.63);

        assertThat(address.id()).isNotNull();
        assertThat(address.userId()).isEqualTo(USER_ID);
        assertThat(address.label()).isEqualTo("Casa");
        assertThat(address.address()).isEqualTo("Rua das Flores, 12");
        assertThat(address.latitude()).isEqualTo(-23.55);
        assertThat(address.longitude()).isEqualTo(-46.63);
        assertThat(UserAddress.create(USER_ID, "Casa", "Rua das Flores, 12", -23.55, -46.63).id())
                .isNotEqualTo(address.id());
    }

    @Test
    void replacingDetailsPreservesBothIdsAndLeavesTheOriginalIntact() {
        UserAddress original = UserAddress.create(USER_ID, "Casa", "Rua A", 0, 0);

        UserAddress replacement = original.replace("  Trabalho  ", "  Rua B  ", 45, -90);

        assertThat(replacement.id()).isEqualTo(original.id());
        assertThat(replacement.userId()).isEqualTo(original.userId());
        assertThat(replacement.label()).isEqualTo("Trabalho");
        assertThat(replacement.address()).isEqualTo("Rua B");
        assertThat(replacement.latitude()).isEqualTo(45);
        assertThat(replacement.longitude()).isEqualTo(-90);
        assertThat(original.label()).isEqualTo("Casa");
        assertThat(original.address()).isEqualTo("Rua A");
        assertThat(original.latitude()).isZero();
        assertThat(original.longitude()).isZero();
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = { " ", "\t\n", "\u2003" })
    void rejectsMissingOrBlankLabels(String label) {
        assertThatIllegalArgumentException().isThrownBy(() -> UserAddress.create(USER_ID, label, "Rua A", 0, 0));
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = { " ", "\t\n", "\u2003" })
    void rejectsMissingOrBlankAddresses(String address) {
        assertThatIllegalArgumentException().isThrownBy(() -> UserAddress.create(USER_ID, "Casa", address, 0, 0));
    }

    @Test
    void enforcesTextLimitsAfterStrippingOuterWhitespace() {
        var address = UserAddress.create(USER_ID, "  " + "a".repeat(80) + "  ",
                "  " + "b".repeat(255) + "  ", 0, 0);

        assertThat(address.label()).hasSize(80);
        assertThat(address.address()).hasSize(255);
        assertThatIllegalArgumentException()
                .isThrownBy(() -> UserAddress.create(USER_ID, "a".repeat(81), "Rua A", 0, 0));
        assertThatIllegalArgumentException()
                .isThrownBy(() -> UserAddress.create(USER_ID, "Casa", "a".repeat(256), 0, 0));
    }

    @ParameterizedTest
    @MethodSource("validCoordinates")
    void acceptsInclusiveCoordinateBoundsAndZero(double latitude, double longitude) {
        var address = UserAddress.create(USER_ID, "Casa", "Rua A", latitude, longitude);

        assertThat(address.latitude()).isEqualTo(latitude);
        assertThat(address.longitude()).isEqualTo(longitude);
    }

    @ParameterizedTest
    @MethodSource("invalidCoordinates")
    void rejectsOutOfRangeOrNonFiniteCoordinates(double latitude, double longitude) {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> UserAddress.create(USER_ID, "Casa", "Rua A", latitude, longitude));
    }

    @Test
    void rejectsMissingAddressOrUserIdentity() {
        assertThatNullPointerException().isThrownBy(() -> new UserAddress(null, USER_ID, "Casa", "Rua A", 0, 0));
        assertThatNullPointerException()
                .isThrownBy(() -> new UserAddress(UUID.randomUUID(), null, "Casa", "Rua A", 0, 0));
    }

    static Stream<Arguments> validCoordinates() {
        return Stream.of(Arguments.of(-90, -180), Arguments.of(90, 180), Arguments.of(0, 0));
    }

    static Stream<Arguments> invalidCoordinates() {
        return Stream.of(Arguments.of(-90.000001, 0), Arguments.of(90.000001, 0),
                Arguments.of(0, -180.000001), Arguments.of(0, 180.000001),
                Arguments.of(Double.NaN, 0), Arguments.of(0, Double.NaN),
                Arguments.of(Double.POSITIVE_INFINITY, 0), Arguments.of(Double.NEGATIVE_INFINITY, 0),
                Arguments.of(0, Double.POSITIVE_INFINITY), Arguments.of(0, Double.NEGATIVE_INFINITY));
    }
}
