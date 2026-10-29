BEGIN;

-- Intake: the agent suggested by the source (e.g. the agent of a listing) waits for approval, and a
-- lead whose email and phone point to different customers is flagged for review instead of merged.
ALTER TABLE crm_leads
    ADD COLUMN suggested_owner_user_id UUID REFERENCES application_users(id) ON DELETE SET NULL,
    ADD COLUMN review_reason TEXT NOT NULL DEFAULT '' CHECK (length(review_reason) <= 500);

CREATE INDEX crm_leads_suggested_owner_idx ON crm_leads (suggested_owner_user_id) WHERE suggested_owner_user_id IS NOT NULL;

-- Daily digest of each user's leads: hour (server time zone) and the last day it went out.
ALTER TABLE crm_settings
    ADD COLUMN digest_hour SMALLINT NOT NULL DEFAULT 7 CHECK (digest_hour BETWEEN 0 AND 23),
    ADD COLUMN digest_sent_on DATE;

-- How a customer is known on each channel: whatsapp:+50588887777, messenger:<PSID>, email:ana@x.com...
CREATE TABLE crm_identities (
    id UUID PRIMARY KEY,
    customer_id UUID NOT NULL REFERENCES customers(id) ON DELETE CASCADE,
    channel VARCHAR(24) NOT NULL CHECK (channel ~ '^[a-z][a-z0-9_]{0,23}$'),
    external_id TEXT NOT NULL CHECK (length(external_id) BETWEEN 1 AND 200),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT crm_identities_unique UNIQUE (channel, external_id)
);

CREATE INDEX crm_identities_customer_idx ON crm_identities (customer_id);

-- Conversation of a lead on every channel. external_id (message id of the channel) avoids storing
-- the same message twice.
CREATE TABLE crm_messages (
    id UUID PRIMARY KEY,
    lead_id UUID NOT NULL REFERENCES crm_leads(id) ON DELETE CASCADE,
    direction VARCHAR(3) NOT NULL CHECK (direction IN ('in', 'out')),
    channel VARCHAR(24) NOT NULL CHECK (channel ~ '^[a-z][a-z0-9_]{0,23}$'),
    subject TEXT NOT NULL DEFAULT '' CHECK (length(subject) <= 300),
    body TEXT NOT NULL DEFAULT '' CHECK (length(body) <= 20000),
    external_id TEXT CHECK (external_id IS NULL OR length(external_id) BETWEEN 1 AND 300),
    status VARCHAR(8) NOT NULL DEFAULT 'received' CHECK (status IN ('received', 'sent', 'failed')),
    error TEXT NOT NULL DEFAULT '' CHECK (length(error) <= 1000),
    user_id UUID REFERENCES application_users(id) ON DELETE SET NULL,
    author TEXT NOT NULL DEFAULT '',
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT crm_messages_external_unique UNIQUE (channel, external_id)
);

CREATE INDEX crm_messages_lead_idx ON crm_messages (lead_id, created_at DESC);

-- Outgoing (SMTP) and incoming (IMAP) mail of the company. smtp_mode 'servidor' uses the server SMTP.
CREATE TABLE crm_mail_settings (
    id SMALLINT PRIMARY KEY DEFAULT 1 CHECK (id = 1),
    smtp_mode VARCHAR(8) NOT NULL DEFAULT 'servidor' CHECK (smtp_mode IN ('servidor', 'empresa')),
    smtp_host TEXT,
    smtp_port INTEGER NOT NULL DEFAULT 587 CHECK (smtp_port BETWEEN 1 AND 65535),
    smtp_username TEXT,
    smtp_password TEXT,
    smtp_from TEXT,
    smtp_security VARCHAR(8) NOT NULL DEFAULT 'starttls' CHECK (smtp_security IN ('starttls', 'ssl', 'none')),
    imap_enabled BOOLEAN NOT NULL DEFAULT FALSE,
    imap_host TEXT,
    imap_port INTEGER NOT NULL DEFAULT 993 CHECK (imap_port BETWEEN 1 AND 65535),
    imap_username TEXT,
    imap_password TEXT,
    imap_folder TEXT NOT NULL DEFAULT 'INBOX',
    imap_ssl BOOLEAN NOT NULL DEFAULT TRUE,
    -- Messages with a UID up to imap_last_uid were read; a new UIDVALIDITY restarts the count.
    imap_uid_validity BIGINT,
    imap_last_uid BIGINT NOT NULL DEFAULT 0,
    imap_checked_at TIMESTAMPTZ,
    imap_last_error TEXT NOT NULL DEFAULT '',
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

INSERT INTO crm_mail_settings (id) VALUES (1);

-- Customizable emails. subject and body accept placeholders such as {nombre} or {codigo_cliente};
-- body is HTML written by the company (values are escaped when they are filled in).
CREATE TABLE crm_email_templates (
    id UUID PRIMARY KEY,
    name TEXT NOT NULL CHECK (length(trim(name)) BETWEEN 1 AND 80),
    subject TEXT NOT NULL CHECK (length(trim(subject)) BETWEEN 1 AND 300),
    body TEXT NOT NULL CHECK (length(body) BETWEEN 1 AND 50000),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT crm_email_templates_name_unique UNIQUE (name)
);

-- Emails waiting to go out (notifications, digests, scheduled emails); a job sends them.
CREATE TABLE crm_outbox (
    id UUID PRIMARY KEY,
    lead_id UUID REFERENCES crm_leads(id) ON DELETE CASCADE,
    kind VARCHAR(16) NOT NULL CHECK (kind ~ '^[a-z][a-z0-9_]{0,15}$'),
    to_email TEXT NOT NULL CHECK (length(to_email) BETWEEN 3 AND 254),
    subject TEXT NOT NULL CHECK (length(subject) <= 300),
    html TEXT NOT NULL,
    text TEXT NOT NULL,
    attempts SMALLINT NOT NULL DEFAULT 0,
    last_error TEXT NOT NULL DEFAULT '',
    sent_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX crm_outbox_pending_idx ON crm_outbox (created_at) WHERE sent_at IS NULL;

-- Content rules applied to incoming leads and messages, per channel.
CREATE TABLE crm_spam_settings (
    id SMALLINT PRIMARY KEY DEFAULT 1 CHECK (id = 1),
    words TEXT[] NOT NULL DEFAULT ARRAY['seo', 'casino', 'backlinks', 'crypto', 'viagra'],
    max_links SMALLINT NOT NULL DEFAULT 1 CHECK (max_links BETWEEN 0 AND 50),
    block_cyrillic BOOLEAN NOT NULL DEFAULT TRUE,
    blocked_domains TEXT[] NOT NULL DEFAULT '{}',
    -- Channels the rules apply to.
    channels TEXT[] NOT NULL DEFAULT ARRAY['form', 'email', 'whatsapp', 'messenger', 'instagram', 'encuentra24'],
    -- Messages from contacts that already are leads are not filtered.
    skip_known_leads BOOLEAN NOT NULL DEFAULT TRUE,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

INSERT INTO crm_spam_settings (id) VALUES (1);

-- Last blocked messages (the job keeps 50).
CREATE TABLE crm_spam_log (
    id UUID PRIMARY KEY,
    channel VARCHAR(24) NOT NULL,
    sender TEXT NOT NULL DEFAULT '',
    reason TEXT NOT NULL,
    excerpt TEXT NOT NULL DEFAULT '',
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX crm_spam_log_created_idx ON crm_spam_log (created_at DESC);

-- Per-user CRM options: the email notifications go to (users have no email in the core).
CREATE TABLE crm_user_settings (
    user_id UUID PRIMARY KEY REFERENCES application_users(id) ON DELETE CASCADE,
    email TEXT NOT NULL DEFAULT '' CHECK (length(email) <= 254),
    notify_new_leads BOOLEAN NOT NULL DEFAULT FALSE,
    notify_assigned BOOLEAN NOT NULL DEFAULT TRUE,
    daily_digest BOOLEAN NOT NULL DEFAULT FALSE
);

COMMIT;
