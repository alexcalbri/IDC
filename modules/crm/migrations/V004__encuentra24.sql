BEGIN;

-- Encuentra24 Leads API: contacts of the company's listings (messages, chats and calls) imported
-- every 5 minutes. Each Encuentra24 lead id is stored as the message id, so nothing repeats.
CREATE TABLE crm_encuentra24_settings (
    id SMALLINT PRIMARY KEY DEFAULT 1 CHECK (id = 1),
    enabled BOOLEAN NOT NULL DEFAULT FALSE,
    api_key TEXT,
    -- Company slug in Encuentra24 (e.g. emerald-investment).
    slug TEXT CHECK (slug IS NULL OR slug ~ '^[a-z0-9][a-z0-9-]{0,99}$'),
    last_sync TIMESTAMPTZ,
    last_result TEXT NOT NULL DEFAULT '',
    last_error TEXT NOT NULL DEFAULT '',
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

INSERT INTO crm_encuentra24_settings (id) VALUES (1);

COMMIT;
