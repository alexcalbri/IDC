#!/usr/bin/env bash
# Ayudante root que actualiza el core de IdeasCore a pedido del server owner.
# Lo instala install.sh/update.sh con --install como /usr/local/sbin/ideascore-core-update y lo corre
# ideascore-core-update.timer cada minuto.
#
# El backend nunca corre como root: solo inserta filas 'pending' en core_update_requests (base central).
# Este ayudante las toma, trae el codigo a un espejo propio de root y ejecuta update.sh desde ese espejo.
# Nunca ejecuta git ni scripts como root dentro de /opt/ideascore/source (lo puede escribir el usuario
# ideascore): todo lo que corre como root sale de /var/lib/ideascore/mirror.
set -Eeuo pipefail
umask 077

readonly CONFIG_FILE=/etc/ideascore/server.env
readonly STATE_DIR=/var/lib/ideascore
readonly MIRROR_DIR=$STATE_DIR/mirror
readonly REPO_URL=https://github.com/alexcalbri/IDC.git
readonly MAINTENANCE_LOCK_FILE=/run/ideascore-maintenance.lock
readonly HELPER_LOCK_FILE=/run/ideascore-core-update.lock
readonly DEPLOYED_FILE=$STATE_DIR/deployed-commit
readonly MIN_INTERVAL_MINUTES=10
readonly KEEP_LOGS=10
readonly INSTALLED_PATH=/usr/local/sbin/ideascore-core-update
readonly VERSION_PATTERN='^[0-9]+\.[0-9]+\.[0-9]+$'
readonly REF_PATTERN='^[A-Za-z0-9][A-Za-z0-9._/-]{0,100}$'

die() { printf 'ERROR: %s\n' "$*" >&2; exit 1; }

[[ $EUID -eq 0 ]] || die 'Debe ejecutarse como root.'

# Instala este script y sus unidades systemd; lo llaman install.sh y update.sh.
if [[ ${1:-} == --install ]]; then
    umask 022
    install -d -m 700 -o root -g root "$STATE_DIR"
    install -o root -g root -m 755 "${BASH_SOURCE[0]}" "$INSTALLED_PATH"
    cat > /etc/systemd/system/ideascore-core-update.service <<'UNIT'
[Unit]
Description=Update the IdeasCore core when the server owner requests it
After=network-online.target postgresql.service
Wants=network-online.target
[Service]
Type=oneshot
ExecStart=/usr/local/sbin/ideascore-core-update
TimeoutStartSec=2h
UNIT
    cat > /etc/systemd/system/ideascore-core-update.timer <<'UNIT'
[Unit]
Description=Check IdeasCore core update requests every minute
[Timer]
OnBootSec=2min
OnUnitActiveSec=1min
[Install]
WantedBy=timers.target
UNIT
    chmod 644 /etc/systemd/system/ideascore-core-update.{service,timer}
    systemctl daemon-reload
    systemctl enable --now ideascore-core-update.timer
    printf 'Ayudante de actualizacion del core instalado.\n'
    exit 0
fi

[[ -r $CONFIG_FILE ]] || die "No se encontro $CONFIG_FILE."
command -v flock >/dev/null 2>&1 || die 'No se encontro flock (paquete util-linux).'

# Una sola copia del ayudante a la vez.
exec 9>"$HELPER_LOCK_FILE"
flock -n 9 || exit 0

config_value() {
    awk -F= -v key="$1" '$1 == key { sub(/^[^=]*=/, ""); print; exit }' "$CONFIG_FILE"
}

DB_URL=$(config_value DB_URL)
DB_NAME=${DB_URL##*/}
[[ $DB_NAME =~ ^[a-z][a-z0-9_]{0,62}$ ]] || die 'No se pudo inferir la base central desde DB_URL.'
CORE_UPDATE_REF=$(config_value CORE_UPDATE_REF)
CORE_UPDATE_REF=${CORE_UPDATE_REF:-master}
[[ $CORE_UPDATE_REF =~ $REF_PATTERN && $CORE_UPDATE_REF != *..* && $CORE_UPDATE_REF != *.lock ]] ||
    die 'CORE_UPDATE_REF invalido en server.env.'

central_psql() {
    runuser -u postgres -- psql -X -q -d "$DB_NAME" -v ON_ERROR_STOP=1 "$@"
}

# Verdadero si ninguna instalacion ni actualizacion tiene el lock de mantenimiento.
maintenance_lock_free() {
    ( flock -n 8 ) 8>>"$MAINTENANCE_LOCK_FILE"
}

# Se omite en silencio hasta que la migracion V010 este aplicada.
[[ $(central_psql -Atc "SELECT to_regclass('public.core_update_requests') IS NOT NULL") == t ]] || exit 0

# Recuperacion: una fila 'running' sin proceso de mantenimiento activo quedo interrumpida.
if [[ -n $(central_psql -Atc "SELECT 1 FROM core_update_requests WHERE status = 'running' LIMIT 1") ]] &&
    maintenance_lock_free; then
    central_psql <<'SQL'
UPDATE core_update_requests
SET status = 'error', message = 'Actualizacion interrumpida', finished_at = now()
WHERE status = 'running';
SQL
fi

# Enfriamiento: minimo 10 minutos desde que termino la ultima solicitud; la pendiente espera.
[[ -z $(central_psql -Atc "SELECT 1 FROM core_update_requests WHERE status IN ('done', 'error') AND finished_at > now() - interval '$MIN_INTERVAL_MINUTES minutes' LIMIT 1") ]] || exit 0

PENDING=$(central_psql -At -F '|' -c "SELECT id, target_version FROM core_update_requests WHERE status = 'pending' ORDER BY id LIMIT 1")
[[ -n $PENDING ]] || exit 0
REQUEST_ID=${PENDING%%|*}
TARGET_VERSION=${PENDING#*|}
[[ $REQUEST_ID =~ ^[0-9]+$ ]] || die 'Identificador de solicitud invalido.'

# Si hay otra instalacion o actualizacion en curso, la solicitud sigue pendiente.
maintenance_lock_free || exit 0

LOG_FILE=/var/log/ideascore-core-update-$REQUEST_ID.log
FINALIZED=false

# Ultimas 60 lineas del log, sin bytes invalidos ni caracteres de control (salvo salto de linea y tab).
log_tail() {
    [[ -f $LOG_FILE ]] || return 0
    tail -n 60 "$LOG_FILE" | tail -c 8000 | iconv -c -f UTF-8 -t UTF-8 | tr -d '\000-\010\013-\037\177' || true
}

# Los valores siempre viajan como variables de psql, nunca interpolados en el SQL.
finish_request() {
    local status=$1 message=$2 tail_text
    tail_text=$(log_tail)
    central_psql -v id="$REQUEST_ID" -v status="$status" -v message="$message" -v log_tail="$tail_text" <<'SQL'
UPDATE core_update_requests
SET status = :'status',
    message = NULLIF(:'message', ''),
    log_tail = NULLIF(:'log_tail', ''),
    finished_at = now()
WHERE id = :'id'::bigint AND status IN ('pending', 'running');
SQL
    FINALIZED=true
}

# Los fallos al registrar el resultado quedan en el journal y en el log de la solicitud.
report_failure() {
    local msg="No se pudo registrar el resultado de la solicitud $REQUEST_ID en la base central"
    printf 'ERROR: %s\n' "$msg" >&2
    [[ ! -f $LOG_FILE ]] || printf '%s ERROR: %s\n' "$(date -u +%Y-%m-%dT%H:%M:%SZ)" "$msg" >> "$LOG_FILE"
}

# Conserva solo los ultimos KEEP_LOGS logs de actualizaciones y de compilaciones.
prune_logs() {
    local pattern item
    for pattern in '/var/log/ideascore-core-update-*.log' '/var/log/ideascore-update/*.log'; do
        while IFS= read -r item; do
            if [[ -f $item ]]; then
                unlink -- "$item"
            fi
        done < <(compgen -G "$pattern" | xargs -r ls -1t | tail -n "+$((KEEP_LOGS + 1))" || true)
    done
    return 0
}

on_exit() {
    local code=$?
    trap - EXIT ERR
    if [[ $FINALIZED != true ]]; then
        finish_request error 'El ayudante termino de forma inesperada; revisa el log' || report_failure
    fi
    exit "$code"
}
trap on_exit EXIT
trap 'exit 143' TERM INT

fail_request() {
    finish_request error "$1" || report_failure
    exit 1
}

mirror_git() {
    GIT_TERMINAL_PROMPT=0 git -c core.hooksPath=/dev/null -C "$MIRROR_DIR" "$@"
}

# Deja el espejo en el ultimo commit de CORE_UPDATE_REF. Cada paso usa || return para que set -e
# no se suspenda dentro de un "if".
sync_mirror() {
    install -d -m 700 -o root -g root "$STATE_DIR" || return 1
    if [[ ! -d $MIRROR_DIR/.git ]]; then
        rm -rf -- "$MIRROR_DIR"
        GIT_TERMINAL_PROMPT=0 git -c core.hooksPath=/dev/null clone --quiet "$REPO_URL" "$MIRROR_DIR" || return 1
    fi
    chmod 700 "$MIRROR_DIR" || return 1
    [[ $(mirror_git remote get-url origin) == "$REPO_URL" ]] || return 1
    mirror_git fetch --quiet --no-tags origin "$CORE_UPDATE_REF" || return 1
    mirror_git checkout --quiet --force --detach FETCH_HEAD || return 1
    mirror_git clean -ffdxq || return 1
}

[[ $TARGET_VERSION =~ $VERSION_PATTERN ]] || fail_request 'Version destino invalida.'
install -m 600 /dev/null "$LOG_FILE"
FINALIZED=false

sync_mirror >> "$LOG_FILE" 2>&1 || fail_request 'No se pudo descargar el codigo desde GitHub'
TARGET_COMMIT=$(mirror_git rev-parse HEAD)
[[ $TARGET_COMMIT =~ ^[0-9a-f]{40}$ ]] || fail_request 'No se pudo leer el commit descargado'
grep -Fxq "version = \"$TARGET_VERSION\"" "$MIRROR_DIR/server/build.gradle.kts" ||
    fail_request "El codigo descargado de $CORE_UPDATE_REF no corresponde a la version $TARGET_VERSION"

# Ya desplegado: no se vuelve a compilar ni a reiniciar.
if [[ -f $DEPLOYED_FILE && $(cat "$DEPLOYED_FILE") == "$TARGET_COMMIT" ]]; then
    finish_request done 'Ya estaba actualizado' || report_failure
    prune_logs
    exit 0
fi

RUNNING_ID=$(central_psql -At -v id="$REQUEST_ID" <<'SQL'
UPDATE core_update_requests
SET status = 'running', started_at = now()
WHERE id = :'id'::bigint AND status = 'pending'
RETURNING id;
SQL
)
[[ $RUNNING_ID == "$REQUEST_ID" ]] || fail_request 'La solicitud ya no estaba pendiente'

printf '%s Actualizando a %s (commit %s)\n' "$(date -u +%Y-%m-%dT%H:%M:%SZ)" "$TARGET_VERSION" "$TARGET_COMMIT" >> "$LOG_FILE"
UPDATE_STATUS=0
# update.sh reinicia ideascore.service; este ayudante es su propia unidad, asi que no se interrumpe.
IDEASCORE_TRUSTED_DIR=$MIRROR_DIR bash "$MIRROR_DIR/scripts/ubuntu/update.sh" \
    --non-interactive --commit "$TARGET_COMMIT" --target ambos >> "$LOG_FILE" 2>&1 || UPDATE_STATUS=$?

if (( UPDATE_STATUS == 0 )); then
    printf '%s\n' "$TARGET_COMMIT" > "$DEPLOYED_FILE"
    finish_request done "Actualizado a $TARGET_VERSION" || report_failure
else
    finish_request error 'Fallo la actualizacion; revisa el log' || report_failure
fi
prune_logs
