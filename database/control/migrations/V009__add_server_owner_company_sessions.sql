BEGIN;

-- Company sessions a server owner opened from the app's company switcher. The session belongs to
-- the company's business owner user (changes are recorded with that user); this row says which
-- server owner is behind it, so they can move to any company or back to the server.
CREATE TABLE IF NOT EXISTS server_owner_company_sessions (
    token_hash TEXT PRIMARY KEY,
    server_owner_user_id UUID NOT NULL REFERENCES application_users(id) ON DELETE CASCADE,
    company_code VARCHAR(63) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    expires_at TIMESTAMPTZ NOT NULL
);

CREATE INDEX IF NOT EXISTS server_owner_company_sessions_expires_idx ON server_owner_company_sessions (expires_at);

COMMIT;
