# Hotspot Module

Status: in development (marked with `.development`; not released to `master`). Captive WiFi portal per company for UniFi
networks managed through the UniFi Site Manager cloud API (`api.ui.com`).

## What it does

- Serves a configurable portal at `https://<company domain>/guest/s/default/`.
- Shows a different screen depending on the **WiFi network** the guest joined:
  UniFi sends the SSID in the redirect (`ssid`) and *Configuracion → Redes WiFi*
  sets, per SSID, the access type:
  - **Formulario** (also every SSID not listed): the registration flow below.
  - **Codigo de usuario** (needs the *Usuarios* integration): the user types the
    code shown in *Accesos WiFi*. Accepted: active users of this company; in the
    server company also the server owners; and users of the server company that
    a server owner authorized for that network of this company. Reconnection is
    counted per user.
  - **Codigo de cliente**: the guest types the `customer_code` shown in Clientes.
    The customer must belong to the database where the portal runs (the company,
    or the server workspace when the hotspot is enabled there), must not be
    `inactive`/`archived`, and its `customer_type` must be one of the network's
    allowed types (empty = any type). Each network can override the access and
    reconnection times. Wrong codes are limited to 5 per device every 15 minutes
    and 30 per company per minute; unknown codes and disallowed types get the
    same answer.
- Registers each guest (name, email, phone, optional birthday) and creates the
  guest in **Clientes** with `customer_type = 'hotspot'`. An existing customer
  with the same email is linked without changing their data or type.
- Emails a one-time 6-digit code. The code expires after the configured minutes,
  allows 5 attempts and is consumed when used.
- After a valid code, authorizes the guest MAC in UniFi
  (`AUTHORIZE_GUEST_ACCESS`) for the configured time, data and speed limits.
- An email can connect again only after the **reconnection time** since its last
  successful access; after that it receives a new code.
- **Integraciones** (business owner or server owner only), defined by the module
  in `hotspot_integrations`:
  - *Controlador de red*: exactly one network ecosystem authorizes guests
    (`NetworkController`). UniFi is the only one today and stays enabled for
    existing companies; MikroTik or others plug in as new implementations.
  - *Usuarios*: enables the "Codigo de usuario" access mode.
- **Accesos WiFi** (`hotspot.view`): every user sees their own WiFi code; the
  business owner sees every user's code (server owners also see theirs). In the
  server company, server owners grant users access to "Codigo de usuario"
  networks of other companies. Codes live in `hotspot_user_codes`; the core
  users table is only read.
- *Redes WiFi* → **Cargar redes de la consola** lists the SSIDs of the UniFi site
  (`/wifi/broadcasts`, UniFi Network 10+) to configure them.
- Administration uses **native UI pieces** (`ui.type = "native"`): Dashboard,
  Visitantes, Reportes, Portal cautivo and Configuracion, modeled on the
  Conectiva panel and responsive (sidebar on wide screens, top bar on phones).
  The app contains no hotspot-specific code.

## Data

Tenant migrations `migrations/V001__create_hotspot.sql`, `V002__add_networks.sql` and
`V003__add_integrations_and_user_codes.sql`
(customer codes come from the Clientes migration `V004__add_customer_code.sql`):

| Table | Purpose |
| --- | --- |
| `hotspot_settings` | Singleton portal configuration (domain, UniFi, limits, SMTP, appearance). |
| `hotspot_registrations` | One row per form submission or code access (`access_mode`), linked to `customers`. |
| `hotspot_codes` | One-time codes (SHA-256 only), expiry, attempts, use. |
| `hotspot_accesses` | Every UniFi authorization attempt; successful rows drive reconnection. |
| `hotspot_networks` | SSIDs with their access type, allowed customer types and optional times (V002). |
| `hotspot_code_attempts` | Wrong customer/user codes, for the guessing limits; kept one day (V002). |
| `hotspot_integrations` | Integrations of the module and whether they are enabled (V003). |
| `hotspot_user_codes` | WiFi code of each user (and of server owners in the server company) (V003). |
| `hotspot_user_network_grants` | Server company only: networks of other companies its users may join (V003). |

Central table `public_domains` (control migration V005) maps each portal domain
to its company. The UniFi API key and company SMTP password are stored in the
tenant database and never returned to the client.

## Endpoints

| Route | Access |
| --- | --- |
| `GET /guest/s/default/` | Public, resolved by `Host`. UniFi passes `id` (MAC), `ap`, `ssid`, `url`. |
| `POST /guest/s/default/register` | Public. Validates, applies reconnection rule, emails code. |
| `POST /guest/s/default/verify` | Public. Checks code and authorizes in UniFi. |
| `POST /guest/s/default/code` | Public. Customer- or user-code access on networks configured for it. |
| `GET /modules/hotspot/ui/{screen}` | Native screens: `dashboard`, `visitors`, `reports` (`hotspot.reports.view`), `portal`, `settings` (`hotspot.settings.view`). Sections without permission are hidden. |
| `POST /modules/hotspot/ui/portal`, `/ui/settings` | `hotspot.settings.manage`; returns the screen with a notice. `/ui/settings?network=save\|delete\|load&id=` saves, deletes or loads WiFi networks. |
| `POST /modules/hotspot/ui/integrations?integration=&enabled=` | Business owner / server owner. |
| `POST /modules/hotspot/ui/access?grant=add\|delete&id=` | Server owners in the server company. |
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

1. Update the server (`update.sh`) so it has migration V005 and the module builder.
2. Server owner: *Modulos del servidor* → catalog URL
   `https://github.com/alexcalbri/IDC/tree/develop/modules` while Hotspot is in
   development → install **Hotspot** (the server builds it and restarts) →
   *Empresa* → enable it for the company.
3. Create a DNS A/AAAA record for the portal domain pointing to the server.
4. Company: *Hotspot → Configuracion* → domain, UniFi console id, site id and
   API key, times, email and appearance. Wait for domain status **Activo**.
5. UniFi Network → Hotspot / guest portal:
   - authentication: **External portal server**, address
     `https://<domain>/guest/s/default/`;
   - add the portal domain (and the server IP) to the **pre-authorization
     allowance** so unauthorized guests can reach the portal and the email
     provider if needed.
6. Optional: *Configuracion → Redes WiFi* → add each SSID that should ask for the
   customer code instead of the form.
7. Test with a phone: connect to the guest SSID, register, enter the code.

## Not implemented yet

- The SSID comes from the redirect URL, so it selects the screen but is not a
  security boundary: a guest on a code network could edit the URL and register
  with the form instead. The UniFi Integration API does not report which SSID a
  client is connected to (clients only carry MAC and uplink device), so it
  cannot be verified with UniFi; other controllers may support it.
- Other network controllers (MikroTik, ...) and access by employee code (when the
  HR module exists).
- Local UniFi controller API (only the cloud connector).
- Uploading the logo file (the logo is a URL; the Portal cautivo section shows a
  preview).

Clearing the domain field and saving unpublishes the portal; the helper removes
its Nginx site and certificate on the next run.
