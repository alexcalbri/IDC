BEGIN;

-- Token personal de la API de monday de la empresa. Se guarda sellado (enc:v1:...), asi que no lleva reglas de formato ni de largo.
CREATE TABLE ext_compras_monday__settings (
    id SMALLINT PRIMARY KEY DEFAULT 1 CHECK (id = 1),
    token TEXT,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- Una importacion: el tablero, los grupos elegidos (propiedades), el mapeo de columnas, el avance de la lectura y los conteos.
-- attempts cuenta los fallos seguidos de lectura (se reinicia al leer bien) y pages las paginas leidas; applied_by es quien pulso Importar.
CREATE TABLE ext_compras_monday__jobs (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    board_id TEXT NOT NULL,
    board_name TEXT NOT NULL DEFAULT '',
    groups JSONB NOT NULL DEFAULT '[]',
    mapping JSONB NOT NULL DEFAULT '{}',
    status TEXT NOT NULL DEFAULT 'reading' CHECK (status IN ('reading', 'ready', 'applying', 'done', 'failed', 'cancelled')),
    cursor TEXT,
    attempts INTEGER NOT NULL DEFAULT 0,
    pages INTEGER NOT NULL DEFAULT 0,
    read_count INTEGER NOT NULL DEFAULT 0,
    created_count INTEGER NOT NULL DEFAULT 0,
    existing_count INTEGER NOT NULL DEFAULT 0,
    failed_count INTEGER NOT NULL DEFAULT 0,
    error TEXT NOT NULL DEFAULT '',
    created_by UUID,
    created_by_name TEXT NOT NULL DEFAULT '',
    applied_by UUID,
    applied_by_name TEXT NOT NULL DEFAULT '',
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX ext_compras_monday__jobs_status_idx ON ext_compras_monday__jobs (status, created_at);

-- Una sola importacion en marcha por empresa: la base lo garantiza aunque dos peticiones lleguen a la vez.
CREATE UNIQUE INDEX ext_compras_monday__jobs_one_active_idx ON ext_compras_monday__jobs ((true)) WHERE status IN ('reading', 'ready', 'applying');

-- Filas ya leidas de monday, preparadas para aplicar (una por elemento del tablero).
CREATE TABLE ext_compras_monday__rows (
    job_id UUID NOT NULL REFERENCES ext_compras_monday__jobs (id) ON DELETE CASCADE,
    item_id TEXT NOT NULL,
    payload JSONB NOT NULL,
    status TEXT NOT NULL DEFAULT 'pending' CHECK (status IN ('pending', 'applied', 'existing', 'failed')),
    problem TEXT NOT NULL DEFAULT '',
    PRIMARY KEY (job_id, item_id)
);

CREATE INDEX ext_compras_monday__rows_pending_idx ON ext_compras_monday__rows (job_id, item_id) WHERE status = 'pending';

COMMIT;
