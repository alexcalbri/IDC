# Company Provisioning Flow

Status: implemented locally; build validation pending because this workspace has
no Java/JDK available.

This document explains the current flow for creating companies, provisioning a
tenant database, logging in to a company, and enabling or disabling company
modules.

## High-Level Flow

1. A `server_owner` logs in through `/auth/login`.
2. The client opens the dynamic module route `module/empresa`.
3. The Empresa screen calls `POST /companies` with the `server_owner` token.
4. The backend validates the token against the central database.
5. After validation, the backend assumes the PostgreSQL `server_owner` role with
   `SET ROLE`.
6. The backend creates the tenant database and the `business_owner` PostgreSQL
   login role.
7. The backend applies Core and tenant migrations to the new tenant database.
8. The backend seeds the tenant `business_owner`, `clientes`, `hostpot` and
   `crm` module registry rows.
9. The backend registers the company in the central `companies` table and adds
   the tenant connection to the running login resolver.
10. The `business_owner` can then log in using username, password and
    `companyCode`.
11. The company login response includes enabled modules, and the dashboard
    renders those modules through the generic `module/{moduleId}` route.

## Client Files

### `app/shared/src/commonMain/kotlin/com/ideasdeveloper/idc/App.kt`

Owns the top-level Compose navigation shell.

- Keeps fixed routes only for `login`, `dashboard`, `settings` and the generic
  `module/{moduleId}` route.
- Reads `moduleId` from Navigation `SavedState` using `androidx.savedstate.read`.
- Routes `module/empresa` for `server_owner` sessions to
  `CompanyProvisioningScreen`.
- Routes all other modules to `ServerDrivenModuleScreen`, which loads module metadata from the server.
- Uses `NavRoute.Module(moduleId)` instead of hardcoded module routes like
  `hostpot` or `crm`.

### `app/shared/src/commonMain/kotlin/com/ideasdeveloper/idc/navigation/NavRoute.kt`

Defines app navigation routes.

- Uses `data class Module(val moduleId: String)` for all module navigation.
- Converts module routes to `module/$moduleId`.
- Keeps Core shell routes as `Login`, `Dashboard` and `Settings`.

### `app/shared/src/commonMain/kotlin/com/ideasdeveloper/idc/app/features/dashboard/ui/DashboardScreen.kt`

Renders the dashboard after login.

- Shows Empresa for `server_owner` and `business_owner`.
- Reads `session.enabledModules` and renders those modules dynamically.
- Maps known module IDs:
  - `clientes`
  - `hostpot`
  - `crm`
- Navigates all modules through `NavRoute.Module(moduleId)`.

### `app/shared/src/commonMain/kotlin/com/ideasdeveloper/idc/app/features/company/ui/CompanyProvisioningScreen.kt`

Provides the current Empresa administration UI.

- Allows `server_owner` to create a company with:
  - company code;
  - company name;
  - business owner username;
  - business owner password;
  - business owner password confirmation in the client form.
- Calls `POST /companies`.
- Loads existing companies through `GET /companies`.
- Shows module state per company.
- Shows PostgreSQL diagnostic details returned by protected server-owner provisioning routes when database provisioning fails.
- Allows enabling/disabling optional modules through
  `PUT /companies/{code}/modules/{moduleId}`.
- Keeps `clientes` visible as locked because it is the base customer module.

### `app/shared/src/commonMain/kotlin/com/ideasdeveloper/idc/app/features/company/data/*`

Contains client DTOs and HTTP client code for Empresa.

- `CompanyApi.kt`
  - `createCompany(...)` calls `POST /companies`.
  - `listCompanies(...)` calls `GET /companies`.
  - `updateModule(...)` calls
    `PUT /companies/{companyCode}/modules/{moduleId}`.
- `CreateCompanyRequest.kt`
  - Payload for company creation.
- `CreateCompanyResponse.kt`
  - Response after successful provisioning.
- `CompanySummaryResponse.kt`
  - Company list and module state response.
- `CompanyErrorResponse.kt`
  - Error response from company endpoints.

### `app/shared/src/commonMain/kotlin/com/ideasdeveloper/idc/app/features/modules/*`

Provides the generic module client flow.

- `ModuleApi.kt` calls `/modules/{moduleId}/metadata`.
- `ModuleDefinition.kt` mirrors the server metadata contract.
- `ServerDrivenModuleScreen.kt` renders module title, description and view list from server metadata.

### `app/shared/src/commonMain/kotlin/com/ideasdeveloper/idc/app/features/auth/data/LoginResponse.kt`

Represents login success on the client.

- Includes `enabledModules`.
- Company sessions use this field to decide which module cards to show in the
  dashboard.

### `app/shared/src/*Main/kotlin/.../PersistentSessionStore.*.kt`

Persists login sessions per platform.

Updated platform files:

- Android
- JVM/Desktop
- JS/Web
- iOS

Each store now saves and restores `enabledModules` so persisted sessions keep
module visibility after app restart.

## Backend Files

### `server/src/main/kotlin/com/ideasdeveloper/idc/server/app/Application.kt`

Wires the Ktor application.

- Installs JSON serialization.
- Registers login rate limiting.
- Registers admin/company route rate limiting.
- Creates `LoginDatabases`.
- Creates central `SessionService` for server-owner token validation.
- Wires company routes with:
  - `ServerOwnerAuthorizer`;
  - `CompanyProvisioningService`.
- Registers module metadata routes and each module-owned endpoint namespace.
- Builds `CompanyProvisioningConfig` from server config.

### `server/src/main/resources/application.conf`

Defines runtime config.

Current provisioning config:

- `provisioning.administrationJdbcUrl`
  - Env override: `PROVISIONING_DB_URL`.
  - Defaults to `DB_URL`.
- `provisioning.tenantJdbcUrlPrefix`
  - Env override: `TENANT_JDBC_URL_PREFIX`.
- `provisioning.migrationsRoot`
  - Env override: `MIGRATIONS_ROOT`.

The backend no longer uses separate `PROVISIONING_DB_USER` or
`PROVISIONING_DB_PASSWORD`. It uses the runtime DB connection and assumes the
PostgreSQL `server_owner` role only after validating the IdeasCore
`server_owner` token.

### `server/src/main/kotlin/com/ideasdeveloper/idc/server/company/api/CompanyRoutes.kt`

Defines company administration HTTP routes.

- `GET /companies`
  - Requires a valid `server_owner` token.
  - Returns companies and module state.
- `POST /companies`
  - Requires a valid `server_owner` token.
  - Creates/provisions a company.
- `PUT /companies/{code}/modules/{moduleId}`
  - Requires a valid `server_owner` token.
  - Enables or disables optional modules for a company.

All routes use `Authorization: Bearer <token>`.

### `server/src/main/kotlin/com/ideasdeveloper/idc/server/company/application/ServerOwnerAuthorizer.kt`

Validates server-owner authorization.

- Validates the session token with `SessionService`.
- Checks the user exists in central `server_owners`.
- Returns the PostgreSQL role name of the authenticated `server_owner`.

The returned PostgreSQL role is passed to provisioning code and used with
`SET ROLE`.

### `server/src/main/kotlin/com/ideasdeveloper/idc/server/modules/*`

Defines the server module contract and registry.

- `ServerModule.kt` declares module metadata and optional route installation.
- `ModuleRoutes.kt` exposes `/modules`, `/modules/{moduleId}/metadata` and installs module-owned routes.
- Current modules are bundled at build time. GitHub/package download and hot installation are planned architecture, not implemented behavior.

### `server/src/main/kotlin/com/ideasdeveloper/idc/server/company/application/CompanyProvisioningService.kt`

Owns company provisioning and module administration.

Main responsibilities:

- Validate company code, company name, business owner username, password and
  colors.
- Use the runtime connection and `SET ROLE <server_owner>` after token
  validation.
- Create tenant database.
- Create `business_owner` PostgreSQL login role.
- Grant tenant DB connection to runtime and business owner roles.
- Apply tenant migrations:
  - Core users;
  - Core sessions;
  - business owner singleton;
  - customers;
  - tenant modules.
- Seed the business owner in `application_users` and `business_owner`.
- Grant runtime permissions on tenant tables.
- Insert company into central `companies`.
- Insert audit records into `provisioning_audit_log`.
- Register the tenant connection in `LoginDatabases`.
- List companies and module states.
- Enable or disable optional modules.

Module rules:

- `clientes` is locked and always enabled.
- `hostpot` is optional.
- `crm` is optional.

### `server/src/main/kotlin/com/ideasdeveloper/idc/server/company/application/CompanyProvisioningConfig.kt`

Holds provisioning config:

- administration JDBC URL;
- runtime DB user/password;
- tenant JDBC URL prefix;
- migrations root.

### `server/src/main/kotlin/com/ideasdeveloper/idc/server/company/api/*.kt`

Contains backend DTOs:

- `CreateCompanyRequest`
- `CreateCompanyResponse`
- `CompanySummaryResponse`
- `CompanyModuleResponse`
- `UpdateCompanyModuleRequest`
- `CompanyErrorResponse`

### `server/src/main/kotlin/com/ideasdeveloper/idc/server/auth/infrastructure/database/LoginDatabases.kt`

Resolves login scope.

- Server login uses the central database.
- Company login uses central `companies.code` to resolve the tenant database.
- Tenant pools can be preloaded from `TENANT_DATABASES_JSON`.
- Newly created tenants are registered at runtime with `registerTenant(...)`.
- After a restart, tenants can be reconstructed from central `companies.database_name`, `TENANT_JDBC_URL_PREFIX` and the server runtime DB credentials.
- Company login reads enabled modules from tenant `tenant_modules`.
- Login response includes `enabledModules`.

### `server/src/main/kotlin/com/ideasdeveloper/idc/server/auth/domain/LoginSuccessResponse.kt`

Backend login success DTO.

- Includes `enabledModules`.
- Server sessions return an empty list.
- Company sessions return enabled tenant modules.

## Database Migrations

### `database/core/migrations/V001__create_application_users.sql`

Creates `application_users`.

Used in both central and tenant databases.

### `database/core/migrations/V002__create_application_sessions.sql`

Creates `application_sessions`.

Used in both central and tenant databases.

### `database/control/migrations/V001__create_server_registry.sql`

Creates central control-plane tables:

- `server_owners`
- `companies`

### `database/control/migrations/V002__create_provisioning_audit_log.sql`

Creates central audit table:

- `provisioning_audit_log`

Used to track:

- company creation;
- module enable;
- module disable.

### `database/tenant/migrations/V001__create_business_owner.sql`

Creates singleton `business_owner`.

Each tenant database has exactly one business owner row.

### `database/tenant/migrations/V002__create_customers.sql`

Creates shared customer identity structures:

- `customers`
- `customer_field_definitions`

This belongs to tenant Core because all modules share Customer/Prospect
identity.

### `database/tenant/migrations/V003__create_tenant_modules.sql`

Creates `tenant_modules`.

Initial rows:

- `clientes`: enabled;
- `hostpot`: disabled;
- `crm`: disabled.

## Module Scaffolds

### `modules/clientes`

Core module scaffold for the shared customer surface.

- `ClientesModule.id = "clientes"`
- Enabled by default in new tenant DBs.
- Locked from disable in backend module administration.

### `modules/hostpot`

Existing planned scaffold.

- Registered in tenant module seed as `hostpot`.
- Disabled by default.
- Can be enabled/disabled by `server_owner`.
- The generic runtime screen loads this module metadata from the server; functional module behavior is still pending.

### `modules/crm`

Existing planned scaffold.

- Registered in tenant module seed as `crm`.
- Disabled by default.
- Can be enabled/disabled by `server_owner`.
- The generic runtime screen loads this module metadata from the server; functional module behavior is still pending.

## Installer

### `scripts/ubuntu/install.sh`

Updated to support company provisioning.

Important changes:

- Verifies the new control migration exists:
  - `database/control/migrations/V002__create_provisioning_audit_log.sql`
- Applies that migration during install.
- Creates PostgreSQL `server_owner` with:
  - `CREATEDB`;
  - `CREATEROLE`;
  - no `SUPERUSER`.
- Grants the `server_owner` role to runtime `db_user`.
- Grants runtime access to:
  - central identity tables;
  - `companies`;
  - `application_sessions`;
  - `provisioning_audit_log`.
- Writes:
  - `TENANT_JDBC_URL_PREFIX`;
  - `MIGRATIONS_ROOT`.

This is what allows:

1. runtime server receives a `server_owner` token;
2. backend validates the token;
3. backend runs `SET ROLE server_owner`;
4. backend creates tenant DBs and business owner roles.

## Security Model

The app token is not a PostgreSQL credential. PostgreSQL only sees the runtime
role and the assumed `server_owner` role.

The protection is enforced in this order:

1. HTTP route requires `Authorization: Bearer <token>`.
2. Backend validates the session token against central `application_sessions`.
3. Backend verifies the user exists in central `server_owners`.
4. Backend uses that user's PostgreSQL role with `SET ROLE`.
5. Provisioning executes under the PostgreSQL `server_owner` role.

Current hardening:

- `server_owner` is not `SUPERUSER`.
- Business owners are created with `NOCREATEDB` and `NOCREATEROLE`.
- Company code and PostgreSQL role names are strictly validated.
- SQL identifiers are quoted.
- Provisioning actions are audited.
- Admin routes have rate limiting.
- `clientes` cannot be disabled.

Known production hardening still recommended:

- Replace broad `CREATEDB`/`CREATEROLE` with PostgreSQL `SECURITY DEFINER`
  functions for only the required provisioning operations.
- Add more detailed audit metadata such as request IP and user agent.
- Add tests around rollback/partial provisioning failures.
- Add admin UI confirmation for destructive operations once uninstall/delete
  exists.

## Current Validation Status

No Gradle build has been run in this workspace because Java/JDK is not
available in `PATH` and `JAVA_HOME` is not set to a valid JDK.

Static checks performed:

- Confirmed no module-specific Compose routes remain for `hostpot` or `crm`.
- Confirmed the old `getString("moduleId")` Navigation issue is gone.
- Confirmed no `PROVISIONING_DB_USER` or `PROVISIONING_DB_PASSWORD` references
  remain.
- Confirmed installer writes `TENANT_JDBC_URL_PREFIX` and `MIGRATIONS_ROOT`.

## Manual Test Flow

1. Run a clean install with the updated `install.sh`.
2. Log in as `server_owner`.
3. Open Empresa.
4. Create a company.
5. Log out.
6. Log in with:
   - business owner username;
   - business owner password;
   - company code.
7. Confirm the dashboard shows `Clientes`.
8. Log back in as `server_owner`.
9. Open Empresa.
10. Enable `CRM` or `Hostpot` for the company.
11. Log back in to that company.
12. Confirm enabled modules appear in the dashboard.
