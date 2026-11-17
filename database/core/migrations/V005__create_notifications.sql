BEGIN;

-- Centro de notificaciones del Core: cualquier modulo avisa a un usuario a traves del servicio
-- ModuleAccessService.notifications; los modulos no tocan esta tabla. Cada empresa tiene la suya.
-- user_id no lleva clave foranea porque el dueno del servidor (usuario central) tambien recibe
-- avisos en el espacio del servidor; las notificaciones de mas de 90 dias se limpian solas.
CREATE TABLE IF NOT EXISTS notifications (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL,
    module_id VARCHAR(64) NOT NULL,
    title VARCHAR(160) NOT NULL,
    body TEXT NOT NULL DEFAULT '',
    -- Destino al hacer clic: pantalla del modulo y sus parametros (objeto JSON de texto).
    target_screen VARCHAR(120),
    target_params TEXT NOT NULL DEFAULT '{}',
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    read_at TIMESTAMPTZ
);

CREATE INDEX IF NOT EXISTS notifications_user_created_idx ON notifications (user_id, created_at DESC);
CREATE INDEX IF NOT EXISTS notifications_user_unread_idx ON notifications (user_id) WHERE read_at IS NULL;
CREATE INDEX IF NOT EXISTS notifications_created_idx ON notifications (created_at);

COMMIT;
