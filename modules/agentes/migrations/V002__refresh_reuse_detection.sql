BEGIN;

-- Hash del refresh token anterior (el ya rotado) de las conexiones OAuth y cuando se roto. Si alguien lo presenta de nuevo
-- pasada una breve ventana de gracia es un reuso (el token se filtro): la conexion se revoca.
ALTER TABLE agent_tokens ADD COLUMN IF NOT EXISTS previous_refresh_hash VARCHAR(64);
ALTER TABLE agent_tokens ADD COLUMN IF NOT EXISTS previous_rotated_at TIMESTAMPTZ;

CREATE INDEX IF NOT EXISTS agent_tokens_previous_refresh_idx ON agent_tokens (previous_refresh_hash) WHERE previous_refresh_hash IS NOT NULL;

COMMIT;
