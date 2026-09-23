#!/usr/bin/env bash
# First installation on Ubuntu 24.04/26.04 with systemd.
# Download this file, review it, then run: sudo bash install.sh
# Keep this guard POSIX-compatible: `sh install.sh` ignores the shebang.
if [ -z "${BASH_VERSION:-}" ]; then
    if command -v bash >/dev/null 2>&1 && [ -f "$0" ]; then
        exec bash -- "$0" "$@"
    fi
    printf '%s\n' 'Este instalador requiere Bash. Descarga el archivo y ejecuta: sudo bash install.sh' >&2
    exit 1
fi

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
readonly INSTALL_SWAP_FILE=$APP_DIR/install.swap
readonly INSTALL_SWAP_SIZE_MB=2048
readonly GRADLE_JVM_ARGS="-Xmx1536M -Dfile.encoding=UTF-8"
readonly NODE_OPTIONS_VALUE="--max-old-space-size=2048"
INSTALL_SWAP_CREATED=false

cleanup_install_swap() {
    if [[ $INSTALL_SWAP_CREATED == true ]]; then
        swapoff "$INSTALL_SWAP_FILE" >/dev/null 2>&1 || true
        rm -f "$INSTALL_SWAP_FILE"
    fi
}

trap 'cleanup_install_swap; printf "InstalaciÃ³n interrumpida (lÃ­nea %s). Se conservan los archivos y datos para diagnÃ³stico.\n" "$LINENO" >&2' ERR
trap 'cleanup_install_swap' EXIT

ensure_install_swap() {
    local available_kb total_swap_kb
    available_kb=$(awk '/MemAvailable:/ { print $2 }' /proc/meminfo)
    total_swap_kb=$(awk '/SwapTotal:/ { print $2 }' /proc/meminfo)

    if (( available_kb >= 2097152 || total_swap_kb >= 1048576 )); then
        return
    fi

    [[ ! -e $INSTALL_SWAP_FILE ]] || die "Ya existe $INSTALL_SWAP_FILE. Revisalo antes de instalar."
    printf 'Memoria limitada detectada; se creara swap temporal de %s MiB para compilar.\n' "$INSTALL_SWAP_SIZE_MB"
    if command -v fallocate >/dev/null 2>&1; then
        fallocate -l "${INSTALL_SWAP_SIZE_MB}M" "$INSTALL_SWAP_FILE" ||
            dd if=/dev/zero of="$INSTALL_SWAP_FILE" bs=1M count="$INSTALL_SWAP_SIZE_MB" status=none
    else
        dd if=/dev/zero of="$INSTALL_SWAP_FILE" bs=1M count="$INSTALL_SWAP_SIZE_MB" status=none
    fi
    chmod 600 "$INSTALL_SWAP_FILE"
    mkswap "$INSTALL_SWAP_FILE" >/dev/null
    swapon "$INSTALL_SWAP_FILE"
    INSTALL_SWAP_CREATED=true
}

run_gradle() {
    runuser -u ideascore -- env \
        JAVA_HOME="$JAVA_HOME" \
        NODE_OPTIONS="$NODE_OPTIONS_VALUE" \
        bash ./gradlew \
        --no-daemon \
        --no-configuration-cache \
        --max-workers=1 \
        --console=plain \
        "-Dorg.gradle.jvmargs=$GRADLE_JVM_ARGS" \
        "-Pkotlin.compiler.execution.strategy=in-process" \
        "$@"
}
for unit in /etc/systemd/system/ideascore-certbot.service /etc/systemd/system/ideascore-certbot.timer; do
    [[ ! -e $unit && ! -L $unit ]] || die 'Ya existe una configuración de renovación de IdeasCore.'
done

# Never overwrite an existing installation or adopt an unrelated Linux account.
[[ ! -e $APP_DIR && ! -e $CONFIG_DIR && ! -e $SERVICE_FILE ]] ||
    die 'Ya hay archivos de IdeasCore. Este script es para una primera instalación, no para actualizarla.'
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

# Resume only the exact temporary site for this domain, before application
# provisioning. Never adopt a live HTTPS site or custom Nginx configuration.
acme_site_config() {
    cat <<NGINX
server {
    listen 80;
    listen [::]:80;
    server_name $DOMAIN;
    location /.well-known/acme-challenge/ { root $ACME_ROOT; }
    location / { return 503; }
}
NGINX
}
RESUME_ACME=false
if [[ -e $NGINX_SITE || -L $NGINX_SITE ]]; then
    [[ -f $NGINX_SITE && ! -L $NGINX_SITE ]] && cmp -s "$NGINX_SITE" <(acme_site_config) ||
        die 'La configuración Nginx existente no es el sitio temporal de este dominio; no se modificará.'
    RESUME_ACME=true
    printf 'Se reanudará la preparación HTTPS del dominio %s.\n' "$DOMAIN"
fi
if [[ -e /etc/nginx/sites-enabled/ideascore || -L /etc/nginx/sites-enabled/ideascore ]]; then
    [[ $RESUME_ACME == true && -L /etc/nginx/sites-enabled/ideascore &&
       $(readlink /etc/nginx/sites-enabled/ideascore) == "$NGINX_SITE" ]] ||
        die 'El sitio habilitado de IdeasCore no corresponde al sitio temporal esperado.'
fi
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
ask COMPANY_NAME 'Nombre de la primera empresa' ''
ask COMPANY_CODE 'Codigo de empresa para iniciar sesion' empresa
ask TENANT_USER 'Cuenta interna nueva para la base de empresa' idc_tenant_app
for identifier in "$COMPANY_CODE" "$TENANT_USER"; do
    [[ $identifier =~ ^[a-z][a-z0-9_]{0,62}$ && $identifier != pg_* && $identifier != postgres ]] || die 'Identificador invalido.'
done
[[ -n ${COMPANY_NAME// /} ]] || die 'El nombre de empresa es obligatorio.'
ask CONTROL_DB 'Base central de administracion nueva' idc_control
ask SERVER_OWNER 'Usuario de login del propietario del servidor' server_owner
for identifier in "$CONTROL_DB" "$SERVER_OWNER"; do
    [[ $identifier =~ ^[a-z][a-z0-9_]{0,62}$ && $identifier != pg_* && $identifier != postgres ]] ||
        die 'Identificador invalido: usa letras minusculas, numeros y guion bajo.'
done
[[ $CONTROL_DB != "$DB_NAME" && $SERVER_OWNER != "$DB_USER" ]] || die 'Las bases y las cuentas internas deben ser distintas.'
[[ $TENANT_USER != "$DB_USER" && $TENANT_USER != "$SERVER_OWNER" ]] || die 'La cuenta interna de empresa debe ser distinta.'
ask_password() {
    local secret repeated
    while true; do
        read -r -s -p "Contrasena para $2 (minimo 16 caracteres ASCII): " secret </dev/tty
        printf '\n'
        [[ ${#secret} -ge 16 && $secret != *[!\ -\~]* ]] || continue
        read -r -s -p 'Repite la contrasena: ' repeated </dev/tty
        printf '\n'
        [[ $secret == "$repeated" ]] && break
        printf 'Las contrasenas no coinciden.\n'
    done
    printf -v "$1" '%s' "$secret"
}
ask_password SERVER_PASSWORD "$SERVER_OWNER"
printf 'Este usuario tambien sera propietario de la primera empresa.\n'
printf '\nSe instalará %s en %s, con base %s, usuario %s y HTTP %s:%s.\n' \
    "$GIT_REF" "$APP_DIR" "$DB_NAME" "$DB_USER" "$APP_HOST" "$APP_PORT"
ask CONFIRM '¿Continuar? Escribe si' no
[[ $CONFIRM == si ]] || die 'Cancelado sin instalar paquetes.'

# 1. Ubuntu packages. Ktor is downloaded by Gradle as an application dependency.
ensure_packages ca-certificates curl git openjdk-21-jdk-headless \
    postgresql postgresql-client python3 bind9-dnsutils nginx openssl
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
if [[ $RESUME_ACME == true ]]; then
    # Remove only one exact copy; any other occurrence still blocks a conflict.
    NGINX_CONFIG=${NGINX_CONFIG/"$(acme_site_config)"/}
fi
if [[ $NGINX_CONFIG == *"$DOMAIN"* ]]; then
    die 'El dominio ya aparece en Nginx. Revisa su configuración antes de instalar; no se sobrescribirá.'
fi
install -d -m 755 "$ACME_ROOT" "$ACME_ROOT/.well-known" "$ACME_ROOT/.well-known/acme-challenge"
acme_site_config > "$NGINX_SITE"
chmod 644 "$NGINX_SITE"
if [[ ! -L /etc/nginx/sites-enabled/ideascore ]]; then
    ln -s "$NGINX_SITE" /etc/nginx/sites-enabled/ideascore
fi
nginx -t
systemctl enable --now nginx
systemctl reload nginx

# Check every published address, including IPv6, using an unpredictable token.
# This also rejects stale DNS entries pointing at a different web server.
DNS_TOKEN=$(openssl rand -hex 24)
printf '%s' "$DNS_TOKEN" > "$ACME_ROOT/.well-known/acme-challenge/$DNS_TOKEN"
chmod 644 "$ACME_ROOT/.well-known/acme-challenge/$DNS_TOKEN"
for address in "${DOMAIN_IPS[@]}"; do
    printf 'Verificando dominio %s en %s, puerto 80...\n' "$DOMAIN" "$address"
    resolve_address=$address
    [[ $address != *:* ]] || resolve_address="[$address]"
    reply=$(curl --noproxy '*' --fail --silent --show-error --max-time 15 \
        --resolve "$DOMAIN:80:$resolve_address" "http://$DOMAIN/.well-known/acme-challenge/$DNS_TOKEN") ||
        die "No se pudo leer el desafío HTTP por $address:80. Revisa DNS, firewall/NAT y /var/log/nginx/error.log. Corrige la causa y vuelve a ejecutar con el mismo dominio."
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
for role in "$SERVER_OWNER" "$TENANT_USER"; do
    [[ $(pg_admin -Atc "SELECT count(*) FROM pg_roles WHERE rolname = '$role'") == 0 ]] || die "El rol $role ya existe."
done
[[ $(pg_admin -Atc "SELECT count(*) FROM pg_database WHERE datname = '$CONTROL_DB'") == 0 ]] || die 'La base central ya existe.'

# 2. Build without root privileges or Android SDK. The wrapper pins Gradle.
useradd --system --user-group --create-home --home-dir "$APP_DIR" --shell /usr/sbin/nologin ideascore
chmod 755 "$APP_DIR"
JAVA_HOME="/usr/lib/jvm/java-21-openjdk-$(dpkg --print-architecture)"
[[ -x $JAVA_HOME/bin/java ]] || die 'No se encontró el JDK 21 instalado.'
runuser -u ideascore -- git clone --depth 1 --branch "$GIT_REF" -- "$REPO_URL" "$APP_DIR/source"
cd "$APP_DIR/source"
ensure_install_swap
[[ -f scripts/ubuntu/migrations.sh ]] || die 'La rama descargada no incluye el ejecutor de migraciones.'
for migration in database/core/migrations/V001__create_application_users.sql \
    database/core/migrations/V002__create_application_sessions.sql \
    database/control/migrations/V001__create_server_registry.sql \
    database/tenant/migrations/V001__create_business_owner.sql; do
    [[ -f $migration ]] || die "La rama descargada no incluye $migration. Publica los cambios antes de instalar."
done
runuser -u ideascore -- env JAVA_HOME="$JAVA_HOME" bash ./gradlew --stop >/dev/null 2>&1 || true
run_gradle -PserverOnly=true :server:tasks --all
run_gradle -PserverOnly=true :server:test :server:installDist
[[ -x server/build/install/server/bin/server ]] || die 'No se encontró la distribución del servidor.'
install -d -m 755 "$APP_DIR/app"
cp -R server/build/install/server/. "$APP_DIR/app/"
chown -R root:root "$APP_DIR/app"
chmod -R a+rX "$APP_DIR/app"
chmod -R go-w "$APP_DIR/app"

# Build the browser app without mobile SDKs. Gradle supplies Node and Yarn.
run_gradle -PwebOnly=true :app:webApp:jsBrowserDistribution
WEB_DIST="$APP_DIR/source/app/webApp/build/dist/js/productionExecutable"
[[ -f $WEB_DIST/index.html && -f $WEB_DIST/webApp.js ]] || die 'No se encontro la distribucion web completa.'
install -d -m 755 "$APP_DIR/web"
cp -R "$WEB_DIST/." "$APP_DIR/web/"
chown -R root:root "$APP_DIR/web"
chmod -R a+rX "$APP_DIR/web"
chmod -R go-w "$APP_DIR/web"

# 3. SCRAM verifier: the plaintext password never becomes an SQL statement
# or a command argument. Printable ASCII avoids SASLprep ambiguities.
scram_verifier() { python3 -c '
import base64, hashlib, hmac, os, sys
b64 = lambda data: base64.b64encode(data).decode("ascii")
salt = os.urandom(16)
key = hashlib.pbkdf2_hmac("sha256", sys.stdin.buffer.read(), salt, 4096)
stored = hashlib.sha256(hmac.digest(key, b"Client Key", "sha256")).digest()
server = hmac.digest(key, b"Server Key", "sha256")
print("SCRAM-SHA-256$4096:" + b64(salt) + "$" + b64(stored) + ":" + b64(server))
'; }
SCRAM_VERIFIER=$(printf '%s' "$DB_PASSWORD" | scram_verifier)
pg_admin --set=db_user="$DB_USER" --set=db_name="$DB_NAME" --set=verifier="$SCRAM_VERIFIER" <<'SQL'
CREATE ROLE :"db_user" LOGIN PASSWORD :'verifier';
CREATE DATABASE :"db_name";
REVOKE ALL ON DATABASE :"db_name" FROM PUBLIC;
SQL
unset SCRAM_VERIFIER
pg_admin --set=control_db="$CONTROL_DB" --set=db_user="$DB_USER" <<'SQL'
CREATE DATABASE :"control_db";
REVOKE ALL ON DATABASE :"control_db" FROM PUBLIC;
GRANT CONNECT ON DATABASE :"control_db" TO :"db_user";
SQL
source scripts/ubuntu/migrations.sh
for target_db in "$CONTROL_DB" "$DB_NAME"; do
    apply_migration "$target_db" core database/core/migrations/V001__create_application_users.sql
    apply_migration "$target_db" core database/core/migrations/V002__create_application_sessions.sql
done
apply_migration "$CONTROL_DB" control database/control/migrations/V001__create_server_registry.sql
apply_migration "$DB_NAME" tenant database/tenant/migrations/V001__create_business_owner.sql
TENANT_PASSWORD=$(openssl rand -hex 32)
verifier=$(printf '%s' "$TENANT_PASSWORD" | scram_verifier)
pg_admin -v tenant_user="$TENANT_USER" -v verifier="$verifier" -v tenant_db="$DB_NAME" <<'SQL'
CREATE ROLE :"tenant_user" LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE PASSWORD :'verifier';
GRANT CONNECT ON DATABASE :"tenant_db" TO :"tenant_user";
SQL
verifier=$(printf '%s' "$SERVER_PASSWORD" | scram_verifier)
pg_admin -v login_role="$SERVER_OWNER" -v verifier="$verifier" \
    -v control_db="$CONTROL_DB" -v tenant_db="$DB_NAME" <<'SQL'
CREATE ROLE :"login_role" LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE PASSWORD :'verifier';
GRANT CONNECT ON DATABASE :"control_db" TO :"login_role";
GRANT CONNECT ON DATABASE :"tenant_db" TO :"login_role";
SQL
unset verifier
runuser -u postgres -- psql -X -d "$CONTROL_DB" -v ON_ERROR_STOP=1 \
    -v db_user="$DB_USER" -v server_owner="$SERVER_OWNER" \
    -v company_name="$COMPANY_NAME" -v company_code="$COMPANY_CODE" -v tenant_db="$DB_NAME" <<'SQL'
BEGIN;
REVOKE CREATE ON SCHEMA public FROM PUBLIC;
INSERT INTO application_users VALUES (gen_random_uuid(), :'server_owner', true);
INSERT INTO server_owners SELECT id FROM application_users WHERE postgres_role = :'server_owner';
INSERT INTO companies (id, code, name, database_name, is_active)
    VALUES (gen_random_uuid(), :'company_code', :'company_name', :'tenant_db', true);
GRANT USAGE ON SCHEMA public TO :"db_user";
GRANT SELECT ON application_users, server_owners, companies TO :"db_user";
GRANT SELECT, INSERT, UPDATE, DELETE ON application_sessions TO :"db_user";
COMMIT;
SQL
runuser -u postgres -- psql -X -d "$DB_NAME" -v ON_ERROR_STOP=1 \
    -v tenant_user="$TENANT_USER" -v business_owner="$SERVER_OWNER" <<'SQL'
BEGIN;
REVOKE CREATE ON SCHEMA public FROM PUBLIC;
INSERT INTO application_users VALUES (gen_random_uuid(), :'business_owner', true);
INSERT INTO business_owner (user_id) SELECT id FROM application_users WHERE postgres_role = :'business_owner';
GRANT USAGE ON SCHEMA public TO :"tenant_user";
GRANT SELECT ON application_users, business_owner TO :"tenant_user";
GRANT SELECT, INSERT, UPDATE, DELETE ON application_sessions TO :"tenant_user";
COMMIT;
SQL
PGPASSWORD="$DB_PASSWORD" psql -X -h 127.0.0.1 -U "$DB_USER" -d "$CONTROL_DB" \
    -v ON_ERROR_STOP=1 -c 'SELECT count(*) AS usuarios_iniciales FROM application_users;'

# Reject passwordless pg_hba configurations before enabling public login.
for target_db in "$CONTROL_DB" "$DB_NAME"; do
    if PGPASSWORD="$(openssl rand -hex 32)" psql -X -h 127.0.0.1 -U "$SERVER_OWNER" -d "$target_db" -c 'SELECT 1' >/dev/null 2>&1; then
        die 'PostgreSQL acepta una contrasena incorrecta. Configura scram-sha-256 en pg_hba.conf.'
    fi
done

# 4. systemd reads this root-only file; it must never be committed to Git.
install -d -m 700 "$CONFIG_DIR"
{
    printf 'JAVA_HOME=%s\nHOST=%s\nPORT=%s\n' "$JAVA_HOME" "$APP_HOST" "$APP_PORT"
    printf 'DB_URL=jdbc:postgresql://127.0.0.1:5432/%s\nDB_USER=%s\n' "$CONTROL_DB" "$DB_USER"
    printf '%s' "$DB_PASSWORD" | python3 -c \
        'import json, sys; print("DB_PASSWORD=" + json.dumps(sys.stdin.read()))'
    printf 'DB_POOL_SIZE=10\n'
    printf '%s' "$TENANT_PASSWORD" | python3 -c '
import json, sys
entry = {"databaseName": sys.argv[1], "jdbcUrl": "jdbc:postgresql://127.0.0.1:5432/" + sys.argv[1],
         "user": sys.argv[2], "password": sys.stdin.read()}
print("TENANT_DATABASES_JSON=" + json.dumps(json.dumps([entry])))
' "$DB_NAME" "$TENANT_USER"
} > "$CONFIG_DIR/server.env"
chmod 600 "$CONFIG_DIR/server.env"
unset DB_PASSWORD
unset TENANT_PASSWORD

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

# Send credentials via stdin, never command arguments or logs. Revoke the
# temporary test sessions immediately; do not display or persist their tokens.
for login_code in '' "$COMPANY_CODE"; do
    printf '%s' "$SERVER_PASSWORD" | python3 -c '
import json, sys, urllib.request
body = json.dumps({"username": sys.argv[1], "password": sys.stdin.read(),
                   "companyCode": sys.argv[2] or None}).encode()
request = urllib.request.Request("http://127.0.0.1:8080/auth/login", data=body,
    headers={"Content-Type": "application/json"})
with urllib.request.urlopen(request, timeout=20) as response:
    result = json.load(response)
    if response.status != 200 or not result.get("accessToken"):
        sys.exit("Login inicial invalido")
    expected_role = "business_owner" if sys.argv[2] else "server_owner"
    if result.get("role") != expected_role or result.get("companyCode") != (sys.argv[2] or None):
        sys.exit("El login no devuelve el propietario o empresa esperados")
' "$SERVER_OWNER" "$login_code"
done
unset SERVER_PASSWORD
for target_db in "$CONTROL_DB" "$DB_NAME"; do
    runuser -u postgres -- psql -X -d "$target_db" -v ON_ERROR_STOP=1 \
        -c 'UPDATE application_sessions SET revoked_at = CURRENT_TIMESTAMP WHERE revoked_at IS NULL;'
done
printf 'Login del propietario verificado en servidor y empresa.\n'

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
    root $APP_DIR/web;
    index index.html;
    location /auth/ {
        proxy_pass http://127.0.0.1:$APP_PORT;
        proxy_http_version 1.1;
        proxy_set_header Host \$host;
        proxy_set_header X-Real-IP \$remote_addr;
        proxy_set_header X-Forwarded-For \$remote_addr;
        proxy_set_header X-Forwarded-Proto \$scheme;
    }
    location / {
        try_files \$uri \$uri/ /index.html;
        add_header Cache-Control "no-cache";
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
WEB_INDEX=$(curl --noproxy '*' --fail --silent --show-error --max-time 15 \
    --resolve "$DOMAIN:443:127.0.0.1" "https://$DOMAIN/")
[[ $WEB_INDEX == "$(cat "$APP_DIR/web/index.html")" ]] || die 'El dominio no esta sirviendo el index.html de la app.'
curl --noproxy '*' --fail --silent --show-error --max-time 15 \
    --resolve "$DOMAIN:443:127.0.0.1" "https://$DOMAIN/webApp.js" >/dev/null
LOGIN_STATUS=$(curl --noproxy '*' --silent --show-error --max-time 15 \
    --resolve "$DOMAIN:443:127.0.0.1" --output /dev/null --write-out '%{http_code}' \
    --header 'Content-Type: application/json' --data '{}' "https://$DOMAIN/auth/login")
[[ $LOGIN_STATUS == 400 ]] || die 'La ruta HTTPS /auth/login no devuelve la validacion esperada (400).'
RENEWAL_VERIFIED=false
if "$CERTBOT_BIN" renew --cert-name "$CERT_NAME" --dry-run; then
    RENEWAL_VERIFIED=true
else
    printf 'AVISO: la instalación funciona por HTTPS, pero no se pudo verificar la renovación.\n' >&2
    printf 'Revisa /var/log/letsencrypt/letsencrypt.log. Si indica rateLimited o Service busy, espera antes de reintentar.\n' >&2
    printf 'Reintenta solo la prueba: %s renew --cert-name %s --dry-run\n' "$CERTBOT_BIN" "$CERT_NAME" >&2
fi
printf '\nIdeasCore instalado: https://%s/\n' "$DOMAIN"
printf 'PostgreSQL, backend y certificado HTTPS verificados.\n'
if [[ $RENEWAL_VERIFIED == true ]]; then
    printf 'Renovación de prueba verificada.\n'
else
    printf 'Renovación automática configurada; prueba de renovación PENDIENTE. No vuelvas a ejecutar todo el instalador.\n'
fi
printf 'Configuración: /etc/ideascore/server.env\nLogs: sudo journalctl -u ideascore -f\n'
printf 'Login disponible: POST https://%s/auth/login\n' "$DOMAIN"
printf 'Aplicacion web disponible: https://%s/\n' "$DOMAIN"
printf 'Propietario del servidor y de la primera empresa: %s\n' "$SERVER_OWNER"
printf 'Codigo de empresa para login: %s\n' "$COMPANY_CODE"
printf 'Pendiente: panel administrativo, sus permisos y provisionamiento de modulos.\n'
