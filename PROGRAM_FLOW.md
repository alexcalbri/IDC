# Program Flow

Status: documents only features currently implemented in the repository. It
does not describe planned architecture as working behavior.

Build validation is pending in this workspace because Java/JDK is not available
in `PATH` and `JAVA_HOME` is not set to a valid JDK.

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
   - provisioning audit log.
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
7. Builds the server module registry with the currently bundled modules:
   - `clientes`;
   - `crm`;
   - `hostpot`.
8. Registers routes:
   - `GET /`;
   - `POST /auth/login`;
   - `GET /companies`;
   - `POST /companies`;
   - `PUT /companies/{code}/modules/{moduleId}`;
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

Created by `database/tenant/migrations/V002__create_customers.sql`.

Stores shared Customer/Prospect identity for all modules in the tenant.

### `customer_field_definitions`

Created by `database/tenant/migrations/V002__create_customers.sql`.

Stores tenant-defined field metadata for future server-driven customer forms.

### `tenant_modules`

Created by `database/tenant/migrations/V003__create_tenant_modules.sql`.

Initial module states:

- `clientes`: enabled;
- `hostpot`: disabled;
- `crm`: disabled.

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
5. `PostgresCredentialVerifier` validates the submitted credentials against
   PostgreSQL.
6. `ApplicationUserRepository` confirms the PostgreSQL role maps to an active
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
- Other `module/{moduleId}` values show `ServerDrivenModuleScreen`, which requests module metadata from the server.
- `settings` shows a placeholder screen.

Module-specific client routes like `composable("crm")` or `composable("hostpot")` are
not implemented. All modules use `module/{moduleId}` and load their display
metadata from `/modules/{moduleId}/metadata`.

## 8. Dashboard Flow

Implemented in `app/features/dashboard/ui/DashboardScreen.kt`.

The dashboard:

- shows Empresa for `server_owner` and `business_owner`;
- reads `session.enabledModules`;
- renders module cards for enabled modules;
- navigates modules through `NavRoute.Module(moduleId)`.

Known module display mappings:

- `clientes`
- `hostpot`
- `crm`

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

## 10. Module Administration Flow

Implemented by:

- client: `CompanyProvisioningScreen.kt`
- client API: `CompanyApi.kt`
- server route: `CompanyRoutes.kt`
- server service: `CompanyProvisioningService.kt`

Flow:

1. `server_owner` opens Empresa.
2. Client loads companies with `GET /companies`.
3. Server returns each company's module states.
4. Client shows module controls.
5. `server_owner` enables or disables optional modules.
6. Client calls `PUT /companies/{code}/modules/{moduleId}`.
7. Server validates `server_owner` token.
8. Server assumes PostgreSQL `server_owner` role.
9. Server updates tenant `tenant_modules`.
10. Server writes an audit log row.
11. Server returns updated company state.

Implemented module rules:

- `clientes` is locked and always enabled.
- `hostpot` can be enabled or disabled.
- `crm` can be enabled or disabled.

## 11. Company Login And Module Visibility

Implemented by `LoginDatabases` and `DashboardScreen`.

Flow:

1. User logs in with `companyCode`.
2. Server resolves the tenant DB from central `companies`.
3. Server authenticates the PostgreSQL role against the tenant DB.
4. Server reads enabled modules from tenant `tenant_modules`.
5. Server returns `enabledModules` in login response.
6. Client dashboard renders module cards from `enabledModules`.

## 12. Branding And Shell

Implemented files:

- `app/core/company/CompanyIdentity.kt`
- `app/core/company/CompanyIdentityStore.kt`
- platform `PersistentCompanyIdentityStore.*.kt`
- `app/features/shell/ui/AuthenticatedTopBar.kt`

Current behavior:

- Company identity can be restored and used by login/dashboard/module
  placeholder screens.
- The authenticated top bar is reused by dashboard/module screens.

## 13. Installed Module Scaffolds And Server Registry

Implemented scaffold directories:

- `modules/clientes`
- `modules/empresa`
- `modules/hostpot`
- `modules/crm`

Working behavior:

- The server build includes the `clientes`, `hostpot` and `crm` module source directories.
- `Application.kt` registers these modules in `ModuleRegistry`.
- `GET /modules` returns the module definitions known by the running server.
- `GET /modules/{moduleId}/metadata` returns display metadata for the generic client screen.
- Each bundled module owns its server route namespace under `/modules/{moduleId}`.
- `clientes` is seeded as enabled in tenant DBs.
- `hostpot` and `crm` are seeded as disabled and can be toggled.
- Functional module screens still render metadata/placeholders; full business UI and data flows are not implemented yet.

## 14. Web Deployment Flow

Implemented in `scripts/ubuntu/install.sh`.

The installer:

- builds the web app;
- copies it to `/opt/ideascore/web`;
- configures Nginx to serve the web app;
- proxies `/auth/`, `/companies` and `/modules` to Ktor.

## 15. Update Script

Implemented file: `scripts/ubuntu/update.sh`.

The updater:

- verifies an existing installer-created layout;
- fetches the selected branch/tag/commit;
- builds server and web artifacts;
- replaces runtime app and web artifacts;
- restarts `ideascore.service`;
- checks the local HTTP endpoint.

This document does not claim the updater applies new database migrations or
privilege changes. Fresh install is the cleanest way to test the current
company provisioning flow.

## 16. Security Behavior Implemented

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
- Functional CRM module screens.
- Functional Hostpot module screens.
- Settings screen.
- Full server-driven form/list rendering.
- PostgreSQL `SECURITY DEFINER` replacement for direct `CREATEDB`/`CREATEROLE`.
- Automated upgrade/migration of already installed servers for the new company
  provisioning permissions.
- Build/test validation in this workspace.

## 18. Manual Test Flow

Use a fresh install with the updated installer.

1. Install server with `scripts/ubuntu/install.sh`.
2. Open the web app.
3. Log in as `server_owner`.
4. Open Empresa.
5. Create a company.
6. Confirm it appears in the company list.
7. Enable `CRM` or `Hostpot`.
8. Log out.
9. Log in with the company's business owner credentials and company code.
10. Confirm dashboard shows `Clientes` and any enabled optional modules.
