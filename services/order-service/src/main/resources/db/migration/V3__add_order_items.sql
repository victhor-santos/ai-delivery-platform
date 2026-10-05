ALTER TABLE orders ADD COLUMN total NUMERIC(14, 2);
ALTER TABLE orders ADD CONSTRAINT orders_total_range CHECK (
    total IS NULL OR (total > 0 AND total <= 494999999950.50)
);

CREATE TABLE order_items (
    order_id UUID NOT NULL REFERENCES orders(id) ON DELETE CASCADE,
    item_position INTEGER NOT NULL,
    menu_item_id UUID NOT NULL,
    name VARCHAR(120) NOT NULL,
    quantity INTEGER NOT NULL,
    unit_price NUMERIC(10, 2) NOT NULL,
    PRIMARY KEY (order_id, item_position),
    CONSTRAINT order_items_menu_item_unique UNIQUE (order_id, menu_item_id),
    CONSTRAINT order_items_position_range CHECK (item_position BETWEEN 0 AND 49),
    CONSTRAINT order_items_name_not_blank CHECK (name ~ '[^[:space:]]'),
    CONSTRAINT order_items_quantity_range CHECK (quantity BETWEEN 1 AND 99),
    CONSTRAINT order_items_unit_price_range CHECK (unit_price > 0 AND unit_price <= 99999999.99)
);
