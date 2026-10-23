#!/usr/bin/env bash
# Publishes develop on master without test sources.
#
# Tests live only on develop. This script merges develop into master, removes every
# test source directory (src/test, src/commonTest, src/jvmTest, ...) in the same merge
# commit and pushes master. Run it from a clean checkout:
#
#   bash scripts/release-to-master.sh          # asks before pushing
#   bash scripts/release-to-master.sh --yes    # pushes without asking
set -Eeuo pipefail

readonly SOURCE=develop
readonly TARGET=master
# Any Kotlin/Gradle test source set: src/test, src/commonTest, src/jvmTest, src/androidUnitTest...
readonly TEST_DIR_PATTERN='(^|/)src/[A-Za-z]*([Tt]est)/'

die() { printf 'ERROR: %s\n' "$*" >&2; exit 1; }

ASSUME_YES=false
[[ ${1:-} == --yes ]] && ASSUME_YES=true

cd "$(git rev-parse --show-toplevel)"
[[ -z $(git status --porcelain) ]] || die 'Hay cambios sin commit. Haz commit o descartalos antes de publicar.'

REMOTE=$(git rev-parse --abbrev-ref "$SOURCE@{upstream}" 2>/dev/null | cut -d/ -f1) ||
    die "La rama $SOURCE no tiene rama remota configurada."
[[ -n $REMOTE ]] || die "La rama $SOURCE no tiene rama remota configurada."
STARTING_BRANCH=$(git rev-parse --abbrev-ref HEAD)

printf 'Actualizando desde %s...\n' "$REMOTE"
git fetch --prune "$REMOTE"
[[ $(git rev-parse "$SOURCE") == $(git rev-parse "$REMOTE/$SOURCE") ]] ||
    die "$SOURCE local no coincide con $REMOTE/$SOURCE. Sube o actualiza $SOURCE antes de publicar."

restore() {
    # Leaves the repository as it was if anything fails before the push.
    if git rev-parse -q --verify MERGE_HEAD >/dev/null; then
        git merge --abort || true
    fi
    git checkout -q "$STARTING_BRANCH" || true
}
trap 'restore; printf "Publicacion cancelada; no se subio nada.\n" >&2' ERR

if git show-ref -q --verify "refs/heads/$TARGET"; then
    git checkout -q "$TARGET"
    git merge -q --ff-only "$REMOTE/$TARGET" || die "$TARGET local tiene commits que no estan en $REMOTE/$TARGET."
else
    git checkout -q -b "$TARGET" "$REMOTE/$TARGET"
fi

SOURCE_COMMIT=$(git rev-parse --short "$SOURCE")
printf 'Fusionando %s (%s) en %s...\n' "$SOURCE" "$SOURCE_COMMIT" "$TARGET"
# Conflicts are expected only on test files deleted in master and changed in develop.
git merge --no-ff --no-commit "$SOURCE" >/dev/null 2>&1 || true
git rev-parse -q --verify MERGE_HEAD >/dev/null || {
    restore
    printf '%s ya contiene todo %s; no hay nada que publicar.\n' "$TARGET" "$SOURCE"
    trap - ERR
    exit 0
}

# ls-files also lists unmerged paths, so conflicted test files are removed as well.
mapfile -t test_dirs < <(git ls-files | grep -E "$TEST_DIR_PATTERN" | sed -E 's#(src/[A-Za-z]*[Tt]est)/.*#\1#' | sort -u)
if (( ${#test_dirs[@]} > 0 )); then
    printf 'Quitando tests de %s:\n' "$TARGET"
    printf ' - %s\n' "${test_dirs[@]}"
    git rm -r -q --cached -- "${test_dirs[@]}"
    # Remove the files from the working tree too so they are not left as untracked files.
    rm -rf -- "${test_dirs[@]}"
fi

unmerged=$(git diff --name-only --diff-filter=U)
if [[ -n $unmerged ]]; then
    printf 'Conflictos fuera de los tests; resuelvelos en %s a mano:\n%s\n' "$SOURCE" "$unmerged" >&2
    false
fi

git commit -q -m "Release $SOURCE $SOURCE_COMMIT to $TARGET

Merged $SOURCE into $TARGET and removed test sources; tests stay on $SOURCE."
trap - ERR

printf '\nCommit de publicacion creado en %s:\n' "$TARGET"
git log --oneline -1
printf 'Cambios respecto a %s/%s: %s archivos.\n' "$REMOTE" "$TARGET" "$(git diff --name-only "$REMOTE/$TARGET" HEAD | wc -l | tr -d ' ')"
if git ls-files | grep -Eq "$TEST_DIR_PATTERN"; then
    die "Quedaron archivos de test en $TARGET; revisa antes de subir."
fi

if [[ $ASSUME_YES != true ]]; then
    read -r -p "Subir $TARGET a $REMOTE? Escribe si: " answer
    if [[ $answer != si ]]; then
        printf 'No se subio nada. El commit queda en %s local; puedes subirlo con: git push %s %s\n' "$TARGET" "$REMOTE" "$TARGET"
        git checkout -q "$STARTING_BRANCH"
        exit 0
    fi
fi

git push "$REMOTE" "$TARGET"
git checkout -q "$STARTING_BRANCH"
printf '%s publicado en %s/%s sin tests.\n' "$SOURCE" "$REMOTE" "$TARGET"
