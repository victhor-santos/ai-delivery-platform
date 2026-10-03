package com.victhor.delivery.delivery.application;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Stream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.victhor.delivery.delivery.domain.Courier;
import com.victhor.delivery.delivery.domain.Delivery;
import com.victhor.delivery.delivery.domain.DeliveryLocation;
import com.victhor.delivery.delivery.domain.DeliveryStateConflictException;
import com.victhor.delivery.delivery.domain.GeoPoint;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DeliveryRouteServiceTests {

    private static final Instant NOW = Instant.parse("2026-10-03T12:00:00.123456789Z");
    private static final RouteContext CONTEXT = new RouteContext(NOW.plusSeconds(60));
    private static final GeoPoint ORIGIN = new GeoPoint(-23.5505, -46.6333);
    private static final GeoPoint DESTINATION = new GeoPoint(-23.561, -46.656);

    @Mock
    private DeliveryRouteRepository routes;
    @Mock
    private RouteOptimizer optimizer;
    private DeliveryRouteService service;
    private Delivery delivery;
    private DeliveryRoutingSnapshot snapshot;

    @BeforeEach
    void prepare() {
        service = new DeliveryRouteService(routes, optimizer, Clock.fixed(NOW, ZoneOffset.UTC));
        delivery = Delivery.create(UUID.randomUUID(), new DeliveryLocation("Restaurant", ORIGIN),
                new DeliveryLocation("Destination", DESTINATION), NOW.minusSeconds(30));
        snapshot = new DeliveryRoutingSnapshot(delivery, 7);
    }

    @Test
    void readsCallsRemoteThenSavesWithSnapshotVersionAndServerTime() {
        when(routes.findSnapshot(delivery.id())).thenReturn(Optional.of(snapshot));
        when(optimizer.optimizeRoute(ORIGIN, DESTINATION, CONTEXT)).thenReturn(route());
        when(routes.save(any(), any())).thenAnswer(invocation -> invocation.getArgument(1));
        var plan = service.plan(delivery.id(), CONTEXT);
        assertThat(plan.deliveryId()).isEqualTo(delivery.id());
        assertThat(plan.deliveryVersion()).isEqualTo(8);
        assertThat(plan.departureAt()).isEqualTo(CONTEXT.departureAt());
        assertThat(plan.plannedAt()).isEqualTo(Instant.parse("2026-10-03T12:00:00.123456Z"));
        assertThat(plan.optimizedRoute()).isEqualTo(route());
        var order = inOrder(routes, optimizer);
        order.verify(routes).findSnapshot(delivery.id());
        order.verify(optimizer).optimizeRoute(ORIGIN, DESTINATION, CONTEXT);
        order.verify(routes).save(snapshot, plan);
        assertThat(delivery.updatedAt()).isEqualTo(delivery.createdAt());
    }

    @Test
    void neverCallsOptimizerForMissingDelivery() {
        assertThatThrownBy(() -> service.plan(UUID.randomUUID(), CONTEXT)).isInstanceOf(DeliveryNotFoundException.class);
        verifyNoInteractions(optimizer);
        verify(routes, never()).save(any(), any());
    }

    @ParameterizedTest
    @MethodSource("remoteFailures")
    void preservesExistingPlanWhenOptimizationFails(RuntimeException failure) {
        when(routes.findSnapshot(delivery.id())).thenReturn(Optional.of(snapshot));
        when(optimizer.optimizeRoute(ORIGIN, DESTINATION, CONTEXT)).thenThrow(failure);
        assertThatThrownBy(() -> service.plan(delivery.id(), CONTEXT)).isSameAs(failure);
        verify(routes, never()).save(any(), any());
    }

    static Stream<RuntimeException> remoteFailures() {
        return Stream.of(new RouteServiceUnavailableException(), new RouteNotFoundException(), new UnsupportedRouteLocationException());
    }

    @Test
    void rejectsUnrelatedRemoteRouteAndPropagatesStaleSaveWithoutRetry() {
        when(routes.findSnapshot(delivery.id())).thenReturn(Optional.of(snapshot));
        var unrelated = new OptimizedRoute(List.of(new GeoPoint(0, 0)), List.of(), 0, 0, NOW, NOW, "v1", "v1", "synthetic");
        when(optimizer.optimizeRoute(ORIGIN, DESTINATION, CONTEXT)).thenReturn(unrelated);
        assertThatThrownBy(() -> service.plan(delivery.id(), CONTEXT)).isInstanceOf(RouteServiceUnavailableException.class);
        verify(routes, never()).save(any(), any());
        when(optimizer.optimizeRoute(ORIGIN, DESTINATION, CONTEXT)).thenReturn(route());
        when(routes.save(any(), any())).thenThrow(new StaleRoutePlanException());
        assertThatThrownBy(() -> service.plan(delivery.id(), CONTEXT)).isInstanceOf(StaleRoutePlanException.class);
    }

    @Test
    void allowsPlanningUntilDepartureAndRejectsInTransitAndTerminalStates() {
        when(routes.findSnapshot(delivery.id())).thenReturn(Optional.of(snapshot));
        when(optimizer.optimizeRoute(ORIGIN, DESTINATION, CONTEXT)).thenReturn(route());
        when(routes.save(any(), any())).thenAnswer(invocation -> invocation.getArgument(1));
        var courier = new Courier(UUID.randomUUID(), true);
        delivery.assign(courier, NOW.minusSeconds(20));
        service.plan(delivery.id(), CONTEXT);
        delivery.pickUp(NOW.minusSeconds(10));
        service.plan(delivery.id(), CONTEXT);
        delivery.startTransit(NOW);
        assertThatThrownBy(() -> service.plan(delivery.id(), CONTEXT)).isInstanceOf(DeliveryStateConflictException.class);
        delivery.arrive(NOW);
        delivery.complete(NOW);
        assertThatThrownBy(() -> service.plan(delivery.id(), CONTEXT)).isInstanceOf(DeliveryStateConflictException.class);
        var cancelled = Delivery.create(UUID.randomUUID(), delivery.origin(), delivery.destination(), NOW);
        cancelled.cancel(NOW);
        when(routes.findSnapshot(cancelled.id())).thenReturn(Optional.of(new DeliveryRoutingSnapshot(cancelled, 1)));
        assertThatThrownBy(() -> service.plan(cancelled.id(), CONTEXT)).isInstanceOf(DeliveryStateConflictException.class);
    }

    @Test
    void readsStoredPlanWithoutCallingPythonAndDistinguishesMissingResources() {
        when(routes.findSnapshot(delivery.id())).thenReturn(Optional.of(snapshot));
        assertThatThrownBy(() -> service.findPlan(delivery.id())).isInstanceOf(RoutePlanNotFoundException.class);
        assertThatThrownBy(() -> service.findPlan(UUID.randomUUID())).isInstanceOf(DeliveryNotFoundException.class);
        var stored = new DeliveryRoutePlan(UUID.randomUUID(), delivery.id(), CONTEXT.departureAt(), NOW, 7, route());
        when(routes.findPlan(delivery.id())).thenReturn(Optional.of(stored));
        assertThat(service.findPlan(delivery.id())).isEqualTo(stored);
        verifyNoInteractions(optimizer);
    }

    private OptimizedRoute route() {
        return new OptimizedRoute(List.of(ORIGIN, DESTINATION), List.of(new RouteSegment("A-C", 2.9, 16)),
                2.9, 16, NOW, NOW.minusSeconds(60), "model-v1", "graph-v1", "synthetic");
    }
}
