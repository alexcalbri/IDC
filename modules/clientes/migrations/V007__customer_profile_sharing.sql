BEGIN;

-- One customer record for every company of the same business owner (owner group).
-- The customer belongs to the company where it was created (origin_company_code; '' for customers
-- created before this migration, meaning this company). The business owner, or a user with
-- clientes.share, shares it with the other companies of the group: the record is then copied, with
-- the same id and customer code, to each of them (shared = TRUE) and every edit reaches all copies.
-- A copy that stops being shared stays (other modules may reference it) but is hidden.
ALTER TABLE customers
    ADD COLUMN origin_company_code VARCHAR(63) NOT NULL DEFAULT '',
    ADD COLUMN shared BOOLEAN NOT NULL DEFAULT FALSE;

-- General notes of the customer (tastes and preferences of frequent customers, anything worth
-- knowing in every company). They live only in the origin company's database.
CREATE TABLE customer_notes (
    id UUID PRIMARY KEY,
    customer_id UUID NOT NULL REFERENCES customers(id) ON DELETE CASCADE,
    body TEXT NOT NULL CHECK (length(trim(body)) > 0 AND length(body) <= 5000),
    -- Who wrote it; NULL for server owners and users of other companies. author keeps the name shown.
    user_id UUID,
    author TEXT NOT NULL DEFAULT '',
    company_code VARCHAR(63) NOT NULL DEFAULT '',
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX customer_notes_customer_idx ON customer_notes (customer_id, created_at DESC);

-- What the customer bought or hired in this company: events written by modules (a sale converted
-- in the CRM, a reservation) and entries added by hand. Each company keeps its own.
CREATE TABLE customer_history (
    id UUID PRIMARY KEY,
    customer_id UUID NOT NULL REFERENCES customers(id) ON DELETE CASCADE,
    module_id VARCHAR(63) NOT NULL,
    kind VARCHAR(32) NOT NULL CHECK (kind ~ '^[a-z][a-z0-9_]{0,31}$'),
    title TEXT NOT NULL CHECK (length(trim(title)) > 0 AND length(title) <= 300),
    detail TEXT NOT NULL DEFAULT '' CHECK (length(detail) <= 5000),
    author TEXT NOT NULL DEFAULT '',
    -- UiAction (JSON) that opens the record with the details, e.g. the CRM lead.
    link JSONB,
    -- Identifies the event in its module so writing it twice keeps one row.
    source_ref VARCHAR(200),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX customer_history_customer_idx ON customer_history (customer_id, created_at DESC);
CREATE UNIQUE INDEX customer_history_source_idx ON customer_history (module_id, source_ref) WHERE source_ref IS NOT NULL;

COMMIT;
