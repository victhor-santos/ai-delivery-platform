CREATE TABLE user_credentials (
    user_id UUID PRIMARY KEY REFERENCES users (id) ON DELETE CASCADE,
    password_hash VARCHAR(100) NOT NULL,
    CONSTRAINT user_credentials_hash_not_blank CHECK (password_hash ~ '[^[:space:]]')
);
