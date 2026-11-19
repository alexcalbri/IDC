BEGIN;

-- Imported data keeps the key it had in the source (for example monday:board:123), so importing again updates instead of duplicating.
-- Tasks use source + external_ref (unique per board since V001).
ALTER TABLE tareas_boards ADD COLUMN import_key VARCHAR(200) NULL;
CREATE UNIQUE INDEX tareas_boards_import_key_idx ON tareas_boards (import_key) WHERE import_key IS NOT NULL;

ALTER TABLE tareas_checklist ADD COLUMN import_key VARCHAR(200) NULL;
CREATE UNIQUE INDEX tareas_checklist_import_key_idx ON tareas_checklist (task_id, import_key) WHERE import_key IS NOT NULL;

ALTER TABLE tareas_comments ADD COLUMN import_key VARCHAR(200) NULL;
CREATE UNIQUE INDEX tareas_comments_import_key_idx ON tareas_comments (task_id, import_key) WHERE import_key IS NOT NULL;

ALTER TABLE tareas_attachments ADD COLUMN import_key VARCHAR(200) NULL;
CREATE UNIQUE INDEX tareas_attachments_import_key_idx ON tareas_attachments (task_id, import_key) WHERE import_key IS NOT NULL;

COMMIT;
