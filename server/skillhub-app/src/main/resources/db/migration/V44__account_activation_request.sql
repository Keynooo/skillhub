-- Account activation verification code records for the email-activation registration flow
CREATE TABLE account_activation_request (
    id BIGSERIAL PRIMARY KEY,
    user_id VARCHAR(128) NOT NULL REFERENCES user_account(id) ON DELETE CASCADE,
    email VARCHAR(255) NOT NULL,
    code_hash VARCHAR(255) NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL,
    consumed_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_account_activation_request_user_id ON account_activation_request(user_id);
CREATE INDEX idx_account_activation_request_expires_at ON account_activation_request(expires_at);

COMMENT ON TABLE account_activation_request IS 'Stores email activation verification codes pending local account activation';
COMMENT ON COLUMN account_activation_request.code_hash IS 'BCrypt hash of the one-time activation code';
