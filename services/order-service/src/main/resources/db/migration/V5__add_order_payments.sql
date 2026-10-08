-- A pending payment blocks cancellation; an approved one confirms the order and keeps it from being cancelled.
ALTER TABLE orders ADD COLUMN payment_requested_at TIMESTAMP WITH TIME ZONE;
ALTER TABLE orders ADD COLUMN payment_id UUID;
ALTER TABLE orders ADD CONSTRAINT orders_payment_consistent CHECK (
    (payment_requested_at IS NULL OR (status = 'CREATED' AND total IS NOT NULL AND payment_requested_at >= created_at))
    AND (payment_id IS NULL OR status = 'CONFIRMED')
);

CREATE TABLE order_payments (
    order_id UUID NOT NULL REFERENCES orders(id),
    idempotency_key VARCHAR(100) NOT NULL,
    method VARCHAR(40) NOT NULL,
    amount NUMERIC(14, 2) NOT NULL,
    status VARCHAR(20) NOT NULL,
    payment_id UUID,
    decline_reason VARCHAR(40),
    requested_at TIMESTAMP WITH TIME ZONE NOT NULL,
    completed_at TIMESTAMP WITH TIME ZONE,
    PRIMARY KEY (order_id, idempotency_key),
    CONSTRAINT order_payments_key_format CHECK (idempotency_key ~ '^[A-Za-z0-9._:-]{8,100}$'),
    CONSTRAINT order_payments_method_valid CHECK (
        method IN ('sim-card-approved', 'sim-card-declined', 'sim-card-insufficient-funds')
    ),
    CONSTRAINT order_payments_amount_range CHECK (amount > 0 AND amount <= 494999999950.50),
    CONSTRAINT order_payments_status_valid CHECK (status IN ('PENDING', 'APPROVED', 'DECLINED', 'REJECTED')),
    CONSTRAINT order_payments_outcome_consistent CHECK (
        (status = 'PENDING' AND payment_id IS NULL AND decline_reason IS NULL AND completed_at IS NULL)
        OR (status = 'REJECTED' AND payment_id IS NULL AND decline_reason IS NULL AND completed_at IS NOT NULL)
        OR (status = 'APPROVED' AND payment_id IS NOT NULL AND decline_reason IS NULL AND completed_at IS NOT NULL)
        OR (status = 'DECLINED' AND payment_id IS NOT NULL AND decline_reason ~ '[^[:space:]]'
            AND completed_at IS NOT NULL)
    ),
    CONSTRAINT order_payments_completed_after_request CHECK (completed_at IS NULL OR completed_at >= requested_at)
);

-- At most one intent per order can be in flight or have charged it.
CREATE UNIQUE INDEX order_payments_one_open_per_order ON order_payments (order_id)
    WHERE status IN ('PENDING', 'APPROVED');
