# Extensión `inmobiliaria_monday` 1.0.0 — diseño

Importación de un solo uso desde monday.com hacia la extensión `inmobiliaria`: tableros "All Real Estate Listings",
"New listing", "Sellers | New upcoming listings" (propiedades) y "Deals" (transacciones). Se instala, se migra y se
desinstala; los datos importados quedan en `inmobiliaria`. Los leads ("Initial leads", tableros por agente) ya los importa
la extensión `monday`; las tareas por agente son del módulo `tareas`.

Pedido del usuario (2026-11-18): "quiero que trabajes extenciones de importacion de monday para cada modulo, la idea es
migrar todo desde monday y luego poder desinstalarlo para evitar tener cosas de un solo uso". Patrón común en las notas
compartidas (`monday-import-patron.md`).

## Por qué un contrato en el CRM

Cada extensión carga en su propio classloader hijo del CRM: `inmobiliaria_monday` no ve las clases de `inmobiliaria`.
Se comunican por una interfaz del CRM (tipo compartido del padre), como los demás proveedores:

```kotlin
// modules/crm/.../CrmImportTarget.kt (CRM 0.28.0, aditivo)
data class CrmImportField(val key: String, val label: String, val required: Boolean = false, val hint: String = "")
data class CrmImportKind(val id: String, val label: String, val fields: List<CrmImportField>)
/** [importKey] estable de la fuente (p. ej. "monday:<itemId>"): reimportar actualiza, nunca duplica. */
data class CrmImportRecord(val importKey: String, val values: Map<String, String>)
/** [failedKeys]: registros no guardados; [warnings] ("<importKey>: aviso"): registros guardados con observaciones; [problems]: errores. */
data class CrmImportOutcome(
    val created: Int, val updated: Int, val skipped: Int, val problems: List<String>,
    val failedKeys: List<String> = emptyList(), val warnings: List<String> = emptyList(),
)

interface CrmImportTarget {
    val extensionId: String
    val importKinds: List<CrmImportKind>
    /** Aplica un lote en una transacción; valida cada registro y reporta problemas sin abortar el lote. */
    fun importRecords(tenant: Connection, actor: CrmActor, kind: String, records: List<CrmImportRecord>): CrmImportOutcome
}
// CrmModuleExtensions.importTargets(): List<CrmImportTarget>
```

## Motor en `inmobiliaria` (sin publicar aún: se edita su V001)

- Columna `import_key varchar(120)` en `ext_inmobiliaria__properties` y `ext_inmobiliaria__deals`, índice único parcial
  `WHERE import_key IS NOT NULL`.
- `InmobiliariaImport : CrmImportTarget` con dos tipos:
  - `property`: `title`*, `code`, `kind`, `zone`, `development`, `status`, `agent` (nombre o correo de usuario del CRM),
    `owner_name`/`owner_email`/`owner_phone` (cliente: se busca por correo/teléfono; si no existe, se deja en notas),
    `lot_area`, `built_area`, `bedrooms`, `bathrooms`, `price`, `previous_price`, `currency`, `description`, `notes`,
    enlaces `link_website`, `link_website_es`, `link_craigslist`, `link_encuentra24`, `link_drive`, `link_other`
    (solo http/https; los inválidos van a problemas), `intake` ("true" fuerza Captación: tableros de nuevos listados).
  - `deal`: `property`* (import_key de la propiedad, código o título exacto), `buyer_name`/`buyer_email` (lead visible
    por correo; si no, el nombre queda como parte compradora), `stage` (nombre; se crea si no existe como abierta, salvo
    Closed/Cerrada → ganada, Canceled/Cancelada → perdida), `agreed_price`, `commission_pct`, `commission_amount`,
    `estimated_close_on`, `closed_on`, `comments`, partes por nombre: `seller`, `seller_agent`, `seller_lawyer`,
    `seller_referral`, `buyer`, `buyer_agent`, `buyer_lawyer`, `agents`, `brokers` (separadas por coma; un nombre que
    coincide con un usuario del CRM queda como agente interno).
- Estados de Monday: FOR SALE → En venta; REDUCED → En venta (precio anterior si viene); SOLD/Vendida → Vendida;
  vacío → En venta (o Captación si `intake`). Valores de dinero, %, áreas y fechas con los parseadores de
  `InmobiliariaInput` (fechas `AAAA-MM-DD` y `MM/DD/AAAA`).
- Modelo de confianza (resultado de la revisión): el importador ve todo, actúa como gerente con `crm.settings`;
  `CrmImportTarget` está dentro del modelo de confianza de extensiones (cualquier extensión del CRM podría llamarlo, la
  llamante debe comprobar `crm.settings`). Deduplicación entre tableros: sin propiedad con ese `import_key`, el destino busca por
  código y luego por título normalizado + zona (una sola coincidencia) y la fusiona, asignándole el `import_key` si no tenía.
  La importación no toca WordPress. `CrmImportOutcome` trae `failedKeys` (registros no guardados) y `warnings` ("<key>: aviso");
  `problems` es solo de errores. Lotes de hasta 500 registros y valores de hasta 5000 caracteres.
- Reimportar con el mismo `import_key` actualiza los datos (el precio por `changePrice` si cambió, con historial) y no
  vuelve a disparar efectos de etapa salvo que la etapa cambie. Permisos: el importador actúa como el usuario con
  `crm.settings` que lanzó la importación; las reglas de visibilidad de leads/clientes se respetan.

## Extensión `inmobiliaria_monday`

- Manifiesto: módulo `crm`, `minModuleVersion` 0.28.0, permisos `network.outbound:api.monday.com`, `secrets.write`,
  `crm.items.write`. Requiere `inmobiliaria` activa (si no, la pantalla lo dice y no importa nada).
- Tablas: `ext_inmobiliaria_monday__settings` (token sellado), `__jobs` (tablero, tipo, mapeo JSON, estado
  reading/ready/applying/done/failed, conteos, problemas, creado por, quién pulsó Aplicar, intentos de lectura), `__rows` (job_id FK, item_id, import_key, valores
  JSON, estado pending/applied/failed, problema o aviso).
- Cliente GraphQL copiado de `extensions/monday` (POST `https://api.monday.com/v2`, `API-Version`, host fijado, tope de
  tamaño, `items_page`/`next_items_page` de a 100, tableros paginados). Columnas `link` → URL; el resto `text`.
- Flujo (pantallas propias con `CrmScreenProvider`, sección oculta `inmobiliaria_monday_import`, permiso
  `crm.settings`; tarjeta en la pestaña Importar del CRM vía `CrmImportSource.importCards` que abre esa pantalla):
  1. Token (se guarda sellado, nunca se muestra ni pasa por MCP). 2. Tableros. 3. Elegir tablero y tipo (propiedades /
  transacciones; "es tablero de nuevos listados"). 4. Mapeo columna → campo con sugerencia por título. 5. Leer en
  segundo plano (`ModuleJob` de la extensión) a `__rows`. 6. Vista previa (primeras filas mapeadas y conteos). 7.
  Aplicar por lotes de 100 con `CrmImportTarget.importRecords` (tipo `deal` después de `property`). 8. Resultado con
  conteos y problemas.
- MCP (`crm_inmobiliaria_monday_*`, `crm.settings`): listar tableros, crear importación (tablero, tipo, mapeo), estado,
  aplicar. Nunca reciben ni devuelven el token.
- Desinstalar: borra token, jobs y filas siempre; los datos importados quedan en `inmobiliaria` (con su `import_key`).

## Reglas de robustez (revisión)

- **Columnas sintéticas:** `Nombre` (id `name`) y `Grupo` (id `group`) se anteponen a las columnas del tablero para poder
  mapearlas y sugerirlas (`title` en propiedades, `property` en deals); sus valores salen del elemento, no de `column_values`.
- **Estados de fila:** fallan solo las claves de `failedKeys` (clave exacta; `monday:1` no casa con `monday:12`); el resto
  queda `applied` con el aviso como texto. Conteos del destino sin duplicar; `failed = failedKeys.size`.
- **Errores pasajeros** (`InmobiliariaMondayTransientException`, subclase de la excepción de negocio): red, 429, 5xx y límites
  de uso. El job sigue en `reading`, cuenta `read_attempts` (máx. 5, luego `failed`; una página leída lo reinicia).
- **Mapeo de agente inválido:** cae al sugerido y el job queda `ready` con aviso en `error`.
- **Presupuesto:** se comprueba antes de cada consulta (mín. 3 s) y el plazo HTTP (`timeoutMillis`, `connectTimeoutMillis`,
  `totalMillis` de `PinnedHost.openPinnedHttps`) se acota al tiempo restante. Decisión seguir/parar pura (`readStep`).
- **Orden y concurrencia:** los deals exigen propiedades importadas; índice único parcial
  `ext_inmobiliaria_monday__jobs_active_uq (board_id) WHERE state IN ('reading','applying')` más la comprobación global.
- **Permiso en el runner:** `applied_by` / `applied_by_name` se guardan al pulsar Aplicar y son el `CrmActor`. Cada corrida
  de aplicación comprueba por SQL (`application_users` activo y `business_owner` o permiso `crm.settings`, la misma regla de
  `CrmNotify.userScope`). Limitación: no hay un servicio de acceso utilizable desde un job, así que se replica la consulta.
- **Topes:** valores de columna 5000 caracteres, nombre 300; el MCP `status` limita problemas (50 de 300 caracteres) y vista previa.
- `setMapping` actualiza solo con `state = 'ready'`; falla si ya cambió.
