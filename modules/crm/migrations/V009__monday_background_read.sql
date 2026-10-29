BEGIN;

-- monday boards are read by the import job instead of the request (a big board takes minutes):
-- 'reading' waits for that job and 'failed' keeps the reason in error.
ALTER TABLE crm_import_jobs
    DROP CONSTRAINT IF EXISTS crm_import_jobs_status_check,
    ADD CONSTRAINT crm_import_jobs_status_check CHECK (status IN ('reading', 'mapping', 'running', 'done', 'canceled', 'failed')),
    ADD COLUMN boards JSONB NOT NULL DEFAULT '[]'::jsonb CHECK (jsonb_typeof(boards) = 'array'),
    ADD COLUMN error TEXT NOT NULL DEFAULT '';

COMMIT;
