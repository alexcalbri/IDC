BEGIN;

ALTER TABLE hotspot_user_codes
    DROP COLUMN user_code;

COMMIT;
