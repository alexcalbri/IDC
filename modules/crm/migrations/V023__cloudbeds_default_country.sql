BEGIN;

-- CRM 0.25.0: Cloudbeds rejects a reservation without the guest's country. The automations have no form to ask for
-- it, so the company sets a default (ISO 3166-1 alpha-2, e.g. 'US'); the reservation form starts with it too.
ALTER TABLE crm_cloudbeds_settings
    ADD COLUMN IF NOT EXISTS default_country TEXT NOT NULL DEFAULT '' CHECK (default_country ~ '^([A-Z]{2})?$');

COMMIT;
