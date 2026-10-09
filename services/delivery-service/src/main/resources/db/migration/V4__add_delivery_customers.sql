-- Customer who placed the order, sent with the delivery request event. Deliveries created before the event or
-- directly through the API have no customer.
ALTER TABLE deliveries ADD COLUMN customer_id UUID;
