CREATE TABLE couriers (
    id UUID PRIMARY KEY,
    active BOOLEAN NOT NULL,
    version BIGINT NOT NULL DEFAULT 0
);

CREATE TABLE deliveries (
    id UUID PRIMARY KEY,
    order_id UUID NOT NULL,
    origin_description VARCHAR(255) NOT NULL,
    origin_latitude DOUBLE PRECISION NOT NULL,
    origin_longitude DOUBLE PRECISION NOT NULL,
    destination_description VARCHAR(255) NOT NULL,
    destination_latitude DOUBLE PRECISION NOT NULL,
    destination_longitude DOUBLE PRECISION NOT NULL,
    courier_id UUID REFERENCES couriers(id),
    status VARCHAR(20) NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    assigned_at TIMESTAMP WITH TIME ZONE,
    picked_up_at TIMESTAMP WITH TIME ZONE,
    departed_at TIMESTAMP WITH TIME ZONE,
    arrived_at TIMESTAMP WITH TIME ZONE,
    delivered_at TIMESTAMP WITH TIME ZONE,
    cancelled_at TIMESTAMP WITH TIME ZONE,
    version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT deliveries_order_unique UNIQUE (order_id),
    CONSTRAINT deliveries_origin_not_blank CHECK (origin_description ~ '[^[:space:]]'),
    CONSTRAINT deliveries_destination_not_blank CHECK (destination_description ~ '[^[:space:]]'),
    CONSTRAINT deliveries_origin_latitude_range CHECK (origin_latitude BETWEEN -90 AND 90),
    CONSTRAINT deliveries_origin_longitude_range CHECK (origin_longitude BETWEEN -180 AND 180),
    CONSTRAINT deliveries_destination_latitude_range CHECK (destination_latitude BETWEEN -90 AND 90),
    CONSTRAINT deliveries_destination_longitude_range CHECK (destination_longitude BETWEEN -180 AND 180),
    CONSTRAINT deliveries_assignment_consistent CHECK ((courier_id IS NULL) = (assigned_at IS NULL)),
    CONSTRAINT deliveries_timestamps_ordered CHECK (
        updated_at >= created_at
        AND (assigned_at IS NULL OR assigned_at >= created_at)
        AND (picked_up_at IS NULL OR picked_up_at >= assigned_at)
        AND (departed_at IS NULL OR departed_at >= picked_up_at)
        AND (arrived_at IS NULL OR arrived_at >= departed_at)
        AND (delivered_at IS NULL OR delivered_at >= arrived_at)
        AND (cancelled_at IS NULL OR cancelled_at >= COALESCE(assigned_at, created_at))
    ),
    CONSTRAINT deliveries_lifecycle_consistent CHECK (
        (status = 'CREATED' AND assigned_at IS NULL AND picked_up_at IS NULL AND departed_at IS NULL
            AND arrived_at IS NULL AND delivered_at IS NULL AND cancelled_at IS NULL AND updated_at = created_at)
        OR (status = 'ASSIGNED' AND assigned_at IS NOT NULL AND picked_up_at IS NULL AND departed_at IS NULL
            AND arrived_at IS NULL AND delivered_at IS NULL AND cancelled_at IS NULL AND updated_at = assigned_at)
        OR (status = 'PICKED_UP' AND assigned_at IS NOT NULL AND picked_up_at IS NOT NULL AND departed_at IS NULL
            AND arrived_at IS NULL AND delivered_at IS NULL AND cancelled_at IS NULL AND updated_at = picked_up_at)
        OR (status = 'IN_TRANSIT' AND assigned_at IS NOT NULL AND picked_up_at IS NOT NULL AND departed_at IS NOT NULL
            AND delivered_at IS NULL AND cancelled_at IS NULL AND updated_at = COALESCE(arrived_at, departed_at))
        OR (status = 'DELIVERED' AND assigned_at IS NOT NULL AND picked_up_at IS NOT NULL AND departed_at IS NOT NULL
            AND arrived_at IS NOT NULL AND delivered_at IS NOT NULL AND cancelled_at IS NULL AND updated_at = delivered_at)
        OR (status = 'CANCELLED' AND picked_up_at IS NULL AND departed_at IS NULL AND arrived_at IS NULL
            AND delivered_at IS NULL AND cancelled_at IS NOT NULL AND updated_at = cancelled_at)
    )
);

CREATE UNIQUE INDEX deliveries_active_courier_unique ON deliveries (courier_id)
    WHERE status IN ('ASSIGNED', 'PICKED_UP', 'IN_TRANSIT');
