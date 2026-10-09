-- Events written in the same transaction as the state change and published afterwards, at least once.
CREATE TABLE outbox_events (
    id UUID PRIMARY KEY,
    aggregate_id UUID NOT NULL,
    event_type VARCHAR(100) NOT NULL CHECK (event_type ~ '[^[:space:]]'),
    routing_key VARCHAR(100) NOT NULL CHECK (routing_key ~ '[^[:space:]]'),
    payload TEXT NOT NULL,
    request_id VARCHAR(64),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    published_at TIMESTAMP WITH TIME ZONE,
    attempts INTEGER NOT NULL DEFAULT 0 CHECK (attempts >= 0),
    CONSTRAINT outbox_events_published_after_creation CHECK (published_at IS NULL OR published_at >= created_at)
);

CREATE INDEX outbox_events_pending ON outbox_events (created_at, id) WHERE published_at IS NULL;
