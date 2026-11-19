BEGIN;

-- Claves de importacion: identifican de forma unica lo que una fuente externa (p. ej. "monday:123") ya trajo,
-- para que importar dos veces no duplique solicitudes, cotizaciones ni archivos. NULL = creado en el modulo.
ALTER TABLE compras_requests ADD COLUMN import_key TEXT UNIQUE CHECK (import_key IS NULL OR length(import_key) BETWEEN 1 AND 200);
ALTER TABLE compras_requests ADD COLUMN imported_at TIMESTAMPTZ;
ALTER TABLE compras_quotes ADD COLUMN import_key TEXT UNIQUE CHECK (import_key IS NULL OR length(import_key) BETWEEN 1 AND 200);
ALTER TABLE compras_attachments ADD COLUMN import_key TEXT UNIQUE CHECK (import_key IS NULL OR length(import_key) BETWEEN 1 AND 200);

-- V001 dejo sin nombre dos CHECK; se localizan por su definicion en pg_constraint y se reemplazan.
DO $$
DECLARE
    found RECORD;
    dropped INTEGER := 0;
BEGIN
    -- Aprobada, rechazada y en progreso tienen quien decidio, salvo las importadas (decision "Importado de ...").
    FOR found IN
        SELECT conname FROM pg_constraint
        WHERE conrelid = 'compras_requests'::regclass AND contype = 'c'
          AND pg_get_constraintdef(oid) LIKE '%decided\_by IS NOT NULL%'
          AND pg_get_constraintdef(oid) LIKE '%in\_progress%'
          AND pg_get_constraintdef(oid) NOT LIKE '%import\_key%'
    LOOP
        EXECUTE format('ALTER TABLE compras_requests DROP CONSTRAINT %I', found.conname);
        dropped := dropped + 1;
    END LOOP;
    IF dropped <> 1 THEN
        RAISE EXCEPTION 'V002: se esperaba 1 CHECK de decided_by en compras_requests y se encontraron %', dropped;
    END IF;

    -- El historial acepta el evento "imported".
    dropped := 0;
    FOR found IN
        SELECT conname FROM pg_constraint
        WHERE conrelid = 'compras_events'::regclass AND contype = 'c'
          AND pg_get_constraintdef(oid) LIKE '%attachment%'
          AND pg_get_constraintdef(oid) LIKE '%created%'
          AND pg_get_constraintdef(oid) NOT LIKE '%imported%'
    LOOP
        EXECUTE format('ALTER TABLE compras_events DROP CONSTRAINT %I', found.conname);
        dropped := dropped + 1;
    END LOOP;
    IF dropped <> 1 THEN
        RAISE EXCEPTION 'V002: se esperaba 1 CHECK de kind en compras_events y se encontraron %', dropped;
    END IF;
END
$$;

ALTER TABLE compras_requests ADD CONSTRAINT compras_requests_decided_by_check
    CHECK (status NOT IN ('approved', 'rejected', 'in_progress') OR decided_by IS NOT NULL OR import_key IS NOT NULL);

ALTER TABLE compras_events ADD CONSTRAINT compras_events_kind_check
    CHECK (kind IN ('created', 'edited', 'status', 'quote', 'attachment', 'imported'));

COMMIT;
