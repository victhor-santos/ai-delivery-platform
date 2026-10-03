package com.victhor.delivery.delivery.domain;

import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SegmentTraversalTests {

    private static final Instant ENTERED = Instant.parse("2026-10-03T12:00:00Z");
    private static final UUID DELIVERY = UUID.randomUUID();
    private static final UUID PLAN = UUID.randomUUID();

    @Test
    void incompleteTraversalHasNoInventedTarget() {
        var entry = enter(ENTERED.plusSeconds(20));
        assertThat(entry.exitedAt()).isNull();
        assertThat(entry.labelAvailableAt()).isNull();
        assertThat(entry.actualTravelTimeMinutes()).isNull();
    }

    @Test
    void delayedExitUsesServerAvailabilityAndRepeatsWithoutChangingIt() {
        var entry = enter(ENTERED.plusSeconds(20));
        var completed = entry.exit(ENTERED.plusSeconds(90), ENTERED.plusSeconds(300));
        assertThat(completed.actualTravelTimeMinutes()).isEqualTo(1.5);
        assertThat(completed.labelAvailableAt()).isEqualTo(ENTERED.plusSeconds(300));
        assertThat(completed.exit(ENTERED.plusSeconds(90), ENTERED.plusSeconds(900))).isSameAs(completed);
        assertThatThrownBy(() -> completed.exit(ENTERED.plusSeconds(91), ENTERED.plusSeconds(900)))
                .isInstanceOf(DeliveryStateConflictException.class);
    }

    @ParameterizedTest
    @ValueSource(ints = {-1, 199, Integer.MAX_VALUE})
    void rejectsUnsupportedSequence(int sequence) {
        assertThatThrownBy(() -> SegmentTraversal.enter(DELIVERY, PLAN, sequence, "simulated", ENTERED, ENTERED))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"real", "synthetic", "", "SIMULATED"})
    void requiresExplicitSimulationProvenance(String origin) {
        assertThatThrownBy(() -> SegmentTraversal.enter(DELIVERY, PLAN, 0, origin, ENTERED, ENTERED))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void normalizesEventPrecisionBeforeIdempotencyComparison() {
        var entry = SegmentTraversal.enter(DELIVERY, PLAN, 0, "simulated", ENTERED.plusNanos(123456789), ENTERED.plusSeconds(1));
        assertThat(entry.enteredAt()).isEqualTo(ENTERED.plusNanos(123456000));
        assertThat(entry.matchesEntry(PLAN, "simulated", ENTERED.plusNanos(123456999))).isTrue();
        assertThat(entry.matchesEntry(UUID.randomUUID(), "simulated", entry.enteredAt())).isFalse();
    }

    @Test
    void rejectsFutureEntryNonpositiveDurationAndBackdatedAvailability() {
        assertThatThrownBy(() -> enter(ENTERED.minusSeconds(1))).isInstanceOf(IllegalArgumentException.class);
        var entry = enter(ENTERED.plusSeconds(20));
        assertThatThrownBy(() -> entry.exit(ENTERED, ENTERED.plusSeconds(30))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> entry.exit(ENTERED.minusSeconds(1), ENTERED.plusSeconds(30))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> entry.exit(ENTERED.plusSeconds(30), ENTERED.plusSeconds(29))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> entry.exit(ENTERED.plusSeconds(1), ENTERED.plusSeconds(2))).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void durationSupportsLongHistoricalIntervalsWithoutNanosecondOverflow() {
        var exited = Instant.parse("2500-10-03T12:00:00Z");
        assertThat(enter(ENTERED).exit(exited, exited).actualTravelTimeMinutes()).isPositive().isFinite();
    }

    private SegmentTraversal enter(Instant recordedAt) {
        return SegmentTraversal.enter(DELIVERY, PLAN, 0, "simulated", ENTERED, recordedAt);
    }
}
