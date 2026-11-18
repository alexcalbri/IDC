# Extracción de integraciones del CRM a extensiones — hand-over

Estado: **items 1-3 hechos y verdes** (sin commitear; el usuario commitea desde IntelliJ). **Items 4-6 pendientes** para una
sesión nueva de contexto limpio. Rama develop, sin ramas nuevas. Checkout compartido: tocar SOLO `modules/crm/**` y
`extensions/**` (no `modules/hotspot/` ni `app/shared`). Build central:
`export JAVA_HOME="/c/Program Files/JetBrains/IntelliJ IDEA 2026.2.1/jbr"` y `./gradlew :server:test`.

## Gotchas de build
- Tras ediciones hechas por subagentes, Gradle a veces da un falso "BUILD SUCCESSFUL in ~2s, all UP-TO-DATE" sin recompilar.
  Verificar SIEMPRE con **`--rerun-tasks`**: `./gradlew :server:test --rerun-tasks`.
- `:server:validateModules` valida el layout de `modules/<id>/` (solo README.md, module.json, migrations, server). Si otra
  sesión deja un archivo suelto ahí, rompe el build para todos; entonces usar `-x validateModules` hasta que lo muevan.
  (Al cierre de esta nota ya estaba resuelto: el doc de UniFi se movió a `extensions/unifi/DISENO.md`.)
- Los paquetes en `extensions/<id>/` NO los compila el CI central (no están en los sourceSets de `:server`); se compilan/validan
  al instalarlos en el servidor. El CI central solo verifica el lado CRM/Core. Verificación real de la extensión = instalar el paquete.

## PATRÓN EXACTO (plantillas vivas: extensions/cloudbeds, extensions/encuentra24, extensions/monday)
Estructura del paquete:
- `extensions/<id>/extension.json`: `{ id==carpeta, module:"crm", displayName, description, version:"1.0.0",
  minModuleVersion:"0.27.0", source:{type:"github", url:"https://github.com/alexcalbri/idc/tree/<40_ceros_placeholder>/extensions/<id>"},
  permissions:[...], settings:[], migrations:["V001__settings.sql"], uninstall:{options:[...]} }`. El commit placeholder
  (40 ceros) lo reemplaza el dueño al publicar.
- `extensions/<id>/migrations/V001__settings.sql`: crea `ext_<id>__settings` entre `BEGIN;`…`COMMIT;`, SIN INSERT, validador
  estricto (sin SELECT/comentarios/funciones raras). Solo tablas con prefijo `ext_<id>__`.
- `extensions/<id>/server/src/main/kotlin/com/ideasdeveloper/idc/extensions/<id>/`:
  - `<Id>ServerExtension.kt`: `object <Id>ServerExtension : ServerExtension, <Provider>` con `manifest` (ExtensionManifest que
    DEBE coincidir EXACTO con extension.json: id/module/version/minModuleVersion/permisos/uninstall), `migrationPaths =
    listOf("extensions/<id>/migrations/V001__settings.sql")`, `runtimeTables = listOf("ext_<id>__settings")`, `onEnable`
    (si hace falta), `uninstallPreview`/`uninstall`, y los overrides del provider. Más
    `class <Id>ServerExtensionProvider : ServerExtensionProvider { override fun create() = <Id>ServerExtension }`.
  - **META-INF/services**: NO se escribe a mano. El builder del servidor (ExtensionPackageBuilder.writeJar) genera
    `META-INF/services/…ServerExtensionProvider` a partir del nombre de clase `<Id>ServerExtensionProvider`. Solo hay que
    declarar esa clase con ese nombre exacto (convención providerClassName).
  - `<Id>SettingsStore.kt`: persistencia sobre `ext_<id>__settings`; `load` tolera sin fila → settings deshabilitados por
    defecto; `saveForm` hace UPSERT (INSERT … ON CONFLICT (id) DO UPDATE); secretos vía `host.openSecret/sealSecret`.
  - El resto del código específico de la integración (cliente API, parseo propio de su API, etc.).

Lado CRM (lo que se queda):
- **`CrmHost`** (modules/crm/.../CrmHost.kt): facade público y estable; única superficie que la extensión usa del CRM.
  Ya tiene: lead, addActivity, items/linkItem/setItemStatus/setItemData/removeItem, templates/renderFor, enqueueMail, inboxAdd,
  receive, notifyNewLead, loadExit/archivesOnCheckout, isEmail, openSecret/sealSecret/isSecretPlaceholder/isSecretUnreadable/
  maskSecret/UnreadableSecretText, **extensionInactiveReason(tenant,id)/extensionEnabled(tenant,id)** (gate genérico), y los
  helpers de reservas/settings de cloudbeds. Añadir métodos nuevos aquí si una integración los necesita (delegando a internos).
- **Registro de proveedores** (`CrmActions.kt`): `object CrmModuleExtensions` guarda `linked` = TODAS las ServerExtension
  enlazadas al arrancar (hook Core `ServerModule.linkExtensions`, llamado en `server/.../app/Application.kt` tras
  `ExtensionRegistry.of(...)`). Helpers: `cloudbeds()`, `encuentra24()`, `importSources()` (= `linked.filterIsInstance<CrmImportSource>()`).
  Patrón: define una interfaz `<X>Provider` en el CRM; la extensión la implementa; el CRM la invoca por ahí (patrón plugin,
  cross-classloader por el tipo compartido del padre). Si no hay proveedor → degradar, nunca romper.
- El CRM conserva cascarones/hooks que delegan al proveedor: job (ModuleJob en CrmServerModule), MCP, UI server-driven
  (CrmScreens/CrmServerModule), y hooks core→extensión (p. ej. CrmInbox.detectAd). Datos en tablas `crm_*` CORE.
- Migración vieja del CRM: EDITARLA para que ya NO cree la tabla/columna de settings de esa integración (instalación limpia;
  el dueño acepta recrear empresas; SIN copia legacy). El archivo de migración se deja con BEGIN;+comentario+COMMIT; (sin
  cambios de esquema) para no romper la numeración/registro. Quitar la tabla/columna de `runtimeTables` (CrmServerModule) y de
  `CrmSecrets.tenantColumns`.
- Quitar la integración de `CrmServerModule.bundledExtensions` y de `CrmExtensions` (all/names/configuredIntegrations);
  CONSERVAR `CrmExtensionIds.<X>` (lo usa el gate).
- **`CrmExtensionsTest`**: bajar el conteo de bundled (ahora 2 = Meta + WordPress) y quitar asserts de la integración extraída
  (permisos, opciones de uninstall, columnas de secreto en tenantColumns). Si editar una migración rompe un test del servidor
  por una columna inexistente (p. ej. `DuplicatedSettingsTest`), ajustar ese assert.
- NO subir versiones del CRM (todo el refactor es el mismo 0.27.0 sin commitear).

## HECHO y VERDE
1. **Import genérico = CSV+URL.** SPI `CrmImportSource` (interface en el CRM) + `CrmImportSources` (registro dinámico vía
   `CrmModuleExtensions.importSources()`). Helpers genéricos públicos: storeRows/insertJob/apply/runPending/createMissing/header/
   classify/values/Context/create. El runner genérico acepta solo sources `file` y `leadspro`; las demás las aportan extensiones.
   `CrmImportSourceResult(message, tone: String = UiTone.Info, screen, params)` — OJO: `UiTone` son constantes String, no un tipo.
   NOTA: el parser genérico ya NO reconoce el layout de export de Monday ni las heurísticas de columna "Grupo"/"Tablero".
2. **Encuentra24 → extensions/encuentra24/** (settings en ext_encuentra24__settings). El parser de texto de anuncios
   `CrmEncuentra24Ads.fromText` se QUEDÓ en el CRM (función pura; la usa CrmInbox.leadSource y tests sin extensión); solo
   `rememberAd` (enlazar el anuncio) va por `Encuentra24Provider.detectAd`.
3. **Monday → extensions/monday/** (id "monday"; token movido de `crm_settings.monday_token` a `ext_monday__settings`;
   editadas V008 y V022). MCP (list_monday_boards, import_monday) y UI de Monday viven en el paquete; el CRM despacha
   genéricamente las acciones de `screen=="import"`. `CrmImportSource` ganó `action(...)`.

bundledExtensions del CRM = **Meta + WordPress**. Verificado con `:server:test --rerun-tasks` (10 tareas ejecutadas, pruebas pasan).

## PENDIENTE — items 4-6 (sesión nueva, una a la vez, verde antes de seguir)
4. **LeadsPro — INVESTIGAR PRIMERO (NO investigado a fondo aún).** Lo único averiguado (del mapeo del import): `CrmImport.fromLeadsPro(tenant)`
   depende de la extensión WordPress — llama `CrmExtensions.inactive(WordPress)`, `CrmWordPress.load`, exige `settings.ready`, y
   lee vía el plugin IDC Connector (acción "leads", en `.../resources/.../idc-connector.php`). Hay un botón en la pantalla de
   import (`enabled = wordpress`) y la fuente `"leadspro"` está en el CHECK de `crm_import_jobs.source` y la etiqueta del runner.
   NO tiene extensión propia hoy. **DECISIÓN pendiente:** ¿LeadsPro se pliega dentro de la extensión WordPress (como una
   `CrmImportSource` que esa extensión aporta) o es su propia extensión? Si no queda claro tras investigar, reportar opciones
   antes de implementar. Hoy el runner genérico acepta "leadspro"; al extraerlo debe pasar a su fuente (quitarlo del runner genérico).
5. **WordPress → extensions/wordpress/.** PRIMERA extracción con RUTAS PÚBLICAS: webhook `/{company}/hook` (montar bajo
   `/modules/crm/extensions/wordpress/` vía `ServerExtension.routes(route, access)`), y sirve `idc-connector.zip` desde
   `CrmPlugin` → hay que **empaquetar el ZIP del plugin con la extensión** (resources del paquete) y servirlo desde la ruta de
   la extensión. Tabla `crm_wp_agents`. Settings de WordPress → ext_wordpress__settings. Incluir LeadsPro aquí según el paso 4.
6. **Meta → extensions/meta/.** La mayor superficie: webhooks de Meta (WhatsApp/Messenger/Instagram), verificación (verify
   token), tokens de app/página. Las rutas Meta YA usan el prefijo `/modules/crm/extensions/meta/...` (ver EXTENSIONS.md "Meta
   extension"). Revisar CrmMeta*, el almacén de tokens, y los tests `CrmMetaTest`. Settings/tokens → ext_meta__settings
   (ojo: Meta separa "app" vs "connection" settings; ver EXTENSIONS.md).

Al terminar: si cambió algo de Compose en app/shared por estas extracciones, correr la pasada `compose-performance`; hasta
ahora NO se tocó app/shared.
