BEGIN;

-- The built-in sections of the lead form become rows of crm_field_sections (system_key), so every section, built-in or
-- created by the company, can be ordered and can hold custom fields. Built-in sections keep their name and are not deleted;
-- "Datos del cliente" (customer) is always first (the CRM pins it, its position is 0).
ALTER TABLE crm_field_sections ADD COLUMN IF NOT EXISTS system_key TEXT;
CREATE UNIQUE INDEX IF NOT EXISTS crm_field_sections_system_key_idx ON crm_field_sections (system_key) WHERE system_key IS NOT NULL;
ALTER TABLE crm_field_sections DROP CONSTRAINT IF EXISTS crm_field_sections_system_key_check;
ALTER TABLE crm_field_sections ADD CONSTRAINT crm_field_sections_system_key_check
    CHECK (system_key IS NULL OR system_key IN ('customer', 'followup', 'message', 'documents'));

DO $$
BEGIN
    -- Only the first time: the company's own sections go after the four built-in ones, in the same relative order.
    IF NOT EXISTS (SELECT 1 FROM crm_field_sections WHERE system_key IS NOT NULL) THEN
        -- A custom section that already has one of the built-in names is renamed so the names stay unique.
        UPDATE crm_field_sections SET name = left(name, 50) || ' (propia)'
        WHERE lower(name) IN ('datos del cliente', 'seguimiento', 'mensaje y detalles', 'documentos');
        UPDATE crm_field_sections SET position = position + 4;
    END IF;
END
$$;

-- One row per built-in section, in today's order of the form; idempotent by system_key.
INSERT INTO crm_field_sections (id, name, position, system_key)
SELECT gen_random_uuid(), v.name, v.position, v.system_key
FROM (VALUES
    ('customer', 'Datos del cliente', 0),
    ('followup', 'Seguimiento', 1),
    ('message', 'Mensaje y detalles', 2),
    ('documents', 'Documentos', 3)
) AS v(system_key, name, position)
WHERE NOT EXISTS (SELECT 1 FROM crm_field_sections s WHERE s.system_key = v.system_key);

COMMIT;
