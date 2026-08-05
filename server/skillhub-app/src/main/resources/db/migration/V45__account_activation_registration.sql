-- Allow pre-registration verification codes: user_id is null until the account is
-- actually created at registration time (code is looked up by email first).
ALTER TABLE account_activation_request ALTER COLUMN user_id DROP NOT NULL;

CREATE INDEX idx_account_activation_request_email ON account_activation_request(email);
