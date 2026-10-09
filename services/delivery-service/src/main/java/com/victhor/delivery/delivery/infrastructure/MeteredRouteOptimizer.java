package com.victhor.delivery.delivery.infrastructure;

import com.victhor.delivery.delivery.application.OptimizedRoute;
import com.victhor.delivery.delivery.application.RouteContext;
import com.victhor.delivery.delivery.application.RouteNotFoundException;
import com.victhor.delivery.delivery.application.RouteOptimizer;
import com.victhor.delivery.delivery.application.RouteServiceUnavailableException;
import com.victhor.delivery.delivery.application.UnsupportedRouteLocationException;
import com.victhor.delivery.delivery.domain.GeoPoint;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;

/** Records the latency and outcome of each route calculation requested from Route Intelligence. */
public class MeteredRouteOptimizer implements RouteOptimizer {

    static final String METRIC = "delivery.route.optimization";

    private final RouteOptimizer delegate;
    private final MeterRegistry registry;

    public MeteredRouteOptimizer(RouteOptimizer delegate, MeterRegistry registry) {
        this.delegate = delegate;
        this.registry = registry;
    }

    @Override
    public OptimizedRoute optimizeRoute(GeoPoint origin, GeoPoint destination, RouteContext context) {
        var sample = Timer.start(registry);
        String outcome = "error";
        try {
            var route = delegate.optimizeRoute(origin, destination, context);
            outcome = "success";
            return route;
        } catch (UnsupportedRouteLocationException exception) {
            outcome = "outside_coverage";
            throw exception;
        } catch (RouteNotFoundException exception) {
            outcome = "route_not_found";
            throw exception;
        } catch (RouteServiceUnavailableException exception) {
            outcome = "unavailable";
            throw exception;
        } finally {
            sample.stop(Timer.builder(METRIC).description("Route calculations requested from Route Intelligence")
                    .tag("outcome", outcome).register(registry));
        }
    }
}
