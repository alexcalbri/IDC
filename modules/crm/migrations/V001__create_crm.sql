BEGIN;

-- One row per company: CRM options.
CREATE TABLE crm_settings (
    id SMALLINT PRIMARY KEY DEFAULT 1 CHECK (id = 1),
    -- Leads are classified by interest (Surf, Yoga... or property type) and users only see the
    -- interests assigned to them. Off: no interests at all.
    interests_enabled BOOLEAN NOT NULL DEFAULT FALSE,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

INSERT INTO crm_settings (id) VALUES (1);

-- Pipeline stages, configurable per company. kind tells whether a lead in the stage is still open,
-- was won (booked, sold) or lost (cold).
CREATE TABLE crm_stages (
    id UUID PRIMARY KEY,
    name TEXT NOT NULL CHECK (length(trim(name)) BETWEEN 1 AND 60),
    kind VARCHAR(8) NOT NULL DEFAULT 'open' CHECK (kind IN ('open', 'won', 'lost')),
    -- Color meaning shown by the app (no colors in module screens).
    tone VARCHAR(8) NOT NULL DEFAULT 'neutral' CHECK (tone IN ('info', 'success', 'warning', 'danger', 'neutral')),
    position INTEGER NOT NULL DEFAULT 0 CHECK (position >= 0),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT crm_stages_name_unique UNIQUE (name)
);

INSERT INTO crm_stages (id, name, kind, tone, position) VALUES
    (gen_random_uuid(), 'Nuevo', 'open', 'info', 0),
    (gen_random_uuid(), 'Contactado', 'open', 'warning', 1),
    (gen_random_uuid(), 'En seguimiento', 'open', 'danger', 2),
    (gen_random_uuid(), 'Ganado', 'won', 'success', 3),
    (gen_random_uuid(), 'Perdido', 'lost', 'neutral', 4);

-- What a lead is interested in. Used only while crm_settings.interests_enabled is on.
CREATE TABLE crm_interests (
    id UUID PRIMARY KEY,
    name TEXT NOT NULL CHECK (length(trim(name)) BETWEEN 1 AND 60),
    position INTEGER NOT NULL DEFAULT 0 CHECK (position >= 0),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT crm_interests_name_unique UNIQUE (name)
);

-- Interests each user works with: the user sees the leads of these interests.
CREATE TABLE crm_user_interests (
    user_id UUID NOT NULL REFERENCES application_users(id) ON DELETE CASCADE,
    interest_id UUID NOT NULL REFERENCES crm_interests(id) ON DELETE CASCADE,

    PRIMARY KEY (user_id, interest_id)
);

-- A lead is the commercial side of a shared customer (Clientes): the person is never duplicated.
CREATE TABLE crm_leads (
    id UUID PRIMARY KEY,
    customer_id UUID NOT NULL REFERENCES customers(id),
    stage_id UUID NOT NULL REFERENCES crm_stages(id),
    -- NULL while nobody has categorized it: it waits in the reception inbox.
    interest_id UUID REFERENCES crm_interests(id) ON DELETE SET NULL,
    -- Responsible user; NULL means unassigned.
    owner_user_id UUID REFERENCES application_users(id) ON DELETE SET NULL,
    -- Where the lead came from (website, WhatsApp, referral...).
    source TEXT NOT NULL DEFAULT '' CHECK (length(source) <= 120),
    language TEXT NOT NULL DEFAULT '' CHECK (length(language) <= 40),
    -- The first message or request of the lead.
    message TEXT NOT NULL DEFAULT '' CHECK (length(message) <= 5000),
    -- Working details: quote, people, preferences...
    details TEXT NOT NULL DEFAULT '' CHECK (length(details) <= 5000),
    -- Dates the lead is interested in (booking, visit, move-in...).
    target_start DATE,
    target_end DATE CHECK (target_end IS NULL OR target_start IS NULL OR target_end >= target_start),
    estimated_value NUMERIC(14, 2) CHECK (estimated_value IS NULL OR estimated_value >= 0),
    follow_up_on DATE,
    stage_changed_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    -- Set when the lead enters a won or lost stage; cleared when it is reopened.
    closed_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX crm_leads_stage_idx ON crm_leads (stage_id, updated_at DESC);
CREATE INDEX crm_leads_interest_idx ON crm_leads (interest_id);
CREATE INDEX crm_leads_owner_idx ON crm_leads (owner_user_id);
CREATE INDEX crm_leads_customer_idx ON crm_leads (customer_id);
CREATE INDEX crm_leads_follow_up_idx ON crm_leads (follow_up_on) WHERE follow_up_on IS NOT NULL AND closed_at IS NULL;

-- Timeline of a lead: notes and every change (stage history included).
CREATE TABLE crm_activity (
    id UUID PRIMARY KEY,
    lead_id UUID NOT NULL REFERENCES crm_leads(id) ON DELETE CASCADE,
    kind VARCHAR(16) NOT NULL CHECK (kind IN ('created', 'note', 'stage', 'owner', 'interest', 'updated')),
    body TEXT NOT NULL DEFAULT '' CHECK (length(body) <= 5000),
    -- Stage history: stage before and after a 'stage' entry.
    from_stage_id UUID REFERENCES crm_stages(id) ON DELETE SET NULL,
    to_stage_id UUID REFERENCES crm_stages(id) ON DELETE SET NULL,
    -- Who did it; NULL for server owners and automatic entries. author keeps the name shown.
    user_id UUID REFERENCES application_users(id) ON DELETE SET NULL,
    author TEXT NOT NULL DEFAULT '',
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX crm_activity_lead_idx ON crm_activity (lead_id, created_at DESC);

COMMIT;
