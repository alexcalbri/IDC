BEGIN;

-- WhatsApp Cloud API, Messenger and Instagram through one Meta app. The system user token of Meta
-- Business is the only token typed; the page token is read from it.
CREATE TABLE crm_meta_settings (
    id SMALLINT PRIMARY KEY DEFAULT 1 CHECK (id = 1),
    enabled BOOLEAN NOT NULL DEFAULT FALSE,
    -- Verifies the X-Hub-Signature-256 of every webhook call.
    app_secret TEXT,
    -- Typed in Meta when subscribing the callback URL.
    verify_token TEXT,
    system_token TEXT,
    page_id TEXT CHECK (page_id IS NULL OR page_id ~ '^[0-9]{1,64}$'),
    page_name TEXT,
    page_token TEXT,
    instagram_account_id TEXT CHECK (instagram_account_id IS NULL OR instagram_account_id ~ '^[0-9]{1,64}$'),
    waba_id TEXT CHECK (waba_id IS NULL OR waba_id ~ '^[0-9]{1,64}$'),
    phone_number_id TEXT CHECK (phone_number_id IS NULL OR phone_number_id ~ '^[0-9]{1,64}$'),
    -- Coexistence: replies sent from the WhatsApp Business app are stored as outgoing messages.
    record_echoes BOOLEAN NOT NULL DEFAULT TRUE,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

INSERT INTO crm_meta_settings (id) VALUES (1);

-- Last 30 webhook and API events, shown in the settings. Never stores tokens or signatures.
CREATE TABLE crm_meta_log (
    id UUID PRIMARY KEY,
    event VARCHAR(40) NOT NULL,
    detail TEXT NOT NULL DEFAULT '',
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX crm_meta_log_created_idx ON crm_meta_log (created_at DESC);

COMMIT;
