#!/usr/bin/env bash
# Genera el par de llaves Ed25519 con que se firman las extensiones de IdeasCore (keyId ideascore-2026).
#
# Uso: scripts/extensions/keygen.sh <ruta-de-la-llave-privada>
#
# - La llave privada se guarda en la ruta indicada, que debe quedar FUERA del repositorio
#   (por ejemplo ~/.ideascore/ideascore-2026.key). Nunca se sube a git ni se comparte.
# - Se imprime la llave publica en base64 (DER SubjectPublicKeyInfo). Pegala tal cual en
#   server/src/main/resources/extensions/ideascore-2026.pub
set -euo pipefail

if [ $# -ne 1 ]; then
  echo "Uso: $0 <ruta-de-la-llave-privada>" >&2
  exit 2
fi
key="$1"

if [ -e "$key" ]; then
  echo "Ya existe $key; no se sobrescribe." >&2
  exit 1
fi

mkdir -p "$(dirname "$key")"
folder="$(cd "$(dirname "$key")" && pwd)"
# Rechaza rutas dentro de un repositorio git: la privada no puede terminar versionada.
if git -C "$folder" rev-parse --is-inside-work-tree >/dev/null 2>&1; then
  echo "La llave privada no puede guardarse dentro de un repositorio git ($folder)." >&2
  exit 1
fi

umask 077
openssl genpkey -algorithm ed25519 -out "$key"
chmod 600 "$key"

echo "Llave privada guardada en $key (permisos 600). Guardala en un lugar seguro." >&2
echo "Llave publica (pegala en ideascore-2026.pub):" >&2
openssl pkey -in "$key" -pubout -outform DER | openssl base64 -A
echo
