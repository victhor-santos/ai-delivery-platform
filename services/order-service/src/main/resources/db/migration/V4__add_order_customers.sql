-- Orders created before authentication keep a NULL customer; the application requires one for new orders.
ALTER TABLE orders ADD COLUMN customer_id UUID;
