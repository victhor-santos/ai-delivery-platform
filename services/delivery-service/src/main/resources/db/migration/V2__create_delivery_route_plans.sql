CREATE TABLE delivery_route_plans (
    delivery_id UUID PRIMARY KEY REFERENCES deliveries(id) ON DELETE CASCADE,
    id UUID NOT NULL UNIQUE,
    departure_at TIMESTAMP WITH TIME ZONE NOT NULL,
    planned_at TIMESTAMP WITH TIME ZONE NOT NULL,
    delivery_version BIGINT NOT NULL CHECK (delivery_version > 0),
    optimized_route JSONB NOT NULL,
    CONSTRAINT delivery_route_object CHECK (jsonb_typeof(optimized_route) = 'object'),
    CONSTRAINT delivery_route_fields CHECK (optimized_route ?& ARRAY[
        'route', 'segments', 'distanceKm', 'predictedTravelTimeMinutes', 'predictedAt',
        'contextAsOf', 'modelVersion', 'graphVersion', 'dataOrigin'
    ]),
    CONSTRAINT delivery_route_arrays CHECK (
        jsonb_typeof(optimized_route -> 'route') = 'array'
        AND jsonb_typeof(optimized_route -> 'segments') = 'array'
        AND jsonb_array_length(optimized_route -> 'route') BETWEEN 1 AND 200
        AND jsonb_array_length(optimized_route -> 'route') = jsonb_array_length(optimized_route -> 'segments') + 1
    )
);
