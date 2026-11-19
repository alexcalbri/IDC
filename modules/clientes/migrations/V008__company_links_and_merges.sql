BEGIN;

-- Linked companies (Core table company_links, purpose 'clientes') share every customer: each company
-- copies its own customers to the others in the background, in batches it can resume.

-- Who did what, also for events of the company and not of one customer (custom field created or
-- deleted, company linked or unlinked): those have no customer_id.
ALTER TABLE customer_activity ALTER COLUMN customer_id DROP NOT NULL;
ALTER TABLE customer_activity DROP CONSTRAINT IF EXISTS customer_activity_kind_check;
ALTER TABLE customer_activity ADD CONSTRAINT customer_activity_kind_check CHECK (kind IN (
    'created', 'updated', 'archived', 'restored', 'note_deleted', 'history_deleted',
    'field_created', 'field_deleted', 'linked', 'unlinked', 'merged', 'separated'
));
CREATE INDEX IF NOT EXISTS customer_activity_company_idx ON customer_activity (created_at DESC) WHERE customer_id IS NULL;

-- Progress of copying this company's own customers to one linked company (state and counters only:
-- what is left to copy is every own customer with shared = FALSE). A row is removed when that company
-- is unlinked; a missing row is created again by the job and marks every own customer to be copied.
CREATE TABLE customer_link_jobs (
    target_company_code VARCHAR(63) PRIMARY KEY,
    status VARCHAR(16) NOT NULL DEFAULT 'pending' CHECK (status IN ('pending', 'running', 'done', 'error')),
    copied INTEGER NOT NULL DEFAULT 0,
    merged INTEGER NOT NULL DEFAULT 0,
    review INTEGER NOT NULL DEFAULT 0,
    total INTEGER NOT NULL DEFAULT 0,
    error TEXT NOT NULL DEFAULT '' CHECK (length(error) <= 2000),
    started_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- Duplicates found while sharing (same email in two linked companies), kept in the database of the
-- company whose customer was merged into the other (archived). status:
--   merged: done; moved lists the rows that pointed to the merged customer and now point to the kept
--           one, so "Separar" can move them back.
--   review: not merged (reason says why); the owner decides in "Por revisar".
--   separated: the owner separated them; they are never merged again automatically.
CREATE TABLE customer_merges (
    id UUID PRIMARY KEY,
    kept_id UUID NOT NULL,
    kept_company_code VARCHAR(63) NOT NULL,
    kept_name TEXT NOT NULL DEFAULT '',
    merged_id UUID NOT NULL REFERENCES customers (id) ON DELETE CASCADE,
    merged_name TEXT NOT NULL DEFAULT '',
    email TEXT NOT NULL DEFAULT '',
    status VARCHAR(16) NOT NULL CHECK (status IN ('merged', 'review', 'separated')),
    reason TEXT NOT NULL DEFAULT '' CHECK (length(reason) <= 2000),
    moved JSONB NOT NULL DEFAULT '[]'::jsonb,
    author TEXT NOT NULL DEFAULT '',
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    resolved_by TEXT NOT NULL DEFAULT '',
    resolved_at TIMESTAMPTZ
);

-- One open decision per pair of customers.
CREATE UNIQUE INDEX customer_merges_pair_idx ON customer_merges (merged_id, kept_id);
CREATE INDEX customer_merges_status_idx ON customer_merges (status, created_at DESC);

-- Own customers that are not shared yet (inserted directly by other modules, or left by a failed
-- attempt): the job looks for them on every run. And the copies that are visible, for the safety sweep.
CREATE INDEX customers_unshared_own_idx ON customers (id) WHERE origin_company_code = '' AND NOT shared;
CREATE INDEX customers_shared_own_idx ON customers (id) WHERE origin_company_code = '' AND shared;
CREATE INDEX customers_shared_copies_idx ON customers (origin_company_code) WHERE origin_company_code <> '' AND shared;

COMMIT;
