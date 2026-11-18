BEGIN;

-- Meta (WhatsApp, Messenger e Instagram) ya no vive en el CRM: sus tablas (ext_meta__settings, ext_meta__log, ...) las crea la
-- extension "meta" (extensions/meta/migrations/V001__settings.sql) en cada empresa donde se instala.

COMMIT;
