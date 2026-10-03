package com.victhor.delivery.delivery.application;

import com.victhor.delivery.delivery.domain.GeoPoint;

public interface RouteOptimizer {

    OptimizedRoute optimizeRoute(GeoPoint origin, GeoPoint destination, RouteContext context);
}
