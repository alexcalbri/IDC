# Hotspot Module

Status: implemented (first version). Captive WiFi portal per company for UniFi
networks managed through the UniFi Site Manager cloud API (`api.ui.com`).

## What it does

- Serves a configurable portal at `https://<company domain>/guest/s/default/`.
- Registers each guest (name, email, phone, optional birthday) and creates the
  guest in **Clientes** with `customer_type = 'hotspot'`. An existing customer
  with the same email is linked without changing their data or type.
- Emails a one-time 6-digit code. The code expires after the configured minutes,
  allows 5 attempts and is consumed when used.
- After a valid code, authorizes the guest MAC in UniFi
  (`AUTHORIZE_GUEST_ACCESS`) for the configured time, data and speed limits.
- An email can connect again only after the **reconnection time** since its last
  successful access; after that it receives a new code.
- Administration uses **native UI pieces** (`ui.type = "native"`): Dashboard,
  Visitantes, Reportes, Portal cautivo and Configuracion, modeled on the
  Conectiva panel and responsive (sidebar on wide screens, top bar on phones).
  The app contains no hotspot-specific code.

## Data

Tenant migration `migrations/V001__create_hotspot.sql`:

| Table | Purpose |
| --- | --- |
| `hotspot_settings` | Singleton portal configuration (domain, UniFi, limits, SMTP, appearance). |
| `hotspot_registrations` | One row per form submission, linked to `customers`. |
| `hotspot_codes` | One-time codes (SHA-256 only), expiry, attempts, use. |
| `hotspot_accesses` | Every UniFi authorization attempt; successful rows drive reconnection. |

Central table `public_domains` (control migration V005) maps each portal domain
to its company. The UniFi API key and company SMTP password are stored in the
tenant database and never returned to the client.

## Endpoints

| Route | Access |
| --- | --- |
| `GET /guest/s/default/` | Public, resolved by `Host`. UniFi passes `id` (MAC), `ap`, `ssid`, `url`. |
| `POST /guest/s/default/register` | Public. Validates, applies reconnection rule, emails code. |
| `POST /guest/s/default/verify` | Public. Checks code and authorizes in UniFi. |
| `GET /modules/hotspot/ui/{screen}` | Native screens: `dashboard`, `visitors`, `reports` (`hotspot.reports.view`), `portal`, `settings` (`hotspot.settings.view`). Sections without permission are hidden. |
| `POST /modules/hotspot/ui/portal`, `/ui/settings` | `hotspot.settings.manage`; returns the screen with a notice. |
| `GET /modules/hotspot/report/export?from=&to=` | `hotspot.reports.export` (CSV) |

## Domains and HTTPS

Saving a domain stores it in `public_domains` with status `pending`. The root
helper `scripts/ubuntu/domains-sync.sh` (installed as
`/usr/local/sbin/ideascore-domains-sync`, timer every minute):

1. refuses domains that another Nginx site already serves;
2. in HTTPS installs, obtains a Let's Encrypt certificate by webroot and writes
   an Nginx site that proxies only `/guest/` to Ktor;
3. in HTTP installs, writes the HTTP site directly;
4. marks the domain `active` or `error` (shown in the configuration screen);
5. removes sites and certificates of domains that are no longer registered.

Log: `/var/log/ideascore-domains.log`. After fixing DNS, saving the
configuration again retries a domain in `error`.

## Email

`smtpMode = servidor` uses the server SMTP from `/etc/ideascore/server.env`
(`SMTP_HOST`, `SMTP_PORT`, `SMTP_USERNAME`, `SMTP_PASSWORD`, `SMTP_FROM`,
`SMTP_SECURITY` = `starttls`|`ssl`|`none`; restart `ideascore` after editing).
`smtpMode = empresa` uses the company's own SMTP from the configuration screen.

## Setup checklist

1. Update the server (`update.sh`) so it includes this module and migration V005.
2. Server owner: *Modulos del servidor* → install **Hotspot**; *Empresa* → enable
   it for the company.
3. Create a DNS A/AAAA record for the portal domain pointing to the server.
4. Company: *Hotspot → Configuracion* → domain, UniFi console id, site id and
   API key, times, email and appearance. Wait for domain status **Activo**.
5. UniFi Network → Hotspot / guest portal:
   - authentication: **External portal server**, address
     `https://<domain>/guest/s/default/`;
   - add the portal domain (and the server IP) to the **pre-authorization
     allowance** so unauthorized guests can reach the portal and the email
     provider if needed.
6. Test with a phone: connect to the guest SSID, register, enter the code.

## Not implemented yet

- Local UniFi controller API (only the cloud connector).
- Uploading the logo file (the logo is a URL; the Portal cautivo section shows a
  preview).

Clearing the domain field and saving unpublishes the portal; the helper removes
its Nginx site and certificate on the next run.
