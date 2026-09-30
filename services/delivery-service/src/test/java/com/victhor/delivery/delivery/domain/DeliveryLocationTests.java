package com.victhor.delivery.delivery.domain;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

class DeliveryLocationTests {

    @ParameterizedTest
    @CsvSource({"-90,-180", "90,180", "0,0", "-23.55,-46.63"})
    void acceptsGeographicLimitsAndNormalizesDescription(double latitude, double longitude) {
        var location = new DeliveryLocation("  Restaurante Central  ", new GeoPoint(latitude, longitude));

        assertThat(location.description()).isEqualTo("Restaurante Central");
        assertThat(location.point().latitude()).isEqualTo(latitude);
        assertThat(location.point().longitude()).isEqualTo(longitude);
    }

    @ParameterizedTest
    @ValueSource(doubles = {-90.001, 90.001, Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY})
    void rejectsInvalidLatitude(double latitude) {
        assertThatIllegalArgumentException().isThrownBy(() -> new GeoPoint(latitude, 0));
    }

    @ParameterizedTest
    @ValueSource(doubles = {-180.001, 180.001, Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY})
    void rejectsInvalidLongitude(double longitude) {
        assertThatIllegalArgumentException().isThrownBy(() -> new GeoPoint(0, longitude));
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "\t", "\n"})
    void requiresDescription(String description) {
        assertThatIllegalArgumentException().isThrownBy(() -> new DeliveryLocation(description, new GeoPoint(0, 0)));
    }

    @Test
    void limitsNormalizedDescriptionAndRequiresCoordinates() {
        var point = new GeoPoint(0, 0);
        assertThat(new DeliveryLocation(" " + "x".repeat(255) + " ", point).description()).hasSize(255);
        assertThatIllegalArgumentException().isThrownBy(() -> new DeliveryLocation("x".repeat(256), point));
        assertThatNullPointerException().isThrownBy(() -> new DeliveryLocation("Restaurante", null));
    }
}
