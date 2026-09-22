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
- This bootstrap connects to one configured database. Shared Customer,
  authentication, tenant resolution, module provisioning and control-plane
  capabilities below remain planned, not implemented by this installer.

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

### Flexible fields

Customer information needs to be flexible between companies.

Recommended model:

-   stable, high-value fields as relational columns;
-   configurable tenant-specific attributes in PostgreSQL JSONB;
-   relational field-definition metadata for label, type, validation,
    order, options and active state.

A tenant should be able to add, reorder, disable and eventually remove
custom fields without a schema migration for every field.

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

Managed deployments require a platform-level control plane logically
separate from tenant business data.

It can track:

-   tenants;
-   module catalog;
-   hosting mode;
-   plans/subscriptions;
-   entitlements;
-   installed/enabled modules;
-   module versions;
-   tenant database metadata;
-   support/commercial metadata.

These are conceptual responsibilities, not final table names.

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

------------------------------------------------------------------------

## 14. Security Principles

-   no PostgreSQL credentials in clients;
-   no third-party API secrets in clients;
-   no production secrets committed to Git;
-   authorization enforced server-side;
-   tenant isolation enforced server-side;
-   module availability enforced server-side;
-   external identifiers never replace internal IDs;
-   SQL input must be safely parameterized/validated.

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
