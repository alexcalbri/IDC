#!/usr/bin/env bash
# Sourced by the fresh-install script. Run as the provisioning administrator.
# Migration files must contain one outer BEGIN/COMMIT and transactional SQL.
apply_migration() {
    local target=$1 module=$2 file=$3 version checksum
    version=$(basename "$file" .sql)
    checksum=$(sha256sum "$file")
    checksum=${checksum%% *}
    # Keep the history record and schema changes in the same transaction.
    {
        cat <<'SQL'
BEGIN;
SELECT pg_advisory_xact_lock(735192401);
CREATE TABLE IF NOT EXISTS schema_migrations (
    module TEXT NOT NULL,
    version TEXT NOT NULL,
    checksum TEXT NOT NULL,
    applied_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (module, version)
);
SELECT EXISTS (SELECT 1 FROM schema_migrations WHERE module = :'module' AND version = :'version') AS applied,
       EXISTS (SELECT 1 FROM schema_migrations WHERE module = :'module' AND version = :'version' AND checksum <> :'checksum') AS changed \gset
\if :changed
    DO $$ BEGIN RAISE EXCEPTION 'An applied migration has changed'; END $$;
\endif
\if :applied
    \echo Migration already applied
\else
SQL
        python3 - "$file" <<'PY'
import pathlib, sys
sql = pathlib.Path(sys.argv[1]).read_text().strip()
if not sql.startswith('BEGIN;') or not sql.endswith('COMMIT;'):
    sys.exit('Migration must have outer BEGIN/COMMIT')
print(sql[len('BEGIN;'):-len('COMMIT;')])
PY
        # Stop the stream on invalid files so psql cannot commit partial work.
        [[ $? == 0 ]] || return 1
        cat <<'SQL'
INSERT INTO schema_migrations (module, version, checksum) VALUES (:'module', :'version', :'checksum');
\endif
COMMIT;
SQL
    } | runuser -u postgres -- psql -X -d "$target" -v ON_ERROR_STOP=1 \
        -v module="$module" -v version="$version" -v checksum="$checksum"
}
