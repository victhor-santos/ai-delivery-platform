CREATE TABLE users (
    id UUID PRIMARY KEY,
    name VARCHAR(120) NOT NULL,
    email VARCHAR(254) NOT NULL,
    CONSTRAINT users_name_not_blank CHECK (name ~ '[^[:space:]]'),
    CONSTRAINT users_email_not_blank CHECK (email ~ '[^[:space:]]'),
    CONSTRAINT users_email_canonical CHECK (email = lower(email) AND email !~ '[[:space:]]'),
    CONSTRAINT users_email_unique UNIQUE (email)
);

CREATE TABLE user_addresses (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    label VARCHAR(80) NOT NULL,
    address VARCHAR(255) NOT NULL,
    latitude DOUBLE PRECISION NOT NULL,
    longitude DOUBLE PRECISION NOT NULL,
    CONSTRAINT user_addresses_label_not_blank CHECK (label ~ '[^[:space:]]'),
    CONSTRAINT user_addresses_address_not_blank CHECK (address ~ '[^[:space:]]'),
    CONSTRAINT user_addresses_latitude_range CHECK (latitude BETWEEN -90 AND 90),
    CONSTRAINT user_addresses_longitude_range CHECK (longitude BETWEEN -180 AND 180)
);

CREATE INDEX user_addresses_user_label_id_idx ON user_addresses (user_id, label, id);
