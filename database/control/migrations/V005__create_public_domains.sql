BEGIN;

-- Public domains that modules serve without a user session (e.g. hotspot portals).
-- The root helper scripts/ubuntu/domains-sync.sh publishes them in Nginx and
-- obtains certificates; the backend resolves the request Host to a company here.
CREATE TABLE public_domains (
    domain VARCHAR(253) PRIMARY KEY
        CHECK (domain ~ '^([a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?\.)+[a-z][a-z0-9-]{0,61}[a-z0-9]$'),
    company_code VARCHAR(63) NOT NULL REFERENCES companies(code) ON DELETE CASCADE,
    module_id TEXT NOT NULL CHECK (length(trim(module_id)) > 0),
    status VARCHAR(16) NOT NULL DEFAULT 'pending'
        CHECK (status IN ('pending', 'active', 'error', 'removing')),
    last_error TEXT,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT public_domains_company_module_unique
        UNIQUE (company_code, module_id)
);

CREATE INDEX public_domains_status_idx
    ON public_domains (status);

COMMIT;
