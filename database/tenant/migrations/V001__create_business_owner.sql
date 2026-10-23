BEGIN;

-- Exactly one owner is seeded when provisioning. The runtime role cannot
-- delete this row. Later ownership transfer must update it atomically.
CREATE TABLE business_owner (
    singleton BOOLEAN PRIMARY KEY DEFAULT TRUE CHECK (singleton),
    user_id UUID NOT NULL UNIQUE REFERENCES application_users(id)
);

COMMIT;
