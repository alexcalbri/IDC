# IdeasCore Extensions

An **extension** adds optional functionality to an installed module. Unlike
modules, which the server owner installs for the whole server, an extension is
installed per company by the company's business owner, and can be removed
without touching the module it extends.

Examples: a Monday.com import for the CRM module, a Meta (Messenger, Instagram,
WhatsApp) connector for the CRM module, a WordPress or Encuentra24 integration.

## Concepts

| Term | Meaning |
| --- | --- |
| Module | Optional or core feature installed by the server owner for the whole server. |
| Extension | Feature that depends on one installed module and is installed per company. |
| Business owner | Administers one company. Installs and removes extensions in that company. |
| Server owner | Administers the server. Installs modules, and can act in any company that has the required module. |
| Linked group | Two or more companies that share the same business owner and can share an extension configuration. |

## Rules

- The business owner installs extensions only for modules the server owner has
  already installed on the server.
- The server owner can install an extension in any company that has the module
  it requires.
- Each extension declares a minimum module version. It is not activated if the
  installed module is older.
- Removing a module does not remove its extensions' data automatically; the
  extension is marked `incompatible` or `disabled` until the module is back.

## Manifest: `extension.json`

Every extension has a manifest at its root.

```json
{
  "id": "monday_import",
  "module": "crm",
  "displayName": "Monday.com import",
  "description": "Imports leads from selected monday.com boards.",
  "version": "0.1.0",
  "minModuleVersion": "0.23.0",
  "source": {
    "type": "github",
    "url": "https://github.com/alexcalbri/idc/tree/<commit>/extensions/monday_import"
  },
  "permissions": [
    "network.outbound:api.monday.com",
    "secrets.write",
    "crm.leads.create",
    "crm.fields.create"
  ],
  "settings": [
    { "key": "api_token", "secret": true, "required": true }
  ],
  "migrations": ["migrations/V001__create_monday_import_state.sql"],
  "uninstall": {
    "options": [
      { "id": "crm_fields", "label": "Keep CRM fields created by this extension", "default": "ask" },
      { "id": "import_history", "label": "Keep import history", "default": "delete" }
    ]
  }
}
```

`source.type` is `bundled`, `github`, `zip` or `url`. Only `github` requires `source.url` pinned to a 40-hex
commit. A package installed from an uploaded ZIP (`zip`) or from an https URL (`url`) can carry any
informative `source.url`; the server owner installs it with `POST /server/extensions/upload` (multipart `id`
+ `file`) or `POST /server/extensions/from-url` (`{id, url}`), which store and inspect the ZIP and return a
`stagingToken` and the package hash, then `POST /server/extensions/staged/{token}/authorize-unsigned` (unsigned
only) and `POST /server/extensions/staged/{token}/install`. The same gate applies as for GitHub: Ed25519
signature or the owner's authorization of the package hash, manifest validation, `ext_<id>__*` migrations and
no downgrades. URL downloads are https-only (port 443), pinned to a validated public IP (no private, local or
link-local ranges, redirects re-validated) and limited to 50 MB. These four ZIP/URL routes are disabled by
default (403 `FEATURE_DISABLED`); enable them with the service variable `EXTENSION_UPLOAD_ENABLED=true` and restart. Authorizing an unsigned staged package requires the confirmation text to include the first 8 hex characters of its hash.

### Signature file: `extension.sig.json`

The hash and the signature cannot live inside the manifest they cover, so they
are stored next to it:

```json
{
  "packageSha256": "64_hex_characters",
  "signature": { "keyId": "ideascore-2026", "value": "base64" }
}
```

- `packageSha256` is the tree hash of the extension folder. Files are taken in
  order of their relative path (with `/`), leaving out `extension.sig.json`.
  For each one, `path\n` + the hex SHA-256 of its content + `\n` is appended,
  and the SHA-256 of the whole text is the hash.
- `signature` is Ed25519 over the 32 bytes of that hash. The public key
  `ideascore-2026` ships with the Core; the private key never enters the
  repository (`scripts/extensions/keygen.sh` and `sign.sh`).
- An unsigned package has no `signature` (see "Unsigned extensions").
- Extensions shipped inside a module (`"source": {"type": "bundled"}`) are
  covered by the module package and have no signature file. The CRM no longer
  bundles any: its Meta, Cloudbeds, Monday, Encuentra24 and WordPress
  integrations are each a separate `"source": {"type": "github"}`
  package (`extensions/meta/`, `extensions/cloudbeds/`, `extensions/monday/`,
  `extensions/encuentra24/`, `extensions/wordpress/`), versioned and deployed on
  its own, so updating one does not require reinstalling the CRM.
  - `wordpress` (`extensions/wordpress/`): public routes under
    `/modules/crm/extensions/wordpress/` (`GET idc-connector.zip`, the plugin built
    from the package resources, and `POST /{company}/hook`, signed with the shared
    secret). Tables: `ext_wordpress__settings` (site, sealed secret) and
    `ext_wordpress__agents`. It also provides the `leadspro` import source
    (CT Leads Pro) and the `import_leadspro` agent tool, read through the same
    site and secret.

  - `meta` (`extensions/meta/`): public routes under
    `/modules/crm/extensions/meta/` (the server app's `webhook`, `deauthorize`,
    `data-deletion`, `deletion`, the `connect/{company}` page, and the company's own
    `{company}/webhook`). Tables: `ext_meta__settings` (connection and sealed
    secrets), `ext_meta__log`, `ext_meta__server_app` (server workspace),
    `ext_meta__routes` (server workspace) and `ext_meta__tickets`. The CRM keeps
    the reply channels (WhatsApp, Messenger, Instagram) and reaches the extension
    through `MetaProvider` and `CrmHost`.

### Validation

- `id` matches `[a-z][a-z0-9_]{0,62}` and the folder name.
- `module` must be installed on the server and enabled in the company.
- `minModuleVersion` is compared numerically, the same way module versions are.
- `permissions` must come from the closed list. Any other value rejects the package.
- HTTP routes must live under `/modules/<module>/extensions/<id>/`.
- Migrations may only create or change tables owned by the extension, named
  `ext_<id>__*` (two underscores, so extension `a` cannot reach the tables of
  extension `a_b`). They must not change core tables such as `customers` or
  the lead tables. The validator is a strict allow list (no quoted identifiers,
  escapes, comments, `SELECT`, or function calls outside a short list), and the
  migrations run with statement and lock timeouts.
- `source.url` must point to a commit, never to a branch.

## Lifecycle

Extension states, stored per company in `tenant_extensions.status`:

```text
installed ⇄ enabled ⇄ disabled
    │          │
    └──────────┴──► incompatible   (module updated to a version below minModuleVersion)
any ─────────────► removed         (uninstall)
```

- **Install:** check the module and its version, verify the signature and hash,
  record the package, run the extension migrations, store the extension
  objects, and set the status to `installed`.
- **Enable:** requires every `required` setting. Routes start answering.
- **Disable:** routes answer with an error. No data is removed.
- **Incompatible:** set by the server when a module update leaves the extension
  below its minimum version. The business owner is notified. It can be enabled
  again after a compatible extension version is installed.
- **Uninstall:** see below.

## Ownership of data

- Extension settings are stored in `tenant_extension_settings`, inside the
  company database.
- Fields an extension creates are registered under its module (for example
  "CRM fields"), with the extension recorded as owner. They are not generic core
  fields.
- Every object an extension creates is recorded in `tenant_extension_objects`,
  so uninstall knows exactly what to touch.
- Leads and customers are never deleted by an uninstall.

## Uninstall

Uninstall is a three-step operation:

1. **Preview:** the server lists what will be deleted and what will be kept,
   with counts, for the company.
2. **Choice:** the user answers each option the extension declares in
   `uninstall.options`, using the declared default when no answer is given.
3. **Execute:** one transaction per company runs the extension's uninstall
   script and deletes its settings and registered objects.

Settings are always deleted. Everything else depends on the options the user
chose. An uninstall that fails leaves the extension as it was.

## Secrets

- Secrets are encrypted before they are stored. Each company has its own data
  key, which is itself encrypted with a server master key kept outside the
  database and readable only by the service user.
- Secrets are never returned by the API. Responses show a masked value.
- Extensions must not log secrets.

## Linked groups

- Companies in a group share one business owner. The server verifies this when
  the group is created and before every apply.
- A company belongs to at most one group. The relationship is symmetric.
- A configuration can be applied to selected companies in the group. Each
  company receives its own encrypted copy. Changes to the source are not
  propagated automatically; they must be applied again.
- Uninstalling can target one company or all selected companies in the group.
- If a company's business owner changes, the company is suspended in its group
  until the server owner reviews it. Suspended companies receive no applies.

### Applying to several companies

1. The actor selects the source, the target companies and the operation.
2. The server validates each target (membership, module, version, permissions,
   signature, business owner).
3. A preview shows the changes per company. Nothing is written yet.
4. Each target runs in its own transaction. Steps are idempotent: install if
   missing, run pending migrations, store the re-encrypted settings, enable.
5. Each target ends as `applied`, `failed` (with reason) or `skipped`. A failure
   does not roll back other targets.
6. Retries apply only to failed targets, inside the same operation.
7. The control database records one entry per operation and one per target.

## Meta extension

Meta is a package extension of the CRM module (`extensions/meta/`, tables `ext_meta__*`). Its settings are split in two:

- **App configuration** (app ID, app secret, verify token): can be shared within
  a linked group, as an encrypted copy per company. A company outside the group
  provides its own.
- **Connections** (Facebook page, Instagram account, WhatsApp number): always
  belong to one company. A page or number can be connected to only one company
  at a time, and webhook events are routed to that company.

The webhook route of the server's Meta app is
`/modules/crm/extensions/meta/webhook`; a company with its own app uses
`/modules/crm/extensions/meta/<company>/webhook`. The routes used before CRM
0.25.0 (`/modules/crm/meta/...` and `/modules/crm/hooks/<company>/meta`) no
longer answer, so the callback URL in the Meta console must be updated.

## Unsigned extensions

A signed package is verified against IdeasCore's signing key. A server owner can
authorize an unsigned package, but only:

- for one package, identified by its hash;
- with an explicit acknowledgement stored in the audit log with the actor, the
  hash and the source.

An unsigned extension runs with the same privileges as the server. The JVM does
not sandbox extension code, so the permission list is enforced by review, not by
the runtime.

**Trust boundary.** This applies to signed packages too: extension code runs in
the server process and can reach what the server reaches (other companies'
databases and the master key). The only real protection is the signature, or
the server owner's authorization of one exact hash after reviewing that commit.
Before authorizing, the app shows the commit, a link to it and the computed
tree hash, and the authorization is refused if the package changed since then.
Isolating extensions in their own process is future work.
