BEGIN;

-- Cambio 9 (CRM 0.24.0): templates with translation, automations, button fields and new notices.

-- Translation of templates into the guest's language. The provider key is stored like the other integration
-- credentials of the company (masked on screen). The guest's language comes from a lead custom field
-- (language_field) whose values are translated to language codes with language_map ({"Español": "es", ...}).
ALTER TABLE crm_settings
    ADD COLUMN IF NOT EXISTS translation_provider TEXT NOT NULL DEFAULT ''
        CHECK (translation_provider IN ('', 'google', 'anthropic')),
    ADD COLUMN IF NOT EXISTS translation_api_key TEXT NOT NULL DEFAULT '',
    ADD COLUMN IF NOT EXISTS translation_model TEXT NOT NULL DEFAULT '',
    ADD COLUMN IF NOT EXISTS language_field TEXT NOT NULL DEFAULT '',
    ADD COLUMN IF NOT EXISTS language_map JSONB NOT NULL DEFAULT '{}'::jsonb CHECK (jsonb_typeof(language_map) = 'object');

-- Each template has a base language and can be translated automatically before sending.
ALTER TABLE crm_email_templates
    ADD COLUMN IF NOT EXISTS base_language TEXT NOT NULL DEFAULT 'es' CHECK (base_language ~ '^[a-z]{2}(-[A-Za-z]{2,4})?$'),
    ADD COLUMN IF NOT EXISTS translate BOOLEAN NOT NULL DEFAULT FALSE;

-- Cache of translated templates; content_hash is the hash of the subject and body that were translated,
-- so editing the template makes old rows unused (they are deleted when the template changes).
CREATE TABLE IF NOT EXISTS crm_template_translations (
    template_id UUID NOT NULL REFERENCES crm_email_templates(id) ON DELETE CASCADE,
    language TEXT NOT NULL CHECK (language ~ '^[a-z]{2}(-[A-Za-z]{2,4})?$'),
    content_hash TEXT NOT NULL,
    subject TEXT NOT NULL,
    body TEXT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (template_id, language, content_hash)
);

-- New personal notices: a new inbound message of an assigned lead and a follow-up that is due (email; the in-app
-- notice is always sent).
ALTER TABLE crm_user_settings
    ADD COLUMN IF NOT EXISTS notify_new_message BOOLEAN NOT NULL DEFAULT TRUE,
    ADD COLUMN IF NOT EXISTS notify_followup BOOLEAN NOT NULL DEFAULT TRUE;

-- Follow-up notices are sent once per lead and follow-up date.
ALTER TABLE crm_leads ADD COLUMN IF NOT EXISTS follow_up_notified_on DATE;

-- Custom fields of type button: config holds {"template": "<template id>"} or {"automations": ["<id>", ...]}.
ALTER TABLE crm_field_definitions DROP CONSTRAINT IF EXISTS crm_field_definitions_field_type_check;
ALTER TABLE crm_field_definitions ADD CONSTRAINT crm_field_definitions_field_type_check CHECK (
    field_type IN ('text', 'textarea', 'number', 'email', 'phone', 'url', 'date', 'boolean', 'dropdown', 'files', 'button')
);
ALTER TABLE crm_field_definitions
    ADD COLUMN IF NOT EXISTS config JSONB NOT NULL DEFAULT '{}'::jsonb CHECK (jsonb_typeof(config) = 'object');

-- Automations: a trigger, optional conditions and ordered actions (JSON validated by the CRM).
-- trigger_type: lead_created, stage_changed, field_changed, message_received, date, manual.
-- trigger_config: e.g. {"stage": "<id>"}, {"field": "cf_x"}, {"field": "fecha_ingreso", "offsetDays": -1, "hour": 9}.
-- conditions: [{"kind": "stage_in"|"interest_in"|"field_equals"|"field_not_equals"|"field_empty"|"field_filled"|"has_email"|"has_phone", ...}]
-- actions: [{"kind": "send_template"|"set_stage"|"set_field"|"notify"|"cloudbeds_reserve"|"cloudbeds_checkin", ...}]
-- warn_before_minutes: for date triggers, how long before the run to check the data the actions need.
-- notify_user_ids: who gets the missing-data notices (empty = the lead's assigned users).
CREATE TABLE IF NOT EXISTS crm_automations (
    id UUID PRIMARY KEY,
    name TEXT NOT NULL CHECK (length(trim(name)) BETWEEN 1 AND 120),
    enabled BOOLEAN NOT NULL DEFAULT FALSE,
    trigger_type TEXT NOT NULL CHECK (
        trigger_type IN ('lead_created', 'stage_changed', 'field_changed', 'message_received', 'date', 'manual')
    ),
    trigger_config JSONB NOT NULL DEFAULT '{}'::jsonb CHECK (jsonb_typeof(trigger_config) = 'object'),
    conditions JSONB NOT NULL DEFAULT '[]'::jsonb CHECK (jsonb_typeof(conditions) = 'array'),
    actions JSONB NOT NULL DEFAULT '[]'::jsonb CHECK (jsonb_typeof(actions) = 'array'),
    warn_before_minutes INTEGER NOT NULL DEFAULT 0 CHECK (warn_before_minutes BETWEEN 0 AND 43200),
    notify_user_ids JSONB NOT NULL DEFAULT '[]'::jsonb CHECK (jsonb_typeof(notify_user_ids) = 'array'),
    position INTEGER NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE UNIQUE INDEX IF NOT EXISTS crm_automations_name_idx ON crm_automations (lower(name));

-- Every run (and every missing-data notice) per lead. run_key makes date automations run once per lead and per
-- value of the date (e.g. "date:2026-12-01"), and makes warnings go out once (e.g. "warn:2026-12-01").
CREATE TABLE IF NOT EXISTS crm_automation_runs (
    id UUID PRIMARY KEY,
    automation_id UUID NOT NULL REFERENCES crm_automations(id) ON DELETE CASCADE,
    lead_id UUID NOT NULL REFERENCES crm_leads(id) ON DELETE CASCADE,
    run_key TEXT NOT NULL,
    -- pending: queued by an event, waiting for the job; running: claimed (a stale one is marked failed by the job).
    status TEXT NOT NULL CHECK (status IN ('pending', 'running', 'done', 'failed', 'missing_data', 'warned', 'skipped')),
    detail TEXT NOT NULL DEFAULT '',
    started_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE UNIQUE INDEX IF NOT EXISTS crm_automation_runs_once_idx ON crm_automation_runs (automation_id, lead_id, run_key);
CREATE INDEX IF NOT EXISTS crm_automation_runs_lead_idx ON crm_automation_runs (lead_id, created_at DESC);
CREATE INDEX IF NOT EXISTS crm_automation_runs_created_idx ON crm_automation_runs (created_at DESC);
CREATE INDEX IF NOT EXISTS crm_automation_runs_open_idx ON crm_automation_runs (created_at) WHERE status IN ('pending', 'running');

COMMIT;
