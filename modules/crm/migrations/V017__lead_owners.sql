BEGIN;

-- Several people can be assigned to a lead, all equal (there is no main person). From this migration on
-- this table is the source of truth: the CRM no longer reads or writes crm_leads.owner_user_id.
CREATE TABLE IF NOT EXISTS crm_lead_owners (
    lead_id UUID NOT NULL REFERENCES crm_leads(id) ON DELETE CASCADE,
    user_id UUID NOT NULL REFERENCES application_users(id) ON DELETE CASCADE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,

    PRIMARY KEY (lead_id, user_id)
);

CREATE INDEX IF NOT EXISTS crm_lead_owners_user_idx ON crm_lead_owners (user_id);

-- The current assignee of every lead goes to the list without losing it. Safe to run again.
INSERT INTO crm_lead_owners (lead_id, user_id, created_at)
SELECT id, owner_user_id, created_at FROM crm_leads WHERE owner_user_id IS NOT NULL
ON CONFLICT DO NOTHING;

-- crm_leads.owner_user_id (and its index) is kept on purpose so this change can be rolled back: it stays
-- as it was at this migration and is no longer updated. A later migration can drop it.
COMMENT ON COLUMN crm_leads.owner_user_id IS 'Deprecated since V017: use crm_lead_owners. Not updated any more.';

COMMIT;
