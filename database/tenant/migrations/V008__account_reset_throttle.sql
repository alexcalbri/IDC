BEGIN;

-- Contadores acumulados de la recuperacion de contrasena por usuario en una ventana de 24 h:
-- codigos emitidos y codigos incorrectos. Antes vivian en memoria (se perdian al reiniciar).
-- La tabla es de esta empresa, asi que basta el nombre de usuario como clave.
CREATE TABLE IF NOT EXISTS account_reset_throttle (
    user_name TEXT PRIMARY KEY CHECK (length(user_name) BETWEEN 1 AND 63),
    window_start TIMESTAMPTZ NOT NULL,
    issued INTEGER NOT NULL DEFAULT 0 CHECK (issued >= 0),
    failures INTEGER NOT NULL DEFAULT 0 CHECK (failures >= 0),
    last_issued TIMESTAMPTZ
);

COMMIT;
