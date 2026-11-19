# Extension `membresia_monday` (1.0.0)

One-time migration of a monday.com members board (for example Esc "Members & Memberships") into the
**Membresia** module (0.2.0 or newer). Install it, import, check the result, then uninstall it: members,
memberships, visits, plans and Clientes fields stay; the token and the import history go away.

## Use

1. *Extensiones* (business owner): install **Importacion de membresias desde monday** for the company and enable it.
2. *Membresias > Importar* (permission `membresia.import`; the token needs the business owner or `membresia.plans`):
   save the monday API token (sealed with the company key, never shown again), load the boards and choose one.
3. Check the suggested column mapping (titles in Spanish or English): plan/tier, period (timeline) or start and end,
   amount, guest passes, source, host, unit/room, notes, email, phone, and the Clientes fields country, document or
   passport, date of birth and emergency contact. Groups named "Cancel..." import as cancelled.
4. *Leer tablero*: a background run reads the board page by page (resumable, at most 20 000 items). Subitems with a
   date become visits (guest visits when they name a guest).
5. Review the summary: members, plans that will be created (most common amount, median period length or 30 days),
   Clientes fields that will be created or skipped, and problems. *Aplicar* imports in batches.
6. Uninstall from *Extensiones*. Option **Historial de importaciones de monday** (default delete) removes the jobs.

## Rules

- Idempotent: every item is `monday:<itemId>` in `membresia_memberships.import_key` (subitems in
  `membresia_visits.import_key`). Importing again adds new items and new visits and never changes a membership
  that already exists (manual edits and cancellations win).
- Customers are matched by an existing import key, then by the email of a visible customer, else created.
  Clientes fields are only filled when empty; an existing field of an incompatible type is skipped and reported.
- Only one import runs at a time. Monday rate limits (429), complexity errors and 5xx are retried up to 5 times; an
  expired cursor restarts the read once.
- Personal data in the staging rows is cleared when a job finishes, fails or is cancelled.
- The token never reaches agents. MCP tools (permission `membresia.import`): `membresia_monday_list_boards`,
  `membresia_monday_read_board`, `membresia_monday_import_status` (counts and problem codes only) and
  `membresia_monday_apply` (needs `confirm_members`, dictated by the user).

## Data

`ext_membresia_monday__settings` (sealed token), `ext_membresia_monday__jobs` (state, cursor, counters, summary) and
`ext_membresia_monday__rows` (staged rows and their outcome). Migration `migrations/V001__settings.sql`.

## Jobs

The Core does not schedule `ServerExtension.jobs` yet, so the Membresia module job `membresia-import` (every minute)
calls `runPending` of every enabled import source with a 20 second budget.
