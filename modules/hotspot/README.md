# Hotspot Module

Status: in development (marked with `.development`; not released to `master`). Captive WiFi portal per company, currently
for UniFi networks managed through the UniFi Site Manager cloud API (`api.ui.com`). Network operations use the
`NetworkController` contract so future ecosystems can add an adapter without changing session expiry logic.

## What it does

- Serves a configurable portal at `https://<company domain>/guest/s/default/`.
- Shows a different screen depending on the **WiFi network** the guest joined:
  UniFi sends the SSID in the redirect (`ssid`) and *Configuracion → Redes WiFi*
  sets, per SSID, the access type:
  - **Formulario** (also every SSID not listed): the registration flow below.
  - **Codigo de usuario** (needs the *Usuarios* integration): the user types the
    code shown with their user details in *Accesos WiFi*. It has the same
    eight-character body as a guest code, prefixed with lowercase `us`
    (for example, `us23456789`). Accepted: active users of this company; in the
    server company also the server owners; and users of the server company that
    a server owner authorized for that network of this company. Reconnection is
    counted per user.
  - **Codigo de cliente**: available only when the CRM integration is enabled
    and a pipeline stage is selected in *Configuracion*. The guest types the
    `customer_code` shown in Clientes (also visible read-only in the CRM lead).
    The customer must belong to the database where the portal runs and have a
    lead in the selected stage. If that lead has `target_start` and/or
    `target_end`, access starts one day before `target_start` and ends after
    `target_end` (using the server time zone). The configurable CRM check runs
    daily at the selected time (default 07:00) or at the selected interval
    (default 60 minutes), and disconnects devices when the lead leaves the
    selected stage or its date window ends. Failed disconnections are retried
    on the next scheduled run. With CRM disabled, customer-code networks cannot
    be configured or used.

    Ordinary customers never get in. Wrong codes are limited to 5 per device
    every 15 minutes and 30 per company per minute; unknown codes and customers
    the network does not let in get the same answer. A customer whose passes are
    expired or used up is told the access is not current.
  - The network form only shows the access sources for *Codigo de cliente*
    (`UiField.visibleWhen`); every access type can override the access and
    reconnection times.
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
  business owner and server owner can see, revoke, or restore users' codes.
  User-code sessions do not expire automatically; revoking a code disconnects
  its devices, with failed disconnections retried by the scheduled job. In the
  server company, server owners grant users access to "Codigo de usuario"
  networks of other companies. Codes live in `hotspot_user_codes`; the core
  users table is only read.
- *Configuracion* only shows the settings of the enabled network controller:
  with UniFi, the connection card (**Buscar consolas** appears only while no
  console is saved; clearing the console id and saving brings it back). Access
  times and email are separate cards that work with any controller.
- *Redes WiFi*: the SSID is a dropdown with the networks of the controller's
  console (`/wifi/broadcasts`, UniFi Network 10+, cached for a minute), leaving
  out the ones already configured. Without a configured controller it is typed.
- Administration uses **native UI pieces** (`ui.type = "native"`): Dashboard,
  Visitantes, Reportes, Portal cautivo and Configuracion, modeled on the
  Conectiva panel and responsive (sidebar on wide screens, top bar on phones).
  The app contains no hotspot-specific code.

## Data

Tenant migrations through `V007__prefix_user_codes.sql`
(customer codes come from the Clientes migration `V004__add_customer_code.sql`):

| Table | Purpose |
| --- | --- |
| `hotspot_settings` | Singleton portal configuration (domain, UniFi, limits, SMTP, appearance). |
| `hotspot_registrations` | One row per form submission or code access (`access_mode`), linked to `customers`. |
| `hotspot_codes` | One-time codes (SHA-256 only), expiry, attempts, use. |
| `hotspot_accesses` | Every UniFi authorization attempt; successful rows drive reconnection. |
| `hotspot_networks` | SSIDs with their access type, access sources (`access_sources`, V004) and optional times (V002). |
| `hotspot_pass_sources` | Modules that give access passes (V004); written by those modules. |
| `hotspot_access_passes` | Access passes of customers (V004); written by the source modules, `minutes_balance` spent by the portal. |
| `hotspot_code_attempts` | Wrong customer/user codes, for the guessing limits; kept one day (V002). |
| `hotspot_integrations` | Integrations of the module and whether they are enabled (V003). |
| `hotspot_user_codes` | WiFi code of each user (and of server owners in the server company) (V003; `us` prefix in V007). |
| `hotspot_user_network_grants` | Server company only: networks of other companies its users may join (V003). |
| `hotspot_code_sessions` | One shared session per customer or user code (V005); `hotspot_accesses.session_id` links each device. |

Central table `public_domains` (control migration V005) maps each portal domain
to its company. The UniFi API key and company SMTP password are stored in the
tenant database and never returned to the client.

## Shared sessions per code

Access by customer code or user code is one session per code, shared by every device that uses it:

- The first device opens the session with the network's access time (or the pass's minutes).
- Another device that enters the same code while the session is open joins it: it is authorized only
  for the time left, skips the reconnection wait and spends no pass balance (`joined = true`).
- On each scheduled run, the `code-sessions` job finds expired sessions and disconnects each device (MAC) that
  used the code (`UNAUTHORIZE_GUEST_ACCESS` in UniFi); `hotspot_accesses.disconnected_at` records it.
  The job runs on the CRM schedule configured by the company (daily at a selected time or at an
  interval). A device that could not be disconnected is retried at the next scheduled run.
- User-code sessions have no automatic expiry. The business owner or server owner can revoke a
  user's access from *Accesos WiFi*, which expires the sessions and disconnects their devices.
- The reconnection wait counts from the access that opened the session, never from devices that
  joined it. Form access (email code) is per device and does not use sessions.

## Access passes

The contract through which any other module (plugin) gives WiFi access to its
customers: a hotel stay, a membership, hours bought in a cowork... The hotspot
defines the rules; each module decides how it fills them (for example, a hotel
writes the stay from 00:00 of the check-in day to 23:59 of the check-out day).
Tables live in the company database (migration `V004__add_access_passes.sql`);
the source module writes them with plain SQL and never needs hotspot code.

1. **Register once** (on install, or when its own integration is turned on).
   Check first that the hotspot is in that database:

   ```sql
   SELECT to_regclass('public.hotspot_pass_sources') IS NOT NULL;

   INSERT INTO hotspot_pass_sources (id, name, description)
   VALUES ('hotel', 'Hotel', 'Los huespedes se conectan durante su estancia.')
   ON CONFLICT (id) DO UPDATE SET name = EXCLUDED.name, description = EXCLUDED.description,
       updated_at = CURRENT_TIMESTAMP;
   ```

   `id`: lowercase, digits and `_`, up to 32 characters; `hotspot`, `usuarios`,
   `unifi` and `mikrotik` are reserved. The source then shows in *Hotspot →
   Integraciones*, where the business owner or a server owner enables it, and
   in the *Codigo de cliente* networks, which choose whether it lets customers in.

2. **Keep the passes up to date**, one per own reference (reservation,
   membership...), upserting on `(source_id, source_ref)`:

   ```sql
   INSERT INTO hotspot_access_passes (source_id, source_ref, customer_id, rule, valid_from, valid_until)
   VALUES ('hotel', 'reservation-123', :customer_id, 'periodo', :check_in, :check_out)
   ON CONFLICT (source_id, source_ref) DO UPDATE SET valid_from = EXCLUDED.valid_from,
       valid_until = EXCLUDED.valid_until, updated_at = CURRENT_TIMESTAMP;
   ```

   To end a pass early, set `valid_until` to now or delete the row. Deleting the
   source deletes its passes.

3. **Rules** (`rule`), enforced by the portal:

   | Rule | Columns | Behavior |
   |------|---------|----------|
   | `periodo` | `valid_from`, `valid_until` (both required) | Connects while now is in `[valid_from, valid_until)`; **no reconnection wait**. Each session lasts the network's access time but never goes past `valid_until`. Extending the dates extends the access right away. |
   | `saldo` | `minutes_balance` (required); `valid_from`/`valid_until` optional | Each session spends its authorized minutes (the network's access time, at most the balance and the time left until `valid_until`); **no reconnection wait**; balance 0 = no access. The minutes are spent when the controller authorizes the device (they are given back if it refuses), not by actual use. |

   With several usable passes, a `periodo` ending last wins over balances; among
   balances, the one expiring first is used. Each access stores the pass in
   `hotspot_accesses.pass_id`.

4. Passes only count while the source is **enabled in Integraciones** and the
   network lists it. The customer must also be active (not `inactive` or
   `archived`). New rules are added here, in `HotspotPasses`, and in the
   `hotspot_access_passes_rule` constraint.

## Endpoints

| Route | Access |
| --- | --- |
| `GET /guest/s/default/` | Public, resolved by `Host`. UniFi passes `id` (MAC), `ap`, `ssid`, `url`. |
| `POST /guest/s/default/register` | Public. Validates, applies reconnection rule, emails code. |
| `POST /guest/s/default/verify` | Public. Checks code and authorizes in UniFi. |
| `POST /guest/s/default/code` | Public. Customer- or user-code access on networks configured for it. |
| `GET /modules/hotspot/ui/{screen}` | Native screens: `dashboard`, `visitors`, `reports` (`hotspot.reports.view`), `portal`, `settings` (`hotspot.settings.view`). Sections without permission are hidden. |
| `POST /modules/hotspot/ui/portal`, `/ui/settings` | `hotspot.settings.manage`; returns the screen with a notice. `/ui/settings?network=save\|delete&id=` saves or deletes WiFi networks; `?unifi=consoles\|console\|site&id=` picks the UniFi console and site (only while UniFi is enabled). |
| `POST /modules/hotspot/ui/integrations?integration=&enabled=` | Business owner / server owner. |
| `POST /modules/hotspot/ui/access?usercode=revoke\|restore&id=` | Business owner or server owner; revocation disconnects active user-code sessions. |
| `POST /modules/hotspot/ui/access?grant=add\|delete&id=` | Server owners in the server company. |
| `GET /modules/hotspot/report/export?from=&to=` | `hotspot.reports.export` (CSV) |

## AI agent tools

With the `agentes` module, agents can use (`HotspotMcpTools.kt`), with the same permissions and
validations as the screens:

| Tool | Permission | Does |
|---|---|---|
| `hotspot_summary` | `hotspot.reports.view` | Registrations, unique visitors, authorized and failed connections in a date range (up to 366 days), connections per day and emails collected. |
| `hotspot_search_visitors` | `hotspot.reports.view` | Portal registrations searched by name, email or phone, newest first. |
| `hotspot_get_settings` | `hotspot.settings.view` | Domain and its status, appearance, legal texts, times, limits, email and integrations. Secrets only as "configured". |
| `hotspot_update_settings` | `hotspot.settings.manage` | Changes only the fields sent (same `merge` validation as the forms). Changing the domain registers it for Nginx/HTTPS like the portal form. |
| `hotspot_list_networks` | `hotspot.settings.view` | Configured SSIDs with id, access type, who gets in and times, plus the access sources that can be chosen. |
| `hotspot_save_network` | `hotspot.settings.manage` | Creates a network, or edits one by id changing only what is sent. |
| `hotspot_delete_network` | `hotspot.settings.manage` | Deletes a network (marked destructive); its guests go back to the form. |
| `hotspot_set_integration` | `hotspot.settings.manage` + business owner | Turns an integration on or off, like *Integraciones*. |
| `hotspot_my_wifi_code` | `hotspot.view` | The connected user's WiFi code (created on first use, like *Accesos WiFi*). |
| `hotspot_export_report` | `hotspot.reports.export` | The CSV of *Reportes* for a date range. |
| `hotspot_list_user_codes` | `hotspot.view` + business owner | Every user's WiFi code. |
| `hotspot_list_access_grants`, `hotspot_add_access_grant`, `hotspot_remove_access_grant` | `hotspot.view` + server owner in the server company | Access of server-company users to user-code networks of other companies. |
| `hotspot_unifi_list_consoles`, `hotspot_unifi_choose_console`, `hotspot_unifi_choose_site` | `hotspot.settings.manage` | Pick the UniFi console and site with the API key saved in the app (like *Buscar consolas*). |
| `hotspot_list_controller_ssids` | `hotspot.settings.view` | SSIDs of the network controller, to create networks. |

Not offered, so credentials never pass through an agent: the UniFi API key and the SMTP password
(typed only in the app; the UniFi tools use the stored key server side). Read-only agent tokens
only get the reading tools. The module's own agent instructions are in `HotspotMcpTools.kt`.

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

1. Update the server (`update.sh`) so it has the module builder and current migrations.
2. Server owner: *Modulos del servidor* → catalog URL
   `https://github.com/alexcalbri/IDC/tree/develop/modules` while Hotspot is in
   development → install or update **Hotspot** (the server builds it and
   restarts) → *Empresa* → enable it for the company. Install/update **CRM** and
   enable it in the same company to validate customer codes against a selected
   CRM pipeline stage. Cloudbeds is not required for this integration.
3. Create a DNS A/AAAA record for the portal domain pointing to the server.
4. Company: *Hotspot → Integraciones* → enable UniFi; *Configuracion* → UniFi
   API key → Buscar consolas (console and site), times, email and CRM review
   schedule; *Configuracion* → select the CRM stage when CRM is enabled; *Portal
   cautivo* → domain and appearance. Wait for domain status **Activo**.
5. UniFi Network → Hotspot / guest portal:
   - authentication: **External portal server**, address
     `https://<domain>/guest/s/default/`;
   - add the portal domain (and the server IP) to the **pre-authorization
     allowance** so unauthorized guests can reach the portal and the email
     provider if needed.
6. Optional: *Configuracion → Redes WiFi* → pick each SSID that should ask for
   the customer or user code instead of the form.
7. Test with a phone: connect to the guest SSID, register, enter the code.

## Not implemented yet

- The SSID comes from the redirect URL, so it selects the screen but is not a
  security boundary: a guest on a code network could edit the URL and register
  with the form instead. The UniFi Integration API does not report which SSID a
  client is connected to (clients only carry MAC and uplink device), so it
  cannot be verified with UniFi; other controllers may support it.
- Other network controllers (MikroTik, ...).
- Local UniFi controller API (only the cloud connector).
- Uploading the logo file (the logo is a URL; the Portal cautivo section shows a
  preview).

Clearing the domain field and saving unpublishes the portal; the helper removes
its Nginx site and certificate on the next run.
