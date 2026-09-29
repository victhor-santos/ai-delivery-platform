CREATE TABLE restaurants (
    id UUID PRIMARY KEY,
    name VARCHAR(120) NOT NULL,
    active BOOLEAN NOT NULL DEFAULT TRUE,
    CONSTRAINT restaurants_name_not_blank CHECK (name ~ '[^[:space:]]')
);

CREATE INDEX restaurants_name_id_idx ON restaurants (name, id);
