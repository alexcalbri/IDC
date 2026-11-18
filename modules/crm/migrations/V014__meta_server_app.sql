BEGIN;

-- El modo de conexion de Meta (connection_mode, oauth_token, meta_user_id), la app de Meta del dueno del servidor, las rutas por
-- activo y los enlaces de conexion ahora son tablas de la extension "meta" (ext_meta__settings, ext_meta__server_app,
-- ext_meta__routes y ext_meta__tickets), creadas por extensions/meta/migrations/V001__settings.sql.

COMMIT;
