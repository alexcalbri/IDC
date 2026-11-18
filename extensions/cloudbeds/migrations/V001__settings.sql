BEGIN;

CREATE TABLE ext_cloudbeds__settings (
    id SMALLINT PRIMARY KEY DEFAULT 1 CHECK (id = 1),
    enabled BOOLEAN NOT NULL DEFAULT FALSE,
    api_key TEXT,
    property_id TEXT CHECK (property_id IS NULL OR property_id ~ '^[0-9]{1,30}$'),
    payment_method VARCHAR(10) NOT NULL DEFAULT 'cash' CHECK (payment_method IN ('cash', 'credit', 'ebanking', 'pay_pal')),
    welcome_enabled BOOLEAN NOT NULL DEFAULT FALSE,
    welcome_template_id UUID,
    welcome_days_before SMALLINT NOT NULL DEFAULT 1 CHECK (welcome_days_before BETWEEN 0 AND 60),
    welcome_hour SMALLINT NOT NULL DEFAULT 9 CHECK (welcome_hour BETWEEN 0 AND 23),
    archive_on_checkout BOOLEAN NOT NULL DEFAULT TRUE,
    last_sync TIMESTAMPTZ,
    last_error TEXT NOT NULL DEFAULT '',
    default_country TEXT NOT NULL DEFAULT '' CHECK (default_country ~ '^([A-Z]{2})?$'),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

COMMIT;
