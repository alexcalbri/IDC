BEGIN;

-- Lead fields the company adds in Configuracion. The type is one of the form field types of the
-- app; a dropdown keeps its options and whether several can be chosen (multiple).
CREATE TABLE crm_field_definitions (
    id UUID PRIMARY KEY,
    field_key VARCHAR(63) NOT NULL UNIQUE CHECK (field_key ~ '^cf_[a-z0-9_]{1,60}$'),
    label TEXT NOT NULL CHECK (length(trim(label)) > 0),
    field_type VARCHAR(32) NOT NULL CHECK (
        field_type IN ('text', 'textarea', 'number', 'email', 'phone', 'url', 'date', 'boolean', 'dropdown', 'files')
    ),
    options JSONB NOT NULL DEFAULT '[]'::jsonb CHECK (jsonb_typeof(options) = 'array'),
    multiple BOOLEAN NOT NULL DEFAULT FALSE,
    required BOOLEAN NOT NULL DEFAULT FALSE,
    position INTEGER NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- Values of the custom fields by field_key: text as string, boolean as boolean, multiple dropdowns
-- as an array of options. Files live in crm_attachments.
ALTER TABLE crm_leads
    ADD COLUMN custom_fields JSONB NOT NULL DEFAULT '{}'::jsonb CHECK (jsonb_typeof(custom_fields) = 'object');

-- Files attached to a lead: the built-in "documents" field or a custom field of type files.
-- An upload has no lead until the form that holds it is saved; unsaved uploads are removed after a day.
CREATE TABLE crm_attachments (
    id UUID PRIMARY KEY,
    lead_id UUID REFERENCES crm_leads(id) ON DELETE CASCADE,
    field_key VARCHAR(63),
    file_name TEXT NOT NULL CHECK (length(file_name) BETWEEN 1 AND 255),
    -- Folder inside a picked folder, e.g. "Contrato/Anexos"; '' for loose files.
    folder_path TEXT NOT NULL DEFAULT '',
    content_type TEXT NOT NULL DEFAULT 'application/octet-stream',
    size_bytes BIGINT NOT NULL CHECK (size_bytes >= 0),
    content BYTEA NOT NULL,
    uploaded_by TEXT NOT NULL DEFAULT '',
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CHECK ((lead_id IS NULL) = (field_key IS NULL))
);

-- History entries for added and removed files.
ALTER TABLE crm_activity DROP CONSTRAINT IF EXISTS crm_activity_kind_check;
ALTER TABLE crm_activity ADD CONSTRAINT crm_activity_kind_check
    CHECK (kind IN ('created', 'note', 'stage', 'owner', 'interest', 'updated', 'web', 'item', 'files'));

CREATE INDEX crm_attachments_lead_idx ON crm_attachments (lead_id, field_key);
CREATE INDEX crm_attachments_pending_idx ON crm_attachments (created_at) WHERE lead_id IS NULL;

COMMIT;
