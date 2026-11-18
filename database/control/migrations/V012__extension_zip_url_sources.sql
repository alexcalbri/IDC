BEGIN;

-- Un paquete externo tambien puede instalarse desde un ZIP subido ('zip') o desde una URL https ('url').
-- Solo 'github' sigue exigiendo una URL fijada a un commit de 40 hex; en 'zip' y 'url' source_url es
-- informativo ('zip:<nombre>' o la URL de descarga). La seguridad la dan la firma o la autorizacion por hash.
ALTER TABLE server_extensions DROP CONSTRAINT IF EXISTS server_extensions_source_type_check;
ALTER TABLE server_extensions
    ADD CONSTRAINT server_extensions_source_type_check
    CHECK (source_type IN ('bundled', 'github', 'zip', 'url'));

COMMIT;
