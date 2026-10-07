CREATE TABLE payment_attempts (
    id UUID PRIMARY KEY,
    customer_id UUID NOT NULL,
    order_id UUID NOT NULL,
    idempotency_key VARCHAR(100) NOT NULL,
    amount NUMERIC(14, 2) NOT NULL,
    method VARCHAR(40) NOT NULL,
    status VARCHAR(20) NOT NULL,
    decline_reason VARCHAR(40),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT payment_attempts_idempotency_key_unique UNIQUE (customer_id, idempotency_key),
    CONSTRAINT payment_attempts_idempotency_key_format CHECK (idempotency_key ~ '^[A-Za-z0-9._:-]{8,100}$'),
    CONSTRAINT payment_attempts_amount_range CHECK (amount > 0 AND amount <= 494999999950.50),
    CONSTRAINT payment_attempts_outcome_matches_method CHECK (
        (method = 'APPROVED_CARD' AND status = 'APPROVED' AND decline_reason IS NULL)
        OR (method = 'DECLINED_CARD' AND status = 'DECLINED' AND decline_reason = 'CARD_DECLINED')
        OR (method = 'INSUFFICIENT_FUNDS_CARD' AND status = 'DECLINED' AND decline_reason = 'INSUFFICIENT_FUNDS')
    )
);

-- At most one approved attempt per customer and order, even under concurrent requests.
CREATE UNIQUE INDEX payment_attempts_one_approval_per_order
    ON payment_attempts (customer_id, order_id) WHERE status = 'APPROVED';

CREATE INDEX payment_attempts_by_order ON payment_attempts (customer_id, order_id, created_at, id);
