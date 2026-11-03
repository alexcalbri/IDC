BEGIN;

-- Attachment contents move out of the database into the server file storage (CompanyFiles):
-- each row keeps the backend that holds the file ('local' today, e.g. 'gdrive' later) and its
-- reference there. Rows uploaded with V010 keep their content until the attachments job moves it.
ALTER TABLE crm_attachments
    ALTER COLUMN content DROP NOT NULL,
    ADD COLUMN storage_backend VARCHAR(32),
    ADD COLUMN storage_ref TEXT,
    -- Removed from its lead (or its field deleted): the job deletes the stored file, then the row.
    ADD COLUMN deleted_at TIMESTAMPTZ,
    ADD CONSTRAINT crm_attachments_stored_check CHECK (content IS NOT NULL OR (storage_backend IS NOT NULL AND storage_ref IS NOT NULL));

-- Deleting a lead keeps its rows (lead_id NULL, field_key set) so the job can delete their files.
ALTER TABLE crm_attachments
    DROP CONSTRAINT IF EXISTS crm_attachments_check,
    DROP CONSTRAINT IF EXISTS crm_attachments_lead_id_fkey,
    ADD CONSTRAINT crm_attachments_lead_id_fkey FOREIGN KEY (lead_id) REFERENCES crm_leads(id) ON DELETE SET NULL;

DROP INDEX IF EXISTS crm_attachments_pending_idx;
CREATE INDEX crm_attachments_cleanup_idx ON crm_attachments (created_at) WHERE lead_id IS NULL OR deleted_at IS NOT NULL;

COMMIT;
