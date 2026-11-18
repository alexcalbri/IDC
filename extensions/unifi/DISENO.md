# UniFi → extensión independiente del hotspot — diseño

Rama develop. Solo edición de archivos (el dueño commitea/pushea). Alcance tocado: `modules/hotspot/`,
`extensions/unifi/` y, si hace falta, nada del Core (ver más abajo: **no** se toca contrato del Core).

Objetivo: el controlador UniFi deja de vivir en el módulo hotspot y pasa a una **extensión desplegable**
(`extensions/unifi/`), igual que Cloudbeds salió del CRM. El hotspot queda agnóstico del *código* del
controlador: resuelve el controlador activo desde las extensiones habilitadas vía un SPI, sin importar
`UnifiController`/`UnifiClient`.

## 1. SPI de controladores (espejo de `CrmActionProvider`)

En el módulo hotspot (archivo nuevo `HotspotIntegrationsSpi.kt` o dentro de `HotspotIntegrations.kt`):

```kotlin
/** Opción genérica id→nombre para los selectores de consola/sitio del controlador. */
data class ControllerOption(val id: String, val name: String)

/** Lo implementa una extensión para aportar un NetworkController sin que el hotspot dependa de su código. */
interface NetworkControllerProvider {
    fun controller(): NetworkController
    // Descubrimiento para la pantalla de Integraciones (hoy UnifiClient.consoles/sites):
    fun consoles(settings: HotspotSettings): List<ControllerOption>
    fun sites(settings: HotspotSettings, consoleId: String): List<ControllerOption>
}

/** Registro poblado por el Core al arrancar (igual que CrmModuleExtensions.link). */
object HotspotModuleExtensions {
    @Volatile private var providers: List<NetworkControllerProvider> = emptyList()
    fun link(extensions: List<ServerExtension>) { providers = extensions.filterIsInstance<NetworkControllerProvider>() }
    fun controllers(): List<NetworkController> = providers.map { it.controller() }
    fun providerFor(id: String): NetworkControllerProvider? = providers.firstOrNull { it.controller().id == id }
}
```

`HotspotIntegrations.controllers` deja de ser `listOf(UnifiController)` y pasa a
`HotspotModuleExtensions.controllers()`. `controller(connection)` resuelve igual, pero sobre esa lista
dinámica. Si ninguna extensión está instalada, `controllers()` = vacío y `controller()` = null (hoy ya
maneja null en portal, pantallas y jobs).

## 2. Qué se mueve a `extensions/unifi/`

- `UnifiClient.kt` (cliente api.ui.com + modelos `UnifiConsole/UnifiSite/UnifiStation/UnifiException`).
- `UnifiController` (sale de `HotspotIntegrations.kt`) → al archivo de la extensión.
- Un `object UnifiExtension : ServerExtension, NetworkControllerProvider` con su manifest
  (`extension.json` v1.0.0, `module="hotspot"`, `minModuleVersion` = versión del hotspot que publiquemos),
  `UnifiExtensionProvider` (ServiceLoader), mapeando `UnifiConsole/UnifiSite` → `ControllerOption`.
- Test `UnifiClientTest` → dir de test de la extensión (fuera del CI central; se valida al instalar).

El hotspot conserva los tipos **genéricos** que la extensión usa como contrato:
`NetworkController`, `GuestAuthorization`, `ConnectedGuest`, `NetworkControllerException`, `HotspotSettings`.
Igual que Cloudbeds usa tipos públicos del CRM: el código de extensión corre en el proceso del servidor
(trust boundary de EXTENSIONS.md).

## 3. Settings de UniFi — **se quedan en `hotspot_settings` (host SPI), NO a tabla propia**

Decisión y justificación:

- Las columnas `unifi_console_id/unifi_site_id/unifi_api_key` ya existen en `hotspot_settings` (V001) y el
  `NetworkController` recibe `HotspotSettings` en cada llamada. La extensión las lee a través de ese
  parámetro — no necesita tabla propia.
- Mover a `ext_unifi__settings` obligaría a **editar V001** (rompe checksums → `CompanyProvisioningService`
  rechaza TODAS las empresas existentes → recrearlas, destructivo) o una migración nueva que las borre, más
  un facade con `sealSecret/openSecret` para la api_key y una copia de una sola vez con problema de orden
  (mismo dolor que la etapa E/F de Cloudbeds). No lo justifica el plazo ni el beneficio.
- Comportamiento idéntico: `duplicatedSettings` sigue reseteando esas tres columnas al copiar empresas; la
  pantalla las guarda igual. Quedan como columnas "del host" que un controlador de red usa; inertes si no
  hay extensión de red instalada.

Trade-off honesto: el hotspot sigue **conociendo nombres `unifi_*`** en su esquema y su UI (abajo). Lo que
se desacopla es el **código del controlador** (cliente API, autorizar/desconectar, descubrir consolas/sitios).

## 4. UI y MCP — se quedan en el hotspot, keyed por id del controlador

Hoy referencian el `object UnifiController` por tipo (`when (controller)`, `== UnifiController`,
`is UnifiController`) y llaman `UnifiClient.consoles/sites` directo. Tras mover el controlador, eso no compila.
Cambios (sin mover la UI; mismos textos y flujo):

- `HotspotScreens` y `HotspotServerModule`: `controller == UnifiController` → `controller?.id == "unifi"`
  (o un `HotspotIntegrations.NetworkUnifi = "unifi"`). `unifiCard/unifiFields/consolePicker/sitePicker`
  se mantienen y pasan a usar `ControllerOption` en vez de `UnifiConsole/UnifiSite`.
- `unifiPicker` (HotspotServerModule) llama `HotspotModuleExtensions.providerFor("unifi")?.consoles/sites`
  en vez de `UnifiClient`. El `catch (UnifiException)` → `catch (NetworkControllerException)`.
- `HotspotMcpTools` (`unifi_list_consoles/choose_console/choose_site`, `list_controller_ssids`): igual, por
  id de controlador + SPI; `list_controller_ssids` ya usa la interfaz genérica.

Esto repite exactamente lo que Cloudbeds dejó como **follow-up mayor** (UI de settings y MCP quedaron como
cascarones en el módulo porque mover la UI/MCP a la extensión exige primitivas nuevas del Core que no existen
antes del 1 dic). Aquí se mantienen en el hotspot keyed por id — opción pragmática y de comportamiento idéntico.

## 5. Core — **no se toca el contrato**

`supportsExtensions`, `bundledExtensions`, `linkExtensions`, `extensionPermissions` ya existen en
`ServerModule` y `Application.kt` ya llama `linkExtensions` por módulo. El hotspot solo **sobreescribe**
`supportsExtensions = true` y `linkExtensions { HotspotModuleExtensions.link(it) }` en
`HotspotServerModule`. Sin cambios en `server/.../extensions/` → sin colisión con la otra sesión.

UniFi **no** entra en `bundledExtensions` (se distribuye como paquete github/instalable, igual que Cloudbeds).

## 6. Versión

Hotspot sube de `0.10.0` a **`0.11.0`** (API del módulo cambia: nuevo SPI, `supportsExtensions`), en
`module.json` y `HotspotServerModule.kt:119`. `extension.json` de UniFi: `minModuleVersion = 0.11.0`.

## 7. Riesgos / pendientes

- Si `hotspot_integrations` tiene `unifi` habilitado pero la extensión no está instalada, `controller()` =
  null. Hoy portal/pantallas/jobs ya toleran null (muestran "elige controlador"). Se conserva.
- El paquete de la extensión no lo compila Gradle central (solo `modules/*` entra en `:server`); se valida
  instalándolo en el servidor. Su `extension.json` `source.url` necesita el **commit real** que genere el
  dueño al pushear (queda placeholder, igual que Cloudbeds).
- README del hotspot nombra las tools MCP `hotspot_unifi_*`; el código no lleva prefijo — verificar/ajustar
  al implementar (no cambia el comportamiento).
- Desplegar hotspot 0.11.0 + instalar el paquete unifi; La Jolla ya tiene la fila integración `unifi` → debe
  reconciliar al instalar. Prueba real queda pendiente en el servidor.

## 8. Resumen de archivos

- **Modifica** (hotspot): `HotspotIntegrations.kt` (quita UnifiController/UnifiClient, SPI dinámico),
  `HotspotServerModule.kt` (supportsExtensions, linkExtensions, pickers/save por id+SPI, versión),
  `HotspotScreens.kt` (id en vez de tipo, ControllerOption), `HotspotMcpTools.kt` (id+SPI),
  `module.json` (versión). Mueve fuera: `UnifiClient.kt`, `UnifiClientTest.kt`.
- **Crea** (`extensions/unifi/`): `extension.json`, `server/.../unifi/UnifiExtension.kt`
  (ServerExtension+Provider), `UnifiClient.kt` + `UnifiController` movidos, `META-INF/services`, test movido.
- **No toca**: Core, CRM, otras extensiones.
