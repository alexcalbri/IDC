BEGIN;

-- Origin/category of a customer. Modules that create customers (e.g. hotspot)
-- set their own type; customers created from Clientes stay 'general'.
ALTER TABLE customers
    ADD COLUMN customer_type VARCHAR(32) NOT NULL DEFAULT 'general'
        CHECK (customer_type ~ '^[a-z][a-z0-9_]{0,31}$');

CREATE INDEX customers_customer_type_idx
    ON customers (customer_type);

COMMIT;
