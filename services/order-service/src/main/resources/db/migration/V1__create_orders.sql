CREATE TABLE orders (
    id UUID PRIMARY KEY,
    restaurant_id UUID NOT NULL,
    destination_address VARCHAR(255) NOT NULL,
    destination_latitude DOUBLE PRECISION NOT NULL,
    destination_longitude DOUBLE PRECISION NOT NULL,
    status VARCHAR(20) NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    confirmed_at TIMESTAMP WITH TIME ZONE,
    cancelled_at TIMESTAMP WITH TIME ZONE,
    version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT orders_destination_address_not_blank CHECK (destination_address ~ '[^[:space:]]'),
    CONSTRAINT orders_destination_latitude_range CHECK (destination_latitude BETWEEN -90 AND 90),
    CONSTRAINT orders_destination_longitude_range CHECK (destination_longitude BETWEEN -180 AND 180),
    CONSTRAINT orders_status_valid CHECK (status IN ('CREATED', 'CONFIRMED', 'CANCELLED')),
    CONSTRAINT orders_timestamps_ordered CHECK (
        updated_at >= created_at
        AND (confirmed_at IS NULL OR confirmed_at >= created_at)
        AND (cancelled_at IS NULL OR cancelled_at >= COALESCE(confirmed_at, created_at))
    ),
    CONSTRAINT orders_lifecycle_consistent CHECK (
        (status = 'CREATED' AND confirmed_at IS NULL AND cancelled_at IS NULL AND updated_at = created_at)
        OR (status = 'CONFIRMED' AND confirmed_at IS NOT NULL AND cancelled_at IS NULL AND updated_at = confirmed_at)
        OR (status = 'CANCELLED' AND cancelled_at IS NOT NULL AND updated_at = cancelled_at)
    )
);
