# Diseño: Cloudbeds como extensión independiente del CRM

Objetivo del dueño: **actualizar Cloudbeds sin reinstalar/actualizar todo el CRM**, con
Cloudbeds versionado y desplegable por separado (su propio `extension.json`, su propia
versión y firma).

Estado hoy: Cloudbeds es una extensión **bundled** (`CrmCloudbedsExtension`), compilada
dentro del JAR del CRM junto a Meta/Monday/WordPress/Encuentra24. Al ir en el mismo JAR,
**es físicamente imposible** actualizarla sin reconstruir el CRM. Para cumplir el objetivo,
Cloudbeds tiene que salir a un **paquete de extensión propio** (carpeta `extensions/cloudbeds/`),
que el servidor compila/instala aparte y gatea por `minModuleVersion` — el modelo que ya
describe `EXTENSIONS.md`.

---

## 1. Acoplamiento actual (resumen del mapeo)

**CRM → Cloudbeds**
- `CrmLeadItemActions.reserve(...)` → `CrmCloudbeds.reserve(... , lead.estimatedValue)`
- `CrmLeadItemActions.assignRoom(...)` → items `source="cloudbeds"`
- `CrmAutomationEngine.executeAction(...)` → casos `CloudbedsReserve`/`CloudbedsCheckIn`
- `CrmServerModule` rutas `integrations:cbSave/cbTest`, `rooms:assign`, `lead:reserve`; jobs `cloudbeds-welcome` (15 min), `cloudbeds-sync` (30 min)
- `CrmIntegrationMcpTools`: `update_cloudbeds`, `cloudbeds_test`, `create_reservation`, `assign_room`, `search_rooms`, `get_integrations`
- `CrmCloudbedsScreens`: UI de búsqueda, reserva y settings

**Cloudbeds → CRM** (qué necesita del CRM)
- Del lead: `name`, `email`, `phone`, `estimatedValue`, `targetStart/targetEnd`, `id`
- Habitaciones elegidas: `CrmItems` `source="cloudbeds"`, `status="chosen"` (fechas, `roomTypeId`, `max`, `adults`, `children`)
- Escribe: `crm_reservations`, `crm_lead_items`, actividad del lead, `crm_inbox` (bienvenida), cola `crm_notify`, `leads.archived_at` (vía `CrmExit`)

**Anclas duras que impiden sacarlo tal cual**
- **A. Acciones de automatización hardcodeadas**: `enum CrmActionKind` + `when` exhaustivo en `CrmAutomationEngine`. No hay registro; una extensión no puede añadir *kinds*.
- **B. `crm_reservations` es tabla CORE del CRM** (FK a `crm_leads`), leída por `CrmExit` (archivado al check-out) y `CrmCustomerTimeline`. Las reglas de extensión solo permiten tablas `ext_<id>__*`; no puede mudarse sin romper el core.
- **C. Invariante de versión**: `CrmExtensionsTest` (línea 129-130) exige `version == MinModuleVersion == minModuleVersion` de las bundled. Cloudbeds está atado a la versión del CRM.
- **D. Jobs y MCP tools** de Cloudbeds viven dentro del CRM (`CrmServerModule`, `CrmIntegrationMcpTools`).
- **E. Enlace binario**: como paquete aparte, Cloudbeds se compila contra el CRM en ejecución. Si el CRM cambia las firmas que Cloudbeds usa, se marca `incompatible` hasta instalar una versión compatible (comportamiento normal de extensión, gateado por `minModuleVersion`).

---

## 2. Interfaz limpia propuesta (CRM ↔ extensión Cloudbeds)

La extensión **no** debe llamar internos del CRM a discreción. El CRM publica un **SPI estable**
(superficie pequeña y versionada) y la extensión solo depende de eso:

### 2.1 `CrmHost` — API que el CRM expone a sus extensiones
Interfaz estable (en el CRM, paquete público del módulo) con lo que Cloudbeds necesita:
- `lead(tenant, leadId): LeadView` (name, email, phone, estimatedValue, targetStart/End)
- `chosenRooms(tenant, leadId): List<RoomItem>` y `linkRoomItem(...)` / `clearRoomItems(...)`
- `saveReservation(tenant, leadId, reservation)` y `reservations(tenant, …)` — **la escritura de `crm_reservations` se queda en el CRM** (permiso `crm.reservations.write` ya existe)
- `addActivity(tenant, leadId, text)`, `enqueueNotify(...)`, `inboxAdd(...)`
- `renderTemplate(tenant, key, lead)` (para la bienvenida)

Así `crm_reservations`, `CrmExit` y `CrmCustomerTimeline` **no se tocan**: siguen siendo del CRM
y la extensión escribe a través del SPI.

### 2.2 Registro de acciones de automatización (resuelve ancla A)
Reemplazar el `enum + when` por un **registro de acciones** poblado por las extensiones habilitadas:
- SPI `CrmActionProvider { kinds(): List<CrmActionSpec>; execute(kind, ctx): Result }`
- `CrmAutomationEngine.executeAction` despacha por el registro; si un *kind* quedó huérfano
  (extensión desinstalada/no cargada) devuelve un error claro en vez de caer en `else`.
- Las validaciones/UI (`CrmAutomationScreens`, `CrmAutomationMcpTools`) leen los specs del registro.
- **El motor de automatizaciones es del CRM, no del Core** → este registro vive en el CRM;
  **no hay que tocar el Core** para esto.

### 2.3 Puntos de extensión del Core que sí se usan (ya existen)
De `ExtensionContract.kt`: `migrationPaths` (`ext_cloudbeds__*`), `jobs` (ModuleJob), `routes` bajo
`/modules/crm/extensions/cloudbeds/`, `validateSettings`, `onEnable`, `uninstallPreview/uninstall`.
Los jobs `cloudbeds-welcome`/`cloudbeds-sync` y las rutas `cbSave/cbTest/reserve/rooms:assign`
pasan a declararse **desde la extensión**.

### 2.4 Datos propios de la extensión
Solo configuración y estado propio de Cloudbeds van a `ext_cloudbeds__*` (p. ej. settings/credenciales
si se mueven fuera de `crm_cloudbeds_settings`). Las reservas **no** (son del CRM).

---

## 3. Opciones

| | Qué logra | Toca Core | Riesgo pre-1dic | Cumple el objetivo |
|---|---|---|---|---|
| **A. Extracción completa** | Cloudbeds = paquete `extensions/cloudbeds/` propio, versión/firma aparte; SPI `CrmHost` + registro de acciones en el CRM; reservas se quedan core vía SPI | No (el motor de automatización es del CRM) | **Alto** (refactor grande en CRM + nuevo paquete + contrato binario) | **Sí** |
| **B. Igual que A pero por fases** | Fase 1: crear SPI + registro de acciones y mover el código de Cloudbeds detrás del SPI (sigue bundled). Fase 2: sacar el paquete aparte | No | Medio (fase 1) / Alto (fase 2) | Sí, al terminar fase 2 |
| **C. Solo separar versión** | Relajar la invariante y dar versión propia a Cloudbeds, pero **sigue en el JAR del CRM** | No | Bajo | **No** (no se despliega aparte) |

**Recomendación: Opción B (por fases).** La Fase 1 (SPI `CrmHost` + registro de acciones,
código de Cloudbeds detrás del SPI, comportamiento idéntico) es la que de verdad desacopla y es
testeable sin riesgo de despliegue; deja a Cloudbeds a un paso de salir del JAR. La Fase 2 (paquete
propio, firma, catálogo) se hace cuando el dueño quiera, ya con la superficie estable.

---

## 4. Lo que preservo sí o sí
- Precio por noche recién añadido: `splitEvenly` + parámetro `estimatedValue` en `reservationForm`/`reserve` (CRM 0.26.1). Se mueve con el código, intacto.
- Comportamiento idéntico de cara al usuario (reserva, check-in, bienvenida, sync, settings).

## 5. Decisiones que necesito del dueño antes de implementar
1. **¿Opción A, B o C?** (recomiendo B).
2. **`crm_reservations`**: ¿confirmas que se queda como tabla **core del CRM** y la extensión escribe vía SPI? (alternativa: moverla a `ext_cloudbeds__*`, lo que obliga a reescribir `CrmExit`/`CrmCustomerTimeline` — no recomendado).
3. **MCP tools de Cloudbeds**: ¿se quedan en el CRM gateadas por "extensión habilitada", o migran a la extensión?
4. **Versión**: al desacoplar, Cloudbeds arranca su propia línea (p. ej. `cloudbeds 1.0.0`, `minModuleVersion = <CRM actual>`). ¿OK relajar la invariante de `CrmExtensionsTest` para que Cloudbeds deje de estar atada a la versión del CRM?
5. **Momento**: producción arranca el 1 dic desde master. ¿Hacemos Fase 1 ahora en develop y Fase 2 después del 1 dic?
