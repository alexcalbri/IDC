BEGIN;

-- Automatic archive after check-out. Without Cloudbeds the lead is archived once its end date has
-- arrived and exit_hour has passed in the company's time zone; with Cloudbeds the check-out reported
-- by Cloudbeds archives it. Only leads in exit_stage_ids are touched while exit_enabled is on.
ALTER TABLE crm_settings
    ADD COLUMN IF NOT EXISTS exit_enabled BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN IF NOT EXISTS exit_hour SMALLINT NOT NULL DEFAULT 14 CHECK (exit_hour BETWEEN 0 AND 23),
    -- IANA zone (America/Panama); empty uses the time zone of the server, like the daily digest.
    ADD COLUMN IF NOT EXISTS exit_time_zone TEXT NOT NULL DEFAULT '' CHECK (length(exit_time_zone) <= 64),
    ADD COLUMN IF NOT EXISTS exit_stage_ids UUID[] NOT NULL DEFAULT '{}';

-- "Mostrar archivados" of the lead views, remembered per user.
ALTER TABLE crm_user_settings
    ADD COLUMN IF NOT EXISTS show_archived BOOLEAN NOT NULL DEFAULT FALSE;

-- A restored lead stays out of the exit automation until its end date or stage changes.
ALTER TABLE crm_leads ADD COLUMN IF NOT EXISTS restored_at TIMESTAMPTZ;

-- The exit job looks for open leads of some stages with a check-out date; the "Archivados" column lists
-- the most recently archived first.
CREATE INDEX IF NOT EXISTS crm_leads_exit_due_idx ON crm_leads (stage_id, target_end)
    WHERE archived_at IS NULL AND restored_at IS NULL AND target_end IS NOT NULL;
CREATE INDEX IF NOT EXISTS crm_leads_archived_idx ON crm_leads (updated_at DESC)
    WHERE archived_at IS NOT NULL;

COMMIT;
