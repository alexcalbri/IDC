BEGIN;

CREATE TABLE customers (
    id UUID NOT NULL,
    display_name TEXT NOT NULL CHECK (length(trim(display_name)) > 0),
    primary_email TEXT,
    primary_phone TEXT,
    status VARCHAR(32) NOT NULL DEFAULT 'active'
        CHECK (status IN ('prospect', 'active', 'inactive', 'archived')),
    flexible_attributes JSONB NOT NULL DEFAULT '{}'::jsonb
        CHECK (jsonb_typeof(flexible_attributes) = 'object'),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT customers_pkey
        PRIMARY KEY (id)
);

CREATE INDEX customers_display_name_idx
    ON customers (lower(display_name));

CREATE INDEX customers_primary_email_idx
    ON customers (lower(primary_email))
    WHERE primary_email IS NOT NULL;

CREATE INDEX customers_primary_phone_idx
    ON customers (primary_phone)
    WHERE primary_phone IS NOT NULL;

CREATE INDEX customers_flexible_attributes_gin_idx
    ON customers USING GIN (flexible_attributes);

CREATE TABLE customer_field_definitions (
    id UUID NOT NULL,
    field_key VARCHAR(63) NOT NULL CHECK (field_key ~ '^[a-z][a-z0-9_]{0,62}$'),
    label TEXT NOT NULL CHECK (length(trim(label)) > 0),
    field_type VARCHAR(32) NOT NULL CHECK (
        field_type IN (
            'text',
            'textarea',
            'number',
            'money',
            'date',
            'datetime',
            'email',
            'phone',
            'boolean',
            'select',
            'multi_select'
        )
    ),
    control_type VARCHAR(32) NOT NULL DEFAULT 'text',
    is_required BOOLEAN NOT NULL DEFAULT FALSE,
    is_active BOOLEAN NOT NULL DEFAULT TRUE,
    display_order INTEGER NOT NULL DEFAULT 0 CHECK (display_order >= 0),
    options JSONB NOT NULL DEFAULT '[]'::jsonb CHECK (jsonb_typeof(options) = 'array'),
    validation_rules JSONB NOT NULL DEFAULT '{}'::jsonb
        CHECK (jsonb_typeof(validation_rules) = 'object'),
    default_value JSONB,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT customer_field_definitions_pkey
        PRIMARY KEY (id),

    CONSTRAINT customer_field_definitions_field_key_unique
        UNIQUE (field_key)
);

CREATE INDEX customer_field_definitions_active_order_idx
    ON customer_field_definitions (is_active, display_order, field_key);

COMMIT;
