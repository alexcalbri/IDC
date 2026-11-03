BEGIN;

-- Changes made to each customer in Clientes: who created, edited or archived it and what changed.
-- Other modules add their own events to the customer's history (CustomerTimeline), not here.
CREATE TABLE customer_activity (
    id UUID PRIMARY KEY,
    customer_id UUID NOT NULL REFERENCES customers(id) ON DELETE CASCADE,
    kind VARCHAR(16) NOT NULL CHECK (kind IN ('created', 'updated', 'archived')),
    body TEXT NOT NULL DEFAULT '' CHECK (length(body) <= 5000),
    -- Who did it; NULL for server owners (central users). author keeps the name shown.
    user_id UUID REFERENCES application_users(id) ON DELETE SET NULL,
    author TEXT NOT NULL DEFAULT '',
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX customer_activity_customer_idx ON customer_activity (customer_id, created_at DESC);

COMMIT;
