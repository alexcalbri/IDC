#!/usr/bin/env bash
# Update an existing IdeasCore Ubuntu installation created by install.sh.
if [ -z "${BASH_VERSION:-}" ]; then
    if command -v bash >/dev/null 2>&1 && [ -f "$0" ]; then
        exec bash -- "$0" "$@"
    fi
    printf '%s\n' 'Este actualizador requiere Bash. Ejecuta: sudo bash update.sh' >&2
    exit 1
fi

set -Eeuo pipefail
set +x
umask 077

die() { printf 'ERROR: %s\n' "$*" >&2; exit 1; }

ask() {
    local value
    read -r -p "$2 [$3]: " value </dev/tty
    printf -v "$1" '%s' "${value:-$3}"
}

git_source() {
    runuser -u ideascore -- git -C "$SOURCE_DIR" "$@"
}

[[ $EUID -eq 0 ]] || die 'Ejecuta con sudo bash update.sh.'

# Modo no interactivo (lo usa ideascore-core-update): sin preguntas, commit exacto y destino fijos.
NON_INTERACTIVE=false
ARG_COMMIT=''
ARG_TARGET=''
while (( $# > 0 )); do
    case "$1" in
        --non-interactive) NON_INTERACTIVE=true; shift ;;
        --commit) [[ $# -ge 2 ]] || die '--commit requiere un valor.'; ARG_COMMIT=$2; shift 2 ;;
        --target) [[ $# -ge 2 ]] || die '--target requiere un valor.'; ARG_TARGET=$2; shift 2 ;;
        *) die "Argumento desconocido: $1" ;;
    esac
done
# Se reconstruyen para el re-exec bajo flock.
SCRIPT_ARGS=()
if [[ $NON_INTERACTIVE == true ]]; then
    [[ $ARG_COMMIT =~ ^[0-9a-f]{40}$ ]] || die '--commit debe ser un sha completo de 40 caracteres hexadecimales.'
    case "$ARG_TARGET" in
        server|webapp|ambos) ;;
        *) die '--target debe ser server, webapp o ambos.' ;;
    esac
    SCRIPT_ARGS=(--non-interactive --commit "$ARG_COMMIT" --target "$ARG_TARGET")
else
    [[ -z $ARG_COMMIT && -z $ARG_TARGET ]] || die '--commit y --target solo se usan con --non-interactive.'
    [[ -r /dev/tty ]] || die 'Se necesita una terminal interactiva; usa ssh -t.'
fi

# One install or update at a time (install.sh takes the same lock): the script runs again under flock,
# which holds the lock until it ends.
# -o keeps the lock out of child processes, so a Gradle daemon left running cannot keep it held.
readonly MAINTENANCE_LOCK_FILE=/run/ideascore-maintenance.lock
readonly MAINTENANCE_LOCK_BUSY=75
if [[ ${IDEASCORE_MAINTENANCE_LOCKED:-} != 1 ]]; then
    [[ -f $0 ]] || die 'Descarga update.sh y ejecutalo como archivo: sudo bash update.sh'
    command -v flock >/dev/null 2>&1 || die 'No se encontro flock (paquete util-linux).'
    lock_status=0
    flock -n -o -E "$MAINTENANCE_LOCK_BUSY" "$MAINTENANCE_LOCK_FILE" \
        env IDEASCORE_MAINTENANCE_LOCKED=1 bash -- "$0" ${SCRIPT_ARGS[@]+"${SCRIPT_ARGS[@]}"} || lock_status=$?
    (( lock_status != MAINTENANCE_LOCK_BUSY )) || die 'Ya hay una instalacion o actualizacion de IdeasCore en curso. Espera a que termine.'
    exit "$lock_status"
fi

readonly APP_DIR=/opt/ideascore
readonly SOURCE_DIR=$APP_DIR/source
readonly APP_RUNTIME_DIR=$APP_DIR/app
readonly WEB_RUNTIME_DIR=$APP_DIR/web
# Verdadero si la ruta, ya resuelta (sin enlaces), y cada directorio hasta / son de root y no son
# escribibles por grupo ni otros (los directorios con sticky bit tipo /tmp tampoco sirven).
is_root_private() {
    local path info
    path=$(realpath -e -- "$1") || return 1
    while :; do
        info=$(stat -c '%u %a' -- "$path") || return 1
        [[ ${info%% *} == 0 ]] || return 1
        (( (8#${info##* } & 8#022) == 0 )) || return 1
        [[ $path != / ]] || return 0
        path=${path%/*}
        path=${path:-/}
    done
}

# Verdadero si es un archivo regular (tras resolver enlaces) y toda su ruta es de root.
is_root_private_file() {
    local resolved
    resolved=$(realpath -e -- "$1") || return 1
    [[ -f $resolved && ! -L $resolved ]] && is_root_private "$resolved"
}

# Todo lo que se ejecuta o se lee como root/postgres (migrations.sh, SQL, helpers) sale de TRUSTED_DIR.
# En modo manual es el espejo de root (/var/lib/ideascore/mirror), que se asigna tras elegir la referencia;
# en modo no interactivo es obligatorio y debe ser un directorio de root que nadie mas pueda escribir.
TRUSTED_DIR=${IDEASCORE_TRUSTED_DIR:-}
if [[ $NON_INTERACTIVE == true ]]; then
    [[ -n ${IDEASCORE_TRUSTED_DIR:-} ]] || die 'IDEASCORE_TRUSTED_DIR es obligatorio en modo no interactivo.'
    [[ $TRUSTED_DIR == /* && -d $TRUSTED_DIR ]] || die 'IDEASCORE_TRUSTED_DIR debe ser una ruta absoluta existente.'
    [[ $(realpath -- "$TRUSTED_DIR") != "$(realpath -- "$SOURCE_DIR")" ]] ||
        die 'IDEASCORE_TRUSTED_DIR no puede ser el directorio de codigo fuente.'
    is_root_private "$TRUSTED_DIR" ||
        die 'IDEASCORE_TRUSTED_DIR (y cada directorio que lo contiene) debe ser de root y no escribible por grupo u otros.'
    [[ -f $TRUSTED_DIR/scripts/ubuntu/migrations.sh && -f $TRUSTED_DIR/scripts/ubuntu/core-update.sh ]] ||
        die 'IDEASCORE_TRUSTED_DIR no contiene los scripts de IdeasCore.'
    readonly TRUSTED_DIR
fi

# El script que corre como root debe estar en un lugar que solo root pueda modificar.
SCRIPT_PATH=$(realpath -- "$0")
SCRIPT_DIR=${SCRIPT_PATH%/*}
if ! is_root_private_file "$SCRIPT_PATH"; then
    die "update.sh esta en $SCRIPT_DIR, una ruta que no es solo de root (o es un enlace) y podria haber sido alterada. Descarga update.sh y mirror.sh desde https://raw.githubusercontent.com/alexcalbri/IDC/master/scripts/ubuntu/ a un directorio de root (por ejemplo /root/ideascore-update) y ejecuta sudo bash /root/ideascore-update/update.sh."
fi
readonly CONFIG_FILE=/etc/ideascore/server.env
readonly SERVICE_NAME=ideascore.service
readonly NGINX_SITE=/etc/nginx/sites-available/ideascore
# Todo lo que escribe root vive fuera de lo que ideascore puede modificar: /opt/ideascore es de root.
readonly ROOT_STATE_DIR=/var/lib/ideascore
readonly BACKUP_ROOT=$ROOT_STATE_DIR/backups
readonly BUILD_LOG_DIR=/var/log/ideascore-update
readonly UPDATE_SWAP_FILE=$ROOT_STATE_DIR/update.swap
readonly LEGACY_SWAP_FILE=$APP_DIR/update.swap
readonly APP_HOME_DIR=$APP_DIR/home
# En modo no interactivo se compila en un arbol nuevo (ideascore lo escribe, root no lo lee directamente).
readonly BUILD_TREE=$APP_DIR/build-tree
readonly KEEP_BACKUPS=5
readonly KEEP_LOGS=10
# Same limits as install.sh: the production webpack build needs extra memory for Node.
readonly UPDATE_SWAP_SIZE_MB=4096
readonly GRADLE_JVM_ARGS="-Xmx1536M -Dfile.encoding=UTF-8"
readonly NODE_OPTIONS_VALUE="--max-old-space-size=3072"
readonly APP_PORT=8080
UPDATE_SWAP_CREATED=false

cleanup_update_swap() {
    if [[ $UPDATE_SWAP_CREATED == true ]]; then
        swapoff "$UPDATE_SWAP_FILE" >/dev/null 2>&1 || true
        rm -f "$UPDATE_SWAP_FILE"
    fi
}

trap 'cleanup_update_swap; printf "Actualizacion interrumpida (linea %s). Revisa los respaldos antes de reintentar.\n" "$LINENO" >&2' ERR
trap 'cleanup_update_swap' EXIT

recover_stale_update_swap() {
    [[ -e $UPDATE_SWAP_FILE ]] || return 0
    printf 'Se encontro swap temporal de una actualizacion anterior interrumpida; se recupera.\n'
    if awk 'NR > 1 { print $1 }' /proc/swaps | grep -Fxq "$UPDATE_SWAP_FILE"; then
        swapoff "$UPDATE_SWAP_FILE"
    fi
    rm -f "$UPDATE_SWAP_FILE"
}

ensure_update_swap() {
    local available_kb total_swap_kb
    # Before measuring: a leftover active swap would count as available and then never be removed.
    recover_stale_update_swap
    available_kb=$(awk '/MemAvailable:/ { print $2 }' /proc/meminfo)
    total_swap_kb=$(awk '/SwapTotal:/ { print $2 }' /proc/meminfo)

    if (( available_kb >= 2097152 || total_swap_kb >= 1048576 )); then
        return
    fi

    printf 'Memoria limitada detectada; se creara swap temporal de %s MiB para compilar.\n' "$UPDATE_SWAP_SIZE_MB"
    if command -v fallocate >/dev/null 2>&1; then
        fallocate -l "${UPDATE_SWAP_SIZE_MB}M" "$UPDATE_SWAP_FILE" ||
            dd if=/dev/zero of="$UPDATE_SWAP_FILE" bs=1M count="$UPDATE_SWAP_SIZE_MB" status=none
    else
        dd if=/dev/zero of="$UPDATE_SWAP_FILE" bs=1M count="$UPDATE_SWAP_SIZE_MB" status=none
    fi
    chmod 600 "$UPDATE_SWAP_FILE"
    mkswap "$UPDATE_SWAP_FILE" >/dev/null
    swapon "$UPDATE_SWAP_FILE"
    UPDATE_SWAP_CREATED=true
}

run_gradle() {
    # HOME explicito: /opt/ideascore es de root y el directorio personal de ideascore es home/.
    runuser -u ideascore -- env \
        HOME="$APP_HOME_DIR" \
        GRADLE_USER_HOME="$APP_HOME_DIR/.gradle" \
        JAVA_HOME="$UPDATE_JAVA_HOME" \
        NODE_OPTIONS="$NODE_OPTIONS_VALUE" \
        bash ./gradlew \
        --no-daemon \
        --no-configuration-cache \
        --max-workers=1 \
        --console=plain \
        "-Dorg.gradle.jvmargs=$GRADLE_JVM_ARGS" \
        "-Pkotlin.compiler.execution.strategy=in-process" \
        "$@"
}

config_has_key() {
    local key=$1
    grep -Eq "^${key}=" "$CONFIG_FILE"
}

config_value() {
    local key=$1
    awk -F= -v key="$key" '$1 == key { sub(/^[^=]*=/, ""); print; exit }' "$CONFIG_FILE"
}

append_config_value() {
    local key=$1
    local value=$2
    if config_has_key "$key"; then
        return
    fi
    printf '%s=%s\n' "$key" "$value" >> "$CONFIG_FILE"
    printf 'Configuracion agregada en server.env: %s\n' "$key"
}

configure_server_env() {
    local db_url tenant_prefix
    install -d -m 755 "$APP_DIR/company-backups"
    chown ideascore:ideascore "$APP_DIR/company-backups"
    chmod 700 "$APP_DIR/company-backups"
    install -d -m 700 "$APP_DIR/server-modules"
    chown ideascore:ideascore "$APP_DIR/server-modules"
    install -d -m 700 "$APP_DIR/company-files"
    chown ideascore:ideascore "$APP_DIR/company-files"

    db_url=$(config_value DB_URL)
    [[ -n $db_url ]] || die 'DB_URL no existe en /etc/ideascore/server.env.'
    tenant_prefix="${db_url%/*}/"
    [[ $tenant_prefix != "$db_url/" ]] || die 'No se pudo inferir TENANT_JDBC_URL_PREFIX desde DB_URL.'

    cp -a "$CONFIG_FILE" "$CONFIG_FILE.update-backup.$(date -u +%Y%m%dT%H%M%SZ)"
    append_config_value TENANT_DATABASES_JSON '[]'
    append_config_value TENANT_JDBC_URL_PREFIX "$tenant_prefix"
    append_config_value MIGRATIONS_ROOT "$SOURCE_DIR"
    append_config_value COMPANY_BACKUPS_ROOT "$APP_DIR/company-backups"
    append_config_value COMPANY_FILES_ROOT "$APP_DIR/company-files"
    append_config_value MODULE_CATALOG_URL 'https://github.com/alexcalbri/IDC/tree/master/modules'
    append_config_value MODULE_PACKAGES_ROOT "$APP_DIR/server-modules"
    append_config_value MODULE_GITHUB_TOKEN ''
    # Instalar extensiones desde un ZIP o una URL queda apagado por seguridad; pon true y reinicia ideascore para activarlo.
    append_config_value EXTENSION_UPLOAD_ENABLED false
    append_config_value VERSION_CATALOG_URL ''
    # Llave maestra de los secretos cifrados: se genera una sola vez y nunca se regenera.
    if ! config_has_key IDC_MASTER_KEY; then
        append_config_value IDC_MASTER_KEY "$(openssl rand -base64 32)"
        printf 'Respalda IDC_MASTER_KEY (%s): sin ella no se pueden leer los secretos guardados.\n' "$CONFIG_FILE"
    fi
    append_config_value CORE_UPDATE_REF master
    if [[ -f $NGINX_SITE ]] && grep -Eq 'listen[[:space:]]+443' "$NGINX_SITE"; then
        append_config_value PUBLIC_SCHEME https
    else
        append_config_value PUBLIC_SCHEME http
    fi
    # Base publica fija para los metadatos OAuth de agentes; evita confiar en la cabecera Host del cliente.
    if [[ -f $NGINX_SITE ]]; then
        local public_scheme public_domain
        public_scheme=$(config_value PUBLIC_SCHEME)
        public_domain=$(awk '/server_name/{print $2; exit}' "$NGINX_SITE" | tr -d ';')
        [[ -n $public_domain ]] && append_config_value IDC_PUBLIC_BASE_URL "${public_scheme}://${public_domain}"
    fi
    append_config_value ACME_EMAIL ''
    append_config_value SMTP_HOST ''
    append_config_value SMTP_PORT 587
    append_config_value SMTP_USERNAME ''
    append_config_value SMTP_PASSWORD ''
    append_config_value SMTP_FROM ''
    append_config_value SMTP_SECURITY starttls
    chmod 600 "$CONFIG_FILE"
}

# The unit uses ProtectSystem=strict; backups, company files and module packages need write access.
configure_service_writable_paths() {
    local dropin_dir=/etc/systemd/system/$SERVICE_NAME.d
    local dropin=$dropin_dir/writable-paths.conf
    local expected
    expected=$(printf '[Service]\nReadWritePaths=%s %s %s\n' "$APP_DIR/company-backups" "$APP_DIR/company-files" "$APP_DIR/server-modules")
    if [[ -f $dropin && $(cat "$dropin") == "$expected" ]]; then
        return
    fi
    install -d -m 755 "$dropin_dir"
    printf '%s\n' "$expected" > "$dropin"
    chmod 644 "$dropin"
    systemctl daemon-reload
    printf 'Servicio actualizado con rutas escribibles para backups y modulos.\n'
}

# Adds "location <prefix>" proxying to the backend, before "location / {", when the site lacks it.
ensure_nginx_proxy_location() {
    local prefix=$1
    if [[ ! -f $NGINX_SITE ]]; then
        printf 'AVISO: no se encontro %s; omitiendo configuracion Nginx de %s.\n' "$NGINX_SITE" "$prefix" >&2
        return
    fi
    if grep -Fq "location $prefix " "$NGINX_SITE" || grep -Fq "location $prefix{" "$NGINX_SITE"; then
        return
    fi
    if ! grep -q "proxy_pass http://127.0.0.1:$APP_PORT" "$NGINX_SITE"; then
        printf 'AVISO: %s no parece ser el sitio IdeasCore administrado por el instalador; no se modificara Nginx.\n' "$NGINX_SITE" >&2
        return
    fi

    cp -a "$NGINX_SITE" "$NGINX_SITE.update-backup.$(date -u +%Y%m%dT%H%M%SZ)"
    python3 - "$NGINX_SITE" "$APP_PORT" "$prefix" <<'PY'
from pathlib import Path
import sys

path = Path(sys.argv[1])
port = sys.argv[2]
prefix = sys.argv[3]
text = path.read_text()
marker = "    location / {\n"
block = f"""    location {prefix} {{
        proxy_pass http://127.0.0.1:{port};
        proxy_http_version 1.1;
        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
        proxy_set_header X-Forwarded-For $remote_addr;
        proxy_set_header X-Forwarded-Proto $scheme;
    }}
"""
if f"location {prefix} " in text:
    sys.exit(0)
if marker not in text:
    sys.exit(f"No se encontro el bloque location / para insertar {prefix}.")
path.write_text(text.replace(marker, block + marker, 1))
PY
    nginx -t
    systemctl reload nginx
    printf 'Nginx actualizado para proxyear %s al backend.\n' "$prefix"
}

# Module routes receive attachments (25 MB per file in the CRM); Nginx allows 1 MB by default.
ensure_nginx_module_upload_limit() {
    if [[ ! -f $NGINX_SITE ]] || ! grep -q "proxy_pass http://127.0.0.1:$APP_PORT" "$NGINX_SITE"; then
        return
    fi
    if ! grep -Fq "location /modules {" "$NGINX_SITE"; then
        return
    fi
    if python3 - "$NGINX_SITE" <<'PY'
from pathlib import Path
import sys

text = Path(sys.argv[1]).read_text()
marker = "    location /modules {\n"
parts = text.split(marker)
sys.exit(0 if all(part.lstrip().startswith("client_max_body_size") for part in parts[1:]) else 1)
PY
    then
        return
    fi
    cp -a "$NGINX_SITE" "$NGINX_SITE.update-backup.$(date -u +%Y%m%dT%H%M%SZ)"
    python3 - "$NGINX_SITE" <<'PY'
from pathlib import Path
import sys

path = Path(sys.argv[1])
text = path.read_text()
marker = "    location /modules {\n"
limit = "        client_max_body_size 30m;\n"
parts = text.split(marker)
fixed = parts[0] + "".join(marker + (part if part.lstrip().startswith("client_max_body_size") else limit + part) for part in parts[1:])
path.write_text(fixed)
PY
    nginx -t
    systemctl reload nginx
    printf 'Nginx actualizado: /modules acepta archivos de hasta 30 MB.\n'
}

configure_nginx_server_route() {
    ensure_nginx_proxy_location /server
    # OAuth discovery for AI agents (module Agentes IA, claude.ai custom connectors).
    ensure_nginx_proxy_location /.well-known/oauth-
}

apply_control_migrations() {
    local db_url db_name db_user
    db_url=$(config_value DB_URL)
    db_user=$(config_value DB_USER)
    [[ -n $db_url ]] || die 'DB_URL no existe en /etc/ideascore/server.env.'
    [[ -n $db_user ]] || die 'DB_USER no existe en /etc/ideascore/server.env.'
    # Rutas relativas de aqui en adelante: SQL y migrations.sh se leen del directorio confiable.
    cd "$TRUSTED_DIR"
    db_name="${db_url##*/}"
    [[ -n $db_name && $db_name != "$db_url" ]] || die 'No se pudo inferir la base central desde DB_URL.'
    [[ -f scripts/ubuntu/migrations.sh ]] || die 'No se encontro scripts/ubuntu/migrations.sh.'
    [[ -f database/control/migrations/V003__create_server_modules.sql ]] ||
        die 'No se encontro database/control/migrations/V003__create_server_modules.sql.'
    [[ -f database/control/migrations/V004__create_server_settings_and_module_packages.sql ]] ||
        die 'No se encontro database/control/migrations/V004__create_server_settings_and_module_packages.sql.'
    [[ -f database/control/migrations/V005__create_public_domains.sql ]] ||
        die 'No se encontro database/control/migrations/V005__create_public_domains.sql.'
    [[ -f database/control/migrations/V006__add_server_workspace.sql ]] ||
        die 'No se encontro database/control/migrations/V006__add_server_workspace.sql.'
    [[ -f database/control/migrations/V007__add_audit_actor_identity.sql ]] ||
        die 'No se encontro database/control/migrations/V007__add_audit_actor_identity.sql.'
    [[ -f database/control/migrations/V008__add_company_owner_group.sql ]] ||
        die 'No se encontro database/control/migrations/V008__add_company_owner_group.sql.'
    [[ -f database/control/migrations/V009__add_server_owner_company_sessions.sql ]] ||
        die 'No se encontro database/control/migrations/V009__add_server_owner_company_sessions.sql.'
    [[ -f database/control/migrations/V010__create_core_update_requests.sql ]] ||
        die 'No se encontro database/control/migrations/V010__create_core_update_requests.sql.'
    [[ -f database/control/migrations/V011__create_extensions.sql ]] ||
        die 'No se encontro database/control/migrations/V011__create_extensions.sql.'
    [[ -f database/control/migrations/V012__extension_zip_url_sources.sql ]] ||
        die 'No se encontro database/control/migrations/V012__extension_zip_url_sources.sql.'
    [[ -f database/core/migrations/V003__add_application_user_profile.sql ]] ||
        die 'No se encontro database/core/migrations/V003__add_application_user_profile.sql.'
    [[ -f database/core/migrations/V004__add_application_user_code.sql ]] ||
        die 'No se encontro database/core/migrations/V004__add_application_user_code.sql.'
    [[ -f database/core/migrations/V005__create_notifications.sql ]] ||
        die 'No se encontro database/core/migrations/V005__create_notifications.sql.'

    source scripts/ubuntu/migrations.sh
    apply_migration "$db_name" control database/control/migrations/V001__create_server_registry.sql
    apply_migration "$db_name" control database/control/migrations/V002__create_provisioning_audit_log.sql
    apply_migration "$db_name" control database/control/migrations/V003__create_server_modules.sql
    apply_migration "$db_name" control database/control/migrations/V004__create_server_settings_and_module_packages.sql
    apply_migration "$db_name" control database/control/migrations/V005__create_public_domains.sql
    apply_migration "$db_name" control database/control/migrations/V006__add_server_workspace.sql
    apply_migration "$db_name" control database/control/migrations/V007__add_audit_actor_identity.sql
    apply_migration "$db_name" control database/control/migrations/V008__add_company_owner_group.sql
    apply_migration "$db_name" control database/control/migrations/V009__add_server_owner_company_sessions.sql
    apply_migration "$db_name" control database/control/migrations/V010__create_core_update_requests.sql
apply_migration "$db_name" control database/control/migrations/V011__create_extensions.sql
apply_migration "$db_name" control database/control/migrations/V012__extension_zip_url_sources.sql
    apply_migration "$db_name" core database/core/migrations/V003__add_application_user_profile.sql
    apply_migration "$db_name" core database/core/migrations/V004__add_application_user_code.sql
    runuser -u postgres -- psql -X -d "$db_name" -v ON_ERROR_STOP=1 -v db_user="$db_user" <<'SQL'
GRANT UPDATE (display_name, email) ON application_users TO :"db_user";
GRANT SELECT, UPDATE (user_code) ON application_users TO :"db_user";
GRANT SELECT, INSERT, UPDATE, DELETE ON password_reset_codes TO :"db_user";
GRANT SELECT, INSERT, UPDATE, DELETE ON companies TO :"db_user";
GRANT SELECT, INSERT, UPDATE, DELETE ON server_modules TO :"db_user";
GRANT SELECT, INSERT, UPDATE, DELETE ON server_settings TO :"db_user";
GRANT SELECT, INSERT, UPDATE, DELETE ON public_domains TO :"db_user";
GRANT SELECT, INSERT, UPDATE, DELETE ON server_owner_company_sessions TO :"db_user";
REVOKE INSERT ON core_update_requests FROM :"db_user";
GRANT SELECT ON core_update_requests TO :"db_user";
GRANT INSERT (target_version, from_version, requested_by) ON core_update_requests TO :"db_user";
GRANT USAGE ON SEQUENCE core_update_requests_id_seq TO :"db_user";
GRANT SELECT, INSERT, UPDATE ON server_extensions, extension_operations, extension_operation_targets TO :"db_user";
GRANT SELECT, INSERT ON extension_unsigned_authorizations TO :"db_user";
GRANT USAGE ON SEQUENCE extension_unsigned_authorizations_id_seq, extension_operations_id_seq TO :"db_user";
SQL

    [[ -f database/tenant/migrations/V004__create_application_permissions.sql ]] ||
        die 'No se encontro database/tenant/migrations/V004__create_application_permissions.sql.'
    [[ -f database/tenant/migrations/V005__create_application_user_credentials.sql ]] ||
        die 'No se encontro database/tenant/migrations/V005__create_application_user_credentials.sql.'
    [[ -f modules/clientes/migrations/V002__require_customer_contact_fields.sql ]] ||
        die 'No se encontro modules/clientes/migrations/V002__require_customer_contact_fields.sql.'
    [[ -f modules/clientes/migrations/V003__add_customer_type.sql ]] ||
        die 'No se encontro modules/clientes/migrations/V003__add_customer_type.sql.'
    [[ -f modules/clientes/migrations/V004__add_customer_code.sql ]] ||
        die 'No se encontro modules/clientes/migrations/V004__add_customer_code.sql.'
    local tenant_list
    tenant_list=$(runuser -u postgres -- psql -X -d "$db_name" -At -c "SELECT database_name FROM companies ORDER BY code") ||
        die 'No se pudo leer la lista de empresas (companies); no se migro ninguna base.'
    while IFS= read -r tenant_db; do
        [[ -n $tenant_db ]] || continue
        if [[ ! $tenant_db =~ ^[a-z][a-z0-9_]{0,62}$ ]]; then
            printf 'AVISO: se omite la base "%s": nombre de base no valido.\n' "$tenant_db" >&2
            continue
        fi
        apply_migration "$tenant_db" clientes modules/clientes/migrations/V002__require_customer_contact_fields.sql
        apply_migration "$tenant_db" clientes modules/clientes/migrations/V003__add_customer_type.sql
        apply_migration "$tenant_db" clientes modules/clientes/migrations/V004__add_customer_code.sql
        apply_migration "$tenant_db" tenant database/tenant/migrations/V004__create_application_permissions.sql
        apply_migration "$tenant_db" tenant database/tenant/migrations/V005__create_application_user_credentials.sql
        apply_migration "$tenant_db" core database/core/migrations/V003__add_application_user_profile.sql
        apply_migration "$tenant_db" core database/core/migrations/V005__create_notifications.sql
        runuser -u postgres -- psql -X -d "$tenant_db" -v ON_ERROR_STOP=1 -v db_user="$db_user" <<'SQL'
GRANT SELECT, INSERT, UPDATE ON application_users TO :"db_user";
GRANT SELECT, INSERT, UPDATE, DELETE ON password_reset_codes TO :"db_user";
GRANT SELECT, INSERT, UPDATE, DELETE ON application_permissions, application_user_permissions TO :"db_user";
GRANT SELECT, INSERT, UPDATE, DELETE ON application_user_credentials TO :"db_user";
GRANT SELECT, INSERT, DELETE, UPDATE (read_at) ON notifications TO :"db_user";
SQL
    done <<< "$tenant_list"
    cd /
}

# Instalaciones anteriores dejaban /opt/ideascore como home de ideascore: ese usuario podia plantar enlaces
# simbolicos donde root escribe. Se deja el directorio en manos de root y ideascore pasa a /opt/ideascore/home.
# Debe ejecutarse antes de cualquier otra escritura de root en APP_DIR.
harden_app_dir() {
    local entry name legacy_owner
    # Primero se cierra el directorio: desde aqui ideascore ya no puede crear ni renombrar entradas en el.
    chown root:root "$APP_DIR"
    chmod 755 "$APP_DIR"
    install -d -m 700 -o root -g root "$ROOT_STATE_DIR"

    # Los directorios de ideascore deben ser directorios reales, no enlaces.
    for name in source company-backups company-files server-modules; do
        [[ ! -L $APP_DIR/$name ]] || die "$APP_DIR/$name es un enlace simbolico; no es seguro continuar."
    done

    # Hogar nuevo de ideascore.
    if [[ -L $APP_HOME_DIR ]]; then
        rm -f -- "$APP_HOME_DIR"
    fi
    if [[ ! -d $APP_HOME_DIR ]]; then
        rm -rf --one-file-system -- "$APP_HOME_DIR"
        mkdir -m 700 -- "$APP_HOME_DIR"
    fi
    chown -h ideascore:ideascore "$APP_HOME_DIR"
    chmod 700 "$APP_HOME_DIR"

    # Mueve las entradas ocultas (.gradle, .cache, .config...) sin seguir enlaces: rename no los sigue.
    for entry in "$APP_DIR"/.[!.]* "$APP_DIR"/..?*; do
        [[ -e $entry || -L $entry ]] || continue
        name=${entry##*/}
        if [[ -e $APP_HOME_DIR/$name || -L $APP_HOME_DIR/$name ]]; then
            continue
        fi
        mv -T -- "$entry" "$APP_HOME_DIR/$name"
    done
    if [[ $(getent passwd ideascore | cut -d: -f6) != "$APP_HOME_DIR" ]]; then
        # usermod rechaza cambiar el directorio si ideascore tiene procesos (el servicio corre);
        # no es fatal: Gradle recibe HOME explicito y el cambio se reintenta en la siguiente actualizacion.
        if usermod -d "$APP_HOME_DIR" ideascore; then
            printf 'ideascore ahora usa %s como directorio personal.\n' "$APP_HOME_DIR"
        else
            printf 'AVISO: no se pudo cambiar el directorio personal de ideascore (servicio en uso); se reintentara.\n' >&2
        fi
    fi

    # Swap heredado: se desactiva sin seguir enlaces y se elimina.
    if [[ -e $LEGACY_SWAP_FILE || -L $LEGACY_SWAP_FILE ]]; then
        if [[ ! -L $LEGACY_SWAP_FILE ]] && awk 'NR > 1 { print $1 }' /proc/swaps | grep -Fxq "$LEGACY_SWAP_FILE"; then
            swapoff "$LEGACY_SWAP_FILE"
        fi
        rm -f -- "$LEGACY_SWAP_FILE"
    fi

    # Entradas que root usa directamente en APP_DIR: se eliminan si son enlaces o no son de root.
    for name in update-logs backups app.new app.previous web.new web.previous build-tree; do
        entry=$APP_DIR/$name
        [[ -e $entry || -L $entry ]] || continue
        legacy_owner=$(stat -c %u -- "$entry")
        if [[ -L $entry || $legacy_owner != 0 ]]; then
            rm -rf --one-file-system -- "$entry"
        fi
    done
    # Respaldos antiguos de root: se mueven al nuevo lugar si es un directorio real.
    if [[ -d $APP_DIR/backups && ! -L $APP_DIR/backups && ! -e $BACKUP_ROOT ]]; then
        mv -T -- "$APP_DIR/backups" "$BACKUP_ROOT"
    fi
    install -d -m 700 -o root -g root "$BACKUP_ROOT" "$BUILD_LOG_DIR"
}

# Copia una distribucion compilada a un directorio de root. Se lee como ideascore (tar) para que root no
# siga enlaces del arbol de ideascore, y se rechaza todo lo que no sea archivo o directorio.
copy_dist_as_ideascore() {
    local src=$1 dest=$2
    local archive entry
    # Root no extrae directamente un flujo que controla ideascore: se guarda en un archivo de root,
    # se valida el listado y solo entonces se extrae.
    archive=$(mktemp -p "$ROOT_STATE_DIR" dist.XXXXXX.tar) || die 'No se pudo crear el archivo temporal de la distribucion.'
    chmod 600 "$archive"
    if ! runuser -u ideascore -- tar -C "$src" -cf - . > "$archive"; then
        rm -f -- "$archive"
        die "No se pudo empaquetar la distribucion de $src."
    fi
    while IFS= read -r entry; do
        # Primer caracter del modo: - archivo, d directorio; todo lo demas se rechaza.
        if [[ ${entry:0:1} != - && ${entry:0:1} != d ]]; then
            rm -f -- "$archive"
            die "La distribucion de $src contiene enlaces o archivos especiales."
        fi
    done < <(tar -tvf "$archive")
    while IFS= read -r entry; do
        if [[ $entry == /* || $entry == '..' || $entry == ../* || $entry == */../* || $entry == */.. ]]; then
            rm -f -- "$archive"
            die "La distribucion de $src contiene rutas no permitidas."
        fi
    done < <(tar -tf "$archive")
    tar -C "$dest" -xf "$archive" --no-same-owner --no-same-permissions --no-overwrite-dir || {
        rm -f -- "$archive"
        die "No se pudo extraer la distribucion de $src."
    }
    rm -f -- "$archive"
    if [[ -n $(find "$dest" ! -type f ! -type d -print -quit) ]]; then
        die "La distribucion de $src contiene enlaces o archivos especiales."
    fi
}

# Conserva solo los N mas recientes (por fecha de modificacion) de un patron dentro de un directorio.
prune_old() {
    local dir=$1 pattern=$2 keep=$3 item
    while IFS= read -r item; do
        rm -rf --one-file-system -- "$item"
    done < <(find "$dir" -mindepth 1 -maxdepth 1 -name "$pattern" -printf '%T@ %p\n' | sort -rn | tail -n "+$((keep + 1))" | cut -d' ' -f2-)
}

# Opcional (opt-in): en vez de compilar la webapp (webpack no cabe en 4 GB de RAM), descarga la
# distribucion que el CI (.github/workflows/build.yml) publico para este mismo commit en el release
# "web-dist" de GitHub. Se activa con WEB_DIST_FROM_CI=true en /etc/ideascore/server.env (o en el
# entorno). Si algo falla (sin paquete para ese sha, sin red, checksum o contenido invalidos) devuelve 1
# y el llamador compila como siempre. Deja la ruta en WEB_DIST.
# Todo se descarga y se extrae como ideascore (nunca como root); copy_dist_as_ideascore revisa despues
# que no haya enlaces ni archivos especiales antes de copiar a un directorio de root.
fetch_ci_web_dist() {
    local sha=$1 base name dir
    base=$(config_value WEB_DIST_BASE_URL)
    base=${base:-https://github.com/alexcalbri/IDC/releases/download/web-dist}
    [[ $base =~ ^https://[A-Za-z0-9./_-]+$ ]] || { printf 'AVISO: WEB_DIST_BASE_URL invalida.\n' >&2; return 1; }
    command -v curl >/dev/null 2>&1 || { printf 'AVISO: no hay curl para descargar la webapp del CI.\n' >&2; return 1; }
    name=web-$sha.tar.gz
    dir=$BUILD_DIR/web-ci
    rm -rf --one-file-system -- "$dir"
    install -d -m 755 -o ideascore -g ideascore "$dir" || return 1
    printf 'Descargando la webapp compilada por el CI (%s)...\n' "$name"
    # --fail: un 404 (el CI aun no publica ese commit) cuenta como error y se compila.
    runuser -u ideascore -- curl --fail --silent --show-error --location --proto '=https' --tlsv1.2 \
        --retry 3 --retry-delay 5 --connect-timeout 15 --max-time 600 --max-filesize 209715200 \
        -o "$dir/$name" "$base/$name" || return 1
    runuser -u ideascore -- curl --fail --silent --show-error --location --proto '=https' --tlsv1.2 \
        --retry 3 --retry-delay 5 --connect-timeout 15 --max-time 60 --max-filesize 1048576 \
        -o "$dir/$name.sha256" "$base/$name.sha256" || return 1
    # El .sha256 debe nombrar exactamente este paquete; se compara el hash, no solo el nombre.
    local expected actual
    expected=$(awk -v n="$name" '$2 == n || $2 == "*" n { print $1; exit }' "$dir/$name.sha256")
    [[ $expected =~ ^[0-9a-f]{64}$ ]] || { printf 'AVISO: checksum del CI ilegible.\n' >&2; return 1; }
    actual=$(runuser -u ideascore -- sha256sum "$dir/$name" | awk '{print $1}')
    [[ $actual == "$expected" ]] || { printf 'AVISO: el checksum de la webapp del CI no coincide.\n' >&2; return 1; }
    install -d -m 755 -o ideascore -g ideascore "$dir/dist" || return 1
    runuser -u ideascore -- tar -xzf "$dir/$name" -C "$dir/dist" --no-same-owner || return 1
    [[ -f $dir/dist/index.html && -f $dir/dist/webApp.js ]] || { printf 'AVISO: la webapp del CI esta incompleta.\n' >&2; return 1; }
    WEB_DIST=$dir/dist
}

[[ -d $SOURCE_DIR/.git ]] || die "No se encontro el repositorio en $SOURCE_DIR."
[[ -d $APP_RUNTIME_DIR && -x $APP_RUNTIME_DIR/bin/server ]] || die "No se encontro el servidor instalado en $APP_RUNTIME_DIR."
[[ -d $WEB_RUNTIME_DIR ]] || die "No se encontro la webapp instalada en $WEB_RUNTIME_DIR."
[[ -r $CONFIG_FILE ]] || die "No se encontro $CONFIG_FILE."
id ideascore >/dev/null 2>&1 || die 'No existe el usuario Linux ideascore.'
systemctl cat "$SERVICE_NAME" >/dev/null 2>&1 || die "No existe $SERVICE_NAME."

harden_app_dir

cd "$SOURCE_DIR"
CURRENT_REF=$(git_source rev-parse --abbrev-ref HEAD) || CURRENT_REF=HEAD
CURRENT_COMMIT=$(git_source rev-parse --short HEAD) || CURRENT_COMMIT=desconocido
# root no se queda con el directorio de trabajo dentro de un arbol que ideascore puede escribir.
cd /
if [[ $NON_INTERACTIVE == true ]]; then
    GIT_REF=$ARG_COMMIT
    UPDATE_TARGET=$ARG_TARGET
else
    # Tras una actualizacion automatica el repositorio queda en HEAD desacoplado: se sugiere la rama configurada.
    if [[ $CURRENT_REF == HEAD ]]; then
        CURRENT_REF=$(config_value CORE_UPDATE_REF)
        CURRENT_REF=${CURRENT_REF:-master}
    fi
    ask GIT_REF 'Rama, etiqueta o commit para actualizar' "$CURRENT_REF"
    [[ $GIT_REF =~ ^[a-zA-Z0-9][a-zA-Z0-9._/-]*$ && $GIT_REF != *..* ]] || die 'Referencia Git invalida.'
    ask UPDATE_TARGET 'Que deseas actualizar? server, webapp o ambos' ambos
    case "$UPDATE_TARGET" in
        server|webapp|ambos) ;;
        *) die 'Opcion invalida. Usa server, webapp o ambos.' ;;
    esac
fi

printf 'Instalacion actual: %s (%s)\n' "$CURRENT_REF" "$CURRENT_COMMIT"
printf 'Destino: %s\n' "$GIT_REF"
if [[ $NON_INTERACTIVE == true ]]; then
    CONFIRM=si
else
    ask CONFIRM "Continuar con respaldo, build y reemplazo de $UPDATE_TARGET? Escribe si" no
fi
[[ $CONFIRM == si ]] || die 'Cancelado sin cambiar archivos.'

if [[ $NON_INTERACTIVE == true ]]; then
    BUILD_SHA=$ARG_COMMIT
else
    # Modo manual: root sincroniza su espejo con la referencia elegida y todo sale de ahi.
    MIRROR_LIB_FILE=''
    if is_root_private_file "$SCRIPT_DIR/mirror.sh"; then
        MIRROR_LIB_FILE=$(realpath -e -- "$SCRIPT_DIR/mirror.sh")
    elif is_root_private_file /usr/local/lib/ideascore/mirror.sh; then
        MIRROR_LIB_FILE=$(realpath -e -- /usr/local/lib/ideascore/mirror.sh)
    fi
    [[ -n $MIRROR_LIB_FILE ]] || die 'No se encontro mirror.sh en un lugar seguro. Descarga update.sh y mirror.sh desde https://raw.githubusercontent.com/alexcalbri/IDC/master/scripts/ubuntu/ a un directorio de root (por ejemplo /root/ideascore-update) y ejecuta sudo bash /root/ideascore-update/update.sh.'
    # shellcheck source=mirror.sh
    source "$MIRROR_LIB_FILE"
    printf 'Sincronizando el espejo de root con %s...\n' "$GIT_REF"
    BUILD_SHA=$(sync_mirror "$GIT_REF") || die "No se pudo traer $GIT_REF desde GitHub al espejo $MIRROR_DIR."
    [[ $BUILD_SHA =~ ^[0-9a-f]{40}$ ]] || die 'No se pudo leer el commit del espejo.'
    TRUSTED_DIR=$MIRROR_DIR
    readonly TRUSTED_DIR
    [[ -f $TRUSTED_DIR/scripts/ubuntu/migrations.sh && -f $TRUSTED_DIR/scripts/ubuntu/core-update.sh ]] ||
        die 'El espejo no contiene los scripts de IdeasCore.'
fi

if [[ -n ${JAVA_HOME:-} && -x $JAVA_HOME/bin/java ]]; then
    UPDATE_JAVA_HOME=$JAVA_HOME
else
    UPDATE_JAVA_HOME="/usr/lib/jvm/java-21-openjdk-$(dpkg --print-architecture)"
fi
[[ -x $UPDATE_JAVA_HOME/bin/java ]] || die 'No se encontro Java 21. Instala openjdk-21-jdk-headless o define JAVA_HOME.'
ensure_update_swap
configure_server_env
configure_service_writable_paths
configure_nginx_server_route
ensure_nginx_module_upload_limit

# Se compila desde un arbol nuevo del espejo de root. El codigo fuente se deja en el mismo sha como
# ideascore (mejor esfuerzo, solo como referencia), pero no se confia en el.
git_source fetch --tags --prune origin || printf 'AVISO: no se pudo actualizar %s (fetch).\n' "$SOURCE_DIR" >&2
git_source checkout --detach "$BUILD_SHA" ||
    printf 'AVISO: no se pudo dejar %s en el commit %s.\n' "$SOURCE_DIR" "$BUILD_SHA" >&2
TARGET_COMMIT=${BUILD_SHA:0:7}
BUILD_DIR=$BUILD_TREE
rm -rf --one-file-system -- "$BUILD_DIR"
install -d -m 755 -o ideascore -g ideascore "$BUILD_DIR"
git -c core.hooksPath=/dev/null -c core.fsmonitor=false -C "$TRUSTED_DIR" archive "$BUILD_SHA" |
    runuser -u ideascore -- tar -x -C "$BUILD_DIR" --no-same-owner
[[ -f $BUILD_DIR/gradlew && -f $BUILD_DIR/server/build.gradle.kts ]] || die 'El arbol de compilacion esta incompleto.'
if [[ $UPDATE_TARGET == server || $UPDATE_TARGET == ambos ]]; then
    apply_control_migrations
    bash "$TRUSTED_DIR/scripts/ubuntu/domains-sync.sh" --install
    bash "$TRUSTED_DIR/scripts/ubuntu/core-update.sh" --install
fi

cd "$BUILD_DIR"
runuser -u ideascore -- env HOME="$APP_HOME_DIR" GRADLE_USER_HOME="$APP_HOME_DIR/.gradle" JAVA_HOME="$UPDATE_JAVA_HOME" bash ./gradlew --stop >/dev/null 2>&1 || true

install -d -m 700 -o root -g root "$BUILD_LOG_DIR"
SERVER_BUILD_LOG="$BUILD_LOG_DIR/server-$TARGET_COMMIT.log"
WEB_BUILD_LOG="$BUILD_LOG_DIR/web-$TARGET_COMMIT.log"

if [[ $UPDATE_TARGET == server || $UPDATE_TARGET == ambos ]]; then
    run_gradle -PserverOnly=true :server:test :server:installDist 2>&1 | tee "$SERVER_BUILD_LOG"
    [[ -x $BUILD_DIR/server/build/install/server/bin/server ]] || die 'No se genero la distribucion del servidor.'
fi
if [[ $UPDATE_TARGET == webapp || $UPDATE_TARGET == ambos ]]; then
    WEB_DIST=''
    WEB_FROM_CI=${WEB_DIST_FROM_CI:-$(config_value WEB_DIST_FROM_CI)}
    if [[ $WEB_FROM_CI == true ]]; then
        # Conserva el exito o fallo sin que set -e corte el script: si falla se compila.
        if ! fetch_ci_web_dist "$BUILD_SHA"; then
            printf 'AVISO: no se pudo usar la webapp del CI; se compilara aqui.\n' >&2
            WEB_DIST=''
        fi
    fi
    if [[ -z $WEB_DIST ]]; then
        # build/js keeps the yarn.lock of the previous build; when kotlin-js-store/yarn.lock changed,
        # kotlinStoreYarnLock fails with "Lock file was changed". It is only a build cache.
        runuser -u ideascore -- rm -rf -- "$BUILD_DIR/build/js"
        run_gradle -PwebOnly=true :app:webApp:jsBrowserDistribution 2>&1 | tee "$WEB_BUILD_LOG"
        WEB_DIST="$BUILD_DIR/app/webApp/build/dist/js/productionExecutable"
    fi
    [[ -f $WEB_DIST/index.html && -f $WEB_DIST/webApp.js ]] || die 'No se genero la distribucion web completa.'
fi

TIMESTAMP=$(date -u +%Y%m%dT%H%M%SZ)
BACKUP_DIR=$BACKUP_ROOT/$TIMESTAMP
install -d -m 700 "$BACKUP_DIR"
cp -a "$APP_RUNTIME_DIR" "$BACKUP_DIR/app"
cp -a "$WEB_RUNTIME_DIR" "$BACKUP_DIR/web"
printf '%s\n' "$BUILD_SHA" > "$BACKUP_DIR/source-commit.txt"

if [[ $UPDATE_TARGET == server || $UPDATE_TARGET == ambos ]]; then
    systemctl stop "$SERVICE_NAME"
fi

if [[ $UPDATE_TARGET == server || $UPDATE_TARGET == ambos ]]; then
    rm -rf "$APP_RUNTIME_DIR.new"
    install -d -m 755 "$APP_RUNTIME_DIR.new"
    copy_dist_as_ideascore "$BUILD_DIR/server/build/install/server" "$APP_RUNTIME_DIR.new"
    chown -R root:root "$APP_RUNTIME_DIR.new"
    chmod -R a+rX "$APP_RUNTIME_DIR.new"
    chmod -R go-w "$APP_RUNTIME_DIR.new"

    rm -rf "$APP_RUNTIME_DIR.previous"
    mv "$APP_RUNTIME_DIR" "$APP_RUNTIME_DIR.previous"
    mv "$APP_RUNTIME_DIR.new" "$APP_RUNTIME_DIR"
fi

if [[ $UPDATE_TARGET == webapp || $UPDATE_TARGET == ambos ]]; then
    rm -rf "$WEB_RUNTIME_DIR.new"
    install -d -m 755 "$WEB_RUNTIME_DIR.new"
    copy_dist_as_ideascore "$WEB_DIST" "$WEB_RUNTIME_DIR.new"
    chown -R root:root "$WEB_RUNTIME_DIR.new"
    chmod -R a+rX "$WEB_RUNTIME_DIR.new"
    chmod -R go-w "$WEB_RUNTIME_DIR.new"
    rm -rf "$WEB_RUNTIME_DIR.previous"
    mv "$WEB_RUNTIME_DIR" "$WEB_RUNTIME_DIR.previous"
    mv "$WEB_RUNTIME_DIR.new" "$WEB_RUNTIME_DIR"
fi

if [[ $UPDATE_TARGET == server || $UPDATE_TARGET == ambos ]]; then
    systemctl start "$SERVICE_NAME"
fi
if [[ ($UPDATE_TARGET == server || $UPDATE_TARGET == ambos) && -x "$(command -v curl)" ]]; then
    HEALTH_OK=false
    for attempt in {1..30}; do
        if systemctl is-active --quiet "$SERVICE_NAME" &&
            curl --fail --silent --show-error --max-time 5 http://127.0.0.1:8080/ >/dev/null; then
            HEALTH_OK=true
            break
        fi
        sleep 3
    done

    if [[ $HEALTH_OK != true ]]; then
        systemctl status "$SERVICE_NAME" --no-pager >&2 || true
        journalctl -u "$SERVICE_NAME" -n 80 --no-pager >&2 || true
        die 'El servicio no respondio en http://127.0.0.1:8080/ despues de 90 segundos. Los artefactos anteriores estan en *.previous y el respaldo en /var/lib/ideascore/backups.'
    fi
elif [[ $UPDATE_TARGET == server || $UPDATE_TARGET == ambos ]]; then
    systemctl is-active --quiet "$SERVICE_NAME" || {
        systemctl status "$SERVICE_NAME" --no-pager >&2 || true
        die 'El servicio no arranco. Los artefactos anteriores estan en *.previous y el respaldo en /var/lib/ideascore/backups.'
    }
fi

rm -rf "$APP_RUNTIME_DIR.previous" "$WEB_RUNTIME_DIR.previous"
cd /
[[ $BUILD_DIR != "$BUILD_TREE" ]] || rm -rf --one-file-system -- "$BUILD_TREE"
prune_old "$BACKUP_ROOT" '20*' "$KEEP_BACKUPS"
prune_old "$BUILD_LOG_DIR" '*.log' "$KEEP_LOGS"

printf 'IdeasCore actualizado (%s) de %s a %s.\n' "$UPDATE_TARGET" "$CURRENT_COMMIT" "$TARGET_COMMIT"
printf 'Respaldo: %s\n' "$BACKUP_DIR"
printf 'Logs: sudo journalctl -u ideascore -f\n'
