---
description: Operational and architectural guidance for working on the
  open-source IdeasCore (IDC) Kotlin Multiplatform project. Use when
  inspecting, implementing, refactoring, testing, documenting,
  configuring, or self-hosting IdeasCore.
name: ideascore-project
---

# IdeasCore / IDC --- Project Skill

## Purpose

This skill teaches Codex/agents how to work safely and consistently on
**IdeasCore (IDC)**.

IdeasCore is an evolving open-source, modular business platform built
with Kotlin Multiplatform, Compose Multiplatform, Ktor and PostgreSQL.

This skill is **not** the source of truth for the exact current
repository state. Before implementing a change, inspect the repository.

When information conflicts, use this priority:

1.  Current explicit user instruction.
2.  Current repository state.
3.  Explicitly approved architecture documented in `ARCHITECTURE.md`.
4.  This skill.
5.  Historical summaries, examples and old conversations.

Examples are not requirements.

------------------------------------------------------------------------

# 1. Repository Inspection First

Before modifying code, inspect the current state.

At minimum:

``` bash
git status
git branch --show-current
git log --oneline -5
git diff
```

Inspect `settings.gradle.kts`, relevant `build.gradle.kts` files and the
code affected by the task.

When branch relationships matter:

``` bash
git branch -vv
git log --graph --decorate --oneline --all -20
```

Never assume a commit hash, dependency version, bug, module or endpoint
described in this skill is still current.

------------------------------------------------------------------------

# 2. Project Direction

IdeasCore is:

-   open source;
-   modular and extensible;
-   customer-centric;
-   multi-tenant;
-   server-driven with thin clients;
-   intended for managed hosting and self-hosting;
-   capable of integrating with external services.

Current technology direction:

-   Kotlin Multiplatform;
-   Compose Multiplatform;
-   Ktor server;
-   PostgreSQL;
-   REST/JSON where appropriate.

Do not replace these architectural choices without explicit approval.

------------------------------------------------------------------------

# 3. Client / Server Separation

## KMP client

The client is responsible primarily for:

-   UI;
-   navigation;
-   presentation state;
-   ViewModels;
-   API communication;
-   shared client models/DTOs where appropriate.

The client must not connect directly to PostgreSQL.

Never expose database credentials, provider secrets, JWT signing secrets
or other server secrets to KMP clients.

## Ktor server

The server owns:

-   authentication;
-   authorization;
-   tenant resolution;
-   database access;
-   business rules;
-   validation;
-   module availability enforcement;
-   third-party integration orchestration;
-   audit/security-sensitive operations.

Client-side visibility is never authorization.

------------------------------------------------------------------------

# 4. Customer-Centric Core

IdeasCore revolves around a shared **Customer / Prospect** identity.

A customer may originate from any authorized module, integration, import
or future feature.

Modules must reference the shared customer instead of creating unrelated
customer copies.

Conceptually:

``` text
                 Customer
                    |
      +-------------+-------------+
      |             |             |
    Module A      Module B      Module C
      |                           |
      +-------- Future modules ---+
```

Customer creation and identity resolution should be provided through
shared Core/server functionality.

The exact deduplication rules are not fixed by this skill.

------------------------------------------------------------------------

# 5. Flexible Customer Information

The Customer domain requires a small stable core plus
tenant-configurable fields.

Names such as `name`, `email` and `phone` have been discussed as
examples of possible minimum fields. They are not automatically final
mandatory fields.

Preferred data direction:

-   stable/high-value identity and query fields: relational columns;
-   tenant-defined flexible attributes: PostgreSQL `JSONB`;
-   field definitions: relational metadata describing key, label, type,
    validation, required state, order, options and active state.

Do not run `ALTER TABLE` for every custom field.

Do not put all business data in JSONB.

Use relational tables for entities with identity, lifecycle, history,
transactions, relationships or referential-integrity requirements.

Field removal should normally be non-destructive first
(disable/archive). Permanent deletion of data is a separate destructive
operation.

------------------------------------------------------------------------

# 6. Core vs Optional Modules

IdeasCore Core contains genuinely cross-cutting capabilities.

Conceptually:

``` text
Core
├── tenant context
├── shared customer identity
├── configurable customer information
├── authentication / authorization
├── module infrastructure
├── audit infrastructure
└── shared contracts/events
```

Functional modules are optional.

Previously discussed names such as CRM, Hotspot, Memberships and Hotel
are **examples of modules and interaction patterns**, not the complete
product catalog.

IdeasCore must allow future modules without redesigning the Customer
domain.

Core must not depend on optional module implementation details.

------------------------------------------------------------------------

# 7. Module Ownership

Each module owns its business-specific behavior and data.

A module may:

-   find a customer;
-   originate a customer through Core;
-   reference a customer;
-   enrich customer-related information;
-   maintain module-specific state/history;
-   expose UI/server functionality;
-   define permissions;
-   own migrations;
-   publish or react to domain events;
-   use integration contracts.

Do not place arbitrary module-specific state in the shared Customer
record.

Do not duplicate Customer identity per module.

------------------------------------------------------------------------

# 8. Repository Organization for Modules

The target conceptual organization is:

``` text
modules/
├── module-a/
├── module-b/
├── module-c/
└── future-module/
```

A module may contain:

-   domain logic;
-   server code;
-   shared/client code;
-   migrations;
-   permissions;
-   configuration;
-   tests;
-   event handlers;
-   integration contracts.

Exact Gradle project boundaries must be introduced deliberately. Do not
perform a mass repository restructure unless requested or approved.

------------------------------------------------------------------------

# 9. PostgreSQL and Tenant Databases

Preferred database engine: **PostgreSQL**.

Current tenant isolation direction:

**one PostgreSQL database per tenant/company.**

Each tenant database should contain:

1.  required IdeasCore Core structures;
2.  only the structures of modules installed for that tenant.

A tenant must not automatically receive tables for every available
module.

Example only:

``` text
Tenant A DB
├── Core
├── Module A tables
└── Module C tables

Tenant B DB
├── Core
└── Module B tables
```

Do not infer exact table names or schemas from this example.

------------------------------------------------------------------------

# 10. Module-Owned Migrations

Each optional module should own its database migrations.

Conceptually:

``` text
database/
├── core/
│   └── migrations/
└── modules/
    ├── module-a/
    │   └── migrations/
    └── module-b/
        └── migrations/
```

Installing a module applies that module's migrations to the selected
tenant database.

Do not maintain a monolithic schema that creates tables for modules a
tenant does not use.

Use the migration tooling actually adopted by the repository. If none
exists, propose an appropriate tool and obtain approval before making it
foundational.

Migrations must be versioned, repeatable where appropriate, observable
and safe.

------------------------------------------------------------------------

# 11. Platform Control Plane

IdeasCore requires a platform-level control plane logically separate
from tenant business data.

It may manage:

-   tenant registry;
-   module catalog;
-   hosting mode;
-   plans/subscriptions;
-   module entitlements;
-   installed modules;
-   enabled modules;
-   installed module versions;
-   tenant database connection metadata;
-   support/commercial metadata where appropriate.

These are responsibilities, not mandatory table names.

Do not store tenant customer/business records in the control-plane
database.

------------------------------------------------------------------------

# 12. Module Lifecycle

Treat these as distinct concepts:

``` text
AVAILABLE
   ↓
ENTITLED
   ↓
INSTALLED
   ↓
ENABLED
```

-   **Available:** module exists in the platform/repository.
-   **Entitled:** tenant is authorized to use it under the applicable
    managed/support arrangement.
-   **Installed:** module migrations/components are provisioned for the
    tenant.
-   **Enabled:** module is currently usable.

For fully self-managed open-source installations, commercial entitlement
enforcement may not apply.

Do not embed commercial restrictions inside module business logic unless
explicitly required.

------------------------------------------------------------------------

# 13. Open-Source and Commercial Model

IdeasCore source code and available modules may be public on GitHub.

Current business direction:

### Managed hosting

Project owner hosts the installation and may charge for: - hosting; -
selected modules; - support/service.

### Self-hosted with paid support

Customer hosts IdeasCore. Commercial support may be priced by supported
modules. No hosting charge from the project owner.

### Self-managed open source

Users may deploy and manage the open-source code themselves according to
the project's license.

Keep commercial service policy separate from functional module logic.

Do not implement artificial source-code restrictions merely because
managed services are commercially billed.

------------------------------------------------------------------------

# 14. Enable, Disable and Uninstall

Disabling a module is not the same as uninstalling it.

## Disable

Normally:

-   preserve tables;
-   preserve data;
-   hide/block functionality;
-   enforce disabled state server-side.

## Uninstall

Potentially destructive.

It may remove module-specific structures/data only through an explicit
administrative workflow with safeguards.

Never drop module tables merely because a module is disabled or
temporarily not entitled.

------------------------------------------------------------------------

# 15. Module Dependencies

Modules may explicitly depend on:

-   Core capabilities;
-   another module;
-   a minimum compatible module version.

Dependencies must be explicit and validated before installation/upgrade.

Avoid circular dependencies.

Sharing Customer identity is not by itself a reason for Module A to
depend directly on Module B.

------------------------------------------------------------------------

# 16. External Integrations

Third-party integrations are not functional modules.

Provider-specific code should live separately, conceptually:

``` text
integrations/
├── cloudbeds/
├── provider-b/
└── future-provider/
```

Cloudbeds is a current example of a PMS integration.

A functional module should prefer a provider-neutral contract where
substitution is a real requirement.

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

Do not spread provider-specific HTTP/API code throughout business
modules.

------------------------------------------------------------------------

# 17. External Identity

IdeasCore owns its internal IDs.

Never make a third-party provider ID the primary identity of a Core
entity.

Maintain explicit mappings such as:

``` text
IdeasCore Customer
├── Cloudbeds guest reference
├── Provider B reference
└── future external references
```

Mappings must preserve tenant, provider and resource context.

------------------------------------------------------------------------

# 18. Cross-Module Communication

Independent modules may need to react to events produced by Core or
other modules.

A lightweight internal domain-event mechanism is appropriate.

Examples only:

``` text
CustomerCreated
CustomerUpdated
OpportunityWon
ReservationCreated
MembershipExpired
```

Events describe domain facts, not provider-specific commands.

Do not introduce Kafka, RabbitMQ or another distributed broker unless
scale/deployment requirements justify it.

------------------------------------------------------------------------

# 19. External Side Effects and Reliability

Do not tightly couple a generic internal state transition to an
irreversible third-party action unless that is an explicit business
rule.

Example architecture:

``` text
CRM opportunity won
        ↓
domain event
        ↓
Hotel workflow
        ↓
explicit reservation operation
        ↓
ReservationProvider
        ↓
Cloudbeds
```

External operations must account for:

-   timeouts;
-   retries;
-   idempotency;
-   failure state;
-   auditability;
-   synchronization state.

For important external side effects, consider a transactional outbox or
equivalent reliable mechanism.

------------------------------------------------------------------------

# 20. Security

Never:

-   commit passwords;
-   commit JWT secrets;
-   hardcode production credentials;
-   expose PostgreSQL credentials to clients;
-   expose integration secrets to clients;
-   log passwords/tokens unnecessarily;
-   trust tenant identity solely because a client supplied it;
-   use UI visibility as authorization;
-   concatenate arbitrary user input into SQL.

Use environment variables or the repository's established secret
mechanism.

Authentication, authorization, tenant isolation and module entitlement
checks are server responsibilities.

------------------------------------------------------------------------

# 21. Working Protocol

Local inspection and local implementation are allowed when the user asks
to implement/modify something.

Before changes:

``` bash
git status
git log --oneline -5
git diff
```

After changes:

``` bash
git status
git diff --stat
git diff
```

Preserve pre-existing user changes.

Avoid unrelated refactors.

Make the smallest coherent change that solves the requested problem.

------------------------------------------------------------------------

# 22. Remote Git Operations

Remote writes require explicit user approval.

Do not perform without explicit permission:

-   `git commit`;
-   `git push`;
-   remote merge;
-   history-rewriting rebase;
-   pull request creation/merge;
-   issue creation/modification;
-   branch deletion;
-   tag/release creation.

A request to "implement" authorizes local implementation and testing,
not publishing.

Never use destructive commands such as `git reset --hard`,
`git clean -fd`, `git push --force` or `git branch -D` without explicit
approval.

------------------------------------------------------------------------

# 23. Build and Validation

Use the repository Gradle wrapper.

Inspect available tasks before assuming historical commands still apply.

For substantial changes:

1.  compile affected modules;
2.  run relevant tests;
3.  inspect failures;
4.  fix regressions caused by the change;
5.  report exactly what was tested.

Never claim a build/test passed unless it actually ran successfully.

------------------------------------------------------------------------

# 24. Documentation Maintenance --- Mandatory

The repository should maintain a human-readable project document,
normally `ARCHITECTURE.md`, describing:

-   what IdeasCore is;
-   current architecture;
-   repository structure;
-   Core/Customer model;
-   module system;
-   integration system;
-   tenant/control-plane model;
-   database/migration strategy;
-   current setup/self-host instructions;
-   important architectural decisions.

When a code change materially changes any documented behavior,
architecture, configuration, module lifecycle, environment variable,
deployment step or repository structure, **update the documentation in
the same local change**.

Before editing documentation:

1.  inspect the current document;
2.  inspect the code/configuration that is the source of truth;
3.  update only statements affected by the change;
4.  remove obsolete instructions;
5.  do not preserve historical information as if it were current.

Documentation must describe what the repository actually does, while
clearly labeling planned architecture that is not yet implemented.

Use explicit status labels when useful:

-   `IMPLEMENTED`
-   `APPROVED / NOT YET IMPLEMENTED`
-   `PLANNED`
-   `DEPRECATED`

Do not silently convert examples into requirements.

------------------------------------------------------------------------

# 25. Self-Hosting Documentation Maintenance

The repository should also maintain a self-hosting guide, normally
`SELF_HOSTING.md`.

Whenever changes affect installation or operation, verify whether this
guide must change.

Examples:

-   required JDK version;
-   PostgreSQL version/requirements;
-   environment variables;
-   ports;
-   Docker configuration;
-   migration commands;
-   tenant provisioning;
-   module installation;
-   secrets;
-   reverse proxy requirements;
-   backups;
-   upgrade procedures.

Never invent commands that have not been implemented.

If a self-hosting capability is planned but not implemented, label it
clearly as planned instead of presenting it as working.

------------------------------------------------------------------------

# 26. Requirement Classification

Before implementing an architectural request, classify information as:

### Requirement

Explicit behavior the user wants.

### Approved architecture

A design direction explicitly accepted by the user.

### Example

Illustration of desired behavior/interaction.

### Current implementation

Something verified in the repository.

### Planned

Approved direction not yet implemented.

This distinction is critical for IdeasCore.

------------------------------------------------------------------------

# 27. New Feature Classification

Before implementing a new feature, classify it as one or more of:

``` text
CORE
MODULE
INTEGRATION
CONTROL PLANE
```

Then ask:

1.  Does it belong to shared Customer?
2.  Is it a configurable Customer attribute?
3.  Is it module-specific?
4.  Does it require relational history/lifecycle?
5.  Does another module need a stable contract/event?
6.  Does it require tenant migrations?
7.  Does it affect module entitlement/installation?
8.  Does it require documentation updates?

------------------------------------------------------------------------

# 28. Core Principle

**Inspect first. Keep Customer identity shared. Keep module behavior
specialized. Keep integrations isolated. Keep tenant data isolated. Make
configuration flexible. Validate locally. Keep documentation
synchronized. Publish only with explicit permission.**
