# IdeasCore Modules

This directory is the default source location for modules developed in the
official repository.

The official module repository URL is:

```text
https://github.com/alexcalbri/IDC/tree/master/modules
```

The server accepts that normal GitHub URL and converts it internally to the
GitHub Contents API. The server owner can point `MODULE_CATALOG_URL` to another
GitHub repository when they want to install non-official modules. A direct
Contents API URL also works, for example
`https://api.github.com/repos/owner/repository/contents/modules?ref=main`.

The server lists every folder in that directory and treats a folder as
installable only when it contains a valid `module.json` manifest. Removing a
module folder from the remote repository prevents new installations, but it does
not break servers that already installed the module.

## Module layout (required)

Every module follows this layout. `./gradlew :server:validateModules` checks it
and runs automatically before the server compiles, so a module that breaks the
rules fails the build with a list of the problems.

```text
modules/<id>/
├── README.md                     required: purpose, data, routes, permissions, setup
├── .development                  optional: module still in development (not released)
├── module.json                   required: catalog entry (id, displayName, description, version, bundled)
├── migrations/                   optional: tenant database migrations
│   ├── README.md                 optional
│   └── VNNN__description.sql     e.g. V001__create_orders.sql
└── server/                       required when the module has code
    └── src/
        ├── main/kotlin/com/ideasdeveloper/idc/modules/<id>/
        │   ├── <Id>Module.kt             id and display name constants
        │   ├── <Id>ServerModule.kt       ServerModule: definition, migrations, routes
        │   └── ...                       other server code of the module
        ├── main/resources/modules/<id>/  optional resources
        │   └── web/                      pages of a web-UI module (index.html, js, css)
        └── test/kotlin/com/ideasdeveloper/idc/modules/<id>/
                                          optional tests, run with :server:test
```

Rules:

- `<id>` is the folder name, the module id and the last part of the Kotlin
  package. It must match `[a-z][a-z0-9_]{0,62}`.
- Only `README.md`, `module.json`, `migrations/` and `server/` are allowed at the
  module root (hidden files are ignored). There is no `shared/` or client folder:
  **modules never contain app code**. The app draws modules from the server
  (native pieces) or shows their web pages (see *Module user interface*).
- All Kotlin code uses the package `com.ideasdeveloper.idc.modules.<id>` and lives
  under the matching folder.
- A module that implements `ServerModule` must declare
  `class <Id>ServerModuleProvider : ServerModuleProvider`, where `<Id>` is the id
  in PascalCase (`ventas_online` -> `VentasOnlineServerModuleProvider`). The build
  registers it automatically; without it the server never loads the module.
- Resources live under `resources/modules/<id>/` so two modules cannot overwrite
  each other's files. Web-UI modules call `route.moduleWebResources("<id>", javaClass.classLoader)` in
  `routes` to serve `resources/modules/<id>/web/` at `/modules/<id>/web/`.
- Migrations are named `VNNN__description.sql`, wrap all statements in one
  `BEGIN;` ... `COMMIT;`, are listed in the module's `migrationPaths` and are never
  edited after release (the checksum is verified); add a new version instead.
- Tables created by migrations that the backend reads or writes are listed in
  `runtimeTables` so the runtime role receives access when the module is enabled.
- Module routes live under `/modules/<id>/` and check access with
  `requireModulePermission` or `requireModuleAccess`. The server registers the
  routes of every compiled module at startup, installed or not, so installing or
  removing a module takes effect without a restart; those helpers reject modules
  the server owner has not installed. Public routes without a session (such as
  the hotspot portal) must check `access.isInstalled("<id>")` themselves.

- A module in development carries an empty `.development` file at its root. It
  stays on `develop`: `scripts/release-to-master.sh` removes it from `master`
  together with the tests. Delete the file when the module is ready; the next
  release includes it.

Empty scaffold folders (only directories, as Git leaves them) are ignored by the
check.

## Installable module manifest

Every module has `module.json`. Base modules (`"bundled": true`, today Clientes
and Empresa) are compiled into the server and never offered by the catalog; every
other module is optional and is listed only by the catalog URL configured on the
server. The `version` must equal the `ModuleDefinition` version (the build checks
it).

```json
{
  "id": "example",
  "displayName": "Example",
  "description": "Example business module.",
  "version": "0.1.0",
  "packageUrl": "https://example.com/modules/example.zip",
  "packageSha256": "64_hex_characters_when_available",
  "views": [],
  "permissions": []
}
```

Validation rules:

- `id` must match the folder name (and therefore `[a-z][a-z0-9_]{0,62}`, see
  *Module layout*).
- `displayName`, `description` and `version` must not be blank.
- `packageUrl`, when present, must start with `http://` or `https://`.
- `packageSha256`, when present, must be a 64-character SHA-256 hex digest.
- `locked` is ignored for remote modules; base locked modules belong to Core.

## How optional modules reach a server

The server distribution contains the core and the base modules only. Optional
modules come from the **catalog URL** configured in *Modulos del servidor*
(default `.../tree/master/modules`; use `.../tree/develop/modules` to try modules
still in development). The installed branch does not matter.

When the server owner installs an optional module, the server:

1. downloads that branch from GitHub and extracts `modules/<id>/`;
2. checks the module layout;
3. compiles the module with the Kotlin compiler shipped in the distribution
   (`compiler/`) against the core it is running. If the module does not fit this
   core version, the installation fails with the compiler error and nothing changes;
4. stores the package in `MODULE_PACKAGES_ROOT/<id>/<id>-<version>.jar` (code,
   migrations, resources and `module.json`) and records it in `server_modules`;
5. restarts itself (systemd brings it back in a few seconds) and loads the
   package in its own class loader.

The module list shows the progress (`installing`, `restarting`, `failed`). When
the catalog offers a newer version of an installed module, *Actualizar* rebuilds
it. Removing a module deletes its package; its routes reject requests at once.
A package that cannot be loaded after a core update is skipped at startup (the
server still starts) and must be installed again.

Locally, `./gradlew :server:run` loads optional modules from the source tree, and
every build compiles them to catch errors early.

## Versions and updates

- Publishing a new version means raising `version` in `module.json` and in the
  `ModuleDefinition` (the build requires both to match) on the catalog branch.
  Changing the code without raising the version is invisible to servers.
- When the server owner opens *Modulos del servidor*, the server compares the
  installed version with the catalog's. Versions compare numerically
  (`0.10.0` > `0.9.0`; `1.0.0-beta` < `1.0.0`). *Actualizar* appears only when the
  catalog version is newer; an older catalog version (for example after switching
  from `develop` to `master`) is reported but never installed, so a module is never
  downgraded.
- Updating rebuilds the module from the catalog branch. If the new version does not
  compile against the installed core, the update fails and the current version keeps
  running.
- New migrations of a module version are applied to every company that already uses
  the module when the server starts with that version; companies that enable the
  module later receive them on activation. Applied migrations are recorded and never
  run twice. A failing migration is logged and shown on the module as needing
  attention; other companies continue.
- Never edit a released migration; add `V00N__...sql`. The server and
  `scripts/ubuntu/migrations.sh` reject an applied migration whose content changed.
- Base modules (Clientes, Empresa) are updated with the server (`update.sh`).

## Server workspace

Server owners can use modules without creating a company. Ticking *Usar en el
servidor* on an installed module in *Modulos del servidor* enables it in the
**server workspace**: a company-like tenant database (`idc_servidor`, company
code `servidor`) created on first use and hidden from company administration
(it cannot be listed, edited, deactivated or deleted as a company). Base modules
are always enabled there.

- Server owners use the workspace with their normal session and every permission;
  their dashboard shows the modules enabled in it.
- *Personal del servidor* creates workspace users with per-module permissions,
  like a business owner does for a company. Staff sign in with their username,
  password and the company code `servidor`.
- Modules need no changes: the workspace is a regular tenant for them, so their
  routes, migrations and permissions work as in any company.

## Background jobs

A module declares periodic work in `ServerModule.jobs` (polling an external
API, sending scheduled emails, archiving records):

```kotlin
override val jobs = listOf(
    ModuleJob("welcome-emails", Duration.ofMinutes(15)) { context ->
        // context.companyCode, context.tenant (open connection), context.access
    },
)
```

- The server checks once a minute and runs each job for every active company
  (the server workspace included) that has the installed module enabled, the
  first time after startup and then every `interval`.
- Jobs run one after another on a single background thread. Keep every run
  short and bounded: process a batch and continue on the next run.
- A failing run is logged and retried at the next interval. Runs are not
  persisted: after a restart every job runs again once, so jobs must be
  idempotent (store what was already sent or imported in the tenant).

## Email

`MailSender.sendText` and `MailSender.sendHtml` send through `SmtpSettings`
(the server SMTP is `access.serverMail`; modules may let a company configure
its own). `sendHtml` takes the HTML and a plain-text alternative; the module
builds the HTML and must escape every value that comes from users or
customers.

## Public webhooks

External services (Meta, a WordPress plugin, a PMS) call
`/modules/<id>/hooks/<company>/...`. These routes have no session:

- Open the tenant with `access.tenantConnectionForWebhook(company, "<id>")`. It
  returns null when the company is inactive or does not use the installed
  module; answer 404 then.
- Authenticate the request before trusting or storing anything, typically by
  checking a signature (HMAC) with a secret the company saved in the module.
- Answer quickly; do slow work in a job.

## AI agent tools (MCP)

With the `agentes` module, each user connects their own AI agent, which is
limited to that user's permissions. Each module has its own MCP: it overrides
`ServerModule.mcp` with a `ModuleMcp` (`server/.../modules/McpTools.kt`) that
holds what the module tells agents about itself:

```kotlin
override val mcp: ModuleMcp
    get() = VentasMcpTools.mcp   // in VentasMcpTools.kt: ModuleMcp(Instructions, tools)
```

- `instructions`: what the module is for, how its tools fit together and the
  rules to follow, written for the model. Agents receive it only while they
  can use some tool of the module.
- `tools`: `McpTool`s, each a function of the module:

- Every tool declares the `permission` it needs. The endpoint lists and runs
  it only when the module is enabled for the company and the user holds that
  permission at the moment of the call.
- The handler receives `call.tenant`, `call.permissions` and `call.arguments`
  and must apply the same rules as the module's routes and screens (visibility
  scopes, ownership, finer permissions). Throw `McpToolException` with a
  message the agent can act on.
- Mark tools that only read with `readOnly = true` (read-only tokens use only
  those) and irreversible ones with `destructive = true`.
- `agentes` is only the bridge: it holds no tool and knows no module (and no
  module depends on it). It reads the installed modules' `mcp` on every
  agent request, so a module update that adds or changes tools reaches agents
  once that module is updated, without updating `agentes` or other modules.
  MCP clients refresh their tool list when they reconnect (for example a new
  conversation in claude.ai).
- Offering tools is optional: the agent sees `<moduleId>_<name>` as soon as the
  module is installed, and stops seeing it when the module is removed or
  disabled. Put them in a `<Module>McpTools.kt` next to the module's server
  code. A tool that needs a new permission is used only after the business
  owner grants it, like any screen.
- Credentials (API keys, tokens, secrets, passwords) never pass through an
  agent: no tool takes them as arguments or returns them; return only whether
  they are configured, and when saving other settings send the stored values
  back server side. `ModuleRegistryTest.noModuleToolTakesCredentials` fails
  for an argument named like a credential.
- Override `mcp` with a getter as above: it is built when first read, after
  the module object initialized. A module whose `mcp` throws is skipped (and
  logged once) without affecting the others.
- The registry ignores (and logs once) a tool whose name does not match
  `[a-z][a-z0-9_]*`, whose `<moduleId>_<name>` is longer than 64 characters, or
  whose permission the module does not declare in its `ModuleDefinition`; a
  repeated name keeps the first. `ModuleRegistryTest` fails for any module
  compiled with the server that breaks these rules.
- Server owners: their agent can run any module's tools in any company of the
  server. The bridge adds an `acting_company` argument to every tool for them
  and runs the handler with that company's `tenant` and full permissions
  (`call.permissions.serverOwner = true`: a central user, not in the
  company's `application_users`, as in the server workspace). Do not name an
  argument `acting_company`, and do not assume `serverOwner` means the server
  workspace; compare `companyCode` with `ServerWorkspace.Code` for that.
- The server itself has an MCP too (`ServerMcpTools`, id `servidor`, server
  owners only): companies and modules. No module may use the id `servidor`.

See `modules/agentes/README.md` for the endpoint and an example.

## Customer history

A module can add events to a customer's history in Clientes (the shared customers) by overriding
`ServerModule.customerTimeline` with a getter that returns a `CustomerTimeline`
(`server/.../modules/CustomerTimeline.kt`):

```kotlin
override val customerTimeline: CustomerTimeline
    get() = VentasCustomerTimeline.timeline   // CustomerTimeline(permission = "ventas.view") { query -> ... }
```

- `permission` must be declared by the module; entries are shown only when the module is enabled
  for the company and the user holds it (`ModuleRegistryTest` checks the declaration).
- The function receives `query.customerId`, `query.tenant`, `query.permissions` and `query.limit`;
  it only reads, applies the same visibility rules as the module's screens and returns at most
  `limit` `CustomerTimelineEntry` (date, kind, title, detail, author), newest first.
- Clientes shows every module's entries together (`GET /customers/{id}/history`, the app's
  *Historial* button and `clientes_get_customer_history`) without knowing any module; a module that
  fails is left out and logged. Use it for reservations, stays, special requests, purchases...

## Who made a change

Every user (business owner, server owner and staff) has a name and an email
(`application_users.display_name` and `email`; users created before they were
required complete them at their next sign-in). To record who made a change,
use `access.userIdentity(tenant, permissions)`: it returns the name (or the
username when still missing), the email and the username, reading server
owners from the central database. Do not read `application_users.postgres_role`
for display.

## Module user interface

Modules are independent of the app: the app never contains module-specific
code. Each module declares in its `ModuleDefinition` how clients show it:

```kotlin
ui = ModuleUiEntry(type = "native", entry = "/modules/example/ui", home = "home")
// or
ui = ModuleUiEntry(type = "web", entry = "/modules/example/web/")
```

### Option 1: native pieces (`type = "native"`)

Best performance and native look on Android, iOS, desktop and web. The app
requests `GET {entry}/{screen}?params` and the server answers a `UiScreen`
(shared contract in `core/src/commonMain/kotlin/com/ideasdeveloper/idc/ui/ModuleUi.kt`).
The server builds every screen with the current data; the app only draws it.

| Piece | Purpose |
| --- | --- |
| `UiText` | Text: `title`, `subtitle`, `body`, `caption`, optional tone. |
| `UiColumn`, `UiRow` | Vertical / horizontal groups; non-`fill` rows wrap on narrow screens. |
| `UiGrid` | Responsive grid: as many columns as fit with `minItemWidth` dp. |
| `UiCard` | Surface with optional title and header actions. |
| `UiMetric`, `UiStatus` | KPI value with caption; colored status dot. |
| `UiBarChart`, `UiTable` | Bar chart; horizontally scrollable table. |
| `UiForm` | Fields (`text`, `email`, `phone`, `url`, `number`, `password`, `select`, `textarea`, `color`, `date`, `boolean`, read-only) grouped by section. |
| `UiImage` | Image from an `https://` URL or a module route (`/modules/...`, sent with the session). Web clients need CORS on external hosts. |
| `UiButton`, `UiDivider`, `UiSpacer` | Actions and spacing. |

Actions: `UiNavigate` (another screen), `UiRequest` (call a route, the response
is the next `UiScreen`; optional confirmation), `UiDownload` (text file such as
CSV; saved to Downloads on Android and desktop, to Files > On my iPhone > IDC on
iOS, downloaded by the browser on web), `UiOpenUrl`. Any action with `formId` carries that form's values.
`UiScreen.navigation` adds module sections (sidebar on wide screens, top bar on
phones). Screens carry no colors: see [Colors and themes](#colors-and-themes).

Compatibility: clients skip pieces and actions they do not know, so a newer
server never breaks an older app. New piece types are added to the shared
contract and only need an app update for clients to draw them.

Protect each screen with `requireModuleAccess` (returns all the user's module
permissions) and show only the sections the user may use. See
`modules/hotspot` for a complete example.

### Option 2: own web interface (`type = "web"`)

For views the pieces do not cover. The module serves HTML/JS under its `entry`
(which must start with `/modules/{moduleId}/`). The app calls
`POST /modules/{moduleId}/web-session`, receives a one-time link (60 seconds)
and opens it inside the app: WebView on Android, WKWebView on iOS and an iframe
in the web app, and embedded Chromium (JCEF, jcefmaven) on desktop. The desktop
app downloads Chromium the first time a web module is opened (about 100 MB
download, about 370 MB installed in ~/.idc/jcef) and shows the progress; the
one-time link is requested only after the browser is ready. If Chromium cannot
start, the app offers to open the module in the system browser. `/modules/{moduleId}/web-entry` sets HttpOnly, SameSite=Strict
cookies limited to `/modules/{moduleId}/`, so the module's pages and API calls
pass the same `requireModulePermission` / `requireModuleAccess` checks without
handling tokens. The session token never appears in a URL.

The first page also receives the app theme as query parameters, so it can
draw itself with the same colors: `idc-theme=light|dark` and
`idc-background`, `idc-surface`, `idc-surfaceAlt`, `idc-border`, `idc-text`,
`idc-muted`, `idc-accent`, `idc-onAccent`, `idc-success`, `idc-warning`,
`idc-danger` as `#RRGGBB`. Keep them (for example in CSS variables) for the
rest of the session.

## Colors and themes

The app has two built-in themes that every user picks in **Ajustes >
Apariencia** (saved on the device; **Automatico** follows the system):

- **Claro**: the core palette, white cards over the company brand gradient.
- **Oscuro**: the hotspot panel palette (`#05060B` background, `#11121B` cards).

The company brand colors (Empresa > primary and secondary) are always the
accent: buttons, highlights and the gradient.

Modules must respect the app colors. Native pieces are drawn with the active
theme, so a module never sets colors on its screens (`UiScreen.theme` is
ignored and only kept so older packages still load). Use tones (`success`,
`warning`, `danger`, `info`) to give meaning to texts, metrics and statuses.
Web modules read the theme from the query parameters above.

A module can add themes to the app. They appear in Ajustes next to Claro and
Oscuro while the module is installed on the server (`GET /modules/themes`,
public), and they paint the whole app, not only the module:

```kotlin
object ExampleServerModule : ServerModule {
    override val themes = listOf(
        UiAppTheme(
            id = "ocean",
            name = "Oceano",
            dark = true,
            colors = UiTheme(
                background = "#04131F",
                surface = "#0B2233",
                surfaceAlt = "#12304A",
                border = "#1E4560",
                text = "#EAF6FF",
                muted = "#8FB3C9",
                success = "#3DD6A0",
                warning = "#FFB454",
                danger = "#FF6B6B",
            ),
        ),
    )
}
```

Colors are `#RRGGBB`; a missing one comes from the built-in theme of the same
mode (`dark`). `accent` and `onAccent` are ignored because the accent is the
company brand. `brandBackground = true` paints the screen background with the
brand gradient, as Claro does. The server publishes the theme as
`<moduleId>.<id>` and skips themes with invalid colors.
