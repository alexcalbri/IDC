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
    code shown with their user details in *Empresa → Usuarios* or *Mi perfil*. It has the same
    eight-character body as a guest code, prefixed with lowercase `us`.
    Core stores it in `application_users`; Hotspot keeps only revocable WiFi-access state.
    For example: `us23456789`. Accepted: active users of this company; a
    business owner may use their code from any active company in the same owner
    group; in the server company also the server owners; and users of the server
    company that a server owner authorized for that network. Reconnection is
    counted per user, and access can be revoked per company.
  - **Codigo de cliente**: available only when the CRM integration is enabled
    and a pipeline stage is selected in *Configuracion*. The guest types the
    `customer_code` shown in Clientes (also visible read-only in the CRM lead).
    The customer must belong to the database where the portal runs and have a
    lead in the selected stage. If that lead has `target_start` and/or
    `target_end`, access starts one day before `target_start` and ends after
    `target_end` (using the server time zone). Since CRM 0.22.0 those two columns
    are a copy of the date fields the company chooses in the CRM under
    *Configuracion > Integraciones > Fechas de estadia* (check-in and check-out;
    the CRM migration chooses "Fecha de ingreso" and "Fecha de salida" for companies
    that already had dates). Without that mapping the leads have no dates and the
    window is not limited by dates. The configurable CRM check runs
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
  business owner and server owner can revoke or restore users' WiFi access.
  User-code sessions do not expire automatically; revoking a code disconnects
  its devices, with failed disconnections retried by the scheduled job. In the
  server company, server owners grant users access to "Codigo de usuario"
  networks of other companies. The code belongs to Core and is stored in
  `application_users.user_code`; Hotspot stores only whether WiFi access is
  enabled for that user.
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

Tenant migrations through `V008__remove_hotspot_user_code.sql`
(customer codes come from the Clientes migration `V004__add_customer_code.sql`):

| Table | Purpose |
| --- | --- |
| `hotspot_settings` | Singleton portal configuration (domain, UniFi, limits, email subject, appearance; the old `smtp_*` columns are unused since 0.10.0). The portal's colors are `portal_primary_color`, `portal_secondary_color` and `portal_text_color` (Hotspot > Diseño, V011); a NULL one uses the company's (central `companies` primary, secondary and accent colors, IDC's until changed). The old `primary_color`/`text_color` columns are no longer used. |
| `hotspot_registrations` | One row per form submission or code access (`access_mode`), linked to `customers`; `roamed_from` (V009) marks a continued access from a linked company. |
| `hotspot_codes` | One-time codes (SHA-256 only), expiry, attempts, use. |
| `hotspot_accesses` | Every UniFi authorization attempt; successful rows drive reconnection. |
| `hotspot_networks` | SSIDs with their access type, access sources (`access_sources`, V004), optional times (V002) and `shared` (V009). |
| `hotspot_pass_sources` | Modules that give access passes (V004); written by those modules. |
| `hotspot_access_passes` | Access passes of customers (V004); written by the source modules, `minutes_balance` spent by the portal. |
| `hotspot_code_attempts` | Wrong customer/user codes, for the guessing limits; kept one day (V002). |
| `hotspot_ip_throttle` | Portal requests per source IP (IPv6 grouped by /64) and action (`register`, `code`) in 10-minute windows, for the per-IP limits; survives restarts, old windows are deleted (V012). |
| `hotspot_integrations` | Integrations of the module and whether they are enabled (V003). |
| `hotspot_user_codes` | Hotspot-specific WiFi access state only; user codes live in Core (V003, V006, V007, V008). |
| `hotspot_user_network_grants` | Server company only: networks of other companies its users may join (V003). |
| `hotspot_code_sessions` | One shared session per customer or user code (V005); `hotspot_accesses.session_id` links each device. |

Central table `public_domains` (control migration V005) maps each portal domain
to its company. The UniFi API key is stored in the
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

## Shared networks between linked companies

One SSID can exist on the gateways of two linked companies (same owner group),
for example created with a UniFi Fabric. Each gateway counts guest time from its
own authorization, so moving between them would add a fresh period each time.
Mark the network *Compartida con empresas vinculadas* in *Redes WiFi* of **both**
companies; then:

- When a device opens the portal on that SSID, the portal looks for live access of
  its MAC on the same SSID in this company and the linked ones (only original
  accesses, `roamed_from IS NULL`, and only where the SSID is also shared).
- With one, it authorizes the device without form or code for the time left of
  that access (code sessions follow their session; `0` minutes = no expiry) and
  shows "Bienvenido de nuevo" with the time left. Both gateways end at the same time.
- The continuation is a registration with `roamed_from` = the source company and
  an access with `joined = true`: it does not move the reconnection wait, spends no
  pass balance and is never a source itself, so going back and forth never extends
  the access. The guest is linked to this company's customer with the same email
  if there is one; none is created.
- User codes also open a session here, and revoking that user's WiFi access ends
  the continued sessions in the linked companies too.
- Recognition is by MAC: a phone with a rotating private MAC must log in again.
- The network check above still applies to the continuation.

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

Not offered, so credentials never pass through an agent: the UniFi API key
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

Since 0.10.0 Hotspot has no SMTP of its own. Codes are sent with the company SMTP
(`company_mail_settings`, read with `CompanyMail.read`; the company owner sets it) and, when
the company has none, with the server SMTP from `/etc/ideascore/server.env`
(`SMTP_HOST`, `SMTP_PORT`, `SMTP_USERNAME`, `SMTP_PASSWORD`, `SMTP_FROM`,
`SMTP_SECURITY` = `starttls`|`ssl`|`none`; restart `ideascore` after editing).

A company that had its own SMTP in Hotspot is migrated once by the job `mail-migration`: the
SMTP is copied to the company SMTP only if the company has none yet (the CRM one has priority),
and the old plain-text password is sealed with the company key. The `smtp_*` columns of
`hotspot_settings` are then cleared (`smtp_mode` back to `servidor`); a later SQL migration will drop
them. Without `IDC_MASTER_KEY` the job waits.

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

## Custom HTML (Diseño)

*Diseño* lets the company replace the entry views of the portal with its own
whole HTML page: the registration form and the customer-code and user-code pages
(table `hotspot_portal_templates`, V010; no row = IDC's design). Saving validates
the page (`HotspotTemplates.validate`) and keeps nothing until it passes:

- One form posting (`method="post"`) to the view's address: `/guest/s/default/register`
  for the form, `/guest/s/default/code` for the code views. No other form may post elsewhere.
- Inside it, the fields the backend reads: `name`, `email`, `phone` and
  `<input type="checkbox" name="terms" value="si">` (form) or `code` (code views),
  and `{{campos_ocultos}}` (MAC, AP, SSID and destination of the guest).
- `{{error}}` somewhere, for the portal's messages.
- No `<script>`, frames, `on*=` handlers, `javascript:` URLs, `formaction` or meta refresh.
- Only the placeholders of that view: common `{{titulo}}`, `{{subtitulo}}`, `{{red}}`,
  `{{logo_url}}`, `{{pie}}`, `{{color_primario}}`, `{{color_secundario}}`,
  `{{color_texto}}`; form `{{nombre}}`, `{{correo}}`, `{{celular}}`, `{{cumpleanos}}`,
  `{{consentimiento}}`, `{{terminos_url}}`, `{{privacidad_url}}`; code views `{{tipo_codigo}}`.

Values are escaped; `{{error}}` renders `<div class="idc-error" role="alert">` or
nothing. The other pages (emailed code, success, messages) keep IDC's design with
the portal colors. "Cargar un ejemplo" fills the editor with a valid page.

The "Colores del portal" card at the top of Diseño sets the primary, secondary and
text colors (`#RRGGBB`, `HotspotSettingsRepository.saveColors`). Each blank one
uses the company's color, and IDC's when the company has none (`PortalBrand.with`).

## Network check before authorizing

The SSID in the redirect URL picks the screen, but the redirect is not signed:
a guest could edit it to get the form on a code network, or use a user code
granted for another SSID. So before authorizing, the controller confirms the
device is on that SSID (`NetworkController.authorizeGuest(settings, mac, ssid)`).
UniFi does it with the Network application's station API through the cloud
connector (`/proxy/network/api/s/{site reference}/stat/sta/{mac}`, field
`essid`; wired guests have none and must come with an empty `ssid`), because
the Integration API does not report a client's SSID. The site reference
(`default`) is read once from the Integration API sites list. A different
network shows "Red WiFi distinta" and the access is kept as rejected; when
UniFi cannot be asked the device is not let in.

UniFi keeps a guest authorized on every SSID of the site, so a device let in on
one network could switch to another (for example from a customer network to the
staff one) without the portal. The `network-guard` job (every minute) reads the
connected guests (`stat/sta`) and removes the authorization of any authorized
guest whose SSID differs from the SSID of its latest access in the company; the
new network's portal then asks for its own method. Coming back to the network it
was let in on, the portal lets it in again without form or code for the time it
has left ("Bienvenido de nuevo", the same continuation as shared networks). Until
the next run (up to about a minute) the device may still browse on the new
network. Devices the company never let in are left alone.

The success pages show the session length only for form access: code accesses
last for the customer's stay or until a user is revoked. UniFi's "URL cifrada" option
does not apply to external portals: the redirect keeps `ap`, `id`, `ssid` in
clear text.

## Not implemented yet

- Other network controllers (MikroTik, ...).
- Local UniFi controller API (only the cloud connector).
- Uploading the logo file (the logo is a URL; the Portal cautivo section shows a
  preview).

Clearing the domain field and saving unpublishes the portal; the helper removes
its Nginx site and certificate on the next run.
