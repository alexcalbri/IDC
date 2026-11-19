BEGIN;

-- Clave de importacion: identifica la fila de origen (por ejemplo un item de Monday) para que importar dos veces
-- actualice en lugar de duplicar.
ALTER TABLE membresia_memberships ADD COLUMN import_key VARCHAR(160);
CREATE UNIQUE INDEX membresia_memberships_import_key_idx ON membresia_memberships (import_key) WHERE import_key IS NOT NULL;

ALTER TABLE membresia_visits ADD COLUMN import_key VARCHAR(160);
CREATE UNIQUE INDEX membresia_visits_import_key_idx ON membresia_visits (import_key) WHERE import_key IS NOT NULL;

COMMIT;
