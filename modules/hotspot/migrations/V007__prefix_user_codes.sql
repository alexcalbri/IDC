BEGIN;

ALTER TABLE hotspot_user_codes
    DROP CONSTRAINT hotspot_user_codes_user_code_check;

ALTER TABLE hotspot_user_codes
    ALTER COLUMN user_code TYPE VARCHAR(10);

UPDATE hotspot_user_codes
SET user_code = 'us' || user_code;

ALTER TABLE hotspot_user_codes
    ADD CONSTRAINT hotspot_user_codes_user_code_check
        CHECK (user_code ~ '^us[23456789ABCDEFGHJKLMNPQRSTUVWXYZ]{8}$');

COMMIT;
