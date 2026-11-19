BEGIN;

-- Token personal de la API de monday de la empresa, guardado una vez para que la pantalla Importar liste los tableros.
-- Se guarda sellado (enc:v1:...), asi que no lleva reglas de formato ni de largo.
CREATE TABLE ext_membresia_monday__settings (
    id SMALLINT PRIMARY KEY DEFAULT 1 CHECK (id = 1),
    token TEXT,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- Una importacion de un tablero: se lee por paginas (reading), se revisa el resumen (preview), se aplica (applying) y termina (done).
-- La lectura es reanudable: cursor guarda donde quedo; page_size baja si monday responde que la consulta es muy compleja;
-- attempts cuenta los reintentos seguidos por fallos transitorios y restarts las veces que se releyo desde cero.
CREATE TABLE ext_membresia_monday__jobs (
    id UUID PRIMARY KEY,
    board_id VARCHAR(20) NOT NULL,
    board_name VARCHAR(200) NOT NULL DEFAULT '',
    status VARCHAR(12) NOT NULL DEFAULT 'reading'
        CHECK (status IN ('reading', 'preview', 'applying', 'done', 'failed', 'cancelled')),
    mapping JSONB NOT NULL DEFAULT '{}'::jsonb,
    cursor TEXT,
    page_size INTEGER NOT NULL DEFAULT 50,
    pages_read INTEGER NOT NULL DEFAULT 0,
    items_read INTEGER NOT NULL DEFAULT 0,
    attempts INTEGER NOT NULL DEFAULT 0,
    restarts INTEGER NOT NULL DEFAULT 0,
    summary JSONB,
    total INTEGER NOT NULL DEFAULT 0,
    applied INTEGER NOT NULL DEFAULT 0,
    created INTEGER NOT NULL DEFAULT 0,
    updated INTEGER NOT NULL DEFAULT 0,
    skipped INTEGER NOT NULL DEFAULT 0,
    failed INTEGER NOT NULL DEFAULT 0,
    message TEXT NOT NULL DEFAULT '',
    created_by VARCHAR(200) NOT NULL DEFAULT '',
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX ext_membresia_monday__jobs_status_idx ON ext_membresia_monday__jobs (status, created_at);

-- Una sola importacion activa (leyendo o aplicando) por empresa.
CREATE UNIQUE INDEX ext_membresia_monday__jobs_active_idx ON ext_membresia_monday__jobs ((true)) WHERE status IN ('reading', 'applying');

-- Una fila por elemento del tablero ya convertido (payload = miembro importado); outcome queda vacio hasta aplicarla.
-- Al terminar, fallar o cancelar el trabajo el payload se vacia: no se guardan datos personales mas de lo necesario.
CREATE TABLE ext_membresia_monday__rows (
    job_id UUID NOT NULL REFERENCES ext_membresia_monday__jobs (id) ON DELETE CASCADE,
    row_no INTEGER NOT NULL,
    payload JSONB NOT NULL,
    outcome VARCHAR(10) CHECK (outcome IN ('created', 'updated', 'skipped', 'failed')),
    message TEXT NOT NULL DEFAULT '',
    PRIMARY KEY (job_id, row_no)
);

CREATE INDEX ext_membresia_monday__rows_pending_idx ON ext_membresia_monday__rows (job_id, row_no) WHERE outcome IS NULL;

COMMIT;
