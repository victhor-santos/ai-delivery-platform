ALTER TABLE orders ADD COLUMN delivery_requested_at TIMESTAMP WITH TIME ZONE;
ALTER TABLE orders DROP CONSTRAINT orders_lifecycle_consistent;
ALTER TABLE orders ADD CONSTRAINT orders_delivery_request_consistent CHECK (
    delivery_requested_at IS NULL OR
    (status = 'CONFIRMED' AND confirmed_at IS NOT NULL AND delivery_requested_at >= confirmed_at)
);
ALTER TABLE orders ADD CONSTRAINT orders_lifecycle_consistent CHECK (
    (status = 'CREATED' AND confirmed_at IS NULL AND cancelled_at IS NULL AND updated_at = created_at)
    OR (status = 'CONFIRMED' AND confirmed_at IS NOT NULL AND cancelled_at IS NULL
        AND updated_at = COALESCE(delivery_requested_at, confirmed_at))
    OR (status = 'CANCELLED' AND cancelled_at IS NOT NULL AND updated_at = cancelled_at)
);

CREATE TABLE order_delivery_requests (
    order_id UUID PRIMARY KEY REFERENCES orders(id),
    origin_description VARCHAR(255) NOT NULL CHECK (origin_description ~ '[^[:space:]]'),
    origin_latitude DOUBLE PRECISION NOT NULL CHECK (origin_latitude BETWEEN -90 AND 90),
    origin_longitude DOUBLE PRECISION NOT NULL CHECK (origin_longitude BETWEEN -180 AND 180),
    destination_address VARCHAR(255) NOT NULL CHECK (destination_address ~ '[^[:space:]]'),
    destination_latitude DOUBLE PRECISION NOT NULL CHECK (destination_latitude BETWEEN -90 AND 90),
    destination_longitude DOUBLE PRECISION NOT NULL CHECK (destination_longitude BETWEEN -180 AND 180)
);
