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
├── module.json                   optional: only for the remote module catalog
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
  each other's files. Web-UI modules call `route.moduleWebResources("<id>")` in
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

Empty scaffold folders (only directories, as Git leaves them) are ignored by the
check.

## Installable module manifest

`module.json` is optional. Add it only when the module is distributed through
the remote catalog; modules compiled into the server do not need it.

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
phones) and `UiScreen.theme` sets the module colors.

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
