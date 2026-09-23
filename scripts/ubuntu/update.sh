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
trap 'printf "Actualizacion interrumpida (linea %s). Revisa los respaldos antes de reintentar.\n" "$LINENO" >&2' ERR

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
readonly BACKUP_ROOT=$APP_DIR/backups

[[ -d $SOURCE_DIR/.git ]] || die "No se encontro el repositorio en $SOURCE_DIR."
[[ -d $APP_RUNTIME_DIR && -x $APP_RUNTIME_DIR/bin/server ]] || die "No se encontro el servidor instalado en $APP_RUNTIME_DIR."
WEB_ALREADY_INSTALLED=false
if [[ -d $WEB_RUNTIME_DIR && -f $WEB_RUNTIME_DIR/index.html ]]; then
    WEB_ALREADY_INSTALLED=true
else
    printf 'No se encontro la app web instalada en %s; se compilara e instalara durante esta actualizacion.\n' "$WEB_RUNTIME_DIR"
fi
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
ask CONFIRM 'Continuar con respaldo, build y reemplazo de artefactos? Escribe si' no
[[ $CONFIRM == si ]] || die 'Cancelado sin cambiar archivos.'

if [[ -n ${JAVA_HOME:-} && -x $JAVA_HOME/bin/java ]]; then
    UPDATE_JAVA_HOME=$JAVA_HOME
else
    UPDATE_JAVA_HOME="/usr/lib/jvm/java-21-openjdk-$(dpkg --print-architecture)"
fi
[[ -x $UPDATE_JAVA_HOME/bin/java ]] || die 'No se encontro Java 21. Instala openjdk-21-jdk-headless o define JAVA_HOME.'

git_source fetch --tags --prune origin
git_source checkout "$GIT_REF"
git_source pull --ff-only origin "$GIT_REF" 2>/dev/null || true
TARGET_COMMIT=$(git_source rev-parse --short HEAD)

runuser -u ideascore -- env JAVA_HOME="$UPDATE_JAVA_HOME" bash ./gradlew \
    -PserverOnly=true --no-daemon --console=plain :server:test :server:installDist
[[ -x server/build/install/server/bin/server ]] || die 'No se genero la distribucion del servidor.'

runuser -u ideascore -- env JAVA_HOME="$UPDATE_JAVA_HOME" bash ./gradlew \
    -PwebOnly=true --no-daemon --console=plain :app:webApp:jsBrowserDistribution
WEB_DIST="$SOURCE_DIR/app/webApp/build/dist/js/productionExecutable"
[[ -f $WEB_DIST/index.html && -f $WEB_DIST/webApp.js ]] || die 'No se genero la distribucion web completa.'

TIMESTAMP=$(date -u +%Y%m%dT%H%M%SZ)
BACKUP_DIR=$BACKUP_ROOT/$TIMESTAMP
install -d -m 700 "$BACKUP_DIR"
cp -a "$APP_RUNTIME_DIR" "$BACKUP_DIR/app"
if [[ $WEB_ALREADY_INSTALLED == true ]]; then
    cp -a "$WEB_RUNTIME_DIR" "$BACKUP_DIR/web"
fi
git_source rev-parse HEAD > "$BACKUP_DIR/source-commit.txt"

systemctl stop "$SERVICE_NAME"

rm -rf "$APP_RUNTIME_DIR.new" "$WEB_RUNTIME_DIR.new"
install -d -m 755 "$APP_RUNTIME_DIR.new" "$WEB_RUNTIME_DIR.new"
cp -R server/build/install/server/. "$APP_RUNTIME_DIR.new/"
cp -R "$WEB_DIST/." "$WEB_RUNTIME_DIR.new/"
chown -R root:root "$APP_RUNTIME_DIR.new" "$WEB_RUNTIME_DIR.new"
chmod -R a+rX "$APP_RUNTIME_DIR.new" "$WEB_RUNTIME_DIR.new"
chmod -R go-w "$APP_RUNTIME_DIR.new" "$WEB_RUNTIME_DIR.new"

rm -rf "$APP_RUNTIME_DIR.previous" "$WEB_RUNTIME_DIR.previous"
mv "$APP_RUNTIME_DIR" "$APP_RUNTIME_DIR.previous"
if [[ $WEB_ALREADY_INSTALLED == true ]]; then
    mv "$WEB_RUNTIME_DIR" "$WEB_RUNTIME_DIR.previous"
fi
mv "$APP_RUNTIME_DIR.new" "$APP_RUNTIME_DIR"
mv "$WEB_RUNTIME_DIR.new" "$WEB_RUNTIME_DIR"

systemctl start "$SERVICE_NAME"
sleep 2
systemctl is-active --quiet "$SERVICE_NAME" || {
    systemctl status "$SERVICE_NAME" --no-pager >&2 || true
    die 'El servicio no arranco. Los artefactos anteriores estan en *.previous y el respaldo en backups/.'
}

if command -v curl >/dev/null 2>&1; then
    curl --fail --silent --show-error --max-time 15 http://127.0.0.1:8080/ >/dev/null ||
        die 'El servicio arranco, pero el health check local fallo.'
fi

rm -rf "$APP_RUNTIME_DIR.previous" "$WEB_RUNTIME_DIR.previous"

printf 'IdeasCore actualizado de %s a %s.\n' "$CURRENT_COMMIT" "$TARGET_COMMIT"
printf 'Respaldo: %s\n' "$BACKUP_DIR"
printf 'Logs: sudo journalctl -u ideascore -f\n'
