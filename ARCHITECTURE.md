# IdeasCore (IDC) --- Architecture & Project Guide

> Status: **Architecture baseline / evolving project**
>
> This document explains how IdeasCore is intended to work. Sections
> should distinguish implemented behavior from
> approved-but-not-yet-implemented design as the repository evolves.

## 1. What is IdeasCore?

IdeasCore is an open-source, modular business platform built around a
shared Customer/Prospect identity.

Instead of designing a separate application for every business process,
IdeasCore provides a common Core and allows functional modules to add
specialized behavior around the same customer.

A customer can originate from any authorized module or integration and
can later participate in other modules without being duplicated.

``` text
                    IdeasCore
                        |
                       Core
                        |
                    Customer
                        |
          +-------------+-------------+
          |             |             |
       Module A      Module B      Module C
          |                           |
          +------ Future modules -----+
```

The names CRM, Hotspot, Memberships and Hotel are examples of modules
and interaction patterns. IdeasCore is not limited to them.

------------------------------------------------------------------------

## 2. Technology Direction

### Implemented server bootstrap

- `scripts/ubuntu/install.sh` provides an interactive first-install flow
  for Ubuntu 24.04/26.04: PostgreSQL, OpenJDK 21, Gradle distribution build,
  a dedicated Linux account, and a systemd service. End-to-end validation
  on a clean Ubuntu host is still pending; see `SELF_HOSTING.md`.
- The installer explicitly checks Ubuntu package states, installs missing
  backend dependencies including Nginx, and verifies their availability.
  An existing Certbot executable is reused; otherwise Certbot is installed.
- `-PserverOnly=true` builds the server and Core JVM sources without
  configuring client projects or requiring an Android SDK.
- The installer also builds `:app:webApp:jsBrowserDistribution` with
  `-PwebOnly=true`, using JS-only Core/shared build profiles without mobile
  SDKs. Gradle downloads its Node/Yarn tooling. Nginx serves the production
  files from `/opt/ideascore/web` at `/`, with SPA fallback to `index.html`;
  `/auth/`, `/companies` and `/modules` are proxied unchanged to Ktor. The web
  login pre-fills the current origin, allowing same-origin API calls without
  additional CORS rules.
  The internal Ktor `/` health response remains separate from the web app.
- Ktor starts through `EngineMain`, reads `application.conf`, and checks
  PostgreSQL with `SELECT 1` before installing the HTTP routes. The
  connection pool is closed when the application stops.
- `DB_PASSWORD` is mandatory. The installer stores deployment variables
  in a root-only file outside the repository.
- The installer configures Ktor on `127.0.0.1:8080` and connects it to local
  PostgreSQL on `127.0.0.1:5432` with the supplied database credentials;
  it does not prompt for an IP address or port.
- It requests a public domain and ACME email, validates the domain's A/AAAA
  addresses using a temporary HTTP token, and reuses or installs Certbot.
  Nginx serves HTTPS on port 443 with a Let's Encrypt certificate and
  redirects HTTP to HTTPS. A systemd timer handles renewal; Ktor stays
  on loopback. Real-domain issuance/renewal validation is still pending.
- This bootstrap connects the backend to one central database, provisions
  initial authentication identities and ownership records, and installs the
  current company-provisioning API. New company databases receive shared
  Customer/Prospect structures and the tenant module registry.

Current architectural direction:

-   Kotlin Multiplatform (KMP)
-   Compose Multiplatform
-   Ktor backend
-   PostgreSQL
-   REST/JSON where appropriate
-   one PostgreSQL database per tenant/company
-   thin clients; business/database logic on the server

Always verify exact versions in the repository before documenting or
deploying them.

------------------------------------------------------------------------

## 3. Core vs Modules

### Core

Core contains capabilities that are genuinely shared across the
platform, such as:

-   tenant context;
-   shared Customer identity;
-   configurable Customer information;
-   authentication and authorization infrastructure;
-   module infrastructure;
-   audit infrastructure;
-   shared contracts and domain events.

### Optional modules

Modules contain specialized business behavior.

Each module can own:

-   server logic;
-   UI/shared client code;
-   database migrations;
-   permissions;
-   configuration;
-   tests;
-   event handlers;
-   module-specific entities and history.

Optional modules depend on Core. Core should not depend on the
implementation details of optional modules.

------------------------------------------------------------------------

## 4. Customer-Centric Data Model

IdeasCore uses one shared Customer/Prospect identity inside a tenant.

A module should reference this identity instead of maintaining an
unrelated copy.

Customer creation should go through a shared Core service so that
identity resolution and future duplicate handling remain centralized.

The locked `clientes` module migration creates the shared `customers` table and
`customer_field_definitions` metadata in each company database. Functional
customer APIs and duplicate-resolution rules remain pending.

### Flexible fields

Customer information needs to be flexible between companies.

Recommended model:

-   stable, high-value fields as relational columns;
-   configurable tenant-specific attributes in PostgreSQL JSONB;
-   relational field-definition metadata for label, type, validation,
    order, options, control type and active state.

A tenant should be able to add, reorder, disable and eventually remove
custom fields without a schema migration for every field.

Field definitions are also the source for server-driven client rendering.
The server can describe a field as text, textarea, number, money, date,
datetime, email, phone, boolean, select/dropdown or multi-select, including
option values, display labels, defaults and validation rules. The client may
use these definitions to render generic forms and lists without a client
release for each tenant field change. The server remains authoritative for
validation and must reject invalid values even when a client-side form already
checked them.

Use relational tables---not JSONB---for entities with their own
identity, lifecycle, history, relationships, transactions or integrity
constraints.

------------------------------------------------------------------------

## 5. Tenant Isolation

IdeasCore's approved direction is one PostgreSQL database per
tenant/company.

``` text
IdeasCore Server
       |
  +----+----+
  |         |
Tenant A  Tenant B
   DB        DB
```

A tenant database contains:

1.  required Core structures;
2.  structures belonging only to modules installed for that tenant.

A company that does not have a module installed should not automatically
receive that module's tables.

------------------------------------------------------------------------

## 6. Module Installation Model

**APPROVED / NOT YET IMPLEMENTED: on-demand distribution.**

The initial installation downloads only Core and its required dependencies:
Customer/Prospect functionality, login/sessions, tenant administration,
authorization and module management. Core is not just the Customer table;
it includes the infrastructure needed to operate the server. Optional
module code and tables are excluded from this initial installation.

Module files are shared at server scope, while module installation and
data are scoped to each tenant database:

1. An authorized administrator selects a module for a registered company.
2. The server checks the module version, Core compatibility, dependencies
   and the company's authorization to install it.
3. The server downloads the trusted module package from the project's
   repository/distribution source only if that exact verified version is
   not already available locally. An incomplete or invalid download is
   not treated as an installed package.
4. The server applies outstanding module migrations only to the selected
   company's database and records their versions and installation status.
5. The module becomes enabled for that company only after successful
   provisioning. Retries must not repeat completed migrations.

For example, installing module A for company 1 downloads its files and
creates its tables in database 1. Installing the same version for company 2
reuses those files and creates tables only in database 2. File presence
alone never grants access or creates tables in another company's database.
Dependencies follow the same shared-files/per-company-schema rules.

Packages are identified by module ID and version, with integrity checks.
Updating a shared package must not silently migrate or enable other
companies; compatibility with each company's schema must be checked.
Shared files cannot be removed while another company needs them.

Client module delivery should be server-driven where practical. The Core app
ships a generic module container, navigation surface and reusable controls.
After login, it asks the server which modules are active for the company and
visible to the user, then renders menus, forms, lists and basic actions from
module metadata. Module metadata may include field definitions, dropdown and
multi-select options, permissions, routes and action descriptors. The client
must not treat hidden UI as authorization; protected server operations still
check session, tenant, role, permissions and module availability.

Downloading and executing new Kotlin/Compose code inside an already compiled
Android, iOS or desktop client is not the default architecture. Highly custom
client experiences should ship in normal client releases, while their
visibility and activation remain server-controlled. Web-specific bundle
loading may be evaluated separately if a real requirement appears.

The package format, release distribution and server module loading strategy
remain to be designed. The current installer still clones the repository and
builds the server; selective downloads and the module installer are not
implemented.

Modules own their migrations.

Conceptually:

``` text
database/
├── core/
│   └── migrations/
└── modules/
    ├── module-a/
    │   └── migrations/
    ├── module-b/
    │   └── migrations/
    └── future-module/
        └── migrations/
```

Installing a module applies that module's migrations to the tenant
database.

The module lifecycle distinguishes:

-   **Available** --- exists in IdeasCore;
-   **Entitled** --- tenant is authorized under the applicable
    managed/support model;
-   **Installed** --- structures/migrations are provisioned;
-   **Enabled** --- currently usable.

Disabling a module preserves its data by default. Uninstalling is a
separate, potentially destructive administrative operation.

------------------------------------------------------------------------

## 7. Control Plane

**APPROVED / NOT YET IMPLEMENTED**

Every IdeasCore server, including self-hosted installations, has a central
administration database logically separate from tenant business data.
It registers companies, their codes, database locations and active status
(the future subscription control point). It also holds server-owner identities
and their sessions, because these accounts do not belong to any company.
Company users, business ownership, permissions, sessions and module installation
state belong in each company's database, not in the central registry.
Shared module files/version inventory is server-level infrastructure; its
storage format remains to be designed. PostgreSQL login roles themselves
remain cluster-wide; application membership is local to each company.

### Server and business ownership

- `server_owner` is an IdeasCore permission level, not Ubuntu root or a
  PostgreSQL superuser. It can create companies and their databases,
  authorize/install/enable modules, and manage users and application roles
  across all companies registered on this IdeasCore server. Its scope does
  not include unrelated databases hosted in the same PostgreSQL instance.
- Each company has exactly one `business_owner`, enforced by the data
  model and provisioning workflow. This owner has full application control
  within that company, including its users and roles, but cannot access
  other companies or grant itself server-level privileges. It cannot enable
  modules that the server owner has not authorized for its company.
- Ownership transfer replaces the company's owner atomically; it must not
  leave an active company without an owner or with multiple owners.
- The server administration UI is visible only to `server_owner`. Ktor
  independently checks permissions and target-company scope on every
  administrative operation; hiding the UI is not authorization.
- Database/role creation and migrations use a separate internal
  provisioning identity. End-user PostgreSQL login roles do not receive
  superuser privileges just because they own a server or business in
  IdeasCore. Application roles are distinct from PostgreSQL SQL privileges.

### Initial provisioning and first client launch

The installer requests credentials only for the initial `server_owner`.
It creates the central administration database, required Core authentication
schemas, the company registry and the server-owner identity. It does not
create any company or tenant database. Companies are created from the app
while signed in as `server_owner`; that flow provisions the tenant database,
applies Core tenant migrations and assigns the first `business_owner`.
No default shared passwords or passwords committed to source are allowed.
Re-running provisioning must preserve existing identities and data rather
than reset owners.

The client will request and retain the server's HTTPS URL and company code,
then display login, with a separate server-administration option.
`POST /auth/login` accepts `username`, `password` and optional `companyCode`.
Omitting/nulling the company code selects server-owner login. Supplying a
code resolves an active company through the central registry, then checks
credentials and active membership in that company's database. Empty/invalid,
unknown or inactive codes are rejected, without fallback to server login.
Clients cannot supply arbitrary database destinations. Successful responses
include `scope`, `companyCode` and `role` for selecting the appropriate view;
these response fields are not substitutes for server-side authorization.

**Current provisioning implementation (clean-host validation pending):**
the installer creates one end-user PostgreSQL login for server ownership,
in addition to the runtime service role. The central database holds the
server owner, its sessions, `server_owners` and an initially empty
`companies` registry. `TENANT_DATABASES_JSON` starts as an empty list and can
preload existing tenant databases at server startup. The Empresa core module
now exposes a server-owner-only company creation API that provisions a tenant
database, applies Core and tenant migrations, seeds the `business_owner`, marks
`clientes` enabled and registers the tenant connection in the running server.
The initial PostgreSQL `server_owner` role is the provisioning identity: after
the backend validates a `server_owner` session token, it assumes that role for
the provisioning operation. The same Empresa API can list companies and enable
or disable optional modules such as `hostpot` and `crm`; `clientes` is a locked
base module. Administrative permission coverage is still narrow and registry
records alone do not implement all future permissions. The shared client
login is connected to the API, keeps the session in memory and navigates to
the existing dashboard on success. When the user selects "Mantener sesión
iniciada", the client persists the issued session token locally until its
server-provided expiration time; expired persisted sessions are discarded on
startup. The client persists the first successful server URL and company
code/server-administration choice locally so later launches do not prompt for
those values again. Separate administrative screens remain pending; both login
scopes currently share the dashboard. The browser client initially uses its
current origin as server URL when no saved configuration exists.
This is a fresh-install layout, not an automatic upgrade of an existing server.

The Empresa module is part of Core. It owns company creation, company
registry metadata, tenant provisioning and company identity. Company identity
includes the display name, logo URL and brand colors. Once a company identity
is selected or restored on the client, the same identity must be used across
login, dashboard and every module so the whole app reflects the active
company.

Tenant databases also include a `tenant_modules` registry. The `clientes`
Core module is seeded as enabled for new tenant databases so every company has
the shared Customer/Prospect surface available as a base module. The current
tenant seed also registers `hostpot` and `crm` as disabled optional modules so
the server owner can enable them per company.

Each database records executed scripts by module, version and checksum in
`schema_migrations`. `scripts/ubuntu/migrations.sh` executes schema changes and
their history record in one transaction, skips identical applied scripts and
rejects modified applied scripts. Each optional module will own its numbered
SQL scripts. The module download/upgrade workflow is still pending.

It can track:

-   tenants;
-   module catalog;
-   hosting mode;
-   plans/subscriptions;
-   entitlements;
-   tenant database metadata;
-   support/commercial metadata.

These are conceptual responsibilities, not final table names.
Company-local module installation/version records remain in the company database.

Tenant customer/business records do not belong in the control plane.

For self-managed open-source installations, commercial entitlement
enforcement may not apply.

------------------------------------------------------------------------

## 8. Open-Source and Commercial Operation

IdeasCore source code and modules may be available publicly on GitHub.

The commercial model is based on services rather than hiding module
source code.

### Managed hosting

The project owner hosts the installation and may charge for:

-   hosting;
-   selected modules;
-   support/service.

### Self-hosted with paid support

The customer hosts IdeasCore. Support may be charged according to the
modules being supported, without a hosting charge.

### Self-managed open source

A user may deploy and manage the open-source software according to its
license.

Functional module logic should remain separate from commercial service
enforcement.

------------------------------------------------------------------------

## 9. Integrations

Third-party integrations are separate from functional modules.

Target organization:

``` text
integrations/
├── cloudbeds/
├── provider-b/
└── future-provider/
```

Provider-specific API code should be isolated behind a
capability/adapter when substitution is useful.

Example:

``` text
Hotel Module
     |
     v
ReservationProvider
     ^
     |
Cloudbeds Adapter
```

IdeasCore owns its internal entity IDs. External IDs are mappings, not
primary identities.

------------------------------------------------------------------------

## 10. Example: CRM → Hotel → Cloudbeds

This is an architectural example, not a fixed workflow.

A prospect may exist in the shared Customer domain and participate in a
CRM-like module.

When an opportunity is concreted/won:

``` text
CRM
 |
 | OpportunityWon
 v
Domain Event
 |
 v
Hotel Module
 |
 | reservation workflow
 v
ReservationProvider
 |
 v
Cloudbeds Adapter
 |
 v
Cloudbeds API
```

The Hotel module can maintain hotel-specific customer information such
as:

-   reservation history;
-   stay history;
-   hotel preferences;
-   hotel-specific profile data;
-   references to external reservations/guests.

Moving a CRM stage should not automatically create an irreversible
third-party reservation unless that exact business rule has been
explicitly configured.

------------------------------------------------------------------------

## 11. Domain Events

Modules may communicate through lightweight domain events rather than
depending directly on each other's internals.

Examples:

-   CustomerCreated
-   CustomerUpdated
-   OpportunityWon
-   ReservationCreated
-   MembershipExpired

Event names and payloads are defined from real requirements.

Start with a simple in-process mechanism unless scale/deployment
requirements justify distributed messaging.

------------------------------------------------------------------------

## 12. Reliable External Operations

External APIs can fail.

Important side effects should support:

-   idempotency;
-   retry;
-   failure tracking;
-   auditability;
-   synchronization status.

For important asynchronous operations, a transactional outbox or
equivalent pattern should be considered.

------------------------------------------------------------------------

## 13. Repository Direction

Conceptual target:

``` text
IDC/
├── core/
├── modules/
│   ├── module-a/
│   ├── module-b/
│   └── ...
├── integrations/
│   ├── cloudbeds/
│   └── ...
├── app/
│   ├── shared/
│   ├── androidApp/
│   ├── desktopApp/
│   ├── webApp/
│   └── iosApp/
└── server/
```

This is a target organization, not permission to perform a mass
refactor. The repository should migrate deliberately as real features
are implemented.

### Implemented client/module layout

The shared client is migrating toward a feature-first structure:

- `app/shared/.../app/core/config` contains client-side server/company
  configuration and platform-specific persistence.
- `app/shared/.../app/core/company` contains the shared company identity
  model and platform-specific persistence for name, logo and brand colors.
- `app/shared/.../app/core/session` contains client session state and optional platform-specific session persistence.
- `app/shared/.../app/features/auth` contains login data, presentation
  state and UI.
- `app/shared/.../app/features/dashboard` contains the current dashboard UI.
- The client navigation shell uses a generic `module/{moduleId}` route for
  module screens. Module-specific client routes should not be added for each
  installed module; the dashboard and module container are intended to be fed
  by server-provided module metadata.

The current module scaffolds are `modules/empresa`, `modules/clientes`,
`modules/hostpot` and `modules/crm`. The server build includes the `clientes`,
`hostpot` and `crm` shared/server source directories and registers those
server modules in `ModuleRegistry`. The running server exposes `/modules`,
`/modules/{moduleId}/metadata` and each module-owned namespace under
`/modules/{moduleId}`. Downloading module packages from GitHub/distribution
and loading them on demand remains planned architecture, not implemented
behavior.

Empresa is the first Core module surfaced in the client dashboard. It is
always active for `server_owner` and `business_owner` sessions. For
`server_owner`, `module/empresa` opens the implemented company provisioning
screen. Other module routes use the generic server-driven module screen and
load display metadata from the backend.

The backend source is organized under `com.ideasdeveloper.idc.server`.
The Ktor entry point lives in `server.app`, database bootstrap in
`server.infrastructure.database`, and server authentication is split into
`auth.api`, `auth.application`, `auth.domain` and `auth.infrastructure`.
These packages are separate from the client login feature package.

------------------------------------------------------------------------

## 14. Security Principles

-   no embedded or persisted PostgreSQL service credentials in clients;
-   no third-party API secrets in clients;
-   no production secrets committed to Git;
-   authorization enforced server-side;
-   tenant isolation enforced server-side;
-   module availability enforced server-side;
-   external identifiers never replace internal IDs;
-   SQL input must be safely parameterized/validated.

### Authentication and database access

**APPROVED / NOT YET IMPLEMENTED**

IdeasCore authenticates people using PostgreSQL login roles, but executes
business queries through a dedicated service account for each tenant.
Authentication and the identity used for business queries are separate.

The approved flow is:

1. The client sends the user's PostgreSQL login name and password to Ktor
   over HTTPS. Clients never connect directly to PostgreSQL. The password
   is used transiently for login, not embedded in the application,
   persisted as a session credential, or included in logs.
2. Ktor resolves the allowed database endpoint and tenant using server-owned
   configuration and verified identity/tenant membership. A client cannot
   select an arbitrary JDBC URL, host or database to authenticate against.
   The central registry resolves active company codes; the selected company
   database defines its allowed users. Server identities are checked centrally.
3. Ktor opens a short-lived authentication connection with the submitted
   credentials. PostgreSQL must require password authentication for this
   connection (SCRAM-SHA-256); `trust` or another method that does not
   verify the submitted password cannot establish a valid application login.
4. Ktor verifies that the authenticated identity is active and authorized
   for IdeasCore and the selected tenant. Successful PostgreSQL connection
   alone is not sufficient application authorization. Service and
   administrative accounts are not automatically application users.
5. Ktor closes the authentication connection, stops retaining the password,
   and issues an application session tied to a stable user identity and
   authorized scope. Server administration sessions need not select a
   tenant; business requests must resolve and authorize a tenant explicitly.
   Current code issues opaque tokens and stores only their SHA-256 hashes,
   with configurable expiry. Sessions are stored in the database of their
   scope. Client transport and protected business routes remain pending;
   those routes must recheck company activity and never accept a token from
   another scope merely because its format is valid.
6. On every protected request, Ktor validates the session, tenant access,
   functional permissions and module availability. Business queries use
   the service account and connection pool for that tenant's database.

IdeasCore does not store end-user passwords or their hashes in its own
application tables. PostgreSQL manages those authentication credentials.
Application tables may still store user identity mappings, profiles,
tenant membership, permissions, sessions and audit records. These users
are distinct from the shared Customer/Prospect business entity.

Business queries run with the service account's SQL privileges, not with
the authenticated user's PostgreSQL privileges. Individual PostgreSQL
grants are not implicitly carried over from login. Ktor owns application
authorization and must record the acting user and tenant in audit events.

Disabling application access must revoke or invalidate the user's
application sessions. Disabling a PostgreSQL role or changing its password
does not by itself revoke an already issued IdeasCore session. The
mechanism for propagating such administrative changes remains to be designed.

Runtime service accounts must have only the required data-access privileges.
Database ownership, schema changes and migrations belong to the PostgreSQL
`server_owner` provisioning identity. Service credentials remain server-side;
the runtime role may assume the `server_owner` role only for operations gated
by a validated IdeasCore `server_owner` session.

**Current implementation:** `DatabaseFactory` has one configured Hikari
pool and verifies PostgreSQL connectivity on startup. New installations
point it at the central database, grant the runtime role read access to
identity tables, data access to sessions and registry write access for company
creation. Existing installations are not upgraded automatically.
Tenant pools may be preloaded through server-only `TENANT_DATABASES_JSON`; tenants
created through the Empresa API are added to the running resolver immediately, and
server restarts can reconstruct company tenant connections from the central
`companies.database_name` registry plus `TENANT_JDBC_URL_PREFIX`.
Local code includes `/auth/login`, `GET /companies`, `POST /companies`,
`PUT /companies/{code}/modules/{moduleId}`, `/modules`,
`/modules/{moduleId}/metadata`, active-user mapping, opaque session issuance,
session validation/revocation services and IP-based login/admin rate limits.
Login now selects the proper database and reports server/business ownership;
protected business operations and cross-company server administration remain
unimplemented. The revised installation and end-to-end login still require
clean-host validation. Do not interpret
the current database-owner role as the approved runtime permission model.

------------------------------------------------------------------------

## 15. Documentation Status

This document is expected to evolve with the repository.

When an architectural change is implemented:

1.  verify current code;
2.  update the affected section;
3.  label planned vs implemented behavior;
4.  remove obsolete instructions;
5.  keep examples clearly marked as examples.

`SKILL.md` instructs coding agents to maintain this document when
relevant changes are made.
