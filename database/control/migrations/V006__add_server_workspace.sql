BEGIN;

-- The server workspace is a company-like tenant database where server owners and their staff use
-- modules without creating a company. It is hidden from company administration.
ALTER TABLE companies
    ADD COLUMN is_server_workspace BOOLEAN NOT NULL DEFAULT FALSE;

CREATE UNIQUE INDEX companies_single_server_workspace
    ON companies (is_server_workspace)
    WHERE is_server_workspace;

COMMIT;
