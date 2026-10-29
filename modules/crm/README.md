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
| `cloudbeds-welcome` | 15 min | Welcome emails before check-in. |
| `cloudbeds-sync` | 30 min | Reservation statuses; archive after check-out. |
| `import` | 1 min | Apply 300 rows of running imports. |

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

## WordPress / Real Estate 7 (V005)

Integration through the **IDC Connector** plugin
(`server/src/main/resources/modules/crm/wordpress/idc-connector/`), downloaded
from **Integraciones > WordPress** (`/modules/crm/wordpress/idc-connector.zip`,
built from the module resources). A plugin is used instead of database access:
MySQL is rarely reachable from outside the hosting, would give IDC the whole
WordPress database, and Real Estate 7 data (postmeta `_ct_*`, 11 taxonomies,
serialized values) and its forms only exist inside WordPress.

- **Security**: the CRM generates a secret (48 characters) that is pasted in the
  plugin. Both directions send `X-IDC-Timestamp` and
  `X-IDC-Signature = hex(HMAC-SHA256(secret, timestamp + "." + body))`;
  more than 5 minutes of difference is rejected.
- **IDC -> site** (`POST /wp-json/idc/v1/rpc`): `ping`, `search` (text or MLS,
  taxonomy filters, price range, 20 per page), `listing` (every field of the
  theme: price, MLS, size, lot, year, parking, taxonomies, agent, co-agent,
  brokerage, open house, rental fields, video, virtual tour, gallery and all the
  `_ct_*` meta), `taxonomies`, `agents`, `sold` (status Sold, `_ct_sold_date`,
  `_idc_sold_price`).
- **Site -> IDC** (`POST /modules/crm/hooks/<company>/wordpress`): CT Forms
  submissions (`ct_forms_after_send`, listing detected from the page), listing
  inquiries (`ajax-submit-listings`) and showing requests
  (`ajax-submit-book-listing-showing`) create or update leads (channel `form`,
  origin "Formulario - <property>") with the property linked and its agent as
  suggested responsible (agents mapped to IDC users in Integraciones).
  Favorites (`wpfp_favorites`), saved searches with alerts and property views
  of signed-in users go to the activity of the open lead and never create
  leads. Failed events are queued in WordPress and retried hourly.
- **reCAPTCHA v3** (optional, in the plugin): verified on the server before CT
  Forms handles the submission.

**Lead items** (`crm_lead_items`): properties of the site (`wordpress`), rooms
of a PMS (next version) or items typed by hand (`manual`), with status
Interesado, Ofrecida, Elegida, Descartada, Vendida or Reservada. The lead
detail searches the site (**Buscar propiedades**), links several properties and
**Cerrar venta** marks the property sold on the site and moves the lead to the
first won stage.

## Cloudbeds (V006)

**Integraciones > Cloudbeds**: API key of the property (scopes `read:room`,
`read:rate`, `read:reservation`, `write:reservation`), optional property ID,
the payment method sent to Cloudbeds (required by `postReservation`; payments
are handled in Cloudbeds), welcome email (template, days before check-in,
hour) and archiving after check-out.

- **Buscar habitaciones** (lead detail): free rooms for the lead's dates and
  guests: `getRooms` with dates (unassigned rooms; blocked and virtual ones
  dropped) joined with the cheapest available `totalRate` of each type from
  `getRatePlans`. A user assigns one or several rooms (`crm_lead_items`, source
  `cloudbeds`, status Elegida); the rooms of one reservation share dates.
- **Confirmar reserva**: guests per room, optional country (2 letters) and zip;
  the lead's email is required by Cloudbeds. Availability is checked again,
  `postReservation` creates one reservation with every room (`roomID` per
  room, `sendEmailConfirmation=false`, `thirdPartyIdentifier=idc-<lead>`),
  rooms become Reservada, the reservation is stored (`crm_reservations`) and
  the lead moves to the first won stage. Rooms left unassigned by Cloudbeds are
  reported. A lead with assigned rooms cannot be dragged to a won stage.
- **Welcome email** (job `cloudbeds-welcome`, 15 min): from the configured hour,
  reservations whose check-in is the configured days away or less get the
  welcome template (with `{codigo_cliente}` as WiFi password, `{checkin}`,
  `{checkout}`, `{habitaciones}`, `{reserva}`) once, through the outbox; it is
  also added to the conversation.
- **Status** (job `cloudbeds-sync`, 30 min): `getReservation` of open
  reservations; changes go to the activity and `checked_out` archives the lead
  (`crm_leads.archived_at`): archived leads leave the pipeline, lists and counts.

## Imports and bulk actions (V007)

**Configuracion > Importar** reads rows from:

- **monday** (GraphQL API with a personal token and the board IDs): every column
  as text, the group, the board (mapped to the interest), the creation date,
  updates and subitems as notes; the item id is the import key.
- **CT Leads Pro** through the IDC Connector plugin (0.2.0, action `leads`):
  name, email, phone, pipeline stage, assigned agent, source, tags, notes,
  messages and interested listings (linked as properties).
- **Files**: a CSV pasted as text or a CSV/XLSX from a public https URL (private
  addresses refused, 20 MB, 20,000 rows; XLSX without DTDs). CSV with comma,
  semicolon or tab, BOM and multiline cells. The monday Excel export layout
  (title, one line per group, repeated header) is recognized and the group goes
  to a "Grupo" column.

Each import (`crm_import_jobs`, rows in `crm_import_rows`) goes through:
**mapping** of every column to a CRM field (guessed from the header; monday and
Leads Pro names are known) and of the stage, owner and interest values to the
CRM ones (saved mappings in `crm_import_presets`); **preview** (new, updated,
to review, skipped, without writing); and the **import**, 300 rows a minute by
job `import`, each row committed on its own, cancelable.

Rules (from the CT Leads Extension importer): email typos fixed and phones
normalized; the person is matched by email or phone; when the email and the
phone belong to different customers the row stays **por revisar** and nothing
is merged; rows without name, email and phone are skipped; importing again
updates the lead with the same import key (`crm_leads.import_key`); historical
comments become notes with their original date and the same note is never
added twice; imports send no notifications.

**Acciones en lote**: the filtered leads (50 per page) with a checkbox each,
"select all visible", and move to a stage, assign a responsible user (with
`crm.assign`) or delete (with `crm.leads.delete`).

## Not in this version

Automations (move by rule) and attachments
come in the next versions.
