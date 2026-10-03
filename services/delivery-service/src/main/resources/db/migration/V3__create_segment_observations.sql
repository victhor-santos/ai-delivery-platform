CREATE TABLE delivery_segment_observations (
    id UUID PRIMARY KEY,
    delivery_id UUID NOT NULL REFERENCES deliveries(id) ON DELETE CASCADE,
    route_plan_id UUID NOT NULL,
    sequence INTEGER NOT NULL CHECK (sequence BETWEEN 0 AND 198),
    data_origin VARCHAR(16) NOT NULL CHECK (data_origin = 'simulated'),
    entered_at TIMESTAMP WITH TIME ZONE NOT NULL,
    entry_recorded_at TIMESTAMP WITH TIME ZONE NOT NULL CHECK (entry_recorded_at >= entered_at),
    exited_at TIMESTAMP WITH TIME ZONE,
    label_available_at TIMESTAMP WITH TIME ZONE,
    prediction_snapshot JSONB NOT NULL CHECK (jsonb_typeof(prediction_snapshot) = 'object'),
    UNIQUE (delivery_id, sequence),
    CHECK ((exited_at IS NULL) = (label_available_at IS NULL)),
    CHECK (exited_at IS NULL OR (exited_at > entered_at AND label_available_at >= exited_at
        AND label_available_at >= entry_recorded_at))
);
