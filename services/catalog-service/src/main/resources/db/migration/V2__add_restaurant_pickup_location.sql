ALTER TABLE restaurants
    ADD COLUMN pickup_latitude DOUBLE PRECISION,
    ADD COLUMN pickup_longitude DOUBLE PRECISION,
    ADD CONSTRAINT restaurants_pickup_location_complete
        CHECK ((pickup_latitude IS NULL) = (pickup_longitude IS NULL)),
    ADD CONSTRAINT restaurants_pickup_latitude_range
        CHECK (pickup_latitude BETWEEN -90 AND 90),
    ADD CONSTRAINT restaurants_pickup_longitude_range
        CHECK (pickup_longitude BETWEEN -180 AND 180);
