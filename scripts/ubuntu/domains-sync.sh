#!/usr/bin/env bash
# Publishes module public domains (central table public_domains) in Nginx and
# obtains Let's Encrypt certificates for them. Installed by install.sh/update.sh
# as /usr/local/sbin/ideascore-domains-sync and run as root by
# ideascore-domains.timer. With --renew it only renews certificates.
#
# The backend never runs as root: it only writes rows with status 'pending';
# this helper validates them, configures Nginx and reports 'active' or 'error'.
set -Eeuo pipefail
umask 022

readonly CONFIG_FILE=/etc/ideascore/server.env
readonly APP_PORT=8080
readonly ACME_ROOT=/var/www/ideascore-acme
readonly SITE_PREFIX=ideascore-domain-
readonly SITES_AVAILABLE=/etc/nginx/sites-available
readonly SITES_ENABLED=/etc/nginx/sites-enabled
readonly LOG_FILE=/var/log/ideascore-domains.log
readonly DOMAIN_PATTERN='^([a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?\.)+[a-z][a-z0-9-]{0,61}[a-z0-9]$'

log() { printf '%s %s\n' "$(date -u +%Y-%m-%dT%H:%M:%SZ)" "$*" >> "$LOG_FILE"; }
die() { log "ERROR: $*"; printf 'ERROR: %s\n' "$*" >&2; exit 1; }

[[ $EUID -eq 0 ]] || die 'Debe ejecutarse como root.'

# Installs this script and its systemd units; called from install.sh and update.sh.
if [[ ${1:-} == --install ]]; then
    install -m 755 "${BASH_SOURCE[0]}" /usr/local/sbin/ideascore-domains-sync
    cat > /etc/systemd/system/ideascore-domains.service <<'UNIT'
[Unit]
Description=Publish IdeasCore module domains in Nginx
After=network-online.target postgresql.service nginx.service
Wants=network-online.target
[Service]
Type=oneshot
ExecStart=/usr/local/sbin/ideascore-domains-sync
UNIT
    cat > /etc/systemd/system/ideascore-domains.timer <<'UNIT'
[Unit]
Description=Check IdeasCore module domains every minute
[Timer]
OnBootSec=1min
OnUnitActiveSec=1min
[Install]
WantedBy=timers.target
UNIT
    cat > /etc/systemd/system/ideascore-domains-renew.service <<'UNIT'
[Unit]
Description=Renew IdeasCore module domain certificates
After=network-online.target nginx.service
Wants=network-online.target
[Service]
Type=oneshot
ExecStart=/usr/local/sbin/ideascore-domains-sync --renew
UNIT
    cat > /etc/systemd/system/ideascore-domains-renew.timer <<'UNIT'
[Unit]
Description=Renew IdeasCore module domain certificates twice daily
[Timer]
OnCalendar=*-*-* 03,15:30:00
RandomizedDelaySec=3600
Persistent=true
[Install]
WantedBy=timers.target
UNIT
    chmod 644 /etc/systemd/system/ideascore-domains{,-renew}.{service,timer}
    systemctl daemon-reload
    systemctl enable --now ideascore-domains.timer ideascore-domains-renew.timer
    printf 'Publicador de dominios de modulos instalado.\n'
    exit 0
fi

[[ -r $CONFIG_FILE ]] || die "No se encontro $CONFIG_FILE."

exec 9>/run/ideascore-domains.lock
flock -n 9 || exit 0

config_value() {
    awk -F= -v key="$1" '$1 == key { sub(/^[^=]*=/, ""); print; exit }' "$CONFIG_FILE"
}

DB_URL=$(config_value DB_URL)
DB_NAME=${DB_URL##*/}
[[ $DB_NAME =~ ^[a-z][a-z0-9_]{0,62}$ ]] || die 'No se pudo inferir la base central desde DB_URL.'
PUBLIC_SCHEME=$(config_value PUBLIC_SCHEME)
PUBLIC_SCHEME=${PUBLIC_SCHEME:-https}
ACME_EMAIL=$(config_value ACME_EMAIL)

if [[ ${1:-} == --renew ]]; then
    if [[ $PUBLIC_SCHEME == https ]] && command -v certbot >/dev/null 2>&1; then
        certbot renew --quiet --deploy-hook 'systemctl reload nginx' >> "$LOG_FILE" 2>&1 || log 'certbot renew fallo; revisa /var/log/letsencrypt/letsencrypt.log'
    fi
    exit 0
fi

central_psql() {
    runuser -u postgres -- psql -X -q -d "$DB_NAME" -v ON_ERROR_STOP=1 "$@"
}

# Skip quietly until the control migration that creates public_domains is applied.
[[ $(central_psql -Atc "SELECT to_regclass('public.public_domains') IS NOT NULL") == t ]] || exit 0

set_status() {
    local domain=$1 status=$2 message=${3:-}
    central_psql -v domain="$domain" -v status="$status" -v message="$message" <<'SQL'
UPDATE public_domains
SET status = :'status',
    last_error = NULLIF(:'message', ''),
    updated_at = CURRENT_TIMESTAMP
WHERE domain = :'domain';
SQL
}

site_file() { printf '%s/%s%s' "$SITES_AVAILABLE" "$SITE_PREFIX" "$1"; }

enable_site() {
    local domain=$1
    ln -sfn "$(site_file "$domain")" "$SITES_ENABLED/$SITE_PREFIX$domain"
}

remove_site() {
    local domain=$1
    rm -f "$SITES_ENABLED/$SITE_PREFIX$domain" "$(site_file "$domain")"
}

reload_nginx() {
    nginx -t >> "$LOG_FILE" 2>&1 && systemctl reload nginx
}

proxy_locations() {
    cat <<NGINX
    location = / { return 302 /guest/s/default/; }
    location /guest/ {
        proxy_pass http://127.0.0.1:$APP_PORT;
        proxy_http_version 1.1;
        proxy_set_header Host \$host;
        proxy_set_header X-Real-IP \$remote_addr;
        proxy_set_header X-Forwarded-For \$remote_addr;
        proxy_set_header X-Forwarded-Proto \$scheme;
    }
    location / { return 404; }
NGINX
}

write_http_site() {
    local domain=$1
    {
        printf 'server {\n    listen 80;\n    listen [::]:80;\n    server_name %s;\n' "$domain"
        printf '    location /.well-known/acme-challenge/ { root %s; }\n' "$ACME_ROOT"
        proxy_locations
        printf '}\n'
    } > "$(site_file "$domain")"
}

write_acme_site() {
    local domain=$1
    cat > "$(site_file "$domain")" <<NGINX
server {
    listen 80;
    listen [::]:80;
    server_name $domain;
    location /.well-known/acme-challenge/ { root $ACME_ROOT; }
    location / { return 503; }
}
NGINX
}

write_https_site() {
    local domain=$1 cert_name=$2
    {
        cat <<NGINX
server {
    listen 80;
    listen [::]:80;
    server_name $domain;
    location /.well-known/acme-challenge/ { root $ACME_ROOT; }
    location / { return 301 https://$domain\$request_uri; }
}
server {
    listen 443 ssl;
    listen [::]:443 ssl;
    server_name $domain;
    ssl_certificate /etc/letsencrypt/live/$cert_name/fullchain.pem;
    ssl_certificate_key /etc/letsencrypt/live/$cert_name/privkey.pem;
    ssl_protocols TLSv1.2 TLSv1.3;
NGINX
        proxy_locations
        printf '}\n'
    } > "$(site_file "$domain")"
}

publish_domain() {
    local domain=$1 cert_name="ideascore-domain-$1" output nginx_config
    log "Publicando $domain"
    if [[ ${#domain} -gt 253 || ! $domain =~ $DOMAIN_PATTERN ]]; then
        set_status "$domain" error 'Dominio invalido.'
        return
    fi
    # Never take over a domain that another Nginx site (including the main app) already serves.
    nginx_config=$(nginx -T 2>/dev/null | awk \
        -v available="$(site_file "$domain"):" -v enabled="$SITES_ENABLED/$SITE_PREFIX$domain:" '
        /^# configuration file / { skip = ($4 == available || $4 == enabled) }
        !skip { print }')
    if awk -v wanted="$domain" '
        $1 == "server_name" { for (i = 2; i <= NF; i++) { name = $i; sub(/;$/, "", name); if (name == wanted) found = 1 } }
        END { exit !found }' <<< "$nginx_config"; then
        set_status "$domain" error 'El dominio ya esta configurado en otro sitio de Nginx de este servidor.'
        return
    fi

    if [[ $PUBLIC_SCHEME != https ]]; then
        write_http_site "$domain"
        enable_site "$domain"
        if reload_nginx; then
            set_status "$domain" active
        else
            remove_site "$domain"
            reload_nginx || true
            set_status "$domain" error 'Nginx rechazo la configuracion generada.'
        fi
        return
    fi

    if ! command -v certbot >/dev/null 2>&1; then
        set_status "$domain" error 'Certbot no esta instalado en el servidor.'
        return
    fi
    install -d -m 755 "$ACME_ROOT/.well-known/acme-challenge"
    write_acme_site "$domain"
    enable_site "$domain"
    if ! reload_nginx; then
        remove_site "$domain"
        reload_nginx || true
        set_status "$domain" error 'Nginx rechazo la configuracion generada.'
        return
    fi

    local account_args=(--register-unsafely-without-email)
    [[ -n $ACME_EMAIL ]] && account_args=(--email "$ACME_EMAIL")
    if ! output=$(certbot certonly --webroot --webroot-path "$ACME_ROOT" \
        --cert-name "$cert_name" --domain "$domain" "${account_args[@]}" \
        --agree-tos --non-interactive --keep-until-expiring 2>&1); then
        log "$output"
        remove_site "$domain"
        reload_nginx || true
        set_status "$domain" error "No se pudo obtener el certificado HTTPS. Verifica que el DNS de $domain apunte a este servidor y que el puerto 80 sea accesible."
        return
    fi
    log "$output"

    write_https_site "$domain" "$cert_name"
    if reload_nginx; then
        set_status "$domain" active
        log "$domain activo"
    else
        remove_site "$domain"
        reload_nginx || true
        set_status "$domain" error 'Nginx rechazo la configuracion HTTPS generada.'
    fi
}

# 1. Remove sites whose domain is no longer registered (domain changed or company deleted).
mapfile -t registered < <(central_psql -Atc "SELECT domain FROM public_domains")
changed=false
for file in "$SITES_AVAILABLE/$SITE_PREFIX"*; do
    [[ -e $file ]] || continue
    domain=${file##*/$SITE_PREFIX}
    if ! printf '%s\n' "${registered[@]}" | grep -Fxq -- "$domain"; then
        log "Retirando $domain"
        remove_site "$domain"
        if [[ $PUBLIC_SCHEME == https ]] && command -v certbot >/dev/null 2>&1; then
            certbot delete --cert-name "ideascore-domain-$domain" --non-interactive >> "$LOG_FILE" 2>&1 || true
        fi
        changed=true
    fi
done
if [[ $changed == true ]]; then
    reload_nginx || log 'nginx -t fallo despues de retirar dominios'
fi

# 2. Publish pending domains.
while IFS= read -r domain; do
    [[ -n $domain ]] || continue
    publish_domain "$domain" || set_status "$domain" error 'Fallo inesperado al configurar el dominio; revisa /var/log/ideascore-domains.log.'
done < <(central_psql -Atc "SELECT domain FROM public_domains WHERE status = 'pending' ORDER BY updated_at")
