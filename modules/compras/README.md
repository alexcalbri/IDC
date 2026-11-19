# Modulo Compras

Version 0.2.0. Estado: en desarrollo (`.development`).

Solicitudes de compra por empresa: se solicita, se aprueba y se procede. Cotizar es opcional. Cada negocio es una empresa de IdeasCore, por eso no hay catalogo de sedes; el departamento si es un catalogo configurable.

## Que reemplaza de Monday

- Los tableros **Purchase & Project Requests** de Hotel, F&B, Escuela, Maintenance y Admin: un solo modulo con departamento como filtro.
- El tablero **IT - Requirement**.
- El dashboard **High & Critical Requests**: pantalla "Altas y criticas".
- **Solicitudes de Proyectos - Mantenimiento**: las solicitudes del departamento Mantenimiento (ver "Origen para mantenimiento").

## Datos (base de cada empresa)

Migracion `migrations/V001__create_compras.sql`. Dinero siempre `numeric(14,2)` con su moneda de 3 letras (`BigDecimal` en el codigo, nunca `Double`).

- `compras_settings`: una fila con la moneda por defecto (USD).
- `compras_departments`: departamentos o categorias (Hotel, Alimentos y bebidas, Mantenimiento, Administracion, IT de ejemplo), activables y desactivables.
- `compras_approvers`: aprobadores designados con limite de monto por usuario (`max_amount` nulo = sin limite) y moneda del limite. Sin clave foranea al Core: el codigo valida que el usuario exista.
- `compras_requests`: folio visible `C-00012` (`number`), titulo, descripcion, departamento, monto estimado y moneda, fecha requerida, prioridad (low, medium, high, critical), estado, solicitante, decision (quien, cuando, motivo), monto y moneda aprobados (congelados al aprobar) y origen opcional.
- `compras_quotes`: cotizaciones por proveedor; una sola elegida por solicitud.
- `compras_attachments`: adjuntos de respaldo (soporte, cotizacion, proforma, factura, otro).
- `compras_events`: historial (creada, editada, cambio de estado, cotizacion, adjunto) con actor y fecha.

## Flujo

Estados: Borrador (`draft`), En cotizacion (`quoting`), Pendiente de aprobacion (`pending_approval`), Aprobada (`approved`), Rechazada (`rejected`), Comprada / en progreso (`in_progress`), Completada (`completed`) y Cancelada (`cancelled`).

| Desde | Hacia | Quien |
| --- | --- | --- |
| draft | quoting, pending_approval, cancelled | solicitante o manage |
| quoting | draft, pending_approval, cancelled | solicitante o manage |
| pending_approval | approved | quien pueda aprobar por limite |
| pending_approval | rejected (motivo obligatorio) | aprobador activo (cualquier limite) o dueno |
| pending_approval | draft (retirar) | solicitante o manage |
| rejected | draft (reabrir) | solicitante o manage |
| approved | in_progress, cancelled | solicitante, quien aprobo o manage |
| in_progress | completed | solicitante, quien aprobo o manage |

- Para pedir aprobacion hacen falta titulo y un monto mayor a cero. El monto a aprobar es el de la cotizacion elegida (con su moneda) o, si no hay, el estimado.
- Editar datos y cotizaciones: en borrador o cotizacion (solicitante o manage); manage en cualquier estado no terminal.
- Monto, moneda y cotizaciones solo se editan en borrador o en cotizacion. Para corregir una solicitud pendiente o rechazada, se retira o reabre a borrador (la decision queda en el historial). Una aprobada no vuelve a borrador: si su monto cambia, se cancela y se crea otra, para que el monto aprobado nunca se altere.
- Cada cambio de estado se hace con `UPDATE ... WHERE id = ? AND status = ?`: si otra persona cambio la solicitud antes, responde "La solicitud cambio, recarga". Volver a borrador limpia la decision (queda en el historial).

### Regla de aprobacion

Basta UNA aprobacion de alguien cuyo limite cubra el monto. Se puede aprobar si:

- se es dueno del negocio o del servidor (sin limite, aunque no este en la lista), o
- se tiene el permiso `compras.approve`, se esta en la lista de aprobadores con fila activa y el limite cubre el monto: sin limite, o la misma moneda del limite y monto menor o igual. Un limite no cubre montos en otra moneda (no hay conversion).

Ejemplo: Martin hasta 1000 USD, Taylor hasta 5000 USD, Jackson sin limite. Una compra de 3000 USD la pueden aprobar Taylor o Jackson. La autoaprobacion se permite si se cumple lo anterior.

## Permisos y visibilidad

| Permiso | Para que |
| --- | --- |
| `compras.view` | Entra al modulo y ve sus propias solicitudes. |
| `compras.create` | Crea solicitudes y edita o cancela las suyas. |
| `compras.view_all` | Ve todas las solicitudes y los totales. |
| `compras.approve` | Aprueba o rechaza dentro de su limite (ademas debe estar en la lista de aprobadores). |
| `compras.import` | Importa solicitudes desde otras herramientas (Monday) con las extensiones de importacion instaladas. |
| `compras.manage` | Departamentos, aprobadores y limites, moneda; edita y mueve cualquier solicitud. |

Un usuario ve una solicitud si tiene `view_all` o `manage`, si la pidio, si la decidio, o si tiene `approve` y esta pendiente de aprobacion con un monto que su limite cubre. La misma regla (un solo helper SQL parametrizado) vale en listas, tablero, totales, busquedas, detalle, descargas de adjuntos y MCP. Los totales y el informe de altas y criticas son para `view_all` o `manage`; sin ellos el informe se calcula sobre las solicitudes visibles.

## Pantallas

Inicio, Solicitudes (filtros y "Ver mas"), Tablero (una columna por estado; rechazar se hace desde el detalle porque pide motivo), Por aprobar, Altas y criticas, Totales (solo `view_all` o `manage`; por departamento, mes y estado, separados por moneda), Nueva solicitud, Detalle (datos, cotizaciones, adjuntos, botones de transicion validos e historial) y Ajustes (solo `manage`: moneda, departamentos y aprobadores con limite).

## Rutas

Interfaz nativa bajo `/modules/compras/ui`: `GET /{screen}`, `POST /{screen}` con `op`, `POST /files` (subida) y `GET /files/{id}` (descarga). Cada ruta exige acceso al modulo y el permiso exacto de la operacion.

## MCP (agentes)

Cada herramienta aplica los permisos vivos del usuario y la misma visibilidad y reglas que las pantallas. Los montos viajan como texto decimal. No hay herramientas que manejen credenciales ni adjuntos (los archivos se suben desde la app; el MCP solo muestra sus nombres).

| Herramienta | Permiso | Solo lectura |
| --- | --- | --- |
| `create_request` | `compras.create` | no |
| `list_requests` | `compras.view` | si |
| `get_request` | `compras.view` | si |
| `submit_request` | `compras.create` | no |
| `approve_request` | `compras.approve` | no (aplica el limite del usuario) |
| `reject_request` | `compras.approve` | no (motivo obligatorio) |
| `purchase_summary` | `compras.view_all` | si |

El nombre del agente queda en el historial junto al del usuario ("Ana (agente X)").

## Notificaciones

Nunca hacen fallar la accion. El aviso a aprobadores exige que el destinatario tenga `compras.view`. Al pasar a pendiente de aprobacion: a cada aprobador cuyo limite cubre el monto (excepto al solicitante); si ninguno lo cubre, a los aprobadores sin limite y la pantalla (o el MCP) avisa "Ningun aprobador cubre este monto". Al aprobar o rechazar: al solicitante, con monto y motivo, si no decidio el mismo. Al completar: al solicitante si la completo otra persona. Destino: pantalla `request` con el id.

## Adjuntos

Facturas, proformas, cotizaciones y otros respaldos, hasta 25 MB por archivo y 100 por solicitud. Se pueden anadir en cualquier estado menos cancelada (las facturas llegan despues de comprar); quitarlos solo en borrador o cotizacion, o con `manage`. Cada usuario puede tener hasta 50 subidas pendientes (sin vincular a una solicitud). La descarga comprueba que la solicitud sea visible para el usuario, sirve solo tipos de archivo seguros y envia `X-Content-Type-Options: nosniff`. Un trabajo cada hora (`attachments-cleanup`) borra archivos quitados o subidas huerfanas de mas de un dia.

## Origen para mantenimiento (previsto)

`compras_requests.source_kind` y `source_ref` guardan de donde viene una solicitud, por ejemplo `mantenimiento.work_order` y el id de la orden; son ambos nulos o ambos con valor, y unicos por pareja. `ComprasRepository.create(..., source)` es idempotente: con el mismo origen devuelve la solicitud existente. Todavia no hay pantalla ni herramienta MCP que use el origen: lo usara el modulo mantenimiento.

## Importacion

El modulo soporta extensiones (`supportsExtensions`) que aportan fuentes de importacion; la primera es `compras_monday` (importar tableros de Monday). Cada fuente implementa `ComprasImportSource` y el modulo la enlaza al arrancar (`linkExtensions`). La extension declara el permiso `compras.requests.import`.

- Pantalla **Importar** (nav visible solo con `compras.import`): una tarjeta por fuente activa en la empresa (`isEnabled`), con las piezas que arma la extension. Sin fuentes activas muestra como instalarla; la operacion de una fuente no activa se rechaza.
- `POST /modules/compras/ui/import` con `op=<fuente>:<operacion>`: exige `compras.import` y la atiende la fuente; un error de validacion vuelve a la pantalla con un aviso rojo.
- Trabajo `imports` (cada minuto): llama a `runPending(ctx, 200)` de cada fuente; un fallo de una fuente se registra y no frena a las demas.
- Motor comun `ComprasImportEngine.apply`: idempotente por `import_key` (V002). Las solicitudes importadas quedan con decision "Importado de Monday" (sin aprobador real) y un evento `imported`; no avisan una por una.
- MCP: las herramientas extra de cada fuente (permiso `compras.import`) se suman a las del modulo.
- Las solicitudes importadas nunca se borran al desinstalar la extension.

## Pendientes

- Integracion con el modulo mantenimiento (crear la solicitud desde una orden de trabajo y enlazarla).
- Conversion de monedas en los totales (hoy se separan por moneda).
- Adjuntos y cotizaciones desde MCP.
- Prueba manual en la app de las pantallas, la subida de archivos y las notificaciones.
