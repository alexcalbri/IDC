BEGIN;

-- Cambio 11 (CRM 0.25.0): las integraciones pasan a extensiones por empresa y sus secretos se guardan cifrados.

-- Los secretos se guardan sellados (enc:v1:...). El secreto de WordPress tenia formato y largo fijos; esa regla ya no existe
-- porque los ajustes de WordPress viven en la tabla de su extension (ext_wordpress__settings), sin esa restriccion.

-- Campos personalizados creados por una extension (p. ej. la importacion de una fuente externa): al desinstalarla, el usuario
-- decide si se conservan o se borran. Nulo = creado por la empresa.
ALTER TABLE crm_field_definitions
    ADD COLUMN IF NOT EXISTS extension_id TEXT CHECK (extension_id IS NULL OR extension_id ~ '^[a-z][a-z0-9_]{0,62}$');
CREATE INDEX IF NOT EXISTS crm_field_definitions_extension_idx ON crm_field_definitions (extension_id) WHERE extension_id IS NOT NULL;

-- Marca de la migracion automatica: cuando el trabajo de arranque registro como extensiones las integraciones ya
-- configuradas de esta empresa y cifro sus secretos. Nulo = pendiente.
ALTER TABLE crm_settings ADD COLUMN IF NOT EXISTS extensions_migrated_at TIMESTAMPTZ;

-- El indice unico de las rutas de Meta (una pagina, una cuenta de Instagram o una de WhatsApp pertenece a una sola empresa) ahora
-- lo crea la extension "meta" en ext_meta__routes (extensions/meta/migrations/V001__settings.sql).

COMMIT;
