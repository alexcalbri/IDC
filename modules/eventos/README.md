# Eventos

Version 0.1.0 (in development). Events of the business: **public events** with capacity, waiting list, registrations,
attendance and member prices (workshops, music nights, talks), and **private events** with a client, an agreed amount and
payments (abonos) (weddings, celebrations, retreats). Out of scope in 0.1.0: public registration page, online payments and
tickets with codes: the team registers people and marks payment by hand.

## Data (company database)

Migration `V001__create_eventos.sql`:

- `eventos_settings`: one row (`id = 1`): currency (ISO, default `USD`), spaces and categories (text arrays).
- `eventos_events`: `kind` (`publico` or `privado`), title, description, category, series (free text to group events, for
  example "Escape Sessions"), space, `starts_at`/`ends_at`, `capacity` (public, NULL = no limit), host, responsible user (no
  foreign key: checked when written), `status`, `price_member`/`price_public` (public), `currency`, `client_customer_id`
  (private, customer of Clientes), `agreed_amount` and `guests` (private), notes, `import_key`, cancellation date and reason.
  Check constraints keep each kind with its own fields.
- `eventos_registrations`: customer, `status` (`inscrito`, `espera`, `cancelado`), `seats`, `price_kind` (`miembro`,
  `general`, `cortesia`), `amount` (total, editable), paid and payment date, attendance (who and when), `waitlisted_at` (order of
  the waiting list). A unique index allows one live (not cancelled) registration per customer and event.
- `eventos_payments`: payments (abonos) of private events: date, amount, method, note.

Event states: `borrador`, `publicado`, `lleno`, `realizado`, `cancelado`. A private event shows `publicado` as "Confirmado"
and `borrador` as "Tentativo".

## Permissions

| Permission | What it allows |
| --- | --- |
| `eventos.view` | Open the module, see upcoming and past events, the card of each one, registrations and capacity. |
| `eventos.register` | Register customers, cancel registrations, move people from the waiting list ("Pasar a inscrito"), mark payment and attendance. |
| `eventos.manage` | Create, edit, duplicate, publish and cancel events; prices; private events and their payments; settings. |
| `eventos.import` | Use the "Importar" screen and the import tools of the agents (needs an import extension). |

The business owner and the server owner hold everything. The extension permission is `eventos.import.write`
(create customers, events and payments).

## Rules (`EventosRules`, pure and tested)

- Occupied seats = sum of `seats` of registrations with state `inscrito`; available = capacity - occupied (no capacity = no limit).
- Registering needs a public event that is `publicado` or `lleno`. In ONE transaction with the event row locked
  (`SELECT ... FOR UPDATE`): if the seats fit the person is `inscrito`, otherwise `espera` (the answer says so). When the
  occupied seats reach the capacity the event becomes `lleno` and the responsible is told.
- Proposed price: `miembro` when the event has a member price and the customer has a current membership (see *Membership*),
  otherwise `general`; the amount is price x seats unless the user types one; `cortesia` is free.
- The waiting list keeps its order: while somebody is waiting, a new registration goes to the waiting list even if its seats fit.
- Cancelling a registration of a full event puts it back to `publicado`; if somebody is waiting the responsible is told
  "Se liberó una plaza". Nobody is promoted automatically: "Pasar a inscrito" requires that the seats fit
  ("no hay plazas; aumenta el cupo").
- The capacity cannot go below the occupied seats. Attendance: only registered people, event not cancelled nor draft, start
  date today or earlier (server time zone); marking is idempotent.
- State changes (`manage`): borrador -> publicado; publicado/lleno -> cancelado (reason required) or realizado; cancelado ->
  borrador.
- Private events: balance = agreed amount - sum of payments (negative = paid over, shown as is).
- Server-side validation: title 200, description and notes 10000, amounts 0..99999999.99 with 2 decimals, capacity
  1..100000, seats 1..100, end not before start, ISO currency.
- Duplicating an event copies everything except state (draft), dates, registrations, payments and import key.

## Customers

Customers are read with the visibility filter of Clientes (`origin_company_code = '' OR shared`, not archived), also in
searches and suggestions (ILIKE with escaped wildcards, 10 results). Registering can create a customer (type `eventos`) or
reuse the visible one with the same e-mail; Clientes is told with `access.customerChanged` after the commit.
`GET /modules/eventos/ui/suggest/customers` needs `eventos.register` or `eventos.manage`.

## Customer history

Written with `access.recordCustomerEvent` (module `eventos`, link to the `evento` screen, one record per `sourceRef`):

- `evento_inscripcion` "Inscripción: ..." (`registration:<id>:created`; also when moved from the waiting list, not while waiting).
- `evento_asistencia` "Asistió: ..." (`registration:<id>:attended`).
- `evento_privado` "Evento privado: ..." for the client when a private event is confirmed or created confirmed (`event:<id>:booked`).
- `evento_cancelado` for the client when a confirmed private event is cancelled (`event:<id>:cancelled`).

## Notices (Core notification center)

Sent after the commit, inside `runCatching` (a failure is logged), never to the author. Recipient: the responsible user of
the event when active; otherwise the active users with `eventos.manage` and the business owner. Target: screen `evento`.

- "Nueva inscripción" / "En lista de espera": "{customer} · {event} · {n} plaza(s)".
- "Evento lleno": "{event} · {capacity} plazas".
- "Se liberó una plaza": "{event}: {n} en espera".

## Membership (optional integration)

Without a compile dependency: `EventosMembership.activeMembership` reads the view `membresia_active_members` only when it
exists and Membresia is enabled for the company (inside a savepoint; any failure is logged and counts as "no membership").
It decides the proposed member price and the line "Miembro vigente: {plan} hasta {fecha}".

## Jobs

- `cierre` (1 h): published or full events that ended more than 6 hours ago become `realizado` (batch of 500, idempotent).
- `import-runner` (1 min, 40 s budget): moves forward the reading and applying of the active import sources.

## Code layout

`EventosService` (operations with permission, transaction and after-commit work; synchronous, callers use
`withContext(Dispatchers.IO)`), `EventosRepository` (parameterized JDBC), `EventosRules`, `EventosNotify`,
`EventosCustomerEvents`, `EventosMembership`, `EventosMcpTools`, `EventosImport` (contract) and `EventosImportApply`.
`EventosRoutes.install` is where the native screens are added; `respondEventos` opens the company database for a route.

## MCP tools

Agents act as the user that connected them: writes go through `EventosService` (same rules and permission checks as the
screens) with the user's live permissions. No tool handles credentials and none returns e-mail or phone of a customer.

| Tool | Permission | What it does |
| --- | --- | --- |
| `list_events` | `eventos.view` (read-only) | Events (`from`, `to`, `kind`, `q`, `series`; without dates, the upcoming ones), 100 at most. |
| `get_event` | `eventos.view` (read-only) | One event with capacity, occupied, waiting, attended; private: client, agreed amount, paid, balance. |
| `get_capacity` | `eventos.view` (read-only) | Capacity, occupied, available, waiting and whether it is full. |
| `list_registrations` | `eventos.view` (read-only) | Registrations of an event (name, seats, status, paid, attended). |
| `register_customer` | `eventos.register` | Registers a customer (`event_id`, `customer_id`, `seats`, `price_kind`, `amount`, `paid`, `note`); answers whether it is registered or waiting. |
| `cancel_registration` | `eventos.register` | Cancels a registration. |
| `record_attendance` | `eventos.register` | Marks or unmarks attendance (`attended`, default true). |
| `set_registration_paid` | `eventos.register` | Marks or unmarks the payment. |

The tools of the import extensions (permission `eventos.import`) are added on every request.

## Import

Eventos does not know where data comes from: import **extensions** (for example `eventos_monday`) implement
`EventosImportSource` (`EventosImport.kt`) and the module links them with `linkExtensions` (`supportsExtensions = true`).

- `EventosImportApply.summary` (pure preview): events, new customers, people matched by e-mail (any case) and not matched,
  events by space and problems (no key, no date, no title and client, repeated key).
- `EventosImportApply.apply`: one transaction per row, repeatable, sends no notices. Always a private event found by its
  `import_key`; the client is the one already linked to that event, else the visible customer with the same e-mail, else a new
  customer (`customer_type 'eventos'`). The responsible is matched by e-mail; without a match a line "Responsable en Monday: ..."
  goes at the end of the notes. Payments are inserted with `ON CONFLICT (import_key) DO NOTHING`. Long texts are cut with "…".
  A confirmed or done event writes `evento_privado` in the client's history. Importing again updates only the fields
  Monday owns (title, description, category, space, dates, client, amount, guests; the responsible only when matched) and
  never series, host, notes or an unmatched responsible; the state changes only from draft or towards cancelled.
- The screen `importar` and the agent tools of the sources are added by the screens and `EventosMcpTools.mcp`.

## Native screens and routes

`ModuleUiEntry("native", "/modules/eventos/ui", home = "proximos")`. `GET /modules/eventos/ui/{screen}` builds a screen;
`POST /modules/eventos/ui/{screen}?op=...` runs an operation (the form values travel as a JSON body) and answers the next
screen with a notice (errors of validation are a danger notice on the same screen, never a 500). Every route first calls
`requireModuleAccess` and then checks the exact permission (`EventosActions.viewPermission` / `requiredPermission`); the
service checks it again. Code: `EventosScreens` (pure builders), `EventosForms` and `EventosActions` (`EventosScreensParts.kt`:
forms and parsing), `EventosRoutes` (loading, permissions, operations).

| Screen | Permission | What it shows / operations |
| --- | --- | --- |
| `proximos` (home) | `view` | Metrics (next 30 days, registered this week, full), filters (`tipo`, `categoria`, `espacio`, `serie`, `q`) and a card per ISO week for the next 12 weeks (200 events at most). |
| `pasados` | `view` | Done and cancelled events, the last 100. |
| `evento?id=` | `view` | Card of the event. Public: "Inscribir" form (customer with suggestions + hidden `customer_id`, or the data of a new customer; seats; price type "Automático" proposes the member price when the customer has a current membership; amount; paid; note), registered people, waiting list, "Asistencia". Private: client (button to the Clientes card), guests, agreed amount, paid and balance, payments (abonos) with form and delete. Ops: `register` (register), `status` (manage; cancelling asks for a reason and confirmation), `payment-add`, `payment-delete` (manage; deleting asks for confirmation). |
| `inscripcion?id=` | `view` to open, `register` for its ops | Registration card (e-mail only for who registers). Ops: `paid`, `unpaid`, `attend`, `unattend`, `promote`, `cancel` (asks for confirmation). |
| `asistencia?id=` | `register` | Tablet check-in: big counter "{arrived}/{registered}" and a "Llegó"/"Quitar" button per registered person (`attend`, `unattend` with `registration`). |
| `nuevo?kind=publico\|privado` | `manage` | Event form (date + "HH:mm" hours in the server time zone; responsible = select of `EventosRepository.users`); op `create`. |
| `editar?id=` | `manage` | Same form for an existing event; op `save`. |
| `duplicar?id=` | `manage` | New date and hours; op `duplicate` (copy as draft). |
| `ajustes` | `manage` | Currency, spaces and categories (one per line); op `save`. |
| `importar?source=` | `import` | The screen of the active import source (or buttons to choose one); same behavior as Tareas. |

Navigation (`EventosScreens.navigation`): Próximos, Pasados, and by permission Nuevo evento, Nuevo privado, Ajustes (manage)
and Importar (import + an active source). The customer suggestions route is `GET /modules/eventos/ui/suggest/customers`.
Lists never show the e-mail or phone of a customer.

## Pending

- Tests against a real PostgreSQL tenant (repository SQL, migration V001, `FOR UPDATE` under concurrency, MCP handlers):
  today only the pure rules, import summary and declarations are tested.
- Calendar view: the native pieces have no calendar; upcoming events are shown by ISO week.
- Needs from the Core: `ModuleAccessService.isEnabled` (the integration with Membresia reads `tenant_modules` itself).
- "Today" for attendance and the job uses the server time zone; companies have no time zone yet.
- No recurrence in 0.1.0: use "Duplicar evento"; no BEO, menus or signed contracts for private events.

## Versions

- 0.1.0: first version.
