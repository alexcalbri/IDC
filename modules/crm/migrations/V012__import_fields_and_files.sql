BEGIN;

-- Type of each column as the source reports it (monday: text, status, dropdown, file, checkbox...),
-- used to propose the type of the fields the import creates.
ALTER TABLE crm_import_jobs
    ADD COLUMN IF NOT EXISTS column_types JSONB NOT NULL DEFAULT '{}'::jsonb CHECK (jsonb_typeof(column_types) = 'object');

-- Where an imported file came from (monday:asset:123), so importing the same item again does not
-- attach it twice.
ALTER TABLE crm_attachments
    ADD COLUMN IF NOT EXISTS source_ref TEXT;

CREATE UNIQUE INDEX IF NOT EXISTS crm_attachments_source_idx
    ON crm_attachments (lead_id, source_ref)
    WHERE source_ref IS NOT NULL AND deleted_at IS NULL;

COMMIT;
