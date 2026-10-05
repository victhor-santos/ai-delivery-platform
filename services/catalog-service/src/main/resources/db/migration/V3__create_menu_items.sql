CREATE TABLE menu_items (
    id UUID PRIMARY KEY,
    restaurant_id UUID NOT NULL REFERENCES restaurants (id),
    name VARCHAR(120) NOT NULL,
    description VARCHAR(1000),
    price NUMERIC(10, 2) NOT NULL,
    available BOOLEAN NOT NULL DEFAULT TRUE,
    CONSTRAINT menu_items_name_not_blank CHECK (name ~ '[^[:space:]]'),
    CONSTRAINT menu_items_price_range CHECK (price > 0 AND price <= 99999999.99)
);

CREATE INDEX menu_items_restaurant_name_id_idx ON menu_items (restaurant_id, name, id);
