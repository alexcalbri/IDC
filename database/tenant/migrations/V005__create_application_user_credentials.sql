BEGIN;

CREATE TABLE application_user_credentials (
    user_id UUID PRIMARY KEY REFERENCES application_users(id) ON DELETE CASCADE,
    password_salt TEXT NOT NULL CHECK (length(password_salt) > 0),
    password_hash TEXT NOT NULL CHECK (length(password_hash) > 0),
    iterations INTEGER NOT NULL DEFAULT 120000 CHECK (iterations >= 120000),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

COMMIT;