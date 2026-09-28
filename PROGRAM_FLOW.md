# Program Flow

Status: documents only features currently implemented in the repository. It
does not describe planned architecture as working behavior.

Build validation was run in a clean temporary copy because the local
`server/build` directory can be locked by Windows processes in this workspace.

## 1. Fresh Ubuntu Installation

Implemented by `scripts/ubuntu/install.sh`.

The installer:

1. Requires Bash and root privileges.
2. Asks whether to run a clean installation. When confirmed with `LIMPIAR`, it removes previous IdeasCore files, service, Linux user, Nginx site and selected PostgreSQL objects before continuing.
3. Asks for the public mode: `https` or `http`. HTTP requires an extra `HTTP` confirmation because it does not encrypt credentials or tokens.
4. Installs/checks required system packages.
3. Clones the selected Git branch/tag into `/opt/ideascore/source`.
4. Builds:
   - backend server distribution;
   - browser web application.
5. Creates a central PostgreSQL database.
6. Creates the runtime PostgreSQL role.
7. Applies central migrations:
   - Core users;
   - Core sessions;
   - server registry;
   - provisioning audit log;
   - installed server module catalog.
8. Creates the initial PostgreSQL `server_owner` login role.
9. Registers that server owner in `application_users` and `server_owners`.
10. Grants the runtime role the table permissions needed by the server.
11. Grants the PostgreSQL `server_owner` role to the runtime role so the
    backend can run `SET ROLE` after validating a `server_owner` token.
12. Writes `/etc/ideascore/server.env`.
13. Installs the Ktor server under `/opt/ideascore/app`.
14. Installs the web app under `/opt/ideascore/web`.
15. Creates a systemd service.
16. Configures Nginx to serve the web app and proxy `/auth/`, `/companies` and `/modules` to Ktor.
17. Uses HTTPS with Let's Encrypt when `https` is selected, or plain HTTP when `http` is explicitly confirmed.
18. Tests server-owner login.

Files involved:

- `scripts/ubuntu/install.sh`
- `scripts/ubuntu/migrations.sh`
- `database/core/migrations/V001__create_application_users.sql`
- `database/core/migrations/V002__create_application_sessions.sql`
- `database/control/migrations/V001__create_server_registry.sql`
- `database/control/migrations/V002__create_provisioning_audit_log.sql`
- `database/control/migrations/V003__create_server_modules.sql`

## 2. Server Startup

Implemented by `server/src/main/kotlin/com/ideasdeveloper/idc/server/app/Application.kt`.

On startup, the Ktor server:

1. Installs JSON serialization.
2. Installs rate limiting:
   - `login`: limits `/auth/login`;
   - `admin`: limits company administration routes.
3. Opens the central database pool through `DatabaseFactory`.
4. Creates `LoginDatabases`, which owns login database selection.
5. Creates a central `SessionService` for validating server-owner tokens.
6. Creates `CompanyProvisioningService`.
7. Discovers server module providers from the classpath.
8. Restores installed modules from central `server_modules`; locked modules
   such as `clientes` are always installed.
9. Registers routes:
   - `GET /`;
   - `POST /auth/login`;
   - `GET /companies`;
   - `GET /server/modules`;
   - `POST /server/modules/{moduleId}/install`;
   - `DELETE /server/modules/{moduleId}`;
   - `POST /companies`;
   - `PUT /companies/{code}/modules/{moduleId}`;
   - `PUT /companies/{code}/status`;
   - `DELETE /companies/{code}`;
   - `GET /modules`;
   - `GET /modules/{moduleId}/metadata`;
   - module-owned endpoints under `/modules/{moduleId}`.
9. Closes tenant pools and central database pool on application stop.
8. Closes tenant pools and central database pool on application stop.

Config is read from `server/src/main/resources/application.conf`.

Implemented environment variables include:

- `DB_URL`
- `DB_USER`
- `DB_PASSWORD`
- `DB_POOL_SIZE`
- `TENANT_DATABASES_JSON`
- `SESSION_LIFETIME_SECONDS`
- `PROVISIONING_DB_URL`
- `TENANT_JDBC_URL_PREFIX`
- `MIGRATIONS_ROOT`
- `COMPANY_BACKUPS_ROOT`
- `MODULE_CATALOG_URL`
- `MODULE_PACKAGES_ROOT`
- `VERSION_CATALOG_URL`

`scripts/ubuntu/update.sh` asks whether to update `server`, `webapp` or
`ambos`. Server updates run server migrations and replace `/opt/ideascore/app`.
Webapp updates rebuild and replace `/opt/ideascore/web`.

`GET /version` returns the installed core/app versions and, when
`VERSION_CATALOG_URL` is configured, the latest published core/app versions.
The official version catalog URL is
`https://raw.githubusercontent.com/alexcalbri/IDC/master/version.json`.
Module updates are checked per installed module against the remote module
repository configured in `MODULE_CATALOG_URL` or by the `server_owner`. The
official URL is `https://github.com/alexcalbri/IDC/tree/master/modules`; there
is no single global module version. The login screen warns about a newer client
app version. The server-owner dashboard warns about newer core, app or
installed module versions.

## 3. Central Database Structures

Implemented migrations:

### `application_users`

Created by `database/core/migrations/V001__create_application_users.sql`.

Used in:

- central database for server owners;
- tenant databases for company users.

### `application_sessions`

Created by `database/core/migrations/V002__create_application_sessions.sql`.

Stores hashed session tokens, user IDs, creation/expiration time and revocation
time.

### `server_owners`

Created by `database/control/migrations/V001__create_server_registry.sql`.

Marks central users that are allowed to administer the server.

### `companies`

Created by `database/control/migrations/V001__create_server_registry.sql`.

Stores company registry data:

- code;
- name;
- branding;
- tenant database name;
- active state.

### `provisioning_audit_log`

Created by `database/control/migrations/V002__create_provisioning_audit_log.sql`.

Records implemented provisioning actions:

- company creation;
- module enabled;
- module disabled.

## 4. Tenant Database Structures

Tenant databases are created by `CompanyProvisioningService`.

Applied migrations:

### Core users and sessions

- `database/core/migrations/V001__create_application_users.sql`
- `database/core/migrations/V002__create_application_sessions.sql`

### `business_owner`

Created by `database/tenant/migrations/V001__create_business_owner.sql`.

Stores the singleton business owner user for that company.

### `customers`

Created by `modules/clientes/migrations/V001__create_customers.sql`.

Stores shared Customer/Prospect identity for all modules in the tenant.
Creating a customer requires Nombre, Correo and Telefono. Customer/Core dynamic
fields created from the Clientes view are stored in `flexible_attributes`.

### `customer_field_definitions`

Created by `modules/clientes/migrations/V001__create_customers.sql`.

Stores tenant-defined field metadata for Customer/Core server-driven customer
forms. Module-specific dynamic fields belong to the owning module's tables and
are shown in that module's views.

### `tenant_modules`

Created by `database/tenant/migrations/V003__create_tenant_modules.sql`.

Initial module states:

- `clientes`: enabled.

Optional modules are not seeded until they are installed on the server.

## 5. Authentication Flow

Implemented route: `POST /auth/login`.

Files:

- `server/auth/api/AuthRoutes.kt`
- `server/auth/application/ScopedLoginService.kt`
- `server/auth/application/AuthenticationService.kt`
- `server/auth/application/SessionService.kt`
- `server/auth/infrastructure/database/LoginDatabases.kt`
- `server/auth/infrastructure/security/PostgresCredentialVerifier.kt`
- `server/auth/infrastructure/security/SessionTokenGenerator.kt`

Flow:

1. Client sends username/password and optional `companyCode`.
2. If `companyCode` is absent, login targets the central database.
3. If `companyCode` is present, login resolves the company in central
   `companies`.
4. For company login, `LoginDatabases` opens or reuses the tenant database pool. If the tenant is not preloaded in `TENANT_DATABASES_JSON`, it infers the JDBC URL from `companies.database_name`, `TENANT_JDBC_URL_PREFIX` and the server runtime DB credentials.
5. `PostgresCredentialVerifier` validates PostgreSQL-backed credentials, or
   the tenant verifies an application password stored in
   `application_user_credentials`.
6. `ApplicationUserRepository` confirms the username maps to an active
   `application_users` row.
7. Server login requires a row in `server_owners`.
8. Company login checks whether the user is the singleton `business_owner`.
9. `SessionService` creates an opaque session token and stores only its SHA-256
   hash.
10. Response includes:
    - user ID;
    - username;
    - access token;
    - expiration;
    - scope;
    - role;
    - company code when applicable;
    - enabled tenant modules when applicable.

## 6. Client Login Flow

Implemented in shared Compose code.

Files:

- `app/features/auth/ui/LoginScreen.kt`
- `app/features/auth/presentation/LoginViewModel.kt`
- `app/features/auth/data/LoginApi.kt`
- `app/features/auth/data/LoginRequest.kt`
- `app/features/auth/data/LoginResponse.kt`
- `app/core/config/ClientConfiguration.kt`
- platform `ClientConfigurationStore.*.kt`
- `app/core/session/SessionStore.kt`
- platform `PersistentSessionStore.*.kt`

Flow:

1. User enters server URL, username, password and optionally company code. The server URL may use `https://` or `http://`; HTTP is intended only for installations explicitly published in HTTP mode.
2. `LoginViewModel` calls `LoginApi`.
3. `LoginApi` posts to `/auth/login`.
4. On success, `SessionStore.save(...)` keeps the session in memory.
5. If the user selected persistent login, platform `PersistentSessionStore`
   saves the session.
6. The first successful server URL/company mode is saved in
   `ClientConfigurationStore`.
7. The app navigates to dashboard.

Implemented session persistence platforms:

- Android;
- JVM/Desktop;
- JS/Web;
- iOS.

Persisted sessions include enabled modules.

## 7. Client App Navigation

Implemented in `app/shared/src/commonMain/kotlin/com/ideasdeveloper/idc/App.kt`
and `navigation/NavRoute.kt`.

Fixed app routes:

- `login`
- `dashboard`
- `settings`
- `module/{moduleId}`

Implemented route behavior:

- `login` shows `LoginScreen`.
- `dashboard` shows `DashboardScreen`.
- `module/empresa` shows `CompanyProvisioningScreen` for `server_owner`.
- `module/server-modules` shows `ServerModuleManagementScreen` for `server_owner`.
- Other `module/{moduleId}` values show `ServerDrivenModuleScreen`, which requests module metadata from the server.
- `settings` shows `SettingsScreen`, where the authenticated user can see the
  saved server/company configuration, current session, client version, manually
  check for a newer client version and clear local configuration.

Module-specific client routes like `composable("custom-module")` are
not part of the default architecture. New modules must use `module/{moduleId}`
and provide enough server metadata through `/modules/{moduleId}/metadata` for
the generic `ServerDrivenModuleScreen` to render the module. A dedicated
Compose route is an exception that requires a normal client release.

Authenticated views that can return to the dashboard must expose the action as
`Volver al panel` through `AuthenticatedTopBar` when they receive an
`onReturnToDashboard` callback. If the view contains editable state that can be
changed without an immediate save, `onReturnToDashboard` must be wrapped by a
local pending-change guard:
compare the current form state with the last loaded or saved state, and show a
confirmation dialog before leaving when they differ. The current user-facing
confirmation asks whether the user wants to return to the panel with unsaved
changes.
Actions that persist immediately, such as module enable/disable requests, do
not count as pending changes after the server call completes.

## 8. Dashboard Flow

Implemented in `app/features/dashboard/ui/DashboardScreen.kt`.

The dashboard:

- shows Empresa for `server_owner` and `business_owner`;
- reads `session.enabledModules`;
- renders module cards for enabled modules;
- navigates modules through `NavRoute.Module(moduleId)`.

Known module display mappings:

- `clientes`

Other installed modules fall back to generic display names until their metadata is loaded.

## 9. Company Creation Flow

Implemented by:

- client: `CompanyProvisioningScreen.kt`
- client API: `CompanyApi.kt`
- server route: `CompanyRoutes.kt`
- server service: `CompanyProvisioningService.kt`

Flow:

1. `server_owner` opens `module/empresa`.
2. Client shows company creation form.
3. Client sends `POST /companies` with bearer token.
4. Server validates bearer token through `ServerOwnerAuthorizer`.
5. Server verifies the token belongs to a central `server_owner`.
6. Server opens a DB connection as runtime user.
7. Server runs `SET ROLE <server_owner_postgres_role>`.
8. Server creates tenant database.
9. Server creates business-owner PostgreSQL login role.
10. Server applies tenant migrations.
11. Server inserts tenant `application_users` and `business_owner`.
12. Server grants runtime access to tenant tables.
13. Server inserts central company registry row.
14. Server writes an audit log row.
15. Server registers the tenant connection in memory.
16. Future server restarts can reconstruct that tenant connection from the central `companies.database_name` value.
17. Client refreshes the company list.

After this, the business owner can log in with:

- business owner username;
- business owner password;
- company code.

## 10. Company Lifecycle Flow

Implemented by `CompanyProvisioningScreen`, `CompanyApi`, `CompanyRoutes` and
`CompanyProvisioningService`.

Flow:

1. `server_owner` opens Empresa.
2. Client loads companies with `GET /companies`.
3. `server_owner` can deactivate an active company with
   `PUT /companies/{code}/status` and `{ "active": false }`.
4. Deactivated companies cannot log in because company login only resolves
   active companies.
5. A company can be deleted only after it is deactivated.
6. `DELETE /companies/{code}` removes the central registry row, unregisters
   the tenant connection from the running server, drops the tenant database and
   drops the PostgreSQL business-owner role.

## 11. Module Administration Flow

Implemented by:

- client: `CompanyProvisioningScreen.kt`
- client API: `CompanyApi.kt`
- server route: `CompanyRoutes.kt`
- server service: `CompanyProvisioningService.kt`

Flow:

1. `server_owner` opens Empresa.
2. Client loads companies with `GET /companies`.
3. Server returns each company's module states.
4. `server_owner` enables or disables optional modules for a company.
5. Client calls `PUT /companies/{code}/modules/{moduleId}`.
6. Server validates `server_owner` token.
7. Server assumes PostgreSQL `server_owner` role for tenant module changes.
8. Server updates tenant `tenant_modules`.
9. Server returns updated company state.

Server module catalog flow:

1. `server_owner` opens Modulos del servidor.
2. Client loads the current catalog URL with `GET /server/modules/catalog`.
3. `server_owner` can update the URL with `PUT /server/modules/catalog`.
4. Client loads installed server modules with `GET /server/modules`.
5. Server merges local installed modules with valid remote module folders from
   the saved repository URL, or `MODULE_CATALOG_URL` when no DB setting exists.
   In GitHub mode, the URL points to the Contents API for the `modules`
   directory and each module folder must contain a valid `module.json`.
6. Server returns installed and available modules with each server module's
   active-company count, installed version and latest available version when the
   module is present in the remote catalog.
7. `server_owner` can install an available module with `POST /server/modules/{moduleId}/install`; the server downloads the package when `packageUrl` is present, verifies `packageSha256` when present, and persists the row in central `server_modules` with that module's own version.
8. `server_owner` can remove an installed module from the active server catalog with `DELETE /server/modules/{moduleId}` only when the module is not locked and no company currently has it enabled; the server deletes that row from central `server_modules`.

Implemented module rules:

- `clientes` is locked and always enabled.
- Optional modules only appear for companies after they are installed in the running server module registry.
- Server module installation/removal is a `server_owner` responsibility. A module cannot be removed from the server catalog while any company has it active.
- Enabling or disabling modules for a company is also a `server_owner`
  responsibility. `business_owner` can administer only its own company data and
  users/permissions inside its own tenant database, not module availability.
- Installed server modules survive restarts because the catalog is stored in
  the central `server_modules` table.

Company administration scope:

- `server_owner` can create, update, activate, deactivate and delete companies,
  and can manage users/permissions for any company when the server-owner UI/API
  is added.
- `business_owner` can administer only its own company profile, backups and
  company users/permissions inside its own tenant database.
- Other roles do not see Empresa in the dashboard.

## 12. Company Login And Module Visibility

Implemented by `LoginDatabases` and `DashboardScreen`.

Flow:

1. User logs in with `companyCode`.
2. Server resolves the tenant DB from central `companies`.
3. Server authenticates PostgreSQL-backed credentials or tenant application
   credentials.
4. Server reads enabled modules from tenant `tenant_modules`.
5. For non-owner company users, server keeps only modules where the user has
   `module.view`.
6. Server returns visible `enabledModules` in login response.
7. Client dashboard renders module cards from `enabledModules`.

## 13. Branding And Shell

Implemented files:

- `app/core/company/CompanyIdentity.kt`
- `app/core/company/CompanyIdentityStore.kt`
- platform `PersistentCompanyIdentityStore.*.kt`
- `app/features/shell/ui/AuthenticatedTopBar.kt`
- `app/features/settings/ui/SettingsScreen.kt`

Current behavior:

- Company identity can be restored and used by login/dashboard/module/settings
  screens.
- The authenticated top bar is reused by dashboard/module screens.
- `AuthenticatedTopBar` can receive an optional `onReturnToDashboard`
  callback. Views that pass it show `Volver al panel` in the top-bar menu, and
  views with editable unsaved state must confirm before invoking that callback.
- Settings is a local client screen. It can clear saved client configuration and
  the local session, but it does not modify server data.

## 14. Installed Module Scaffolds And Server Registry

Implemented scaffold directories:

- `modules/clientes`
- `modules/empresa`

Working behavior:

- The base repository includes only Core/base module source. Optional business
  modules such as optional business modules are not part of the core codebase.
- The server build includes local installed module source directories by
  scanning `modules/*` instead of naming optional modules in the core build
  file.
- `Application.kt` discovers server modules through `ServerModuleProvider`
  services and creates `ModuleRegistry` without importing module packages
  directly.
- `ModuleRegistry` restores installed optional modules from central
  `server_modules` and treats locked modules as installed even if the row is
  missing.
- `GET /modules` returns the module definitions known by the running server.
- `GET /modules/{moduleId}/metadata` returns display metadata for the generic client screen.
- Each bundled module owns its server route namespace under `/modules/{moduleId}`.
- Module-owned business endpoints must call `requireModulePermission` before returning data or performing an action. The guard checks session, tenant, active module status and the declared permission server-side.
- `clientes` is seeded as enabled in tenant DBs.
- `clientes` owns its customer schema migration under `modules/clientes/migrations`.
- Available-for-install modules come from module folders in the external
  repository configured through `MODULE_CATALOG_URL`. If a module folder or its
  `module.json` is removed before installation, it no longer appears as
  available. If it was already installed on a server, the local installed
  metadata/package and `server_modules` row keep that server working until the
  `server_owner` removes it.
- Functional optional module screens and data flows belong to their own module
  packages, not to the Core repository.

## 15. Web Deployment Flow

Implemented in `scripts/ubuntu/install.sh`.

The installer:

- builds the web app;
- copies it to `/opt/ideascore/web`;
- configures Nginx to serve the web app;
- proxies `/auth/`, `/companies` and `/modules` to Ktor.

## 16. Update Script

Implemented file: `scripts/ubuntu/update.sh`.

The updater:

- verifies an existing installer-created layout;
- completes missing server environment defaults in `/etc/ideascore/server.env`
  for tenant JDBC resolution, migrations and company backup storage;
- adds the `/server` proxy route to the installer-managed Nginx site when it is
  missing;
- fetches the selected branch/tag/commit;
- builds the server artifact;
- replaces the runtime server app;
- restarts `ideascore.service`;
- checks the local HTTP endpoint.

This document does not claim the updater applies new database migrations or
privilege changes. Fresh install is the cleanest way to test the current
company provisioning flow.

## 17. Security Behavior Implemented

Implemented controls:

- Login route rate limit.
- Admin/company route rate limit.
- Server-owner token validation for company administration.
- PostgreSQL `SET ROLE` only after IdeasCore token validation.
- `server_owner` is not created as PostgreSQL superuser.
- Business owner roles are created with `NOCREATEDB` and `NOCREATEROLE`.
- Strict validation for company code and PostgreSQL role names.
- Provisioning audit table.
- `clientes` cannot be disabled.
- Client-side module visibility is not used as authorization.

## 17. Known Functional Limits

These are not implemented as working features yet:

- Real customer UI beyond the database schema.
- Build/classpath loading and restart orchestration for downloaded external
  modules after package download.
- Full server-driven form/list rendering.
- PostgreSQL `SECURITY DEFINER` replacement for direct `CREATEDB`/`CREATEROLE`.
- Server-owner UI/API for administering users in any company.

## 18. Manual Test Flow

Use a fresh install with the updated installer.

1. Install server with `scripts/ubuntu/install.sh`.
2. Open the web app.
3. Log in as `server_owner`.
4. Open Empresa.
5. Create a company.
6. Confirm it appears in the company list.
7. Open Modulos del servidor and install an optional module if needed.
8. Open Empresa and enable that installed module for the company.
9. Log out.
10. Log in with the company's business owner credentials and company code.
11. Confirm dashboard shows `Clientes` and any enabled optional modules.
