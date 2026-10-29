BEGIN;

-- Personal API token of monday, saved once so the import screen can list the boards to choose from.
ALTER TABLE crm_settings ADD COLUMN monday_token TEXT CHECK (monday_token IS NULL OR length(monday_token) BETWEEN 1 AND 2000);

COMMIT;
