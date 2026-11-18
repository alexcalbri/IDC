BEGIN;

-- Cambio 11 (CRM 0.25.0): las integraciones pasan a extensiones por empresa y sus secretos se guardan cifrados.

-- Los secretos se guardan sellados (enc:v1:...). El secreto de WordPress y el token de monday eran de formato y largo
-- fijos: el valor cifrado ya no cabe en esas reglas, asi que se quitan (el CRM sigue generando el secreto de 48 caracteres).
ALTER TABLE crm_wordpress_settings DROP CONSTRAINT IF EXISTS crm_wordpress_settings_secret_check;
ALTER TABLE crm_settings DROP CONSTRAINT IF EXISTS crm_settings_monday_token_check;

-- Campos personalizados creados por una extension (p. ej. la importacion de monday): al desinstalarla, el usuario
-- decide si se conservan o se borran. Nulo = creado por la empresa.
ALTER TABLE crm_field_definitions
    ADD COLUMN IF NOT EXISTS extension_id TEXT CHECK (extension_id IS NULL OR extension_id ~ '^[a-z][a-z0-9_]{0,62}$');
CREATE INDEX IF NOT EXISTS crm_field_definitions_extension_idx ON crm_field_definitions (extension_id) WHERE extension_id IS NOT NULL;

-- Marca de la migracion automatica: cuando el trabajo de arranque registro como extensiones las integraciones ya
-- configuradas de esta empresa y cifro sus secretos. Nulo = pendiente.
ALTER TABLE crm_settings ADD COLUMN IF NOT EXISTS extensions_migrated_at TIMESTAMPTZ;

-- Una pagina, una cuenta de Instagram o una cuenta de WhatsApp de la app del servidor pertenece a una sola empresa
-- (ya existia desde V014; se repite con IF NOT EXISTS para que el indice este en cualquier base).
CREATE UNIQUE INDEX IF NOT EXISTS crm_meta_routes_asset_idx ON crm_meta_routes (kind, asset_id) WHERE kind <> 'user';

COMMIT;
