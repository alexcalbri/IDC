#!/usr/bin/env bash
# Firma una carpeta de extension: calcula el hash del arbol y escribe extension.sig.json.
#
# Uso:
#   scripts/extensions/sign.sh <carpeta> <llave-privada>   firma y escribe <carpeta>/extension.sig.json
#   scripts/extensions/sign.sh --hash <carpeta>            solo imprime el hash del arbol
#
# Algoritmo del hash (identico a ExtensionTreeHash.kt, que verifica el servidor):
#   1. Se toman todos los archivos regulares de la carpeta, de forma recursiva, excepto
#      extension.sig.json de la raiz.
#   2. Se ordenan por ruta relativa, con '/' como separador, en orden de bytes (LC_ALL=C).
#      Las rutas solo pueden usar [A-Za-z0-9._/-], sin segmentos vacios, '.' ni '..'; si una
#      ruta no cumple, el script falla (y el servidor rechaza el paquete).
#   3. Por cada archivo se acumula:  <ruta>\n<sha256 hex del contenido>\n
#   4. El hash es el SHA-256 de todo lo acumulado, en hex (64 caracteres).
# La firma es Ed25519 sobre los 32 bytes del hash (no sobre su texto hex).
# Requiere openssl 3 o superior.
set -euo pipefail

KEY_ID="ideascore-2026"

# Rutas permitidas: solo [A-Za-z0-9._/-], sin segmentos vacios, '.' ni '..' (igual que ExtensionTreeHash.kt).
check_path() {
  local path="$1"
  if ! [[ "$path" =~ ^[A-Za-z0-9._/-]+$ ]]; then
    echo "Ruta no permitida (solo A-Za-z0-9._/-): $path" >&2
    exit 1
  fi
  case "/$path/" in
    *//*|*/./*|*/../*) echo "Ruta no permitida (segmento vacio, '.' o '..'): $path" >&2; exit 1 ;;
  esac
}

tree_hash() {
  local dir="$1"
  (
    cd "$dir"
    find . -type f ! -path './extension.sig.json' -print | sed 's|^\./||' | LC_ALL=C sort | while IFS= read -r path; do
      check_path "$path"
      printf '%s\n%s\n' "$path" "$(openssl dgst -sha256 -r "$path" | cut -d' ' -f1)"
    done | openssl dgst -sha256 -r | cut -d' ' -f1
  )
}

if [ "${1:-}" = "--hash" ]; then
  [ $# -eq 2 ] || { echo "Uso: $0 --hash <carpeta>" >&2; exit 2; }
  tree_hash "$2"
  exit 0
fi

if [ $# -ne 2 ]; then
  echo "Uso: $0 <carpeta> <llave-privada>" >&2
  exit 2
fi
dir="$1"
key="$2"
[ -f "$dir/extension.json" ] || { echo "Falta $dir/extension.json" >&2; exit 1; }
[ -f "$key" ] || { echo "No existe la llave privada $key" >&2; exit 1; }

hash="$(tree_hash "$dir")"
work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT
# Los 32 bytes del hash, a partir de su hex.
printf "$(printf '%s' "$hash" | sed 's/../\x&/g')" > "$work/hash.bin"
openssl pkeyutl -sign -inkey "$key" -rawin -in "$work/hash.bin" -out "$work/signature.bin"
signature="$(openssl base64 -A -in "$work/signature.bin")"

printf '{\n  "packageSha256": "%s",\n  "signature": { "keyId": "%s", "value": "%s" }\n}\n' \
  "$hash" "$KEY_ID" "$signature" > "$dir/extension.sig.json"
echo "Firmado $dir (sha256 $hash)" >&2
