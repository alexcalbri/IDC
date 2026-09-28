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
[[ -r /dev/tty ]] || die 'Se necesita una terminal interactiva; usa ssh -t.'

readonly APP_DIR=/opt/ideascore
readonly SOURCE_DIR=$APP_DIR/source
readonly APP_RUNTIME_DIR=$APP_DIR/app
readonly WEB_RUNTIME_DIR=$APP_DIR/web
readonly CONFIG_FILE=/etc/ideascore/server.env
readonly SERVICE_NAME=ideascore.service
readonly NGINX_SITE=/etc/nginx/sites-available/ideascore
readonly BACKUP_ROOT=$APP_DIR/backups
readonly UPDATE_SWAP_FILE=$APP_DIR/update.swap
readonly UPDATE_SWAP_SIZE_MB=2048
readonly GRADLE_JVM_ARGS="-Xmx1536M -Dfile.encoding=UTF-8"
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

ensure_update_swap() {
    local available_kb total_swap_kb
    available_kb=$(awk '/MemAvailable:/ { print $2 }' /proc/meminfo)
    total_swap_kb=$(awk '/SwapTotal:/ { print $2 }' /proc/meminfo)

    if (( available_kb >= 2097152 || total_swap_kb >= 1048576 )); then
        return
    fi

    [[ ! -e $UPDATE_SWAP_FILE ]] || die "Ya existe $UPDATE_SWAP_FILE. Revisalo antes de actualizar."
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
    runuser -u ideascore -- env \
        JAVA_HOME="$UPDATE_JAVA_HOME" \
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

    db_url=$(config_value DB_URL)
    [[ -n $db_url ]] || die 'DB_URL no existe en /etc/ideascore/server.env.'
    tenant_prefix="${db_url%/*}/"
    [[ $tenant_prefix != "$db_url/" ]] || die 'No se pudo inferir TENANT_JDBC_URL_PREFIX desde DB_URL.'

    cp -a "$CONFIG_FILE" "$CONFIG_FILE.update-backup.$(date -u +%Y%m%dT%H%M%SZ)"
    append_config_value TENANT_DATABASES_JSON '[]'
    append_config_value TENANT_JDBC_URL_PREFIX "$tenant_prefix"
    append_config_value MIGRATIONS_ROOT "$SOURCE_DIR"
    append_config_value COMPANY_BACKUPS_ROOT "$APP_DIR/company-backups"
    append_config_value MODULE_CATALOG_URL 'https://github.com/alexcalbri/IDC/tree/master/modules'
    append_config_value MODULE_PACKAGES_ROOT "$APP_DIR/server-modules"
    append_config_value VERSION_CATALOG_URL ''
    chmod 600 "$CONFIG_FILE"
}

configure_nginx_server_route() {
    if [[ ! -f $NGINX_SITE ]]; then
        printf 'AVISO: no se encontro %s; omitiendo configuracion Nginx de /server.\n' "$NGINX_SITE" >&2
        return
    fi
    if grep -Eq '^[[:space:]]*location[[:space:]]+/server([[:space:]]|\{)' "$NGINX_SITE"; then
        return
    fi
    if ! grep -q "proxy_pass http://127.0.0.1:$APP_PORT" "$NGINX_SITE"; then
        printf 'AVISO: %s no parece ser el sitio IdeasCore administrado por el instalador; no se modificara Nginx.\n' "$NGINX_SITE" >&2
        return
    fi

    cp -a "$NGINX_SITE" "$NGINX_SITE.update-backup.$(date -u +%Y%m%dT%H%M%SZ)"
    python3 - "$NGINX_SITE" "$APP_PORT" <<'PY'
from pathlib import Path
import sys

path = Path(sys.argv[1])
port = sys.argv[2]
text = path.read_text()
marker = "    location / {\n"
block = f"""    location /server {{
        proxy_pass http://127.0.0.1:{port};
        proxy_http_version 1.1;
        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
        proxy_set_header X-Forwarded-For $remote_addr;
        proxy_set_header X-Forwarded-Proto $scheme;
    }}
"""
if "location /server" in text:
    sys.exit(0)
if marker not in text:
    sys.exit("No se encontro el bloque location / para insertar /server.")
path.write_text(text.replace(marker, block + marker, 1))
PY
    nginx -t
    systemctl reload nginx
    printf 'Nginx actualizado para proxyear /server al backend.\n'
}

apply_control_migrations() {
    local db_url db_name db_user
    db_url=$(config_value DB_URL)
    db_user=$(config_value DB_USER)
    [[ -n $db_url ]] || die 'DB_URL no existe en /etc/ideascore/server.env.'
    [[ -n $db_user ]] || die 'DB_USER no existe en /etc/ideascore/server.env.'
    db_name="${db_url##*/}"
    [[ -n $db_name && $db_name != "$db_url" ]] || die 'No se pudo inferir la base central desde DB_URL.'
    [[ -f scripts/ubuntu/migrations.sh ]] || die 'No se encontro scripts/ubuntu/migrations.sh.'
    [[ -f database/control/migrations/V003__create_server_modules.sql ]] ||
        die 'No se encontro database/control/migrations/V003__create_server_modules.sql.'
    [[ -f database/control/migrations/V004__create_server_settings_and_module_packages.sql ]] ||
        die 'No se encontro database/control/migrations/V004__create_server_settings_and_module_packages.sql.'

    source scripts/ubuntu/migrations.sh
    apply_migration "$db_name" control database/control/migrations/V001__create_server_registry.sql
    apply_migration "$db_name" control database/control/migrations/V002__create_provisioning_audit_log.sql
    apply_migration "$db_name" control database/control/migrations/V003__create_server_modules.sql
    apply_migration "$db_name" control database/control/migrations/V004__create_server_settings_and_module_packages.sql
    runuser -u postgres -- psql -X -d "$db_name" -v ON_ERROR_STOP=1 -v db_user="$db_user" <<'SQL'
GRANT SELECT, INSERT, UPDATE, DELETE ON server_modules TO :"db_user";
GRANT SELECT, INSERT, UPDATE, DELETE ON server_settings TO :"db_user";
SQL

    [[ -f database/tenant/migrations/V004__create_application_permissions.sql ]] ||
        die 'No se encontro database/tenant/migrations/V004__create_application_permissions.sql.'
    [[ -f database/tenant/migrations/V005__create_application_user_credentials.sql ]] ||
        die 'No se encontro database/tenant/migrations/V005__create_application_user_credentials.sql.'
    [[ -f modules/clientes/migrations/V002__require_customer_contact_fields.sql ]] ||
        die 'No se encontro modules/clientes/migrations/V002__require_customer_contact_fields.sql.'
    while IFS= read -r tenant_db; do
        [[ -n $tenant_db ]] || continue
        apply_migration "$tenant_db" clientes modules/clientes/migrations/V002__require_customer_contact_fields.sql
        apply_migration "$tenant_db" tenant database/tenant/migrations/V004__create_application_permissions.sql
        apply_migration "$tenant_db" tenant database/tenant/migrations/V005__create_application_user_credentials.sql
        runuser -u postgres -- psql -X -d "$tenant_db" -v ON_ERROR_STOP=1 -v db_user="$db_user" <<'SQL'
GRANT SELECT, INSERT, UPDATE ON application_users TO :"db_user";
GRANT SELECT, INSERT, UPDATE, DELETE ON application_permissions, application_user_permissions TO :"db_user";
GRANT SELECT, INSERT, UPDATE, DELETE ON application_user_credentials TO :"db_user";
SQL
    done < <(runuser -u postgres -- psql -X -d "$db_name" -At -c "SELECT database_name FROM companies ORDER BY code")
}

[[ -d $SOURCE_DIR/.git ]] || die "No se encontro el repositorio en $SOURCE_DIR."
[[ -d $APP_RUNTIME_DIR && -x $APP_RUNTIME_DIR/bin/server ]] || die "No se encontro el servidor instalado en $APP_RUNTIME_DIR."
[[ -d $WEB_RUNTIME_DIR ]] || die "No se encontro la webapp instalada en $WEB_RUNTIME_DIR."
[[ -r $CONFIG_FILE ]] || die "No se encontro $CONFIG_FILE."
id ideascore >/dev/null 2>&1 || die 'No existe el usuario Linux ideascore.'
systemctl cat "$SERVICE_NAME" >/dev/null 2>&1 || die "No existe $SERVICE_NAME."

cd "$SOURCE_DIR"
CURRENT_REF=$(git_source rev-parse --abbrev-ref HEAD)
CURRENT_COMMIT=$(git_source rev-parse --short HEAD)
ask GIT_REF 'Rama, etiqueta o commit para actualizar' "$CURRENT_REF"
[[ $GIT_REF =~ ^[a-zA-Z0-9][a-zA-Z0-9._/-]*$ && $GIT_REF != *..* ]] || die 'Referencia Git invalida.'
ask UPDATE_TARGET 'Que deseas actualizar? server, webapp o ambos' ambos
case "$UPDATE_TARGET" in
    server|webapp|ambos) ;;
    *) die 'Opcion invalida. Usa server, webapp o ambos.' ;;
esac

if ! git_source diff --quiet || ! git_source diff --cached --quiet; then
    die 'El repositorio de instalacion tiene cambios locales. Resuelvelos antes de actualizar.'
fi

printf 'Instalacion actual: %s (%s)\n' "$CURRENT_REF" "$CURRENT_COMMIT"
printf 'Destino: %s\n' "$GIT_REF"
ask CONFIRM "Continuar con respaldo, build y reemplazo de $UPDATE_TARGET? Escribe si" no
[[ $CONFIRM == si ]] || die 'Cancelado sin cambiar archivos.'

if [[ -n ${JAVA_HOME:-} && -x $JAVA_HOME/bin/java ]]; then
    UPDATE_JAVA_HOME=$JAVA_HOME
else
    UPDATE_JAVA_HOME="/usr/lib/jvm/java-21-openjdk-$(dpkg --print-architecture)"
fi
[[ -x $UPDATE_JAVA_HOME/bin/java ]] || die 'No se encontro Java 21. Instala openjdk-21-jdk-headless o define JAVA_HOME.'
ensure_update_swap
configure_server_env
configure_nginx_server_route

git_source fetch --tags --prune origin
git_source checkout "$GIT_REF"
git_source pull --ff-only origin "$GIT_REF" 2>/dev/null || true
TARGET_COMMIT=$(git_source rev-parse --short HEAD)
if [[ $UPDATE_TARGET == server || $UPDATE_TARGET == ambos ]]; then
    apply_control_migrations
fi

runuser -u ideascore -- env JAVA_HOME="$UPDATE_JAVA_HOME" bash ./gradlew --stop >/dev/null 2>&1 || true

BUILD_LOG_DIR="$APP_DIR/update-logs"
install -d -m 755 "$BUILD_LOG_DIR"
SERVER_BUILD_LOG="$BUILD_LOG_DIR/server-$TARGET_COMMIT.log"
WEB_BUILD_LOG="$BUILD_LOG_DIR/web-$TARGET_COMMIT.log"

if [[ $UPDATE_TARGET == server || $UPDATE_TARGET == ambos ]]; then
    run_gradle -PserverOnly=true :server:test :server:installDist 2>&1 | tee "$SERVER_BUILD_LOG"
    [[ -x server/build/install/server/bin/server ]] || die 'No se genero la distribucion del servidor.'
fi
if [[ $UPDATE_TARGET == webapp || $UPDATE_TARGET == ambos ]]; then
    run_gradle -PwebOnly=true :app:webApp:jsBrowserDistribution 2>&1 | tee "$WEB_BUILD_LOG"
    WEB_DIST="$SOURCE_DIR/app/webApp/build/dist/js/productionExecutable"
    [[ -f $WEB_DIST/index.html && -f $WEB_DIST/webApp.js ]] || die 'No se genero la distribucion web completa.'
fi

TIMESTAMP=$(date -u +%Y%m%dT%H%M%SZ)
BACKUP_DIR=$BACKUP_ROOT/$TIMESTAMP
install -d -m 700 "$BACKUP_DIR"
cp -a "$APP_RUNTIME_DIR" "$BACKUP_DIR/app"
cp -a "$WEB_RUNTIME_DIR" "$BACKUP_DIR/web"
git_source rev-parse HEAD > "$BACKUP_DIR/source-commit.txt"

if [[ $UPDATE_TARGET == server || $UPDATE_TARGET == ambos ]]; then
    systemctl stop "$SERVICE_NAME"
fi

if [[ $UPDATE_TARGET == server || $UPDATE_TARGET == ambos ]]; then
    rm -rf "$APP_RUNTIME_DIR.new"
    install -d -m 755 "$APP_RUNTIME_DIR.new"
    cp -R server/build/install/server/. "$APP_RUNTIME_DIR.new/"
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
    cp -R "$WEB_DIST/." "$WEB_RUNTIME_DIR.new/"
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
        die 'El servicio no respondio en http://127.0.0.1:8080/ despues de 90 segundos. Los artefactos anteriores estan en *.previous y el respaldo en backups/.'
    fi
elif [[ $UPDATE_TARGET == server || $UPDATE_TARGET == ambos ]]; then
    systemctl is-active --quiet "$SERVICE_NAME" || {
        systemctl status "$SERVICE_NAME" --no-pager >&2 || true
        die 'El servicio no arranco. Los artefactos anteriores estan en *.previous y el respaldo en backups/.'
    }
fi

rm -rf "$APP_RUNTIME_DIR.previous" "$WEB_RUNTIME_DIR.previous"

printf 'IdeasCore actualizado (%s) de %s a %s.\n' "$UPDATE_TARGET" "$CURRENT_COMMIT" "$TARGET_COMMIT"
printf 'Respaldo: %s\n' "$BACKUP_DIR"
printf 'Logs: sudo journalctl -u ideascore -f\n'
