BEGIN;

-- Token personal de la API de monday de la empresa (una sola fila, id = 1). Se guarda sellado (enc:v1:...),
-- por eso no lleva reglas de formato ni de largo.
CREATE TABLE ext_inmobiliaria_monday__settings (
    id SMALLINT PRIMARY KEY DEFAULT 1 CHECK (id = 1),
    token TEXT,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- Una importacion: un tablero de monday leido hacia filas y aplicado despues a la extension inmobiliaria.
-- El usuario que la lanzo va sin clave foranea (una migracion de extension no puede apuntar a tablas del nucleo).
CREATE TABLE ext_inmobiliaria_monday__jobs (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    board_id TEXT NOT NULL CHECK (board_id ~ '^[0-9]{1,20}$'),
    board_name TEXT NOT NULL DEFAULT '' CHECK (length(board_name) <= 300),
    kind TEXT NOT NULL CHECK (kind IN ('property', 'deal')),
    new_listings BOOLEAN NOT NULL DEFAULT FALSE,
    -- Columna de monday -> campo de la importacion; arreglo de columnas (titulo y tipo) en el orden del tablero.
    mapping JSONB NOT NULL DEFAULT '{}' CHECK (mapping::text ~ '^[{]'),
    columns JSONB NOT NULL DEFAULT '[]' CHECK (columns::text ~ '^[[]'),
    state TEXT NOT NULL DEFAULT 'reading' CHECK (state IN ('reading', 'ready', 'applying', 'done', 'failed')),
    -- Lectura por paginas: el cursor de monday permite seguir en la siguiente corrida.
    read_started BOOLEAN NOT NULL DEFAULT FALSE,
    read_cursor TEXT NOT NULL DEFAULT '',
    rows_read INTEGER NOT NULL DEFAULT 0 CHECK (rows_read >= 0),
    created_count INTEGER NOT NULL DEFAULT 0 CHECK (created_count >= 0),
    updated_count INTEGER NOT NULL DEFAULT 0 CHECK (updated_count >= 0),
    skipped_count INTEGER NOT NULL DEFAULT 0 CHECK (skipped_count >= 0),
    failed_count INTEGER NOT NULL DEFAULT 0 CHECK (failed_count >= 0),
    problems JSONB NOT NULL DEFAULT '[]' CHECK (problems::text ~ '^[[]'),
    error TEXT NOT NULL DEFAULT '' CHECK (length(error) <= 1000),
    created_by UUID,
    created_by_name TEXT NOT NULL DEFAULT '' CHECK (length(created_by_name) <= 200),
    -- Quien pulso Aplicar: es quien actua en Inmobiliaria (y se le vuelve a comprobar el permiso al aplicar).
    applied_by UUID,
    applied_by_name TEXT NOT NULL DEFAULT '' CHECK (length(applied_by_name) <= 200),
    -- Intentos de lectura que fallaron por un error transitorio (red, 429, 5xx); al quinto la importacion falla.
    read_attempts INTEGER NOT NULL DEFAULT 0 CHECK (read_attempts >= 0),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX ext_inmobiliaria_monday__jobs_state_idx ON ext_inmobiliaria_monday__jobs (state);
CREATE INDEX ext_inmobiliaria_monday__jobs_created_idx ON ext_inmobiliaria_monday__jobs (created_at);
-- Una sola importacion activa (leyendo o aplicando) por tablero.
CREATE UNIQUE INDEX ext_inmobiliaria_monday__jobs_active_uq ON ext_inmobiliaria_monday__jobs (board_id) WHERE state IN ('reading', 'applying');

-- Filas leidas de monday (un elemento por fila). data guarda el valor de cada columna por su titulo.
CREATE TABLE ext_inmobiliaria_monday__rows (
    id BIGSERIAL PRIMARY KEY,
    job_id UUID NOT NULL REFERENCES ext_inmobiliaria_monday__jobs (id) ON DELETE CASCADE,
    item_id TEXT NOT NULL CHECK (item_id ~ '^[0-9]{1,20}$'),
    import_key TEXT NOT NULL CHECK (length(import_key) <= 120),
    data JSONB NOT NULL DEFAULT '{}' CHECK (data::text ~ '^[{]'),
    state TEXT NOT NULL DEFAULT 'pending' CHECK (state IN ('pending', 'applied', 'failed')),
    problem TEXT NOT NULL DEFAULT '' CHECK (length(problem) <= 1000),
    CONSTRAINT ext_inmobiliaria_monday__rows_item_uq UNIQUE (job_id, item_id)
);

CREATE INDEX ext_inmobiliaria_monday__rows_state_idx ON ext_inmobiliaria_monday__rows (job_id, state);

COMMIT;
