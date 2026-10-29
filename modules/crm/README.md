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
| `crm.leads.manage` | Create and edit leads, move stages, add notes, send messages. |
| `crm.leads.delete` | Delete leads (the customer stays). |
| `crm.reception` | See and categorize leads without interest. |
| `crm.leads.all` | See leads of every interest. |
| `crm.assign` | Choose a lead's responsible user; approve or discard suggestions. |
| `crm.settings` | Configure stages, interests, mail, templates, anti-spam and team notifications. |

Users that can own leads are active users that are business owner or hold any
`crm.*` permission. Server owners using the server workspace act without a
company user, so their changes are logged as "Dueno del servidor" and they
cannot be a lead's responsible user. Only users with `crm.assign` or
`crm.leads.all` change the responsible user of a lead.

## Inbox and channels (V002)

Every channel (forms, email, and the channel integrations) hands contacts to
`CrmInbox.receive`:

1. Email typos are fixed (`gmial.com`, `hotmail.con`...) and the phone is
   normalized to `+<digits>` (8 local digits get +505, 10 digits shaped like
   USA/Canada get +1, `00` becomes `+`).
2. The person is found by channel identity (`crm_identities`, e.g. a Messenger
   PSID), then email, then phone. When email and phone belong to different
   customers the lead is linked by email and flagged **Por revisar**; people
   are never merged automatically.
3. Anti-spam rules (`crm_spam_settings`) run for the channels they are enabled
   on, except for contacts that already have an open lead (optional). Blocked
   messages create nothing and are kept in the last-50 log.
4. The message joins the customer's open lead, or a new lead is created in the
   first open stage with its origin (`source`, e.g. "WhatsApp") and, when the
   source suggests one, a responsible user waiting in **Asignaciones**.
   Nothing is assigned automatically.

The lead detail shows the **Conversacion** (`crm_messages`, deduplicated by the
channel's message id) and replies through any available channel (`CrmChannels`;
today email).

## Email

- **Sending** (`crm_mail_settings`): the server SMTP or the company's own
  account. Replies and queued mail use it.
- **Templates** (`crm_email_templates`): subject and HTML or plain-text body
  with placeholders `{nombre}`, `{correo}`, `{telefono}`, `{codigo_cliente}`
  (the customer code, also the WiFi code of the hotspot), `{responsable}`,
  `{etapa}`, `{interes}`, `{checkin}`, `{checkout}`, `{valor}`, `{habitaciones}`,
  `{reserva}`. Values are HTML-escaped.
- **Reading** (IMAP, optional): job `imap` every 5 minutes reads up to 50 new
  messages of the configured folder (read-only; the first read starts from the
  newest message) and passes them to the inbox with the quoted history cut.

## Notifications

Users have no email in the core, so **Equipo** stores one per user
(`crm_user_settings`) with three options: every new lead (only users that see
all leads), leads assigned to them, and a daily digest of their follow-ups due
(at the company's digest hour, once a day). Mail goes through `crm_outbox`,
sent by job `outbox` every minute with up to 5 attempts.

## Jobs

| Job | Interval | Work |
| --- | --- | --- |
| `outbox` | 1 min | Send queued mail. |
| `digest` | 15 min | Daily digest once the digest hour has passed. |
| `imap` | 5 min | Read the company mailbox. |
| `encuentra24` | 5 min | Import Encuentra24 leads. |

## Meta: WhatsApp, Messenger and Instagram (V003)

**Configuracion > Integraciones** with one Meta app:

- **App Secret** (validates `X-Hub-Signature-256` of every call), **verify
  token** and the **system user token** of Meta Business (the only token typed;
  needs `whatsapp_business_messaging`, `whatsapp_business_management`,
  `pages_messaging`, `instagram_manage_messages`). Secrets are masked.
- **Conectar pagina** reads the page token (`GET /me/accounts`, it does not
  expire) and the linked Instagram account; with several pages type the page ID.
- **Conectar WhatsApp** subscribes the app to the WhatsApp Business Account
  (`POST /{waba}/subscribed_apps`) and lists its numbers; one number is saved,
  several are offered to pick.
- Callback URL (one for all): `https://<server>/modules/crm/hooks/<company>/meta`.
  WhatsApp fields `messages` and `smb_message_echoes`; page fields `messages`
  and `standby`; Instagram `messages`.

Incoming: WhatsApp messages create or update the lead by phone (`+wa_id`,
profile name); Messenger / Instagram by PSID / IGSID identity with the name read
from the Graph API. Media, locations and contacts are stored as readable text;
reactions, page echoes, deleted messages and the company's own Instagram account
are ignored; repeated deliveries are deduplicated by message id. Coexistence
echoes (replies from the WhatsApp Business app) are stored as outgoing messages
of the open lead and never create leads. A message with an encuentra24.com link
keeps "Encuentra24 (<channel>)" as origin. The answer to Meta is 200 even when
one message fails (logged); the last 30 events are shown.

Outgoing: WhatsApp, Messenger and Instagram appear as reply channels on the
lead. WhatsApp errors are explained (131047: outside the 24-hour window, only
approved templates; 190: token expired). Messenger / Instagram split long texts
(2000 / 1000 characters) and take thread control once when the Business Suite
inbox controls the conversation.

## Encuentra24 (V004)

**Integraciones > Encuentra24**: API key and company slug. Job `encuentra24`
every 5 minutes calls the Leads API (`/api.php/v1/crm/index`, `x-api-key`) from
one day before the last sync (7 days the first time), 200 per page following
`pagination.nextid`, up to 25 pages, oldest first. Each message, chat or call
goes to the inbox with channel `encuentra24`, origin "Encuentra24", the listing
title and id, the chat link and the original date; the Encuentra24 lead id is
the message id, so nothing is imported twice. Contacts without email and phone
are skipped. **Importar ahora** imports the history from a chosen date. The API
cannot reply or change states in Encuentra24.

## Not in this version

Automations (move by rule), the
WordPress / Real Estate 7 integration, Cloudbeds reservations, imports and
attachments come in the next versions.
