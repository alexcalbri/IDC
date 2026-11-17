#!/usr/bin/env bash
# Espejo git de root para IdeasCore. Lo cargan con "source" los scripts que corren como root
# (core-update.sh y update.sh); no se ejecuta solo.
#
# El espejo (/var/lib/ideascore/mirror) es un clon que solo escribe root. Todo lo que root ejecuta o
# lee al actualizar sale de aqui, nunca de /opt/ideascore/source (lo puede escribir el usuario ideascore).

readonly REPO_URL=https://github.com/alexcalbri/IDC.git
readonly MIRROR_STATE_DIR=/var/lib/ideascore
readonly MIRROR_DIR=$MIRROR_STATE_DIR/mirror
readonly MIRROR_REF_PATTERN='^[A-Za-z0-9][A-Za-z0-9._/-]{0,100}$'

# Git dentro del espejo, sin hooks, sin fsmonitor y sin pedir credenciales.
mirror_git() {
    GIT_TERMINAL_PROMPT=0 git -c core.hooksPath=/dev/null -c core.fsmonitor=false -C "$MIRROR_DIR" "$@"
}

# Deja el espejo exactamente en <rama, etiqueta o sha de 40 hex> de origin e imprime el sha resultante.
# Cada paso usa || return para que set -e no se suspenda cuando se llama dentro de un "if".
sync_mirror() {
    local ref=${1:-}
    if [[ ! $ref =~ $MIRROR_REF_PATTERN || $ref == *..* || $ref == *.lock ]]; then
        printf 'Referencia Git invalida para el espejo.\n' >&2
        return 1
    fi
    install -d -m 700 -o root -g root "$MIRROR_STATE_DIR" || return 1
    if [[ ! -d $MIRROR_DIR/.git ]]; then
        rm -rf --one-file-system -- "$MIRROR_DIR" || return 1
        GIT_TERMINAL_PROMPT=0 git -c core.hooksPath=/dev/null -c core.fsmonitor=false \
            clone --quiet "$REPO_URL" "$MIRROR_DIR" || return 1
    fi
    chmod 700 "$MIRROR_DIR" || return 1
    [[ $(mirror_git remote get-url origin) == "$REPO_URL" ]] || return 1
    mirror_git fetch --quiet --no-tags origin "$ref" || return 1
    mirror_git checkout --quiet --force --detach FETCH_HEAD || return 1
    mirror_git clean -ffdxq || return 1
    mirror_git rev-parse HEAD
}
