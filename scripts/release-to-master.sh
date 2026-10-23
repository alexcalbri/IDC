#!/usr/bin/env bash
# Prepares a release of develop to master without test sources, as a pull request.
#
# Tests live only on develop. The script creates a release/<date>-<commit> branch from
# master, merges develop into it, removes every test source directory (src/test,
# src/commonTest, src/jvmTest, ...) in the same merge commit, pushes the branch and opens
# a pull request to master. master is never pushed directly, so its protection rules apply.
#
#   bash scripts/release-to-master.sh            # push the branch and open the pull request
#   bash scripts/release-to-master.sh --dry-run  # build the release commit locally only
#
# Merge the pull request with "Create a merge commit" (not squash or rebase) so the next
# release keeps a common history with develop.
set -Eeuo pipefail

readonly SOURCE=develop
readonly TARGET=master
# Any Kotlin/Gradle test source set: src/test, src/commonTest, src/jvmTest, src/androidUnitTest...
readonly TEST_DIR_PATTERN='(^|/)src/[A-Za-z]*([Tt]est)/'

die() { printf 'ERROR: %s\n' "$*" >&2; exit 1; }

DRY_RUN=false
[[ ${1:-} == --dry-run ]] && DRY_RUN=true

cd "$(git rev-parse --show-toplevel)"
[[ -z $(git status --porcelain) ]] || die 'Hay cambios sin commit. Haz commit o descartalos antes de publicar.'

REMOTE=$(git rev-parse --abbrev-ref "$SOURCE@{upstream}" 2>/dev/null | cut -d/ -f1)
[[ -n $REMOTE ]] || die "La rama $SOURCE no tiene rama remota configurada."
STARTING_BRANCH=$(git rev-parse --abbrev-ref HEAD)

printf 'Actualizando desde %s...\n' "$REMOTE"
git fetch --prune "$REMOTE"
[[ $(git rev-parse "$SOURCE") == $(git rev-parse "$REMOTE/$SOURCE") ]] ||
    die "$SOURCE local no coincide con $REMOTE/$SOURCE. Sube o actualiza $SOURCE antes de publicar."

if git merge-base --is-ancestor "$SOURCE" "$REMOTE/$TARGET"; then
    printf '%s ya contiene todo %s; no hay nada que publicar.\n' "$TARGET" "$SOURCE"
    exit 0
fi

SOURCE_COMMIT=$(git rev-parse --short "$SOURCE")
RELEASE_BRANCH="release/$(date +%Y%m%d)-$SOURCE_COMMIT"
git show-ref -q --verify "refs/heads/$RELEASE_BRANCH" &&
    die "Ya existe la rama local $RELEASE_BRANCH. Borrala (git branch -D $RELEASE_BRANCH) o publica un commit nuevo."

restore() {
    # Leaves the repository as it was if anything fails.
    if git rev-parse -q --verify MERGE_HEAD >/dev/null; then
        git merge --abort || true
    fi
    git checkout -q "$STARTING_BRANCH" || true
    git branch -q -D "$RELEASE_BRANCH" 2>/dev/null || true
}
trap 'restore; printf "Publicacion cancelada; no se subio nada.\n" >&2' ERR

git checkout -q -b "$RELEASE_BRANCH" "$REMOTE/$TARGET"
printf 'Fusionando %s (%s) en %s...\n' "$SOURCE" "$SOURCE_COMMIT" "$RELEASE_BRANCH"
# Conflicts are expected only on test files deleted in master and changed in develop.
git merge --no-ff --no-commit "$SOURCE" >/dev/null 2>&1 || true
if ! git rev-parse -q --verify MERGE_HEAD >/dev/null; then
    trap - ERR
    restore
    printf '%s ya contiene todo %s; no hay nada que publicar.\n' "$TARGET" "$SOURCE"
    exit 0
fi

# ls-files also lists unmerged paths, so conflicted test files are removed as well.
# grep finding nothing is normal; without "|| true" the inherited ERR trap would fire inside
# the process substitution and abort the merge behind the script's back.
mapfile -t test_dirs < <(git ls-files | { grep -E "$TEST_DIR_PATTERN" || true; } | sed -E 's#(src/[A-Za-z]*[Tt]est)/.*#\1#' | sort -u)
if (( ${#test_dirs[@]} > 0 )); then
    printf 'Quitando tests:\n'
    printf ' - %s\n' "${test_dirs[@]}"
    git rm -r -q --cached -- "${test_dirs[@]}"
    rm -rf -- "${test_dirs[@]}"
fi

unmerged=$(git diff --name-only --diff-filter=U)
if [[ -n $unmerged ]]; then
    printf 'Conflictos fuera de los tests; resuelvelos en %s:\n%s\n' "$SOURCE" "$unmerged" >&2
    false
fi
if git ls-files | grep -Eq "$TEST_DIR_PATTERN"; then
    printf 'Quedaron archivos de test en la publicacion.\n' >&2
    false
fi

TITLE="Release $SOURCE $SOURCE_COMMIT to $TARGET"
git commit -q -m "$TITLE

Merged $SOURCE into $TARGET and removed test sources; tests stay on $SOURCE."
CHANGED=$(git diff --name-only "$REMOTE/$TARGET" HEAD | wc -l | tr -d ' ')
COMMITS=$(git rev-list --count "$REMOTE/$TARGET..$SOURCE")
trap - ERR

printf '\nCommit de publicacion: %s (%s archivos, %s commits de %s).\n' "$(git log --oneline -1)" "$CHANGED" "$COMMITS" "$SOURCE"

if [[ $DRY_RUN == true ]]; then
    git checkout -q "$STARTING_BRANCH"
    printf 'Prueba terminada: la rama %s quedo solo en local. Borrala con: git branch -D %s\n' "$RELEASE_BRANCH" "$RELEASE_BRANCH"
    exit 0
fi

git push -q "$REMOTE" "$RELEASE_BRANCH"
git checkout -q "$STARTING_BRANCH"
# The branch lives on the remote for the pull request; the local copy is no longer needed.
git branch -q -D "$RELEASE_BRANCH"

BODY="Publica \`$SOURCE\` ($SOURCE_COMMIT, $COMMITS commits) en \`$TARGET\` sin los tests, que solo viven en \`$SOURCE\`.

Fusiona con **Create a merge commit** (no squash ni rebase) para que la siguiente publicacion comparta historial con \`$SOURCE\`."

if command -v gh >/dev/null 2>&1 && gh auth status >/dev/null 2>&1; then
    gh pr create --base "$TARGET" --head "$RELEASE_BRANCH" --title "$TITLE" --body "$BODY"
else
    # Without the GitHub CLI, open the pull request from the browser.
    remote_url=$(git remote get-url "$REMOTE")
    repo=$(sed -E 's#(git@github\.com:|https://github\.com/)##; s#\.git$##' <<< "$remote_url")
    printf '\nRama %s subida. Abre el pull request aqui:\n  https://github.com/%s/compare/%s...%s?expand=1\n' \
        "$RELEASE_BRANCH" "$repo" "$TARGET" "$RELEASE_BRANCH"
    printf '(Con GitHub CLI instalado y autenticado, el script lo abre solo.)\n'
fi
