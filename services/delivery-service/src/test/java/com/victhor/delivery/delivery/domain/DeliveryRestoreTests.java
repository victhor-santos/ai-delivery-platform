package com.victhor.delivery.delivery.domain;

import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DeliveryRestoreTests {

    private static final Instant TIME = Instant.parse("2026-10-01T10:00:00Z");
    private static final DeliveryLocation LOCATION = new DeliveryLocation("Local", new GeoPoint(0, 0));
    private static final UUID COURIER_ID = UUID.randomUUID();

    @ParameterizedTest
    @EnumSource(DeliveryFixtures.Stage.class)
    void restoresAllReachableStatesAndPreservesIdentityAndHistory(DeliveryFixtures.Stage stage) {
        Delivery original = DeliveryFixtures.at(stage);
        Delivery restored = Delivery.restore(original.id(), original.orderId(), original.origin(), original.destination(),
                original.courierId(), original.status(), original.createdAt(), original.updatedAt(), original.assignedAt(),
                original.pickedUpAt(), original.departedAt(), original.arrivedAt(), original.deliveredAt(), original.cancelledAt());

        assertThat(restored).usingRecursiveComparison().isEqualTo(original);
    }

    @Test
    void rejectsMismatchedStatusTimestampAndCourier() {
        assertThatIllegalArgumentException().isThrownBy(() -> restore(DeliveryStatus.ASSIGNED, TIME, null, null, null, null, null, null, null));
        assertThatIllegalArgumentException().isThrownBy(() -> restore(DeliveryStatus.CREATED, TIME.plusSeconds(1), null, null, null, null, null, null, null));
        assertThatIllegalArgumentException().isThrownBy(() -> restore(DeliveryStatus.CREATED, TIME, COURIER_ID, null, null, null, null, null, null));
        assertThatIllegalArgumentException().isThrownBy(() -> restore(DeliveryStatus.ASSIGNED, TIME, null, TIME, null, null, null, null, null));
        assertThatIllegalArgumentException().isThrownBy(() -> restore(DeliveryStatus.ASSIGNED, TIME, COURIER_ID, TIME.minusSeconds(1), null, null, null, null, null));
    }

    @Test
    void rejectsMissingEventsAndCancellationAfterPickup() {
        assertThatThrownBy(() -> restore(DeliveryStatus.IN_TRANSIT, TIME, null, null, null, TIME, null, null, null))
                .isInstanceOf(DeliveryStateConflictException.class);
        assertThatThrownBy(() -> restore(DeliveryStatus.DELIVERED, TIME, COURIER_ID, TIME, TIME, TIME, null, TIME, null))
                .isInstanceOf(DeliveryStateConflictException.class);
        assertThatThrownBy(() -> restore(DeliveryStatus.CANCELLED, TIME, COURIER_ID, TIME, TIME, null, null, null, TIME))
                .isInstanceOf(DeliveryStateConflictException.class);
    }

    private Delivery restore(DeliveryStatus status, Instant updated, UUID courierId, Instant assigned, Instant pickedUp,
            Instant departed, Instant arrived, Instant delivered, Instant cancelled) {
        return Delivery.restore(UUID.randomUUID(), UUID.randomUUID(), LOCATION, LOCATION, courierId, status, TIME,
                updated, assigned, pickedUp, departed, arrived, delivered, cancelled);
    }
}
