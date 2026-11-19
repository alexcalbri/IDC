# Mantenimiento

Versión 0.1.0 (en desarrollo). Reemplaza los tableros de Monday de mantenimiento: órdenes de trabajo por propiedad y activo, planes
preventivos que generan órdenes solos, proyectos con flujo de aprobación (por ejemplo los de Emerald), lista de comprobación, notas,
adjuntos, costos, historial por activo, vista de próximos y vencidos, avisos e importación desde Monday.

## Qué reemplaza de Monday

- Tablero de mantenimiento preventivo (grupos Anual, Trimestral, Mensual, Semanal): son los **planes**; cada plan genera su orden y
  sus subitems pasan a ser la lista de comprobación.
- Tablero de solicitudes y proyectos (Pendiente de aprobación, En cotización, Aprobado, En progreso, Completado, Rechazado): son las
  **órdenes de tipo proyecto**, con cotización, departamento y propiedad.
- Las averías que se reportaban por mensaje: ahora las reporta cualquiera con acceso ("Reportar avería").
- La importación única de lo que ya existe en Monday la hace la extensión `mantenimiento_monday` (ver *Importación*).

## Permisos

| Permiso | Qué permite |
| --- | --- |
| `mantenimiento.view` | Entrar, reportar averías, solicitar proyectos, ver las órdenes que reportó o tiene asignadas, y ver activos y planes (lectura). |
| `mantenimiento.execute` | Ver todas las órdenes; en las asignadas a la persona o sin responsable: tomarla, cambiar el estado de trabajo, marcar la lista, anotar, adjuntar, costo real y resolución. |
| `mantenimiento.manage` | Todo en cualquier orden: crear, editar, asignar, cancelar, cotizar, aprobar o rechazar proyectos, y administrar planes, activos, propiedades y ajustes. |
| `mantenimiento.import` | Usar "Importar" y las herramientas de importación de los agentes (necesita una extensión de importación). |

El dueño del negocio y el del servidor tienen todo. Reglas puras en `MantenimientoScope` (con pruebas), aplicadas en SQL por
`MantenimientoRepository.listOrders`, las métricas y el historial de un activo, y por los agentes:

- `seesAll = execute || manage`; `seesOrder = seesAll || solicitante == usuario || responsable == usuario`.
- `canWork = manage || (execute && (sin responsable || responsable == usuario))`; `canEdit = manage`; `canTake`: sin responsable, en estado de trabajo.
- Usuarios asignables: activos que son dueño del negocio o tienen `mantenimiento.execute` o `mantenimiento.manage`.

## Estados, tipos y transiciones

Tipos: `preventive` (la genera un plan), `corrective` (avería o a mano) y `project`. Prioridades: `urgent`, `high`, `medium` (por
defecto), `low`.

Estados fijos: `requested` Pendiente de aprobación, `quoting` En cotización, `open` Abierta ("Aprobado" en proyectos), `in_progress`
En curso, `on_hold` En espera, `done` Completada, `rejected` Rechazada, `cancelled` Cancelada. Las correctivas y preventivas nacen
`open`; los proyectos nacen `requested`.

`OrderTransitions.allowed(kind, from, to, role)` (pura, igual en pantallas, tablero y agentes):

- Proyecto: `requested` pasa a `quoting`, `open` (aprobar), `rejected` o `cancelled`; `quoting` a `open`, `rejected`, `requested` o `cancelled`.
  Solo quien gestiona. Aprobar guarda `approved_at/by`; rechazar exige un motivo (hasta 1000 caracteres).
- `open`, `in_progress` y `on_hold` pasan entre sí y a `done` si el usuario puede trabajar la orden; a `cancelled`, solo quien gestiona.
- `done` pasa a `in_progress` (reabrir), solo quien gestiona. `rejected` y `cancelled` son finales.
- Las correctivas y preventivas no usan `requested`, `quoting` ni `rejected`.
- Al completar se guardan `completed_at/by`; al salir de `done` se limpian. Cada cambio deja una nota de tipo `status`.

## Recurrencia de los planes

`PlanSchedule` (pura, con pruebas). Frecuencias: mensual, trimestral, anual o cada N días (1 a 3650). Cada plan tiene `schedule_mode`:

- **`fixed` (calendario fijo, por defecto):** la fecha avanza sola al generar la orden. Se cuenta siempre desde el ancla del plan
  (`anchor_date`, la primera fecha o la que se edita), no encadenada, para que el fin de mes no se arrastre; si queda en el pasado se
  avanza hasta hoy (las fechas perdidas no se crean).
- **`floating` (desde el último cierre):** al generar la orden `next_due` queda vacío y se calcula al completar la orden (cierre más
  la frecuencia) o al cancelarla (hoy más la frecuencia).

El plan tiene `lead_days` de anticipación (0 a 90) y una sola orden abierta a la vez (índice único parcial por plan: no se acumulan
órdenes viejas). Si la generación de un plan falla se anota `last_error_at` y el plan se excluye 6 horas (se limpia al editarlo o generar bien). Desactivar un plan
(`active = false`) detiene la generación. La próxima fecha se pide al crear o editar el plan.

## Datos (base de la empresa)

Migración `migrations/V001__create_mantenimiento.sql` (cada empresa tiene su propia base):

- `mantenimiento_properties`, `mantenimiento_assets`: propiedades (únicas por nombre sin mayúsculas, archivables) y activos (únicos por
  propiedad y nombre sin mayúsculas entre los no archivados).
- `mantenimiento_plans` (con `anchor_date` y `last_error_at`), `mantenimiento_plan_checklist`: planes y su lista de comprobación.
- `mantenimiento_orders`: folio por secuencia (`OT-0007`), tipo, estado, prioridad, propiedad, activo, plan y fecha del plan (únicos
  juntos: el generador no duplica), cotización, costo real, resolución, vínculos con Tareas y Compras, `import_key`.
- `mantenimiento_order_checklist`, `mantenimiento_order_notes` (`note`, `status`, `assign`, `system`), `mantenimiento_attachments`
  (el archivo vive en el almacenamiento de la empresa; `order_id` es NULL hasta guardar la orden), `mantenimiento_reminders`
  (avisos de vencimiento ya enviados) y `mantenimiento_settings` (una fila: días de "por vencer" y tablero de Tareas).
- Al duplicar una empresa se copian propiedades, activos, planes, su lista y los ajustes (sin usuarios ni tablero de Tareas).

## Trabajos periódicos

- `plan-generator` (1 h, lote de 200 planes): crea la orden preventiva de los planes activos a los que ya les toca y no tienen una abierta;
  una transacción por plan; copia la lista; asigna al responsable por defecto si sigue siendo asignable; un error por plan se registra y sigue.
- `due-reminders` (30 min, lote de 200): órdenes abiertas con responsable que vencen en `due_soon_days` (por defecto 1) o ya vencieron; se
  reclama primero la fila de `mantenimiento_reminders` y solo avisa quien la insertó.
- `attachments-cleanup` (1 h): borra el archivo y la fila de adjuntos quitados y de subidas sin guardar tras 24 horas.
- `tareas-sync` (2 min, lote de 50): refleja las órdenes marcadas en Tareas (solo con tablero configurado).
- `import-runner` (1 min, presupuesto de 40 s): avanza la lectura y aplicación de las fuentes de importación.

## Avisos (centro de notificaciones del Core)

Se crean con el notificador del módulo dentro de `runCatching` (un fallo se registra y nunca rompe el cambio); quien hace el cambio no
recibe aviso de él. Destino: pantalla `order` con el id de la orden.

- "Te asignaron una orden de mantenimiento" al responsable (pantalla, agente o generador), con el texto "OT-0007 · título".
- "Nueva solicitud de proyecto" a quienes gestionan y al dueño del negocio; "Proyecto aprobado" y "Proyecto rechazado" al solicitante.
- "Nueva avería reportada" a quienes gestionan, si la avería nace sin responsable.
- "Orden de mantenimiento completada" al solicitante. "Por vencer" y "vencida" al responsable.

## Pantallas y rutas

Pantallas nativas en `/modules/mantenimiento/ui` (`GET /{pantalla}` y `POST /{pantalla}?op=...` con valores JSON; la respuesta es la
pantalla siguiente con un aviso). Todo exige `mantenimiento.view`; las reglas por orden se aplican después. Navegación: Próximos
(`upcoming`, inicio), Mis órdenes (`mine`), Órdenes (`orders`, ejecutar o gestionar), Activos (`assets`), Planes (`plans`), Ajustes
(`admin`, gestionar) e Importar (`importar`, con `mantenimiento.import` y una fuente activa). Botones "Reportar avería"
(`new-order?kind=corrective`) y "Solicitar proyecto" (`new-order?kind=project`).

- `upcoming?property=`: métricas (abiertas, vencidas, por aprobar, completadas este mes) y grupos Vencidas, Próximos 7 días, Próximos 30
  días, Más adelante y Sin fecha (los vacíos no aparecen), más "Planes que generan pronto".
- `orders?status=&kind=&property=&assignee=&q=&view=list|board`: lista (hasta 200, con aviso) o tablero por estado con la misma comprobación de transición.
- `order?id=`: formulario según el rol, botones de estado por transiciones permitidas, "Tomar orden", tarjeta "Decisión" (con motivo)
  para rechazar un proyecto, lista de comprobación, notas, adjuntos y plan de origen. Quien ejecuta trabaja la orden solo mientras está en
  estado de trabajo (`canWorkNow`); quien gestiona, siempre. El solicitante anota en su orden solo si sigue activa.
- `new-order?kind=`: reportar avería o solicitar proyecto.
- Permisos de pantalla: `admin`, y toda acción (`op`) de `plan`, `asset`: gestionar; `importar`: importar; el resto, ver.
- `assets`, `asset?id=` (historial de órdenes filtrado por visibilidad y planes del activo), `plans`, `plan?id=` (lista editable y órdenes
  generadas), `admin` (propiedades, días de "por vencer", tablero de Tareas) e `importar?source=`.
- Adjuntos: `POST /modules/mantenimiento/ui/files` (25 MB, `view`; queda huérfano hasta guardar) y `GET /modules/mantenimiento/ui/files/{id}`
  (solo quien ve la orden; un huérfano, solo su autor).

## Herramientas MCP

Cada agente actúa como el usuario que lo conectó, con sus permisos vivos: cada herramienta declara `mantenimiento.view` y además aplica
por orden `MantenimientoScope` y `OrderTransitions`; las listas filtran la visibilidad en SQL. Ninguna maneja credenciales y los archivos
nunca se exponen (solo nombre y tamaño). Argumentos en `snake_case`; estados, tipos y prioridades son los códigos fijos. Nombres calificados
`mantenimiento_<herramienta>`.

| Herramienta | Qué hace |
| --- | --- |
| `list_orders` (solo lectura) | Órdenes visibles; por defecto solo las abiertas. Filtros `status`, `kind`, `property`, `asset_id`, `assignee` (`me`, id, nombre o correo), `due_before`, `q`, `include_closed`, `limit` (1 a 100, 50 por defecto). |
| `get_order` (solo lectura) | Una orden (`id` o `number`) con checklist, últimas notas, nombres de adjuntos y lo que puede hacer el usuario (`can`). |
| `list_plans`, `get_plan` (solo lectura) | Planes con frecuencia, modo y próxima fecha; el plan con su lista y las últimas órdenes visibles. |
| `list_assets`, `list_people` (solo lectura) | Activos por propiedad o texto; personas asignables (el correo solo para quien gestiona). |
| `upcoming` (solo lectura) | Contadores, vencidas, próximas (`days` hasta 90) y planes que generarán orden pronto. |
| `create_order` | Avería (`corrective`) o proyecto (`project`, queda Pendiente). Responsable, fecha y cotización solo quien gestiona. `external_ref` es idempotente: clave `agent:<ref>` y `created=false` si ya existía. Avisa como las pantallas. |
| `update_order` | Cambia solo lo enviado. Datos, responsable, fecha y cotización: gestionar. Costo real, resolución y estado de trabajo: poder trabajar la orden. Aprobar, rechazar (`reason`) y cancelar: gestionar. Todo el cambio es una transacción. |
| `close_order` | Completa la orden guardando antes `resolution` y `cost_amount`; exige poder trabajarla; avisa al solicitante. |
| `add_note` | Anota en una orden que el usuario trabaja o que él reportó. |

Se suman las herramientas de las extensiones de importación (permiso `mantenimiento.import`). Los agentes no crean órdenes preventivas
(las genera un plan) ni gestionan planes, activos o propiedades: eso queda en la app.

## Importación

El módulo no sabe de dónde vienen los datos: las **extensiones** de importación implementan `MantenimientoImportSource`
(`MantenimientoImport.kt`) y el módulo las enlaza con `linkExtensions` (`supportsExtensions = true`, permiso de extensión
`mantenimiento.import.write`).

```kotlin
interface MantenimientoImportSource {
    val sourceId: String; val title: String
    fun enabled(tenant: Connection, companyCode: String): Boolean
    fun screen(context: MantenimientoImportContext, params: Map<String, String>): List<UiNode>
    fun action(context: MantenimientoImportContext, op: String, values: Map<String, String>): MantenimientoImportReply
    val mcpTools: List<McpTool> get() = emptyList()   // permiso mantenimiento.import, sin credenciales
    fun runPending(context: MantenimientoImportJobContext, budget: Duration): Int
}
```

- Pantalla `importar?source=` y `POST importar?source=&op=` (exigen `mantenimiento.import`, comprobado antes de abrir la conexión). Con
  varias fuentes hay un botón por cada una.
- El trabajo `import-runner` llama a `MantenimientoImportSources.runPending`: cada fuente recibe una parte igual del presupuesto; una que
  falla se registra y las demás siguen.
- `MantenimientoImportApply` escribe las filas: `applyPlan` y `applyOrder` son una transacción por fila, idempotentes por `import_key`
  (la extensión manda `monday:<itemId>`) y sin avisos. Propiedad y activo se buscan por nombre sin mayúsculas y se crean si faltan; las
  personas se emparejan por correo y las que no, se anotan al final de la descripción. Las órdenes completadas guardan `completed_at`; las
  abiertas por vencer registran sus recordatorios como enviados para no inundar de avisos. Checklist y notas llevan `import_key`; topes de
  100 elementos de lista y 2000 notas por orden; título vacío se omite. Importar otra vez actualiza lo ya importado.
- **Extensión `mantenimiento_monday`** (`extensions/mantenimiento_monday`): token de Monday sellado con los secretos del Core, lector
  GraphQL, pantallas y herramientas `monday_list_boards`, `monday_read_board`, `monday_import_status` y `monday_apply`, que nunca reciben el
  token. Leer y aplicar tableros exige además `mantenimiento.manage` (importar crea planes y órdenes de cualquier tipo). Cada tablero va a "Plantillas preventivas" (planes) o a "Órdenes / proyectos" (con tipo por defecto).

## Integraciones opcionales (Tareas y Compras)

Apagadas por defecto y **sin dependencia de compilación**: los módulos opcionales se compilan por separado contra el Core, así que no se
importa código de Tareas ni de Compras. `MantenimientoBridge` llama a sus **herramientas MCP públicas** (como lo hace `agentes`) con los
permisos vivos de un usuario: si el módulo no está instalado o activo, o el usuario no tiene el permiso de la herramienta, la integración
queda "no disponible" y no rompe nada.

- **Tareas:** en Ajustes, quien gestiona elige un tablero (se listan con `tareas_list_boards` con su propio usuario, que queda como
  publicador y debe seguir teniendo `mantenimiento.manage`; si lo pierde, la sincronización se detiene). Al crear, asignar, cambiar título o fecha, completar o cancelar una orden con responsable, la orden se marca
  (`tareas_sync_needed`) y el trabajo `tareas-sync` crea la tarea enlazada con `tareas_create_task` (`external_ref = mantenimiento:order:<id>`,
  idempotente) y la completa o cancela cuando cambia la orden. Si Tareas no está disponible, la marca queda y se reintenta.
- **Compras:** en una orden, "Solicitar compra" (para quien puede trabajarla y tiene el permiso de la herramienta en Compras) llama a
  `compras_create_request` con el título, la descripción (propiedad, activo), el monto estimado (cotización o costo) y la moneda; la
  reserva es atómica (`purchase_linking_at`) para no duplicar. La orden guarda el id y el folio, y "Ver solicitud de compra" abre la
  pantalla de Compras. Sin Compras disponible no hay botón.
- **Necesidad del Core (pendiente):** un puente formal entre módulos (servicios con interfaz en el Core o algo como
  `access.callModuleTool`) para no depender de las herramientas MCP ni de sus nombres.

## Pendiente

- Pruebas contra un PostgreSQL real (SQL del repositorio, migración V001, manejadores MCP): hoy se prueban las reglas puras, la lectura de
  formularios, las pantallas y la declaración de las herramientas.
- "Hoy" para recordatorios y vencidas usa la zona horaria del servidor; las empresas aún no tienen zona horaria.
- No hay PM por medidor u horas de uso, inventario de repuestos, órdenes de compra propias (eso es Compras), lógica condicional en listas
  ni varios activos por orden.
- El dueño del servidor (que no es un usuario de la empresa) no puede adjuntar archivos, y los campos que guardan quién hizo algo quedan vacíos.

## Código

`MantenimientoModel` (tipos y validaciones), `MantenimientoScope` (permisos y transiciones, puros), `PlanSchedule` (recurrencia, pura),
`MantenimientoRepository` (SQL), `MantenimientoNotify`, `MantenimientoJobs`, `MantenimientoBridge`, `MantenimientoTareasSync`,
`MantenimientoPurchases`, `MantenimientoImport` (contrato para extensiones), `MantenimientoMcpTools` y `MantenimientoServerModule`.
