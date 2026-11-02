# Agentes IA (MCP)

Each user connects their own AI model or agent (Claude, ChatGPT, Cursor, a custom agent...) to
IdeasCore through the [Model Context Protocol](https://modelcontextprotocol.io). The agent acts on
behalf of that user and is **limited to the functions the user is allowed to use**.

## Rules

1. **One agent = one user.** Every token belongs to the user that created it. The agent never has a
   permission of its own: on every request the server reads the user's permissions *at that moment*.
   Removing a permission, deactivating the user or disabling a module takes effect on the agent's
   next call.
2. **The token can only narrow access.** When creating it, the user may limit it to some modules and
   to read-only tools. It can never add anything the user does not have.
3. **Tools apply the same rules as the screens.** Module tools run the module's own validations and
   visibility scopes (for example the CRM interests: an agent sees exactly the leads its user sees).
4. **Everything is recorded.** Every call (successful, failed or denied) is stored in
   `agent_activity` for 180 days; CRM changes appear in the lead activity as
   `usuario (agente <nombre>)`.
5. **Revocable at any time** by the user (own agents) or by holders of `agentes.manage` (any agent
   of the company).

## Connecting an agent

1. The server owner installs the module and the company enables it.
2. The business owner grants `agentes.connect` to the users that may connect agents.
3. The user opens **Agentes IA → Conectar un agente**, chooses modules, read-only and expiration,
   and copies the token (shown once).
4. The agent is configured with the endpoint and the token:

```text
URL:    https://<server>/modules/agentes/mcp
Header: Authorization: Bearer idcmcp.<company>.<secret>
```

Claude Code:

```bash
claude mcp add --transport http ideascore https://<server>/modules/agentes/mcp \
  --header "Authorization: Bearer idcmcp.<company>.<secret>"
```

Generic JSON configuration (Cursor, VS Code and other clients with HTTP + headers):

```json
{
  "mcpServers": {
    "ideascore": {
      "type": "http",
      "url": "https://<server>/modules/agentes/mcp",
      "headers": { "Authorization": "Bearer idcmcp.<company>.<secret>" }
    }
  }
}
```

### With OAuth (claude.ai and other connector-based apps)

No token to copy: add a custom connector with the URL `https://<server>/modules/agentes/mcp`
(claude.ai: *Settings → Connectors → Add custom connector*). The app discovers the authorization
server, registers itself, and opens IdeasCore's sign-in page, where the user enters company
(empty for the server owner), user and password, then chooses modules, read only and how long the
connection lasts. The connection then appears in **Mis agentes** like any other agent and can be
revoked there.

Requirements: HTTPS (claude.ai rejects plain HTTP) and the Nginx location
`/.well-known/oauth-` proxied to the backend. New installs get it from `install.sh`;
`update.sh` adds it to existing sites (a backup of the site file is kept next to it).

## Endpoint

- `POST /modules/agentes/mcp`: JSON-RPC 2.0, Streamable HTTP transport with JSON responses
  (protocol versions `2025-06-18`, `2025-03-26`, `2024-11-05`). Methods: `initialize`, `ping`,
  `tools/list`, `tools/call`; `resources/list` and `prompts/list` return empty lists.
- `GET`/`DELETE` answer 405: the server does not open event streams or keep sessions.
- 401 for missing, malformed, unknown, expired or revoked tokens, inactive users, users without
  `agentes.connect` and companies without the module enabled.
- 120 requests per minute per token (429 with `Retry-After`).
- Requests with an `Origin` header from another host are rejected (DNS rebinding / browsers).

The token is `idcmcp.<company>.<secret>`: the company travels in it, so the agent needs no other
header. Only its SHA-256 is stored.

## OAuth authorization server

Implements the MCP authorization spec (2025-06-18) with OAuth 2.1:

| Endpoint | Purpose |
|---|---|
| `GET /.well-known/oauth-protected-resource[/modules/agentes/mcp]` | Protected resource metadata (RFC 9728). The MCP endpoint's 401 points to it in `WWW-Authenticate: Bearer resource_metadata="..."`. |
| `GET /.well-known/oauth-authorization-server` | Authorization server metadata (RFC 8414); the issuer is the server origin. |
| `POST /modules/agentes/oauth/register` | Dynamic client registration (RFC 7591). Stateless: the client id carries the client metadata signed with HMAC; confidential clients get a secret derived from it. Redirect URIs must be HTTPS or loopback HTTP. |
| `GET/POST /modules/agentes/oauth/authorize` | Authorization code with mandatory PKCE S256. Sign-in page (rate limited per IP, no session is left behind) and consent page. `resource` (RFC 8707), when sent, must be the MCP endpoint. |
| `POST /modules/agentes/oauth/token` | `authorization_code` and `refresh_token` grants. Access tokens last 1 hour; refresh tokens rotate on every use and are bound to their client. A refresh fails (and the connection is revoked) when the user lost access. |
| `POST /modules/agentes/oauth/revoke` | Token revocation (RFC 7009), only for tokens issued to the calling client. |

The HMAC key is created on first use in `server_settings` (`agentes.oauth.signing_key`); deleting it
invalidates registered clients (they register again), not existing connections. Sign-in tickets
(10 minutes) and authorization codes (2 minutes, single use) live in memory: a restart only
cancels flows in progress.

## Data

Migration `V001__create_agent_tokens.sql`:

- `agent_tokens`: owner (tenant user or server owner), name, hash, hint, allowed modules
  (`NULL` = all, including modules enabled later), read only, expiration, last use, revocation.
  OAuth connections also store the client id, the current refresh token hash and when the current
  access token expires; their `expires_at` is the end of the whole connection.
- `agent_activity`: tool, outcome (`ok`, `error`, `denied`), detail and time. A daily job deletes
  entries older than 180 days.

## Permissions

| Permission | Allows |
|---|---|
| `agentes.connect` | Connect own agents, see their activity and revoke them. Required on every agent request. |
| `agentes.manage` | See and revoke every agent of the company with its activity. |

## Offering tools from a module

A module adds tools by overriding `ServerModule.mcpTools` (contract in
`server/.../modules/McpTools.kt`). Each `McpTool` declares the permission it requires; the endpoint
lists and runs it only for users holding it. Agents see it as `<moduleId>_<name>`.

```kotlin
override val mcpTools = listOf(
    McpTool(
        name = "search_orders",
        title = "Buscar pedidos",
        description = "Busca pedidos por cliente o numero.",
        permission = "ventas.view",
        readOnly = true,
        inputSchema = McpSchema.obj(mapOf("query" to McpSchema.string("Texto a buscar."))),
    ) { call -> /* call.tenant, call.permissions, call.arguments */ JsonObject(emptyMap()) },
)
```

Inside the handler apply the same checks as the module's routes (scopes, ownership, finer
permissions with `call.permissions.has(...)`) and throw `McpToolException` with a message the agent
can act on. Mark tools that only read with `readOnly = true` and irreversible ones with
`destructive = true`.

Tools available today: Clientes (`search_customers`, `get_customer`, `list_customer_fields`,
`create_customer`, `update_customer`) and CRM (`list_pipeline`, `search_leads`, `get_lead`,
`list_follow_ups`, `pipeline_summary`, `create_lead`, `update_lead`, `move_lead`, `add_lead_note`).
