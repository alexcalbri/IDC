BEGIN;

ALTER TABLE customers
    ALTER COLUMN primary_email SET NOT NULL,
    ALTER COLUMN primary_phone SET NOT NULL;

ALTER TABLE customers
    ADD CONSTRAINT customers_primary_email_required
        CHECK (length(trim(primary_email)) > 0),
    ADD CONSTRAINT customers_primary_phone_required
        CHECK (length(trim(primary_phone)) > 0);

COMMIT;
