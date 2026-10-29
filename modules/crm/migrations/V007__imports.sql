BEGIN;

-- Key of an imported lead in its source (monday:<item>, leadspro:<user>, file:<row hash>), so
-- importing again updates it instead of creating another lead.
ALTER TABLE crm_leads ADD COLUMN import_key TEXT CHECK (import_key IS NULL OR length(import_key) BETWEEN 1 AND 200);
CREATE UNIQUE INDEX crm_leads_import_key_idx ON crm_leads (import_key) WHERE import_key IS NOT NULL;

-- An import: rows read from a file, monday or CT Leads Pro, the column mapping and its progress.
CREATE TABLE crm_import_jobs (
    id UUID PRIMARY KEY,
    source VARCHAR(16) NOT NULL CHECK (source IN ('file', 'monday', 'leadspro')),
    name TEXT NOT NULL CHECK (length(trim(name)) BETWEEN 1 AND 200),
    status VARCHAR(10) NOT NULL DEFAULT 'mapping' CHECK (status IN ('mapping', 'running', 'done', 'canceled')),
    -- Column -> CRM field, and value maps for stages, owners and interests.
    mapping JSONB NOT NULL DEFAULT '{}'::jsonb CHECK (jsonb_typeof(mapping) = 'object'),
    columns JSONB NOT NULL DEFAULT '[]'::jsonb CHECK (jsonb_typeof(columns) = 'array'),
    total INTEGER NOT NULL DEFAULT 0,
    processed INTEGER NOT NULL DEFAULT 0,
    created INTEGER NOT NULL DEFAULT 0,
    updated INTEGER NOT NULL DEFAULT 0,
    skipped INTEGER NOT NULL DEFAULT 0,
    conflicts INTEGER NOT NULL DEFAULT 0,
    errors INTEGER NOT NULL DEFAULT 0,
    created_by UUID REFERENCES application_users(id) ON DELETE SET NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE crm_import_rows (
    job_id UUID NOT NULL REFERENCES crm_import_jobs(id) ON DELETE CASCADE,
    row_no INTEGER NOT NULL,
    -- Column -> value, plus "__key" (source id) and "__notes" (JSON list of {date, author, text}).
    data JSONB NOT NULL CHECK (jsonb_typeof(data) = 'object'),
    status VARCHAR(10) NOT NULL DEFAULT 'pending' CHECK (status IN ('pending', 'created', 'updated', 'skipped', 'conflict', 'error')),
    message TEXT NOT NULL DEFAULT '',

    PRIMARY KEY (job_id, row_no)
);

CREATE INDEX crm_import_rows_pending_idx ON crm_import_rows (job_id, row_no) WHERE status = 'pending';

-- Saved column mappings, e.g. "monday Surf Camps" or "Leads Pro".
CREATE TABLE crm_import_presets (
    id UUID PRIMARY KEY,
    name TEXT NOT NULL CHECK (length(trim(name)) BETWEEN 1 AND 80),
    mapping JSONB NOT NULL CHECK (jsonb_typeof(mapping) = 'object'),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT crm_import_presets_name_unique UNIQUE (name)
);

COMMIT;
