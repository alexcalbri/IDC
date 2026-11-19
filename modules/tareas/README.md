# Tareas

Version 0.3.0 (in development). Replaces the Monday boards: tasks organized in boards and groups, with several
responsibles, an optional reviewer, checklist, comments, attachments, due reminders and list, kanban, agenda and
workload views.

## Data (company database)

Migrations `V001__create_tareas.sql` (the module) and `V002__import.sql` (`import_key` columns, see *Import*):

- `tareas_boards`, `tareas_board_members`, `tareas_groups`: boards (company-wide or private), their members and
  their groups ("Esta semana", a department...; named freely).
- `tareas_tasks`: title, detail, fixed status, priority, due date, reviewer, customer (no foreign key: checked when
  written), `source` + `external_ref` (see below), archive marker.
- `tareas_task_assignees`, `tareas_checklist`, `tareas_comments`, `tareas_activity`.
- `tareas_attachments`: files live in the company file storage (`ModuleFiles`); the row keeps `backend` + `ref`.
  `task_id` is NULL until the form that uploaded the file is saved; `delete_pending` rows wait for the cleanup job.
- `tareas_reminders`: due reminders already sent (one per task, user, kind and due date).

Statuses (fixed): `pending` Pendiente, `in_progress` En curso, `review` En revision, `done` Hecha, `cancelled`
Cancelada. Priorities: `urgent`, `high`, `medium` (default), `low`.

## Permissions

| Permission | What it allows |
| --- | --- |
| `tareas.view` | Open the module, see the visible boards and "Mis tareas". Responsibles and the reviewer of a task can, with only this permission, change its status, tick its checklist, comment and attach files in THAT task. |
| `tareas.manage` | Create, edit, assign and archive tasks in the boards the user sees. |
| `tareas.boards` | Create, edit and archive boards, groups and members; sees every board (private ones too). |
| `tareas.import` | Use the "Importar" screen and the import tools of the agents (needs an import extension). |

The business owner and the server owner hold everything.

## Visibility

Pure rules in `TareasScope` (tested), applied in SQL by `TareasRepository.list`, `workload` and the board list:

- A `company` board is seen by everyone with `tareas.view`; a `private` one only by its members, by whoever holds
  `tareas.boards` and by the owners. Archived boards are seen only by admins.
- The responsible or the reviewer of a task of a private board sees THAT task (Mis tareas, open it, comment), not the board.
- Editing needs `tareas.manage` and seeing the board; working on a task (status, checklist, comments, files) also
  belongs to its responsibles and reviewer.
- Leaving `review` (approve to Hecha, return to En curso) is for the reviewer or whoever edits the task. If a task has a
  reviewer, a worker asking for Hecha moves it to `review` instead (`TaskTransitions`).
- Assignable users: active users that are business owner or hold any `tareas.*` permission (`TareasRepository.users`).

## Notices (Core notification center)

Created with the module notifier inside `runCatching` (a failure is logged, never breaks the change); the author of the
change is never notified. Target: screen `task` with the task id.

- Assignment: "Te asignaron una tarea" to the added responsibles.
- To review: "Tarea por revisar" to the reviewer. Returned (review to En curso): "Tarea devuelta"; approved (review to
  Hecha): "Tarea aprobada", both to the responsibles.
- Comment: to responsibles and reviewer.

## Jobs

- `import-runner` (1 min, 40 s budget): moves forward the reading and applying of the active import sources (see *Import*).

- `due-reminders` (30 min): open tasks due tomorrow (`due_soon`) or overdue (`overdue`) notify each responsible once per
  due date (batch of 200; the row in `tareas_reminders` is claimed first with `ON CONFLICT DO NOTHING`).
- `attachments-cleanup` (1 h): deletes the stored file and the row of removed attachments and of uploads never linked
  to a task after 24 hours; a failure is logged per file and retried.

## Customer history

The module adds to the customer's history in Clientes through `CustomerTimeline` (`TareasCustomerTimeline`): it is computed
when read and nothing is written in `customer_history` (since 0.3.0; what older versions wrote stays there). Clientes lists
it only when the module is enabled for the company and the user holds `tareas.view`. The user sees only the tasks they may
see in the module (`TareasScope.seesTask`: admins and owners all; otherwise an active company or member board, or being a
responsible or the reviewer), and archived tasks never appear. Entries, newest first, link to the task (`UiOpenModule("tareas", "task")`):

- `task_created` ("Tarea: <titulo>", detail: board and status), at the creation date.
- `task_done` ("Tarea completada: <titulo>"), at the completion date.

The Monday imports write nothing there: imported tasks show up with their own dates. The customer is checked when it
is linked. `GET /modules/tareas/ui/suggest/customers` needs `tareas.manage` and `clientes.view`.

## Extension point for other modules

Another module (for example mantenimiento) can own tasks through `source` + `external_ref` (unique together):

```kotlin
TareasExternal.upsertTask(tenant, ExternalTaskRequest(
    source = "mantenimiento", externalRef = "order:123", boardId = board, groupName = "Esta semana",
    title = "...", detail = "...", priority = TaskPriority.High, dueDate = date, assignees = users))
```

`source` matches `[a-z][a-z0-9_]{0,39}` and is never `manual` or `agent` (reserved); `external_ref` has 1 to 200
characters. The task is created or, when it exists, its title, detail, priority, due date, group and responsibles are
updated (status, reviewer, customer and checklist stay). The caller owns the transaction and checks that the
assignees can use the module. Nobody uses it in 0.1.0.

## Import (0.2.0)

Tareas does not know where data comes from: import **extensions** (for example `tareas_monday`) implement
`TareasImportSource` (`TareasImport.kt`) and the module links them with `linkExtensions` (`supportsExtensions = true`;
the extension permission is `tareas.import.write`: create boards, groups, tasks, comments and attachments).

- **Screen** `importar` (GET and POST `?source=&op=`, permission `tareas.import` checked before the connection opens). The
  "Importar" item of the navigation shows only with that permission and at least one active source. With several sources
  the screen shows a button for each; the body is `source.screen(...)` and an operation is `source.action(...)`, which
  answers a `TareasImportReply(notice, error, params)` (the screen shown next gets `params`).
- **Job** `import-runner` calls `TareasImportSources.runPending(context, budget)`: each active source gets an equal share
  of what is left of the budget; one that fails is logged and the others continue. A source must be short, idempotent and
  log its errors.
- **Agent tools**: `TareasMcpTools.mcp` adds `TareasImportSources.all.flatMap { it.mcpTools }` on every request (permission
  `tareas.import`, no credentials).
- **Writing rows** (`TareasImportApply`): `summary` (pure preview: people matched by email without regard to case, people
  without user, problems such as an empty title), `ensureBoard(importKey, name, targetBoardId, actorId)` (target board if
  given, else the board already created for the key, else a new company-wide one), `apply` and `attachFile`.
  `apply` is one transaction per row, repeatable and sends no notices: the task is `source` + `external_ref = row.key`
  in the board (created or updated: title, detail, status, priority, due date, group, responsibles, reviewer). People
  without a user are noted at the end of the detail ("Responsables en Monday sin usuario: ..."). An open task due tomorrow
  or earlier gets its due reminders recorded as sent, so an import never floods notices. Checklist items and comments carry
  an `import_key` (unique per task): only the missing ones are added; comments keep the original author and date in the
  text. Over the limits (100 checklist items, 2000 comments) the extra ones are left out (logged). Long texts are cut with
  "…" (title 300, detail 20000, comment 5000, checklist 300); a row without title or identifier is skipped. Attachments are
  stored once per `import_key` (25 MB each, 100 per task).
- Importing again updates the tasks already imported and overwrites the local changes made to the imported fields. Importing into an existing board needs `tareas.manage` on it and a new board needs `tareas.boards`; the extension enforces both.
- Imported tasks write a "imported" activity row and nothing in the customer's history.
- When a company is duplicated the boards do not keep their `import_key`.

## Files

`POST /modules/tareas/ui/files` (25 MB, `tareas.view`) stores a pending upload; `GET /modules/tareas/ui/files/{id}`
needs to see the task (a pending upload only reaches its uploader). Linking happens when the task is saved and only for
files the same user uploaded.

## Screens

Native screens under `/modules/tareas/ui` (`GET /{screen}` and `POST /{screen}?op=...`, values in the JSON body; the
answer is the next screen with a notice). Everything needs `tareas.view`; `TareasScreens` builds them from loaded data
and `TareasActions` parses the forms. Navigation: Mis tareas, Tableros and, for who administers boards, Administrar.

- `mine`: counters (open, overdue, to review) and cards Vencidas, Hoy, Próximos 7 días, Más adelante and Sin fecha, with
  the tasks the user is responsible for plus the ones waiting for their review (200 at most).
- `boards`: a card per visible board (open and overdue); "Nuevo tablero" for who administers boards.
- `board?id=&view=list|kanban|agenda|workload&q=&assignee=&status=&due=`: filters and view selector.
  List: a card per group (200 tasks at most, with a warning). Kanban: one column per state, drag to change state (50 per
  column). Agenda: overdue, ISO weeks of the next 8 weeks, later and without date. Workload: open tasks per person.
- `task?id=`: form (title, detail, priority, due date, group, responsibles, reviewer, customer with suggestions only
  for who has `clientes.view`, attachments), read-only without `tareas.manage`; state buttons by role (start, finish or
  send to review, approve, return, reopen, cancel), checklist, comments and collapsed activity.
- `new-task?board=&group=`, `admin` and `board-edit?id=` (name, description, visibility, members; groups: add, rename,
  move up or down, archive).

Operations of `POST` (`op`): `move`, `save`, `create`, `status`, `files`, `archive`, `comment`, `check-add`,
`check-toggle`, `check-remove`, `board-save`, `board-archive`, `group-add`, `group-rename`, `group-up`, `group-down`,
`group-archive`. `tareas.manage` is asked for `create`, `save`, `archive` and `check-remove`, `tareas.boards` for the
board operations and `tareas.view` for the rest; `TareasService` applies the visibility rules afterwards.

## MCP tools

Agents act as the user that connected them: each tool uses the same `TareasScope` and `TareasService` rules as the
screens, with the user's live permissions. No tool handles credentials; files are never exposed (only name and size).
Arguments use `snake_case`; states and priorities are the fixed codes.

| Tool | Permission | What it does |
| --- | --- | --- |
| `list_boards` | `tareas.view` (read-only) | Visible boards with groups, visibility and open/overdue counts. |
| `list_people` | `tareas.view` (read-only) | Users that can be responsibles or reviewer (name, e-mail). |
| `list_tasks` | `tareas.view` (read-only) | Visible tasks; filters `board_id`, `assignee` ("me", id, name or e-mail), `status`, `due` (overdue/today/week/none), `query`, `include_done`, `limit` (1..200, default 50). |
| `get_task` | `tareas.view` (read-only) | One task with checklist items, last comments and activity, attachment names and what the user can do (`can`). |
| `create_task` | `tareas.manage` | Creates a task (`board_id`, `title`, `detail`, `group`, `priority`, `due_date`, `assignees`, `reviewer`, `customer_id`, `checklist`, `external_ref`). Source `agent`; with `external_ref` it does not duplicate (`created=false`). |
| `update_task` | `tareas.view`, and `tareas.manage` inside for fields other than the state | Changes only what is sent (empty `due_date`, `reviewer` or `customer_id` clears it; `assignees` replaces, kept when absent). `status` alone also works for a responsible or the reviewer. |
| `complete_task` | `tareas.view` | Sets Hecha; with a reviewer and a non-reviewer user the task goes to En revision. |
| `add_comment` | `tareas.view` | Comments a task the user can work on. |
| `set_checklist_item` | `tareas.view` | Ticks or unticks an item (`item_id` or `text`; an unknown text adds the item). |

Recommended flow to load minutes (acta): `list_boards`, `list_people`, then `create_task` per point with a stable
`external_ref` so repeating the load does not duplicate tasks.

## Pending

- Tests against a real PostgreSQL tenant (repository SQL, migration V001, MCP handlers): today only the
  pure rules, form parsing, screens and tool declarations are tested.
- Calendar view: the native pieces have no calendar; the board offers an "Agenda" view by ISO week
  instead. A `UiCalendar` piece in the Core contract would replace it.
- "Today" for reminders and overdue uses the server time zone; companies have no time zone yet.
- The server owner (no row in `application_users`) cannot attach files, and the columns that record
  who did something stay empty for them.
- A notification that fails with an SQL error inside the save transaction of a screen aborts that
  transaction (the save is rolled back, nothing is left half written).
- Recurring tasks (the future `mantenimiento` module, through `TareasExternal`), sprints and
  automations are out of scope for 0.1.0.
