package com.victhor.delivery.catalog.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

class PickupLocationTests {

    @ParameterizedTest
    @CsvSource({
            "-23.5505, -46.6333",
            "0, 0",
            "-90, -180",
            "90, 180"
    })
    void acceptsCoordinatesWithinGeographicBounds(double latitude, double longitude) {
        var location = new PickupLocation(latitude, longitude);

        assertThat(location.latitude()).isEqualTo(latitude);
        assertThat(location.longitude()).isEqualTo(longitude);
    }

    @ParameterizedTest
    @ValueSource(doubles = { -90.000001, 90.000001, Double.NaN,
            Double.NEGATIVE_INFINITY, Double.POSITIVE_INFINITY })
    void rejectsInvalidLatitude(double latitude) {
        assertThatIllegalArgumentException().isThrownBy(() -> new PickupLocation(latitude, -46.6333));
    }

    @ParameterizedTest
    @ValueSource(doubles = { -180.000001, 180.000001, Double.NaN,
            Double.NEGATIVE_INFINITY, Double.POSITIVE_INFINITY })
    void rejectsInvalidLongitude(double longitude) {
        assertThatIllegalArgumentException().isThrownBy(() -> new PickupLocation(-23.5505, longitude));
    }
}
