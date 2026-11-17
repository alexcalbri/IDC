BEGIN;

-- Solicitudes de actualizacion del Core hechas desde la app. El ayudante root del servidor las ejecuta
-- y es el unico que hace UPDATE; el backend solo inserta y consulta.
CREATE TABLE IF NOT EXISTS core_update_requests (
    id BIGSERIAL PRIMARY KEY,
    target_version TEXT NOT NULL CHECK (target_version ~ '^[0-9]+\.[0-9]+\.[0-9]+$'),
    from_version TEXT NOT NULL,
    status TEXT NOT NULL DEFAULT 'pending' CHECK (status IN ('pending', 'running', 'done', 'error')),
    requested_by TEXT NOT NULL,
    requested_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    started_at TIMESTAMPTZ,
    finished_at TIMESTAMPTZ,
    message TEXT,
    log_tail TEXT
);

-- Solo una solicitud activa a la vez.
CREATE UNIQUE INDEX IF NOT EXISTS core_update_requests_one_active
    ON core_update_requests ((true)) WHERE status IN ('pending', 'running');

COMMIT;
