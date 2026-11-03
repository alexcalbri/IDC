BEGIN;

-- Every user has a name and an email: changes are recorded with who made them, and the email
-- receives password reset codes (and later module notifications). Users created before this
-- migration complete both when they next sign in.
ALTER TABLE application_users
    ADD COLUMN IF NOT EXISTS display_name VARCHAR(120),
    ADD COLUMN IF NOT EXISTS email VARCHAR(254);

-- One pending password reset code per user, stored as a hash; it expires and allows few attempts.
CREATE TABLE IF NOT EXISTS password_reset_codes (
    user_id UUID PRIMARY KEY REFERENCES application_users(id) ON DELETE CASCADE,
    code_hash VARCHAR(64) NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL,
    attempts INTEGER NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

COMMIT;
