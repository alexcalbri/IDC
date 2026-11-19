# Membresia Module

Status: in development (marked with `.development`; not released to `master`). Version 0.2.0.

Front desk, plans, memberships, visits and guest passes for a coworking space or club. It replaces the
"Esc Members & Memberships (PMS)" board in Monday: the team registers members, sells and renews memberships,
checks people in at reception (tablet friendly), and gets warned before a membership expires. While a
membership is current, the member can get WiFi through the Hotspot module.

Members are **customers of Clientes**; this module never keeps its own copy of the customer. Renewals are
manual and nothing is charged: the amount is only a record.

## Data

Tenant migrations `V001__create_membresia.sql` and `V002` (import support: the `import_key` columns the import engine
uses to stay idempotent, see "Import (extensions)"), each in one transaction. `V001` creates:

| Table | Purpose |
| --- | --- |
| `membresia_settings` | Single row: currency (`USD`), days of warning before expiry (`expiring_days`, default 7, 1 to 60) and the list of sources (`Referido`, `Walk-in`, `Sitio web`, `Otro`). |
| `membresia_plans` | Plans: name (unique ignoring case), price, duration in days, optional included visits (empty = unlimited), guest passes per period, `wifi_access`, active, order. A plan with memberships is deactivated, not deleted. |
| `membresia_memberships` | One row per period sold. Copies the plan name, included visits and guest passes at sale time, so editing a plan never changes memberships already sold. `renewed_from` links a renewal to the previous period. |
| `membresia_visits` | Each check-in: `kind` is `miembro` or `invitado` (with `guest_name`), `override` marks "register anyway". One member visit per membership per day (partial unique index), so a double tap is harmless. |
| `membresia_active_members` | View used as a contract with other modules (see below). |

A duplicated company receives the plans and settings (`duplicatedSettings`); members, memberships and visits stay behind.

## States and rules

Stored status (`status`): `programada`, `activa`, `vencida`, `cancelada`. The status the team **sees** is computed
from the dates, so it is right even when the daily job has not run yet (`MembresiaRules.displayStatus`):

| Shown | When |
| --- | --- |
| Cancelada | Cancelled; it never comes back (to continue, create a new membership). |
| Vencida | `ends_on` is before today. |
| Programada | `starts_on` is after today. |
| Por vencer | Current and it ends within `expiring_days` days. |
| Activa | Current otherwise. |

- `ends_on = starts_on + duration_days - 1` (last day inclusive).
- Creating a membership with past dates is allowed (born `vencida`); future start is `programada`.
- Renewal starts the day after the current `ends_on`, or today if it already ended. A membership can be renewed once and a cancelled one cannot be renewed.
- Member check-in is valid when the membership is Activa or Por vencer and has visits left. Otherwise reception gets a warning and the visit is registered only with "register anyway" (`override`, marked in the record). A cancelled membership never registers a visit, not even with override.
- Guest pass: needs a current membership and passes left (atomic update, so two receptions cannot spend the last pass twice). No override.
- Validation: names up to 80 characters, notes up to 4000, amount from 0 to 99 999 999.99 with 2 decimals, currency of 3 uppercase letters, source one of the configured ones or empty.
- Revenue: sum of `amount` of non-cancelled memberships by month of `starts_on` (12 months) and by plan.

## Permissions

| Permission | Allows |
| --- | --- |
| `membresia.view` | Reception, search, member status, lists and levels. |
| `membresia.visits` | Register member visits and guest passes (including "register anyway"). |
| `membresia.manage` | Create, renew, edit and cancel memberships; see revenue. |
| `membresia.plans` | Create and edit plans; change currency, warning days and sources. |
| `membresia.import` | Run imports of memberships from external sources (see "Import (extensions)"), screens and agent tools. |

Customers are read with the Clientes visibility rule (own or shared, not archived); `customer_code` and custom
fields are never shown.

## Screens

Native UI (`/modules/membresia/ui/{screen}`, home `recepcion`), shown according to permissions:

- **Recepcion** (view): quick search with suggestions, results with status, today's metrics. Built for tablet and phone.
- **Miembro** (view): big status, plan, dates, days left, unit, host, visits and guest passes left; buttons to register a visit or a guest, renew, edit, cancel and open the customer in Clientes; the customer's memberships and last 20 visits.
- **Miembros** (view): table filtered by status.
- **Por nivel** (view): active memberships by plan.
- **Visitas** (view): today's visits.
- **Nueva membresia** (manage): existing customer or a new one (reuses the customer with the same email), plan, start, amount, source, host, unit, notes.
- **Planes** (plans), **Ajustes** (plans), **Ingresos** (manage, chart and tables).

Routes: `GET /modules/membresia/ui/{screen}` returns the screen; actions are `POST /modules/membresia/ui/{action}` and
answer the next screen with a notice; `GET /modules/membresia/ui/suggest/members` feeds the search suggestions.

## Daily job

`membresia-diario` runs every day at 07:00 (each step is guarded, bounded and idempotent):

1. `programada` to `activa` when it started.
2. `activa` to `vencida` when it ended, with a history event.
3. Expiry warning: for current memberships ending in the next `expiring_days` days and not yet warned, notifies the host (if still active), the business owners and the users with `membresia.manage`. Title "Membresia por vencer"; it opens the member screen. Each membership is warned once; a renewal starts clean. The member is **not** emailed in 0.1.0.
4. Hotspot: registers the source and syncs the passes.

## Customer history

Events written to the customer's history in Clientes (`module_id = membresia`, link to the member screen):
`membresia_alta`, `membresia_renovada`, `membresia_cancelada`, `membresia_vencida`. Visits are not written (too many).
Writing the same event twice leaves one entry; a failure here never breaks the operation.

## Notifications

Only the daily expiry warning, sent to the team (see above) through the Core notifications.

## Hotspot integration

Uses the access-pass contract of the Hotspot module (its migration V004: `hotspot_pass_sources`, `hotspot_access_passes`).
It only acts when the company has Hotspot installed, and a Hotspot failure is logged and never breaks a membership change.

To turn it on:

1. Hotspot > Integraciones: enable **Membresias** (the source `membresia` is registered by this module on the first sync).
2. Hotspot > Redes WiFi: create or edit the network and choose the **Codigo de cliente** access type with the source `membresia`.
3. In the plan, keep **WiFi** on.

The member types their customer code. While the membership is current (rule `periodo`, from `starts_on` 00:00 to the
day after `ends_on` 00:00, server time zone) the pass is valid. Cancelling, expiring (also when the end date is moved to the
past), or a plan without WiFi removes the pass. The pass is synced when creating, renewing, editing and cancelling, and by the
daily job, which pages through every scheduled or active membership and deletes `membresia` passes that no longer match a
current membership (so a failed delete is repaired the next day).

Memberships and visits reference `customers` with `ON DELETE RESTRICT`: Clientes archives customers instead of deleting them,
and the sales history must not disappear with a customer.

Note: today Hotspot customer-code networks can only be configured and used while the **CRM integration is enabled** in
Hotspot (a Hotspot requirement, not of this module).

## WiFi code

The member screen shows the member's WiFi code (the customer code from Clientes), read only, to the users who can record
visits or manage memberships (`membresia.visits` or `membresia.manage`), so reception can tell it to the member. The member types it at the Hotspot portal, exactly like a CRM
guest, and gets in while the membership is current. It is the member's WiFi credential, so it is shown only on that
screen, never in lists or search results, and agent tools never return it.

## Import (extensions)

Memberships can be brought from other systems without the module knowing them. An extension registers a
`MembresiaImportSource` in `MembresiaImportSources` (read at the moment it is needed, since extensions are linked after the
module is built). The contract, in short: the source has an id and a name, reads its external records and gives each one a
stable `import_key`; it may also offer agent tools (`mcpTools`), which must require `membresia.import`.

The import engine is idempotent: each customer, plan and membership created by an import stores its `import_key` (V002), so
running the same import again updates or skips what already exists and never duplicates it. Customers are matched by email
among the visible ones before creating new ones. Running an import needs `membresia.import`.

The first source is the extension `membresia_monday` (the Esc Members board in Monday). Once the migration is done the
extension can be uninstalled: the imported data stays in the module.

## Contract for events: `membresia_active_members`

View for other modules (for example a future `events` module) to know who is a member today:

```sql
SELECT customer_id, membership_id, plan_id, plan_name, starts_on, ends_on FROM membresia_active_members;
```

Other modules check it exists first with `to_regclass('public.membresia_active_members')`. The member price is decided by the other module.

## Sensitive fields live in Clientes

Document or passport, date of birth and emergency contact are **not stored here**. The business owner creates them as custom
fields in Clientes (Clientes > Campos personalizados, or the `clientes_create_customer_field` agent tool, for example
`documento`, `fecha_nacimiento`, `contacto_emergencia`). The member screen has "Abrir ficha del cliente". Reception never sees
those fields nor the `customer_code` (it is the WiFi credential).

## AI agent tools (MCP)

Tools are named `membresia_<name>`; each respects the user's live permissions and none takes or returns credentials,
`customer_code` or custom fields. The agent receives the module's instructions: `record_visit` warns and only registers with
`override=true` after the user confirms, and sensitive data lives in Clientes. Import sources can add their own tools
(permission `membresia.import`); the list is read on every request, so installing an extension adds them.

| Tool | Permission | Description |
| --- | --- | --- |
| `search_members` | `membresia.view` (read only) | Finds customers with a membership by name, email or phone, with their current membership. |
| `get_member_status` | `membresia.view` (read only) | Current membership, dates, days left, visits and guest passes left, previous memberships. |
| `list_plans` | `membresia.view` (read only) | Active plans with price, duration, included visits, guest passes and WiFi. |
| `list_expiring` | `membresia.view` (read only) | Memberships ending in the next days (1 to 60; default the module's warning days). |
| `record_visit` | `membresia.visits` | Registers today's visit; warns without registering when expired or out of visits (`override=true` to register anyway); with `guest_name` uses a guest pass. |
| `create_membership` | `membresia.manage` | Creates a membership for an existing customer and an active plan. |
| `renew_membership` | `membresia.manage` | Renews a membership, optionally with another plan, start or amount. |
| `revenue_summary` | `membresia.manage` (read only) | Revenue by month and by plan for the last 1 to 24 months. |

## Pending

- Public sign-up form on the web (the team registers members at reception).
- Email to the member before the membership expires.
- Pause of a membership.

## Changelog

- 0.2.0: import of memberships through extensions (`MembresiaImportSource`, idempotent by `import_key`, V002, permission `membresia.import`, extension `membresia_monday`); WiFi code on the member screen; agent tools list is dynamic so extensions can add tools.
- 0.1.0: first version: plans, memberships, visits and guest passes, reception, expiry warnings to the team, customer history, revenue, Hotspot passes, `membresia_active_members` and agent tools.
