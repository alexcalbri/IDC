# CRM Module

Status: in development (`.development`).

Lead management on top of the shared customer identity. A lead is the
commercial side of a customer from Clientes: the person is never duplicated and
can later take part in other modules. The design follows the lead boards used
by tourism businesses (one board per line of business with stages such as New,
Answered, In communication, Booked, Cold, Old guest) and real-estate CRMs
(pipeline, activity timeline, stage history).

## Concepts

- **Lead** (`crm_leads`): customer, stage, optional interest, responsible user,
  source, language, original message, working details, dates the lead is
  interested in (booking, visit, move-in), estimated value and next follow-up.
- **Stages** (`crm_stages`): configurable pipeline. Each stage has a kind
  (`open`, `won`, `lost`), a tone (color meaning drawn by the app theme) and a
  position. Entering a won or lost stage sets `closed_at`; reopening clears it.
  New companies start with *Nuevo, Contactado, En seguimiento, Ganado,
  Perdido*. While a company has no leads it can switch to a template:
  *General*, *Turismo y hospedaje* or *Bienes raices*.
- **Activity** (`crm_activity`): notes and every change (creation, stage,
  responsible, interest, data), including the stage history (`from_stage_id`,
  `to_stage_id`).

## Interests (optional, per company)

Off by default. The company turns it on in *Configuracion* when leads are
split by line of business (Surf, Yoga, Eventos...) or by property type.

- The company defines its interests (`crm_interests`) and assigns interests to
  each user (`crm_user_interests`).
- A user sees the leads of their interests and the leads they own.
- Users with **Ver todos los leads** (and the business owner) see every lead
  and use the interest as a filter.
- Leads without interest wait in **Recepcion**, visible to users with
  **Recepcion de leads**, who categorize them.
- Deleting an interest sends its leads back to Recepcion.
- With interests off, every CRM user sees every lead and no interest is shown.

The visibility rule lives in `CrmScope` (`canSee` and its SQL twin
`sqlCondition`) and is applied by every lead query.

## Screens (native pieces)

| Screen | Permission | Content |
| --- | --- | --- |
| `pipeline` | `crm.view` | Metrics, filters and a kanban board (`UiBoard`); dragging a card or "Mover a..." changes its stage. |
| `leads` | `crm.view` | Searchable, filterable list (`UiList`) with pages of 50. |
| `reception` | `crm.reception` | Leads without interest (only with interests on). |
| `followups` | `crm.view` | Open leads with follow-up overdue, today or in the next 7 days. |
| `new` | `crm.leads.manage` | New lead. Email and phone are optional (Clientes V005). An existing customer with the same email, or without email the same phone, is reused unchanged; otherwise a `prospect` customer of type `crm` is created. |
| `lead` | `crm.view` | Detail form, notes and activity timeline (`UiTimeline`). Editing contact data updates the shared customer. |
| `settings` | `crm.settings` | Interests switch, interest catalog, interests per user, stages and templates. |

Routes: `GET /modules/crm/ui/{screen}` and `POST /modules/crm/ui/{screen}?op=...`
(`move`, `create`, `save`, `note`, `delete`, `interests`, `interestSave`,
`interestDelete`, `userInterests`, `stageSave`, `stageDelete`, `template`).

## Permissions

| Id | Meaning |
| --- | --- |
| `crm.view` | Use the CRM within the user's scope. |
| `crm.leads.manage` | Create and edit leads, move stages, add notes. |
| `crm.leads.delete` | Delete leads (the customer stays). |
| `crm.reception` | See and categorize leads without interest. |
| `crm.leads.all` | See leads of every interest. |
| `crm.settings` | Configure stages, interests and user interests. |

Users that can own leads are active users that are business owner or hold any
`crm.*` permission. Server owners using the server workspace act without a
company user, so their changes are logged as "Dueno del servidor" and they
cannot be a lead's responsible user.

## Not in this version

Automations (move by rule, emails before the booking date, notifications when a
follow-up arrives), webhooks, inbound channels (forms, WhatsApp, email) and
attachments are planned for a later version.
