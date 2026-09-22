#!/usr/bin/env bash
# First installation on Ubuntu 24.04/26.04 with systemd.
# Download this file, review it, then run: sudo bash install.sh
set -Eeuo pipefail
set +x
umask 077

die() { printf 'ERROR: %s\n' "$*" >&2; exit 1; }
trap 'printf "Instalación interrumpida (línea %s). Se conservan los archivos y datos para diagnóstico.\n" "$LINENO" >&2' ERR

# Read from the terminal even when the script was downloaded via a pipe.
ask() {
    local value
    read -r -p "$2 [$3]: " value </dev/tty
    printf -v "$1" '%s' "${value:-$3}"
}

# Ubuntu's package database distinguishes an installed package from one that
# was removed but still has configuration files. Install only missing packages.
APT_UPDATED=false
ensure_packages() {
    local package status
    local missing=()
    for package in "$@"; do
        status=$(dpkg-query -W -f='${Status}' "$package" 2>/dev/null) || status='missing'
        if [[ $status == 'install ok installed' ]]; then
            printf 'Disponible: %s\n' "$package"
        else
            printf 'Falta: %s (se instalará)\n' "$package"
            missing+=("$package")
        fi
    done
    if (( ${#missing[@]} > 0 )); then
        if [[ $APT_UPDATED == false ]]; then
            apt-get update
            APT_UPDATED=true
        fi
        DEBIAN_FRONTEND=noninteractive apt-get install -y "${missing[@]}"
        for package in "${missing[@]}"; do
            [[ $(dpkg-query -W -f='${Status}' "$package") == 'install ok installed' ]] ||
                die "No se pudo completar la instalación de $package."
        done
    fi
}

[[ $EUID -eq 0 ]] || die 'Ejecuta con sudo bash install.sh.'
[[ -r /etc/os-release ]] || die 'No se pudo identificar Ubuntu.'
source /etc/os-release
[[ $ID == ubuntu && ($VERSION_ID == 24.04 || $VERSION_ID == 26.04) ]] ||
    die 'Este instalador requiere Ubuntu 24.04 o 26.04.'
[[ -d /run/systemd/system ]] || die 'Se necesita systemd (no ejecutar dentro de un contenedor).'
[[ -r /dev/tty ]] || die 'Se necesita una terminal interactiva; usa ssh -t.'

readonly APP_DIR=/opt/ideascore
readonly CONFIG_DIR=/etc/ideascore
readonly SERVICE_FILE=/etc/systemd/system/ideascore.service
readonly REPO_URL=https://github.com/alexcalbri/IDC.git
# Both services run on this Ubuntu host. No server IP needs to be entered.
readonly APP_HOST=127.0.0.1
readonly APP_PORT=8080
readonly NGINX_SITE=/etc/nginx/sites-available/ideascore
readonly ACME_ROOT=/var/www/ideascore-acme
for unit in /etc/systemd/system/ideascore-certbot.service /etc/systemd/system/ideascore-certbot.timer; do
    [[ ! -e $unit && ! -L $unit ]] || die 'Ya existe una configuración de renovación de IdeasCore.'
done

# Never overwrite an existing installation or adopt an unrelated Linux account.
[[ ! -e $APP_DIR && ! -e $CONFIG_DIR && ! -e $SERVICE_FILE ]] ||
    die 'Ya hay archivos de IdeasCore. Este script es para una primera instalación, no para actualizarla.'
[[ ! -e $NGINX_SITE && ! -e /etc/nginx/sites-enabled/ideascore && ! -L /etc/nginx/sites-enabled/ideascore ]] ||
    die 'Ya existe una configuración Nginx de IdeasCore.'
! getent passwd ideascore >/dev/null || die 'El usuario Linux ideascore ya existe.'
! getent group ideascore >/dev/null || die 'El grupo Linux ideascore ya existe.'
[[ $(systemctl show ideascore.service --property=LoadState --value) == not-found ]] ||
    die 'Ya existe un servicio ideascore.'

printf '\nIdeasCore: instalación completa del backend en este Ubuntu.\n'
printf 'Se comprobarán Java 21, PostgreSQL, Nginx, Certbot, Git, curl, Python, DNS y OpenSSL.\n'
printf 'Los componentes que falten se instalarán; Gradle descargará Ktor al compilar.\n'
printf 'Repositorio: %s\nLa rama elegida debe incluir este instalador y sus cambios de backend.\n' "$REPO_URL"
ask GIT_REF 'Rama o etiqueta de GitHub' develop
[[ $GIT_REF =~ ^[a-zA-Z0-9][a-zA-Z0-9._/-]*$ && $GIT_REF != *..* ]] || die 'Referencia Git inválida.'
ask DOMAIN 'Dominio público que apunta a este Ubuntu (sin https:// ni rutas)' ''
DOMAIN=${DOMAIN,,}
[[ ${#DOMAIN} -le 240 && $DOMAIN == *.* && $DOMAIN =~ ^[a-z0-9.-]+$ && ! $DOMAIN =~ ^[0-9.]+$ ]] ||
    die 'Introduce un dominio válido, por ejemplo api.tuempresa.com.'
IFS='.' read -r -a DOMAIN_LABELS <<< "$DOMAIN"
[[ $DOMAIN != *. ]] || die 'El dominio no debe terminar en punto.'
for label in "${DOMAIN_LABELS[@]}"; do
    [[ ${#label} -le 63 && $label =~ ^[a-z0-9]([a-z0-9-]*[a-z0-9])?$ ]] || die 'Dominio inválido.'
done
ask ACME_EMAIL 'Correo para la cuenta de Let’s Encrypt' ''
[[ $ACME_EMAIL =~ ^[a-zA-Z0-9._%+-]+@[a-zA-Z0-9.-]+\.[a-zA-Z]{2,}$ ]] || die 'Correo inválido.'
printf 'Los registros A/AAAA deben apuntar a este servidor; los puertos 80 y 443 deben ser accesibles desde Internet.\n'
printf 'Se solicitará un certificado para https://%s y se aceptarán los términos de Let’s Encrypt: https://letsencrypt.org/repository/\n' "$DOMAIN"
ask DB_NAME 'Nombre de la base de datos nueva' idc
ask DB_USER 'Usuario PostgreSQL nuevo para la aplicación' idc_app
for identifier in "$DB_NAME" "$DB_USER"; do
    [[ $identifier =~ ^[a-z][a-z0-9_]{0,62}$ && $identifier != pg_* && $identifier != postgres ]] ||
        die 'Usa nombres de hasta 63 caracteres: letras minúsculas, números y guion bajo; no nombres reservados.'
done

while true; do
    read -r -s -p 'Contraseña nueva de PostgreSQL (mínimo 16 caracteres ASCII imprimibles): ' DB_PASSWORD </dev/tty
    printf '\n'
    if [[ ${#DB_PASSWORD} -lt 16 || $DB_PASSWORD == *[!\ -\~]* ]]; then
        printf 'Usa al menos 16 caracteres ASCII imprimibles.\n'
        continue
    fi
    read -r -s -p 'Repite la contraseña: ' DB_PASSWORD_AGAIN </dev/tty
    printf '\n'
    [[ $DB_PASSWORD == "$DB_PASSWORD_AGAIN" ]] && break
    printf 'Las contraseñas no coinciden.\n'
done
unset DB_PASSWORD_AGAIN
printf '\nSe instalará %s en %s, con base %s, usuario %s y HTTP %s:%s.\n' \
    "$GIT_REF" "$APP_DIR" "$DB_NAME" "$DB_USER" "$APP_HOST" "$APP_PORT"
ask CONFIRM '¿Continuar? Escribe si' no
[[ $CONFIRM == si ]] || die 'Cancelado sin instalar paquetes.'

# 1. Ubuntu packages. Ktor is downloaded by Gradle as an application dependency.
ensure_packages ca-certificates curl git openjdk-21-jdk-headless \
    postgresql postgresql-client python3 dnsutils nginx openssl
for executable in curl git python3 dig nginx openssl psql pg_isready runuser useradd systemd-analyze; do
    command -v "$executable" >/dev/null 2>&1 || die "Falta el ejecutable $executable después de comprobar los paquetes."
done

# Check public DNS before installing Certbot or requesting a certificate.
DOMAIN_IPS=()
for record in A AAAA; do
    DNS_ANSWER=$(dig @1.1.1.1 +time=5 +tries=2 +short "$DOMAIN" "$record") || die 'No se pudo consultar DNS público.'
    while IFS= read -r address; do
        # Ignore CNAME names; dig also returns their resolved addresses.
        if [[ $address =~ ^[0-9]+\.[0-9]+\.[0-9]+\.[0-9]+$ || $address == *:* ]]; then
            DOMAIN_IPS+=("$address")
        fi
    done <<< "$DNS_ANSWER"
done
(( ${#DOMAIN_IPS[@]} > 0 )) || die 'El dominio no tiene registros públicos A/AAAA resolubles.'
NGINX_CONFIG=$(nginx -T 2>&1) || die 'La configuración Nginx existente no es válida.'
if [[ $NGINX_CONFIG == *"$DOMAIN"* ]]; then
    die 'El dominio ya aparece en Nginx. Revisa su configuración antes de instalar; no se sobrescribirá.'
fi
install -d -m 755 "$ACME_ROOT" "$ACME_ROOT/.well-known" "$ACME_ROOT/.well-known/acme-challenge"
cat > "$NGINX_SITE" <<NGINX
server {
    listen 80;
    listen [::]:80;
    server_name $DOMAIN;
    location /.well-known/acme-challenge/ { root $ACME_ROOT; }
    location / { return 503; }
}
NGINX
chmod 644 "$NGINX_SITE"
ln -s "$NGINX_SITE" /etc/nginx/sites-enabled/ideascore
nginx -t
systemctl enable --now nginx
systemctl reload nginx

# Check every published address, including IPv6, using an unpredictable token.
# This also rejects stale DNS entries pointing at a different web server.
DNS_TOKEN=$(openssl rand -hex 24)
printf '%s' "$DNS_TOKEN" > "$ACME_ROOT/.well-known/acme-challenge/$DNS_TOKEN"
chmod 644 "$ACME_ROOT/.well-known/acme-challenge/$DNS_TOKEN"
for address in "${DOMAIN_IPS[@]}"; do
    resolve_address=$address
    [[ $address != *:* ]] || resolve_address="[$address]"
    reply=$(curl --noproxy '*' --fail --silent --show-error --max-time 15 \
        --resolve "$DOMAIN:80:$resolve_address" "http://$DOMAIN/.well-known/acme-challenge/$DNS_TOKEN") ||
        die "El dominio no llega a este servidor por $address:80. Revisa DNS, firewall y NAT."
    [[ $reply == "$DNS_TOKEN" ]] || die "El registro $address responde desde otro sitio. Corrige el DNS."
done
rm -- "$ACME_ROOT/.well-known/acme-challenge/$DNS_TOKEN"

# Let's Encrypt is a certificate authority; Certbot is its local ACME client.
if ! command -v certbot >/dev/null 2>&1; then
    ensure_packages certbot
else
    printf 'Disponible: Certbot (se reutilizará la instalación existente).\n'
fi
CERTBOT_BIN=$(command -v certbot)
"$CERTBOT_BIN" --version
CERT_NAME="ideascore-$DOMAIN"
"$CERTBOT_BIN" certonly --webroot --webroot-path "$ACME_ROOT" \
    --server https://acme-v02.api.letsencrypt.org/directory \
    --cert-name "$CERT_NAME" --domain "$DOMAIN" --email "$ACME_EMAIL" \
    --agree-tos --non-interactive --keep-until-expiring \
    --deploy-hook 'systemctl reload nginx'

systemctl enable --now postgresql
pg_isready -h 127.0.0.1 -p 5432 || die 'PostgreSQL no responde en 127.0.0.1:5432.'
python3 - "$APP_HOST" "$APP_PORT" <<'PY'
import socket, sys
with socket.socket() as connection:
    try:
        connection.bind((sys.argv[1], int(sys.argv[2])))
    except OSError:
        sys.exit("El puerto HTTP 8080 no está disponible. Revisa qué servicio lo utiliza antes de instalar IdeasCore.")
PY

pg_admin() { runuser -u postgres -- psql -X --dbname=postgres --set=ON_ERROR_STOP=1 "$@"; }
[[ $(pg_admin -Atc "SELECT count(*) FROM pg_roles WHERE rolname = '$DB_USER'") == 0 ]] ||
    die 'El usuario PostgreSQL ya existe. No se cambiará su contraseña.'
[[ $(pg_admin -Atc "SELECT count(*) FROM pg_database WHERE datname = '$DB_NAME'") == 0 ]] ||
    die 'La base de datos ya existe. No se modificará.'

# 2. Build without root privileges or Android SDK. The wrapper pins Gradle.
useradd --system --user-group --create-home --home-dir "$APP_DIR" --shell /usr/sbin/nologin ideascore
chmod 755 "$APP_DIR"
JAVA_HOME="/usr/lib/jvm/java-21-openjdk-$(dpkg --print-architecture)"
[[ -x $JAVA_HOME/bin/java ]] || die 'No se encontró el JDK 21 instalado.'
runuser -u ideascore -- git clone --depth 1 --branch "$GIT_REF" -- "$REPO_URL" "$APP_DIR/source"
cd "$APP_DIR/source"
runuser -u ideascore -- env JAVA_HOME="$JAVA_HOME" bash ./gradlew \
    -PserverOnly=true --no-daemon --console=plain :server:tasks --all
runuser -u ideascore -- env JAVA_HOME="$JAVA_HOME" bash ./gradlew \
    -PserverOnly=true --no-daemon --console=plain :server:test :server:installDist
[[ -x server/build/install/server/bin/server ]] || die 'No se encontró la distribución del servidor.'
install -d -m 755 "$APP_DIR/app"
cp -R server/build/install/server/. "$APP_DIR/app/"
chown -R root:root "$APP_DIR/app"
chmod -R a+rX "$APP_DIR/app"
chmod -R go-w "$APP_DIR/app"

# 3. SCRAM verifier: the plaintext password never becomes an SQL statement
# or a command argument. Printable ASCII avoids SASLprep ambiguities.
SCRAM_VERIFIER=$(printf '%s' "$DB_PASSWORD" | python3 -c '
import base64, hashlib, hmac, os, sys
b64 = lambda data: base64.b64encode(data).decode("ascii")
salt = os.urandom(16)
key = hashlib.pbkdf2_hmac("sha256", sys.stdin.buffer.read(), salt, 4096)
stored = hashlib.sha256(hmac.digest(key, b"Client Key", "sha256")).digest()
server = hmac.digest(key, b"Server Key", "sha256")
print("SCRAM-SHA-256$4096:" + b64(salt) + "$" + b64(stored) + ":" + b64(server))
')
pg_admin --set=db_user="$DB_USER" --set=db_name="$DB_NAME" --set=verifier="$SCRAM_VERIFIER" <<'SQL'
CREATE ROLE :"db_user" LOGIN PASSWORD :'verifier';
CREATE DATABASE :"db_name" OWNER :"db_user";
SQL
unset SCRAM_VERIFIER
PGPASSWORD="$DB_PASSWORD" psql -X -h 127.0.0.1 -p 5432 -U "$DB_USER" -d "$DB_NAME" \
    --set=ON_ERROR_STOP=1 -c 'SELECT 1 AS conexion_correcta;'

# 4. systemd reads this root-only file; it must never be committed to Git.
install -d -m 700 "$CONFIG_DIR"
{
    printf 'JAVA_HOME=%s\nHOST=%s\nPORT=%s\n' "$JAVA_HOME" "$APP_HOST" "$APP_PORT"
    printf 'DB_URL=jdbc:postgresql://127.0.0.1:5432/%s\nDB_USER=%s\n' "$DB_NAME" "$DB_USER"
    printf '%s' "$DB_PASSWORD" | python3 -c \
        'import json, sys; print("DB_PASSWORD=" + json.dumps(sys.stdin.read()))'
    printf 'DB_POOL_SIZE=10\n'
} > "$CONFIG_DIR/server.env"
chmod 600 "$CONFIG_DIR/server.env"
unset DB_PASSWORD

cat > "$SERVICE_FILE" <<'UNIT'
[Unit]
Description=IdeasCore Ktor server
Wants=network-online.target postgresql.service
After=network-online.target postgresql.service

[Service]
Type=simple
User=ideascore
Group=ideascore
WorkingDirectory=/opt/ideascore/app
EnvironmentFile=/etc/ideascore/server.env
ExecStart=/opt/ideascore/app/bin/server
Restart=on-failure
RestartSec=5
TimeoutStopSec=30
NoNewPrivileges=true
PrivateTmp=true
ProtectHome=true
ProtectSystem=strict
UMask=0077

[Install]
WantedBy=multi-user.target
UNIT
chmod 644 "$SERVICE_FILE"
systemd-analyze verify "$SERVICE_FILE"
systemctl daemon-reload
systemctl enable --now ideascore.service

# The HTTP route is installed only after the backend's SELECT 1 succeeds.
BACKEND_READY=false
for attempt in {1..30}; do
    if systemctl is-active --quiet ideascore.service && \
        curl --noproxy '*' --fail --silent --max-time 2 "http://127.0.0.1:$APP_PORT/" >/dev/null; then
        BACKEND_READY=true
        break
    fi
    sleep 2
done
[[ $BACKEND_READY == true ]] || die 'El servidor no respondió. Revisa: sudo journalctl -u ideascore -n 100 --no-pager'

# 5. Public HTTPS terminates at Nginx; Ktor is reachable only on loopback.
cat > "$NGINX_SITE" <<NGINX
server {
    listen 80;
    listen [::]:80;
    server_name $DOMAIN;
    location /.well-known/acme-challenge/ { root $ACME_ROOT; }
    location / { return 301 https://$DOMAIN\$request_uri; }
}
server {
    listen 443 ssl;
    listen [::]:443 ssl;
    server_name $DOMAIN;
    ssl_certificate /etc/letsencrypt/live/$CERT_NAME/fullchain.pem;
    ssl_certificate_key /etc/letsencrypt/live/$CERT_NAME/privkey.pem;
    ssl_protocols TLSv1.2 TLSv1.3;
    location / {
        proxy_pass http://127.0.0.1:$APP_PORT;
        proxy_http_version 1.1;
        proxy_set_header Host \$host;
        proxy_set_header X-Real-IP \$remote_addr;
        proxy_set_header X-Forwarded-For \$remote_addr;
        proxy_set_header X-Forwarded-Proto \$scheme;
    }
}
NGINX
nginx -t
systemctl reload nginx

# A dedicated timer also supports an existing Certbot installation without
# replacing its installation method or modifying renewal of other domains.
cat > /etc/systemd/system/ideascore-certbot.service <<UNIT
[Unit]
Description=Renew the IdeasCore TLS certificate
After=network-online.target nginx.service
Wants=network-online.target
[Service]
Type=oneshot
ExecStart=$CERTBOT_BIN renew --cert-name $CERT_NAME --quiet
UNIT
cat > /etc/systemd/system/ideascore-certbot.timer <<'UNIT'
[Unit]
Description=Check IdeasCore TLS certificate twice daily
[Timer]
OnCalendar=*-*-* 00,12:00:00
RandomizedDelaySec=3600
Persistent=true
[Install]
WantedBy=timers.target
UNIT
chmod 644 /etc/systemd/system/ideascore-certbot.{service,timer}
systemctl daemon-reload
systemctl enable --now ideascore-certbot.timer
"$CERTBOT_BIN" renew --cert-name "$CERT_NAME" --dry-run
curl --noproxy '*' --fail --silent --show-error --max-time 15 \
    --resolve "$DOMAIN:443:127.0.0.1" "https://$DOMAIN/" >/dev/null
printf '\nIdeasCore instalado: https://%s/\n' "$DOMAIN"
printf 'PostgreSQL, backend, certificado HTTPS y renovación de prueba verificados.\n'
printf 'Configuración: /etc/ideascore/server.env\nLogs: sudo journalctl -u ideascore -f\n'
printf 'Ktor escucha solo en 127.0.0.1:8080. El login aún no autentica usuarios.\n'
