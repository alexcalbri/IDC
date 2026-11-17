BEGIN;

-- Last view of "Leads" (pipeline or list) each user used, remembered per user like show_archived.
ALTER TABLE crm_user_settings
    ADD COLUMN IF NOT EXISTS leads_view TEXT NOT NULL DEFAULT 'pipeline' CHECK (leads_view IN ('pipeline', 'list'));

COMMIT;
