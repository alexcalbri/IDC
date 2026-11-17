BEGIN;

-- Users mentioned in the notes of a lead. A mention lets the user read the lead and answer with notes
-- (nothing else) while the row is active (revoked_at IS NULL). One row per user mentioned in each note;
-- activity_id is the note (crm_activity) that mentioned them, so the same note never notifies twice.
CREATE TABLE IF NOT EXISTS crm_lead_mentions (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    lead_id UUID NOT NULL REFERENCES crm_leads(id) ON DELETE CASCADE,
    user_id UUID NOT NULL REFERENCES application_users(id) ON DELETE CASCADE,
    -- The note; the access outlives the history entry, so a deleted note only clears this link.
    activity_id UUID REFERENCES crm_activity(id) ON DELETE SET NULL,
    -- Who wrote the note: NULL for server owners (they are not users of the company database).
    mentioned_by UUID REFERENCES application_users(id) ON DELETE SET NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    -- Set when whoever manages the lead takes the access away ("Quitar acceso").
    revoked_at TIMESTAMPTZ
);

-- A note mentions each user once (re-saving a note never creates or notifies again).
CREATE UNIQUE INDEX IF NOT EXISTS crm_lead_mentions_note_user_idx ON crm_lead_mentions (activity_id, user_id) WHERE activity_id IS NOT NULL;

-- Leads a user can read because of a mention (visibility in the lead views; index-only scan).
CREATE INDEX IF NOT EXISTS crm_lead_mentions_user_idx ON crm_lead_mentions (user_id) INCLUDE (lead_id) WHERE revoked_at IS NULL;
-- "Is this user mentioned in this lead" and the "Mencionados" list of a lead.
CREATE INDEX IF NOT EXISTS crm_lead_mentions_lead_user_idx ON crm_lead_mentions (lead_id, user_id) WHERE revoked_at IS NULL;
-- Foreign keys: lead_id (cascade, revoked rows too) and mentioned_by (ON DELETE SET NULL); user_id is covered by the index above only for active rows.
CREATE INDEX IF NOT EXISTS crm_lead_mentions_lead_idx ON crm_lead_mentions (lead_id);
CREATE INDEX IF NOT EXISTS crm_lead_mentions_user_all_idx ON crm_lead_mentions (user_id);
CREATE INDEX IF NOT EXISTS crm_lead_mentions_by_idx ON crm_lead_mentions (mentioned_by) WHERE mentioned_by IS NOT NULL;

-- "Avisarme por correo cuando me mencionen", per user (on by default).
ALTER TABLE crm_user_settings
    ADD COLUMN IF NOT EXISTS notify_mentioned BOOLEAN NOT NULL DEFAULT TRUE;

COMMIT;
