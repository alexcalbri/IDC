BEGIN;

-- El token de la API de monday ya no es parte del CRM: vive en la tabla ext_monday__settings de la extension "monday"
-- (extensions/monday/migrations/V001__settings.sql). Esta migracion queda sin cambios de esquema.

COMMIT;
