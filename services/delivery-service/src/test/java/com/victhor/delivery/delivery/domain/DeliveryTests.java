package com.victhor.delivery.delivery.domain;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DeliveryTests {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-09-30T15:00:00Z"), ZoneOffset.UTC);
    private static final Instant CREATED = CLOCK.instant();
    private static final DeliveryLocation ORIGIN = new DeliveryLocation("Restaurante Central", new GeoPoint(-23.55, -46.63));
    private static final DeliveryLocation DESTINATION = new DeliveryLocation("Rua das Flores, 42", new GeoPoint(-23.56, -46.64));
    private static final Courier COURIER = new Courier(UUID.randomUUID(), true);

    @Test
    void createsWithGeneratedIdentityAndImmutableLocationSnapshots() {
        UUID orderId = UUID.randomUUID();
        Delivery delivery = Delivery.create(orderId, ORIGIN, DESTINATION, CREATED);

        assertThat(delivery.id()).isNotNull().isNotEqualTo(newDelivery().id());
        assertThat(delivery.orderId()).isEqualTo(orderId);
        assertThat(delivery.origin()).isEqualTo(ORIGIN);
        assertThat(delivery.destination()).isEqualTo(DESTINATION);
        assertThat(delivery.status()).isEqualTo(DeliveryStatus.CREATED);
        assertThat(delivery.createdAt()).isEqualTo(CREATED);
        assertThat(delivery.updatedAt()).isEqualTo(CREATED);
        assertThat(delivery.courierId()).isNull();
        assertThat(delivery.assignedAt()).isNull();
        assertThat(delivery.pickedUpAt()).isNull();
        assertThat(delivery.departedAt()).isNull();
        assertThat(delivery.arrivedAt()).isNull();
        assertThat(delivery.deliveredAt()).isNull();
        assertThat(delivery.cancelledAt()).isNull();
    }

    @Test
    void recordsTheLifecycleSeparatingTravelFromServiceTime() {
        Delivery delivery = newDelivery();
        UUID id = delivery.id();
        UUID orderId = delivery.orderId();
        delivery.assign(COURIER, CREATED.plusSeconds(10));
        delivery.pickUp(CREATED.plusSeconds(20));
        delivery.startTransit(CREATED.plusSeconds(30));
        delivery.arrive(CREATED.plusSeconds(90));

        assertThat(delivery.status()).isEqualTo(DeliveryStatus.IN_TRANSIT);
        assertThat(delivery.updatedAt()).isEqualTo(CREATED.plusSeconds(90));
        assertThat(delivery.deliveredAt()).isNull();

        delivery.complete(CREATED.plusSeconds(120));

        assertThat(delivery.id()).isEqualTo(id);
        assertThat(delivery.orderId()).isEqualTo(orderId);
        assertThat(delivery.origin()).isEqualTo(ORIGIN);
        assertThat(delivery.destination()).isEqualTo(DESTINATION);
        assertThat(delivery.courierId()).isEqualTo(COURIER.id());
        assertThat(delivery.status()).isEqualTo(DeliveryStatus.DELIVERED);
        assertThat(delivery.createdAt()).isEqualTo(CREATED);
        assertThat(delivery.assignedAt()).isEqualTo(CREATED.plusSeconds(10));
        assertThat(delivery.pickedUpAt()).isEqualTo(CREATED.plusSeconds(20));
        assertThat(delivery.departedAt()).isEqualTo(CREATED.plusSeconds(30));
        assertThat(delivery.arrivedAt()).isEqualTo(CREATED.plusSeconds(90));
        assertThat(delivery.deliveredAt()).isEqualTo(CREATED.plusSeconds(120));
        assertThat(delivery.updatedAt()).isEqualTo(delivery.deliveredAt());
        assertThat(delivery.cancelledAt()).isNull();
    }

    @ParameterizedTest
    @MethodSource("invalidCommands")
    void rejectsInvalidTransitionsWithoutChangingAnyField(Stage stage, Command command) {
        Delivery delivery = at(stage);
        List<Object> before = snapshot(delivery);

        assertThatThrownBy(() -> command.apply(delivery, CREATED.plusSeconds(100)))
                .isInstanceOf(DeliveryStateConflictException.class);
        assertThat(snapshot(delivery)).isEqualTo(before);
    }

    static Stream<Arguments> invalidCommands() {
        return Arrays.stream(Stage.values()).flatMap(stage -> Arrays.stream(Command.values())
                .filter(command -> !allowed(stage, command)).map(command -> Arguments.of(stage, command)));
    }

    private static boolean allowed(Stage stage, Command command) {
        return switch (stage) {
            case CREATED -> command == Command.ASSIGN || command == Command.CANCEL;
            case ASSIGNED -> command == Command.PICK_UP || command == Command.CANCEL;
            case PICKED_UP -> command == Command.START_TRANSIT;
            case IN_TRANSIT -> command == Command.ARRIVE;
            case ARRIVED -> command == Command.COMPLETE;
            case DELIVERED, CANCELLED_UNASSIGNED, CANCELLED_ASSIGNED -> false;
        };
    }

    @ParameterizedTest
    @MethodSource("validCommands")
    void rejectsMissingOrBackdatedEventTimesWithoutChangingAnyField(Stage stage, Command command) {
        Delivery delivery = at(stage);
        List<Object> before = snapshot(delivery);

        assertThatNullPointerException().isThrownBy(() -> command.apply(delivery, null));
        assertThatIllegalArgumentException().isThrownBy(() -> command.apply(delivery, delivery.updatedAt().minusNanos(1)));
        assertThat(snapshot(delivery)).isEqualTo(before);
    }

    @ParameterizedTest
    @MethodSource("validCommands")
    void allowsEqualEventTimes(Stage stage, Command command) {
        Delivery delivery = at(stage);
        Instant time = delivery.updatedAt();
        command.apply(delivery, time);
        assertThat(delivery.updatedAt()).isEqualTo(time);
    }

    static Stream<Arguments> validCommands() {
        return Arrays.stream(Stage.values()).flatMap(stage -> Arrays.stream(Command.values())
                .filter(command -> allowed(stage, command)).map(command -> Arguments.of(stage, command)));
    }

    @ParameterizedTest
    @EnumSource(value = Stage.class, names = {"CREATED", "ASSIGNED"})
    void cancellationPreservesAssignmentHistory(Stage stage) {
        Delivery delivery = at(stage);
        UUID courierId = delivery.courierId();
        Instant assignedAt = delivery.assignedAt();

        delivery.cancel(CREATED.plusSeconds(100));

        assertThat(delivery.status()).isEqualTo(DeliveryStatus.CANCELLED);
        assertThat(delivery.courierId()).isEqualTo(courierId);
        assertThat(delivery.assignedAt()).isEqualTo(assignedAt);
        assertThat(delivery.cancelledAt()).isEqualTo(CREATED.plusSeconds(100));
        assertThat(delivery.updatedAt()).isEqualTo(delivery.cancelledAt());
        assertThat(delivery.pickedUpAt()).isNull();
        assertThat(delivery.departedAt()).isNull();
        assertThat(delivery.arrivedAt()).isNull();
        assertThat(delivery.deliveredAt()).isNull();
    }

    @Test
    void rejectsInactiveOrMissingCourierWithoutAssigning() {
        Delivery delivery = newDelivery();
        List<Object> before = snapshot(delivery);

        assertThatThrownBy(() -> delivery.assign(new Courier(UUID.randomUUID(), false), CREATED))
                .isInstanceOf(DeliveryStateConflictException.class);
        assertThatNullPointerException().isThrownBy(() -> delivery.assign(null, CREATED));
        assertThat(snapshot(delivery)).isEqualTo(before);
    }

    @Test
    void requiresOrderLocationsAndCreationTime() {
        assertThatNullPointerException().isThrownBy(() -> Delivery.create(null, ORIGIN, DESTINATION, CREATED));
        assertThatNullPointerException().isThrownBy(() -> Delivery.create(UUID.randomUUID(), null, DESTINATION, CREATED));
        assertThatNullPointerException().isThrownBy(() -> Delivery.create(UUID.randomUUID(), ORIGIN, null, CREATED));
        assertThatNullPointerException().isThrownBy(() -> Delivery.create(UUID.randomUUID(), ORIGIN, DESTINATION, null));
        assertThatNullPointerException().isThrownBy(() -> new Courier(null, true));
    }

    private static Delivery newDelivery() {
        return Delivery.create(UUID.randomUUID(), ORIGIN, DESTINATION, CREATED);
    }

    private static Delivery at(Stage stage) {
        Delivery delivery = newDelivery();
        if (stage == Stage.CREATED) {
            return delivery;
        }
        if (stage == Stage.CANCELLED_UNASSIGNED) {
            delivery.cancel(CREATED.plusSeconds(1));
            return delivery;
        }
        delivery.assign(COURIER, CREATED.plusSeconds(1));
        if (stage == Stage.ASSIGNED) {
            return delivery;
        }
        if (stage == Stage.CANCELLED_ASSIGNED) {
            delivery.cancel(CREATED.plusSeconds(2));
            return delivery;
        }
        delivery.pickUp(CREATED.plusSeconds(2));
        if (stage == Stage.PICKED_UP) {
            return delivery;
        }
        delivery.startTransit(CREATED.plusSeconds(3));
        if (stage == Stage.IN_TRANSIT) {
            return delivery;
        }
        delivery.arrive(CREATED.plusSeconds(4));
        if (stage == Stage.ARRIVED) {
            return delivery;
        }
        delivery.complete(CREATED.plusSeconds(5));
        return delivery;
    }

    private static List<Object> snapshot(Delivery delivery) {
        return Arrays.asList(delivery.id(), delivery.orderId(), delivery.origin(), delivery.destination(),
                delivery.courierId(), delivery.status(), delivery.createdAt(), delivery.updatedAt(),
                delivery.assignedAt(), delivery.pickedUpAt(), delivery.departedAt(), delivery.arrivedAt(),
                delivery.deliveredAt(), delivery.cancelledAt());
    }

    enum Stage {
        CREATED, ASSIGNED, PICKED_UP, IN_TRANSIT, ARRIVED, DELIVERED, CANCELLED_UNASSIGNED, CANCELLED_ASSIGNED
    }

    enum Command {
        ASSIGN, PICK_UP, START_TRANSIT, ARRIVE, COMPLETE, CANCEL;

        void apply(Delivery delivery, Instant time) {
            switch (this) {
                case ASSIGN -> delivery.assign(COURIER, time);
                case PICK_UP -> delivery.pickUp(time);
                case START_TRANSIT -> delivery.startTransit(time);
                case ARRIVE -> delivery.arrive(time);
                case COMPLETE -> delivery.complete(time);
                case CANCEL -> delivery.cancel(time);
            }
        }
    }
}
