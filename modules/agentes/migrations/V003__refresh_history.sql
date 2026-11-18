BEGIN;

-- Historial de refresh tokens ya rotados de cada conexion OAuth (la "familia"). Si alguien presenta de nuevo CUALQUIER
-- generacion anterior pasada la ventana de gracia es un reuso (el token se filtro): la conexion se revoca.
-- Solo se guarda el hash; se borra junto con la conexion.
CREATE TABLE IF NOT EXISTS agent_refresh_history (
    token_id UUID NOT NULL REFERENCES agent_tokens (id) ON DELETE CASCADE,
    refresh_hash VARCHAR(64) NOT NULL,
    rotated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (token_id, refresh_hash)
);

CREATE INDEX IF NOT EXISTS agent_refresh_history_hash_idx ON agent_refresh_history (refresh_hash);

-- Conserva la generacion que ya se recordaba en agent_tokens.previous_refresh_hash.
INSERT INTO agent_refresh_history (token_id, refresh_hash, rotated_at)
SELECT id, previous_refresh_hash, COALESCE(previous_rotated_at, CURRENT_TIMESTAMP)
FROM agent_tokens
WHERE previous_refresh_hash IS NOT NULL
ON CONFLICT DO NOTHING;

-- Reversa manual: DROP TABLE agent_refresh_history; (previous_refresh_hash sigue intacto).

COMMIT;
