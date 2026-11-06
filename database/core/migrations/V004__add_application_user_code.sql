BEGIN;

ALTER TABLE application_users
    ADD COLUMN IF NOT EXISTS user_code VARCHAR(10);

ALTER TABLE application_users
    ADD CONSTRAINT application_users_user_code_format
        CHECK (user_code IS NULL OR user_code ~ '^us[23456789ABCDEFGHJKLMNPQRSTUVWXYZ]{8}$');

CREATE UNIQUE INDEX IF NOT EXISTS application_users_user_code_unique
    ON application_users (user_code)
    WHERE user_code IS NOT NULL;

COMMIT;
