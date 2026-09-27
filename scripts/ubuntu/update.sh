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
readonly CONFIG_FILE=/etc/ideascore/server.env
readonly SERVICE_NAME=ideascore.service
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

[[ -d $SOURCE_DIR/.git ]] || die "No se encontro el repositorio en $SOURCE_DIR."
[[ -d $APP_RUNTIME_DIR && -x $APP_RUNTIME_DIR/bin/server ]] || die "No se encontro el servidor instalado en $APP_RUNTIME_DIR."
[[ -r $CONFIG_FILE ]] || die "No se encontro $CONFIG_FILE."
id ideascore >/dev/null 2>&1 || die 'No existe el usuario Linux ideascore.'
systemctl cat "$SERVICE_NAME" >/dev/null 2>&1 || die "No existe $SERVICE_NAME."

cd "$SOURCE_DIR"
CURRENT_REF=$(git_source rev-parse --abbrev-ref HEAD)
CURRENT_COMMIT=$(git_source rev-parse --short HEAD)
ask GIT_REF 'Rama, etiqueta o commit para actualizar' "$CURRENT_REF"
[[ $GIT_REF =~ ^[a-zA-Z0-9][a-zA-Z0-9._/-]*$ && $GIT_REF != *..* ]] || die 'Referencia Git invalida.'

if ! git_source diff --quiet || ! git_source diff --cached --quiet; then
    die 'El repositorio de instalacion tiene cambios locales. Resuelvelos antes de actualizar.'
fi

printf 'Instalacion actual: %s (%s)\n' "$CURRENT_REF" "$CURRENT_COMMIT"
printf 'Destino: %s\n' "$GIT_REF"
ask CONFIRM 'Continuar con respaldo, build y reemplazo del servidor? Escribe si' no
[[ $CONFIRM == si ]] || die 'Cancelado sin cambiar archivos.'

if [[ -n ${JAVA_HOME:-} && -x $JAVA_HOME/bin/java ]]; then
    UPDATE_JAVA_HOME=$JAVA_HOME
else
    UPDATE_JAVA_HOME="/usr/lib/jvm/java-21-openjdk-$(dpkg --print-architecture)"
fi
[[ -x $UPDATE_JAVA_HOME/bin/java ]] || die 'No se encontro Java 21. Instala openjdk-21-jdk-headless o define JAVA_HOME.'
ensure_update_swap

git_source fetch --tags --prune origin
git_source checkout "$GIT_REF"
git_source pull --ff-only origin "$GIT_REF" 2>/dev/null || true
TARGET_COMMIT=$(git_source rev-parse --short HEAD)

runuser -u ideascore -- env JAVA_HOME="$UPDATE_JAVA_HOME" bash ./gradlew --stop >/dev/null 2>&1 || true

BUILD_LOG_DIR="$APP_DIR/update-logs"
install -d -m 755 "$BUILD_LOG_DIR"
SERVER_BUILD_LOG="$BUILD_LOG_DIR/server-$TARGET_COMMIT.log"

run_gradle -PserverOnly=true :server:test :server:installDist 2>&1 | tee "$SERVER_BUILD_LOG"
[[ -x server/build/install/server/bin/server ]] || die 'No se genero la distribucion del servidor.'

TIMESTAMP=$(date -u +%Y%m%dT%H%M%SZ)
BACKUP_DIR=$BACKUP_ROOT/$TIMESTAMP
install -d -m 700 "$BACKUP_DIR"
cp -a "$APP_RUNTIME_DIR" "$BACKUP_DIR/app"
git_source rev-parse HEAD > "$BACKUP_DIR/source-commit.txt"

systemctl stop "$SERVICE_NAME"

rm -rf "$APP_RUNTIME_DIR.new"
install -d -m 755 "$APP_RUNTIME_DIR.new"
cp -R server/build/install/server/. "$APP_RUNTIME_DIR.new/"
chown -R root:root "$APP_RUNTIME_DIR.new"
chmod -R a+rX "$APP_RUNTIME_DIR.new"
chmod -R go-w "$APP_RUNTIME_DIR.new"

rm -rf "$APP_RUNTIME_DIR.previous"
mv "$APP_RUNTIME_DIR" "$APP_RUNTIME_DIR.previous"
mv "$APP_RUNTIME_DIR.new" "$APP_RUNTIME_DIR"

systemctl start "$SERVICE_NAME"
if command -v curl >/dev/null 2>&1; then
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
else
    systemctl is-active --quiet "$SERVICE_NAME" || {
        systemctl status "$SERVICE_NAME" --no-pager >&2 || true
        die 'El servicio no arranco. Los artefactos anteriores estan en *.previous y el respaldo en backups/.'
    }
fi

rm -rf "$APP_RUNTIME_DIR.previous"

printf 'IdeasCore actualizado de %s a %s.\n' "$CURRENT_COMMIT" "$TARGET_COMMIT"
printf 'Respaldo: %s\n' "$BACKUP_DIR"
printf 'Logs: sudo journalctl -u ideascore -f\n'
