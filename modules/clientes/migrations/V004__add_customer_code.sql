BEGIN;

-- Public code a customer uses to identify itself (e.g. to connect to a WiFi network by code).
-- Eight characters from a 32-symbol alphabet without look-alikes (no 0/O, 1/I); the random bytes
-- come from gen_random_uuid(), which is cryptographically secure. The database generates the code
-- so every insert path gets one.
CREATE FUNCTION customers_generate_code() RETURNS VARCHAR(8)
LANGUAGE plpgsql VOLATILE AS $$
DECLARE
    alphabet CONSTANT TEXT := '23456789ABCDEFGHJKLMNPQRSTUVWXYZ';
    random_bytes BYTEA;
    candidate VARCHAR(8);
    byte_index INTEGER;
BEGIN
    LOOP
        random_bytes := uuid_send(gen_random_uuid());
        candidate := '';
        -- Bytes 6 and 8 carry the UUID version/variant bits; the others are random.
        FOREACH byte_index IN ARRAY ARRAY[0, 1, 2, 3, 4, 5, 10, 11] LOOP
            candidate := candidate || substr(alphabet, get_byte(random_bytes, byte_index) % 32 + 1, 1);
        END LOOP;
        EXIT WHEN NOT EXISTS (SELECT 1 FROM customers WHERE customer_code = candidate);
    END LOOP;
    RETURN candidate;
END;
$$;

ALTER TABLE customers ADD COLUMN customer_code VARCHAR(8);

-- One UPDATE per row so each generated code sees the ones assigned before it.
DO $$
DECLARE
    customer RECORD;
BEGIN
    FOR customer IN SELECT id FROM customers LOOP
        UPDATE customers SET customer_code = customers_generate_code() WHERE id = customer.id;
    END LOOP;
END;
$$;

ALTER TABLE customers
    ALTER COLUMN customer_code SET DEFAULT customers_generate_code(),
    ALTER COLUMN customer_code SET NOT NULL,
    ADD CONSTRAINT customers_customer_code_format
        CHECK (customer_code ~ '^[23456789ABCDEFGHJKLMNPQRSTUVWXYZ]{8}$'),
    ADD CONSTRAINT customers_customer_code_unique
        UNIQUE (customer_code);

COMMIT;
