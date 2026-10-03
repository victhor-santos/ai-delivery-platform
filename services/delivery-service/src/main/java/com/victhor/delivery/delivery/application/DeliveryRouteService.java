package com.victhor.delivery.delivery.application;

import java.time.Clock;
import java.util.UUID;

public class DeliveryRouteService {

    private final DeliveryRouteRepository routes;
    private final RouteOptimizer optimizer;
    private final Clock clock;

    public DeliveryRouteService(DeliveryRouteRepository routes, RouteOptimizer optimizer, Clock clock) {
        this.routes = routes;
        this.optimizer = optimizer;
        this.clock = clock;
    }

    public DeliveryRoutePlan plan(UUID deliveryId, RouteContext context) {
        var snapshot = routes.findSnapshot(deliveryId).orElseThrow(DeliveryNotFoundException::new);
        snapshot.requirePlannable();
        var delivery = snapshot.delivery();
        var route = optimizer.optimizeRoute(delivery.origin().point(), delivery.destination().point(), context);
        try {
            route.validateEndpoints(delivery.origin().point(), delivery.destination().point());
        } catch (RuntimeException exception) {
            throw new RouteServiceUnavailableException(exception);
        }
        var plan = new DeliveryRoutePlan(UUID.randomUUID(), deliveryId, context.departureAt(), clock.instant(),
                snapshot.version() + 1, route);
        return routes.save(snapshot, plan);
    }

    public DeliveryRoutePlan findPlan(UUID deliveryId) {
        routes.findSnapshot(deliveryId).orElseThrow(DeliveryNotFoundException::new);
        return routes.findPlan(deliveryId).orElseThrow(RoutePlanNotFoundException::new);
    }
}
