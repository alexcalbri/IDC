BEGIN;

-- What a lead is interested in or bought: properties of the website, rooms of a PMS or items typed
-- by hand. external_id is the id in the source (listing id, room id).
CREATE TABLE crm_lead_items (
    id UUID PRIMARY KEY,
    lead_id UUID NOT NULL REFERENCES crm_leads(id) ON DELETE CASCADE,
    source VARCHAR(16) NOT NULL CHECK (source ~ '^[a-z][a-z0-9_]{0,15}$'),
    external_id TEXT NOT NULL CHECK (length(external_id) BETWEEN 1 AND 200),
    title TEXT NOT NULL CHECK (length(trim(title)) BETWEEN 1 AND 300),
    subtitle TEXT NOT NULL DEFAULT '' CHECK (length(subtitle) <= 500),
    url TEXT NOT NULL DEFAULT '' CHECK (url = '' OR url ~ '^https?://'),
    price NUMERIC(14, 2) CHECK (price IS NULL OR price >= 0),
    status VARCHAR(12) NOT NULL DEFAULT 'interested'
        CHECK (status IN ('interested', 'offered', 'chosen', 'discarded', 'sold', 'booked')),
    starts_on DATE,
    ends_on DATE CHECK (ends_on IS NULL OR starts_on IS NULL OR ends_on >= starts_on),
    data JSONB NOT NULL DEFAULT '{}'::jsonb CHECK (jsonb_typeof(data) = 'object'),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT crm_lead_items_unique UNIQUE (lead_id, source, external_id)
);

CREATE INDEX crm_lead_items_lead_idx ON crm_lead_items (lead_id);
CREATE INDEX crm_lead_items_external_idx ON crm_lead_items (source, external_id);

-- Activity of the customer on the website (favorites, saved searches, views) and item changes.
ALTER TABLE crm_activity DROP CONSTRAINT IF EXISTS crm_activity_kind_check;
ALTER TABLE crm_activity ADD CONSTRAINT crm_activity_kind_check
    CHECK (kind IN ('created', 'note', 'stage', 'owner', 'interest', 'updated', 'web', 'item'));

COMMIT;
