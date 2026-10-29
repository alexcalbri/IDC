BEGIN;

-- Email and phone become optional: leads from chats or calls often arrive without one of them.
-- The columns stay NOT NULL and an unknown value is stored as '' so readers always get text.
ALTER TABLE customers
    DROP CONSTRAINT IF EXISTS customers_primary_email_required,
    DROP CONSTRAINT IF EXISTS customers_primary_phone_required,
    -- Column checks from V001 (named by PostgreSQL).
    DROP CONSTRAINT IF EXISTS customers_primary_email_check,
    DROP CONSTRAINT IF EXISTS customers_primary_phone_check,
    ALTER COLUMN primary_email SET DEFAULT '',
    ALTER COLUMN primary_phone SET DEFAULT '';

COMMIT;
