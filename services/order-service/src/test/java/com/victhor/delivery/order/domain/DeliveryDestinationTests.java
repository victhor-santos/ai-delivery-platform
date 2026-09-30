package com.victhor.delivery.order.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

class DeliveryDestinationTests {

    @ParameterizedTest
    @CsvSource({ "0,0", "-90,-180", "90,180", "-23.55,-46.63" })
    void acceptsValidCoordinatesAndTrimsTheAddress(double latitude, double longitude) {
        var destination = new DeliveryDestination("  Rua Central, 10  ", latitude, longitude);

        assertThat(destination.address()).isEqualTo("Rua Central, 10");
        assertThat(destination.latitude()).isEqualTo(latitude);
        assertThat(destination.longitude()).isEqualTo(longitude);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = { " ", "\t\n", "\u2003" })
    void rejectsMissingOrBlankAddress(String address) {
        assertThatIllegalArgumentException().isThrownBy(() -> new DeliveryDestination(address, 0, 0));
    }

    @Test
    void limitsAddressLengthAfterTrimming() {
        assertThat(new DeliveryDestination("  " + "a".repeat(255) + "  ", 0, 0).address()).hasSize(255);
        assertThatIllegalArgumentException().isThrownBy(() -> new DeliveryDestination("a".repeat(256), 0, 0));
    }

    @ParameterizedTest
    @ValueSource(doubles = { -90.000001, 90.000001, Double.NaN, Double.NEGATIVE_INFINITY, Double.POSITIVE_INFINITY })
    void rejectsInvalidLatitude(double latitude) {
        assertThatIllegalArgumentException().isThrownBy(() -> new DeliveryDestination("Rua Central", latitude, 0));
    }

    @ParameterizedTest
    @ValueSource(doubles = { -180.000001, 180.000001, Double.NaN, Double.NEGATIVE_INFINITY, Double.POSITIVE_INFINITY })
    void rejectsInvalidLongitude(double longitude) {
        assertThatIllegalArgumentException().isThrownBy(() -> new DeliveryDestination("Rua Central", 0, longitude));
    }
}
