BEGIN;

-- Sections of the custom fields of a lead: each company names its own ("Datos de la reserva") and puts
-- fields in them. A field without section shows in "Campos personalizados". Deleting a section keeps
-- its fields (they become unsectioned).
CREATE TABLE IF NOT EXISTS crm_field_sections (
    id UUID PRIMARY KEY,
    name TEXT NOT NULL CHECK (length(trim(name)) BETWEEN 1 AND 60),
    position INTEGER NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE UNIQUE INDEX IF NOT EXISTS crm_field_sections_name_idx ON crm_field_sections (lower(name));

ALTER TABLE crm_field_definitions
    ADD COLUMN IF NOT EXISTS section_id UUID REFERENCES crm_field_sections(id) ON DELETE SET NULL;
-- Foreign key: the sets of fields of a section and the SET NULL when it is deleted.
CREATE INDEX IF NOT EXISTS crm_field_definitions_section_idx ON crm_field_definitions (section_id) WHERE section_id IS NOT NULL;

-- Besides the cf_ keys of the fields the company creates, this migration creates fecha_ingreso and
-- fecha_salida (fecha_ingreso_2... when the key is taken by another kind of field).
ALTER TABLE crm_field_definitions DROP CONSTRAINT IF EXISTS crm_field_definitions_field_key_check;
ALTER TABLE crm_field_definitions ADD CONSTRAINT crm_field_definitions_field_key_check
    CHECK (field_key ~ '^(cf_[a-z0-9_]{1,60}|fecha_(ingreso|salida)(_[0-9]{1,3})?)$');

-- Keys of the date fields that count as the check-in and the check-out of the stay (Cloudbeds, the hotspot,
-- the exit automation and the pipeline cards read them). '' means not chosen. leads.target_start and
-- target_end stay as an internal copy that the CRM fills from these fields on every save.
ALTER TABLE crm_settings
    ADD COLUMN IF NOT EXISTS stay_start_field TEXT NOT NULL DEFAULT '' CHECK (length(stay_start_field) <= 63),
    ADD COLUMN IF NOT EXISTS stay_end_field TEXT NOT NULL DEFAULT '' CHECK (length(stay_end_field) <= 63);

-- Text to date without ever failing: NULL when it is not YYYY-MM-DD or not a real day (2025-02-30). The CRM uses it to
-- copy the mapped date fields into target_start / target_end, so one bad value never aborts a save.
CREATE OR REPLACE FUNCTION crm_safe_date(value TEXT) RETURNS DATE
LANGUAGE plpgsql IMMUTABLE AS $fn$
BEGIN
    IF value IS NULL OR value !~ '^[0-9]{4}-[0-9]{2}-[0-9]{2}$' THEN
        RETURN NULL;
    END IF;
    RETURN value::date;
EXCEPTION WHEN others THEN
    RETURN NULL;
END
$fn$;

-- Companies with leads that have dates: section "Datos de la reserva" with the two date fields, the values
-- copied into custom_fields and the mapping set. Companies without dates receive nothing. Safe to run twice:
-- existing sections, fields and values are reused and never overwritten.
DO $$
DECLARE
    section_uuid UUID;
    start_key TEXT := 'fecha_ingreso';
    end_key TEXT := 'fecha_salida';
    found_type TEXT;
    attempt INTEGER;
BEGIN
    IF NOT EXISTS (SELECT 1 FROM crm_leads WHERE target_start IS NOT NULL OR target_end IS NOT NULL) THEN
        RETURN;
    END IF;

    SELECT id INTO section_uuid FROM crm_field_sections WHERE lower(name) = lower('Datos de la reserva');
    IF section_uuid IS NULL THEN
        section_uuid := gen_random_uuid();
        INSERT INTO crm_field_sections (id, name, position)
        SELECT section_uuid, 'Datos de la reserva', COALESCE(max(position) + 1, 0) FROM crm_field_sections;
    END IF;

    -- Start key: fecha_ingreso if free or already a date field, else fecha_ingreso_2, _3...
    attempt := 1;
    LOOP
        start_key := CASE WHEN attempt = 1 THEN 'fecha_ingreso' ELSE 'fecha_ingreso_' || attempt END;
        SELECT field_type INTO found_type FROM crm_field_definitions WHERE field_key = start_key;
        EXIT WHEN found_type IS NULL OR found_type = 'date' OR attempt >= 99;
        attempt := attempt + 1;
    END LOOP;
    INSERT INTO crm_field_definitions (id, field_key, label, field_type, section_id, position)
    SELECT gen_random_uuid(), start_key, 'Fecha de ingreso', 'date', section_uuid, next_position.value
    FROM (SELECT COALESCE(max(position) + 1, 0) AS value FROM crm_field_definitions) AS next_position
    WHERE NOT EXISTS (SELECT 1 FROM crm_field_definitions WHERE field_key = start_key);

    attempt := 1;
    LOOP
        end_key := CASE WHEN attempt = 1 THEN 'fecha_salida' ELSE 'fecha_salida_' || attempt END;
        SELECT field_type INTO found_type FROM crm_field_definitions WHERE field_key = end_key;
        EXIT WHEN found_type IS NULL OR found_type = 'date' OR attempt >= 99;
        attempt := attempt + 1;
    END LOOP;
    INSERT INTO crm_field_definitions (id, field_key, label, field_type, section_id, position)
    SELECT gen_random_uuid(), end_key, 'Fecha de salida', 'date', section_uuid, next_position.value
    FROM (SELECT COALESCE(max(position) + 1, 0) AS value FROM crm_field_definitions) AS next_position
    WHERE NOT EXISTS (SELECT 1 FROM crm_field_definitions WHERE field_key = end_key);

    UPDATE crm_leads SET custom_fields = custom_fields
        || CASE WHEN target_start IS NOT NULL AND NOT jsonb_exists(custom_fields, start_key)
                THEN jsonb_build_object(start_key, to_char(target_start, 'YYYY-MM-DD')) ELSE '{}'::jsonb END
        || CASE WHEN target_end IS NOT NULL AND NOT jsonb_exists(custom_fields, end_key)
                THEN jsonb_build_object(end_key, to_char(target_end, 'YYYY-MM-DD')) ELSE '{}'::jsonb END
    WHERE (target_start IS NOT NULL AND NOT jsonb_exists(custom_fields, start_key))
       OR (target_end IS NOT NULL AND NOT jsonb_exists(custom_fields, end_key));

    INSERT INTO crm_settings (id) VALUES (1) ON CONFLICT (id) DO NOTHING;
    UPDATE crm_settings SET stay_start_field = start_key WHERE id = 1 AND stay_start_field = '';
    UPDATE crm_settings SET stay_end_field = end_key WHERE id = 1 AND stay_end_field = '';
END
$$;

COMMIT;
