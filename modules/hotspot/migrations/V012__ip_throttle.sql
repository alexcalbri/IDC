BEGIN;

-- Limite de solicitudes del portal por IP de origen (IPv6 agrupada por /64) en una ventana fija.
-- Antes vivia en memoria (se perdia al reiniciar). action: 'register' o 'code'.
CREATE TABLE IF NOT EXISTS hotspot_ip_throttle (
    ip_group TEXT NOT NULL CHECK (length(ip_group) BETWEEN 1 AND 64),
    action VARCHAR(16) NOT NULL CHECK (action IN ('register', 'code')),
    window_start TIMESTAMPTZ NOT NULL,
    hits INTEGER NOT NULL DEFAULT 0 CHECK (hits >= 0),

    PRIMARY KEY (ip_group, action, window_start)
);

-- Para borrar ventanas vencidas.
CREATE INDEX IF NOT EXISTS hotspot_ip_throttle_window_idx ON hotspot_ip_throttle (window_start);

COMMIT;
