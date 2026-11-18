# Diseño: instalar extensiones desde la configuración del módulo (GitHub / ZIP / URL)

Objetivo: que cada módulo que **soporte extensiones** ofrezca, dentro de su **Configuración**, una sección
**Extensiones** desde la que se puedan instalar — desde el **catálogo GitHub oficial**, desde un **ZIP** o
desde una **URL** — y gestionar (habilitar/configurar/desinstalar) las de ese módulo.

## Estado actual (resumen del mapeo)
- **Instalar paquete** = solo **dueño del servidor**, pantalla nativa `ExtensionPackagesSection` (dentro de
  `ServerModuleManagementScreen`), backend `/server/extensions/*`. Fuente: **solo catálogo GitHub por commit**.
- **Habilitar/configurar/desinstalar por empresa** = `ExtensionsScreen` (nativa), agrupada por módulo, desde
  Empresa (business owner) o Aprovisionamiento (dueño del servidor).
- **Seguridad**: firma Ed25519 válida, o el dueño del servidor autoriza el **hash del árbol** exacto (con
  confirmación, revisión de hash/commit y auditoría). El código de la extensión corre con privilegios del servidor.
- **No hay** flag "soporta extensiones" (se infiere de `extensionPermissions`/`bundledExtensions` no vacíos).
- La Configuración del CRM es **server-driven**; **no puede** abrir la pantalla nativa de extensiones.

## Propuesta

### 1. Flag por módulo
Añadir `supportsExtensions: Boolean` a `ModuleDefinition` (default `false`; el CRM lo pone `true`). La app usa el
flag para mostrar la sección "Extensiones" en la Configuración de ese módulo. (Alternativa: inferirlo de
`extensionPermissions`/`bundledExtensions` no vacíos, sin campo nuevo.)

### 2. Entrada "Extensiones" en la Configuración del módulo (nativa, filtrada por módulo)
Como la Configuración es server-driven y **no** puede hospedar la UI nativa de instalación, la sección
"Extensiones" del módulo será un **punto de entrada** que abre la pantalla **nativa** de extensiones
**filtrada a ese módulo**:
- Nueva `NavRoute.ModuleExtensions(moduleId)` y un `moduleId?` en `ExtensionsScreen`/`ExtensionPackagesSection`
  para listar/instalar solo las de ese módulo.
- El ítem "Extensiones" del menú de Configuración se marca con una acción especial que la app intercepta para
  navegar a esa ruta nativa (en vez de un `UiNavigate` dentro del módulo).
- **Permisos**: *instalar desde fuente* (GitHub/ZIP/URL) sigue siendo **dueño del servidor**; el *business owner*
  ve, para su empresa, habilitar/configurar/desinstalar las extensiones de ese módulo. (Misma pantalla, acciones
  según rol, como ya hace `ExtensionsScreen`.)

### 3. Fuentes de instalación (backend + UI), todas con el MISMO gate de seguridad
- **Catálogo GitHub** (hoy): sin cambio.
- **ZIP**: subir un archivo (multipart) → extraer (extracción genérica, no solo el layout de GitHub) →
  `buildFromDirectory` + `verify` → firma válida **o** autorización por hash del dueño del servidor.
- **URL**: descargar (manteniendo `followRedirects=NEVER`) → igual que ZIP.
- Cambios backend: nuevas rutas de ingesta (multipart ZIP / URL) en `/server/extensions/*`; extracción genérica
  en `ExtensionPackageSafety`; **relajar** `checkSource` (hoy exige commit de 40 hex) y `ExtensionManifestValidator`
  (hoy exige `source.url` github `/tree/<40hex>/extensions/<id>`) para un **tipo de fuente nueva** (`zip`/`url`),
  dejando `source.url` opcional en paquetes "sideloaded". Se reutiliza `verify` y la autorización por hash.
- UI: selector de archivo (no existe hoy en la app) y campo URL en la sección de instalación (server owner).

## Seguridad (importante)
Un ZIP o una URL **no** tienen commit fijo ni origen GitHub, así que la **autorización por hash** del dueño del
servidor es la **única prueba de origen**: debe revisar el paquete antes de autorizar. No se saltan validación de
manifiesto, estructura, `MigrationTableValidator`, compilación ni la prohibición de downgrade.

## Estado: IMPLEMENTADO y verde (sin commitear)
Decisiones tomadas: 1) instalar sigue siendo del dueño del servidor; 2) entrada nativa filtrada por módulo
(acción `UiOpenExtensions`); 3) flag explícito `supportsExtensions`; 4) todo junto.
- Flag `ModuleDefinition.supportsExtensions` (CRM=true).
- Acción UI compartida `UiOpenExtensions(moduleId)` (apps viejas la ignoran → sin subir minAppVersion); pestaña
  "Extensiones" en el `tabBar` del CRM; `NavRoute.ModuleExtensions(moduleId)` → `ExtensionsScreen(moduleId=…)` filtrada.
- Backend fuentes nuevas: `POST /server/extensions/upload` (multipart ZIP), `/from-url`, `/staged/{token}/authorize-unsigned`,
  `/staged/{token}/install` — flujo inspeccionar→autorizar→instalar con token de staging (SecureRandom 192b, caduca 30 min).
  Extracción genérica anti zip-slip/zip-bomb; descarga URL anti-SSRF (PinnedHost, solo https/443, re-ancla cada redirección).
  Migración control `V012__extension_zip_url_sources.sql` (CHECK amplía source_type a bundled/github/zip/url).
- UI (server owner) `ManualInstallPanel` en ExtensionPackagesSection: ZIP (FilePicker multiplataforma reusado) + URL,
  con diálogo de autorizar-sin-firma del flujo staged.
- Revisión de seguridad: sin críticos (SSRF/zip-slip/zip-bomb/gate verificados). ALTA de diseño (token server_owner robado → sideload) ENDURECIDO:
  - Flag `EXTENSION_UPLOAD_ENABLED` (env, default FALSE) gatea las 4 rutas nuevas (403 FEATURE_DISABLED); catálogo GitHub no se gatea. En install.sh/update.sh.
  - `authorizeStaged` exige que la confirmación incluya los primeros 8 hex del hash (`CONFIRMATION_HASH_REQUIRED`); la UI ya manda `autorizar {id} {sha8}`.
- Verificación central: `:server:test` verde (incl. ExtensionZipUrlSourcesTest, ExtensionUploadGateRouteTest, ExtensionPackageServiceTest); `:app:shared` compila JVM/JS/Android.

### Follow-ups de robustez (no de seguridad, pendientes)
Carrera de purga del staging cerca de 30 min, tope de disco en staging, limpieza de huérfanos con try/finally, timeout global de descarga (hoy por salto).
Pendiente de prueba manual: subida multipart real, descarga https real y el picker en cada plataforma. Para USAR ZIP/URL hay que poner `EXTENSION_UPLOAD_ENABLED=true` en el servidor.

## Decisiones que necesito antes de implementar
1. **¿Instalar desde fuente (GitHub/ZIP/URL) sigue siendo solo del dueño del servidor?** (recomendado, por el
   límite de seguridad; el business owner solo habilita/configura).
2. **Sección "Extensiones" = abrir la pantalla nativa filtrada por módulo** (recomendado) vs. reconstruir la
   instalación como UI server-driven (más trabajo, duplica lógica, el "files" server-driven no sirve bien para instalar).
3. **Flag explícito `supportsExtensions`** en ModuleDefinition vs. inferirlo de permisos/bundled.
4. **Alcance/orden** dado el 1 dic: ¿hago primero la pieza 1+2 (flag + entrada por módulo, reusa lo que ya existe) y
   dejo ZIP/URL (pieza 3, backend nuevo + selector de archivo, poco verificable en CI central) como segunda etapa?
