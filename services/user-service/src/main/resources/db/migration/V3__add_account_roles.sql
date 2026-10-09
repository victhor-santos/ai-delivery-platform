-- Existing accounts were all self-registered customers.
ALTER TABLE user_credentials ADD COLUMN role VARCHAR(16) NOT NULL DEFAULT 'CUSTOMER';
ALTER TABLE user_credentials ALTER COLUMN role DROP DEFAULT;
ALTER TABLE user_credentials ADD CONSTRAINT user_credentials_role_known CHECK (role IN ('CUSTOMER', 'OPERATOR'));
