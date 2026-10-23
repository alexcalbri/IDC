BEGIN;

CREATE TABLE provisioning_audit_log (
    id UUID PRIMARY KEY,
    actor_role VARCHAR(63) NOT NULL,
    action VARCHAR(63) NOT NULL,
    company_code VARCHAR(63),
    module_id VARCHAR(63),
    details JSONB NOT NULL DEFAULT '{}'::jsonb CHECK (jsonb_typeof(details) = 'object'),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX provisioning_audit_log_created_at_idx
    ON provisioning_audit_log (created_at);

CREATE INDEX provisioning_audit_log_company_code_idx
    ON provisioning_audit_log (company_code)
    WHERE company_code IS NOT NULL;

COMMIT;
