# Propietarios

Registro de desarrollos (urbanizaciones o proyectos), sus lotes y quienes son sus propietarios. Cada lote guarda su número, número de
registro, área en m², tipo de propiedad y estado, sus titulares vigentes (con porcentaje opcional), el historial de cambios de
titularidad y archivos adjuntos como escrituras y planos. Versión 0.1.0.

Los propietarios son clientes de **Clientes** (módulo base): aquí solo se guarda quién es titular de qué lote y desde/hasta cuándo.
Los datos de contacto no se duplican.

## Datos

Migración `migrations/V001__create_propietarios.sql` (registrada en `PropietariosServerModule.migrationPaths`):

| Tabla | Para qué |
| --- | --- |
| `propietarios_developments` | Desarrollos: nombre, ubicación, enlace de mapa, coordenadas, notas. Se archivan, no se borran. `import_key` único. |
| `propietarios_lots` | Lotes de un desarrollo (FK `RESTRICT`). Estado fijo: `disponible`, `reservado`, `vendido`, `en_construccion`, `construido`. Número de lote único por desarrollo (entre los no archivados). Índices por número de lote y de registro. |
| `propietarios_ownerships` | Titularidades: lote, cliente (FK a `customers`, `RESTRICT`), porcentaje opcional (>0 y ≤100), `starts_on`, `ends_on`, `ended_at`. Vigente = `ends_on` vacío o posterior a hoy. Un cliente no puede tener dos titularidades abiertas del mismo lote. |
| `propietarios_lot_history` | Historial por lote: `alta`, `traspaso`, `baja`, `edicion`, `importacion`. |
| `propietarios_attachments` | Adjuntos (patrón de Tareas): subida pendiente sin lote, `backend` + `ref` del almacenamiento de archivos, limpieza por trabajo. |

Todo se filtra por empresa (cada empresa tiene su base de datos). Todo el que tiene `propietarios.view` ve todos los desarrollos.
Solo se muestran clientes visibles (`origin_company_code = '' OR shared`, sin archivar).

## Rutas (UI nativa, `/modules/propietarios/ui`, inicio `desarrollos`)

| Pantalla | Qué muestra |
| --- | --- |
| `desarrollos` | Lista con métricas (lotes, área total, titulares vigentes). Con `propietarios.admin`, formulario para crear un desarrollo. |
| `desarrollo?id` | Lotes del desarrollo con filtro por estado y búsqueda, totales de área por estado, enlace al mapa; edición y archivo (admin). |
| `lote?id` | Datos del lote, titulares vigentes (y anteriores), cotitulares, adjuntos, historial y botón **Ver en mapa**. `lote?development=<id>` abre el formulario de un lote nuevo. |
| `traspaso?lot` | Cambio de titularidad: fecha, hasta 4 titulares nuevos (campo con sugerencias de clientes) con porcentaje, y notas. Vacío = baja. |
| `titularidad?id` | Editar porcentaje y fechas de una titularidad (historial `edicion`). |
| `propietarios` | Clientes con titularidades vigentes: lotes y área total; búsqueda por nombre o correo. |
| `propietario?id` | Lotes vigentes y pasados de un cliente; botón para abrir su ficha en Clientes. |
| `buscar?q` | Busca por número de lote o de registro en todos los desarrollos. |
| `importar` | Pantalla de las extensiones de importación (solo con `propietarios.import` y alguna fuente activa). |

Rutas de apoyo: `POST /files` y `GET /files/{id}` (adjuntos, máximo 25 MB, descarga siempre como archivo con `safeContentType`) y
`GET /suggest/customers` (exige `propietarios.manage` y `clientes.view`; solo clientes visibles, máximo 6, texto recortado a 200).

## Permisos

- `propietarios.view`: ver desarrollos, lotes, titulares, historial y adjuntos.
- `propietarios.manage`: crear y editar lotes, registrar cambios de titularidad y cotitulares, adjuntar archivos.
- `propietarios.admin`: crear, editar y archivar desarrollos; archivar lotes.
- `propietarios.import`: importar con extensiones (permiso de extensión: `propietarios.import.write`).

## Cambio de titularidad

En una transacción, con el lote bloqueado (`FOR UPDATE`): cierra las titularidades abiertas (`ends_on` = fecha del cambio), abre las nuevas
(los clientes deben ser visibles, sin repetidos, porcentajes sumando 100 % como máximo), pasa el lote a `vendido` si estaba `disponible` o
`reservado` y escribe el historial (`traspaso`, o `baja` si no hay titulares nuevos). Una fecha anterior al inicio de una titularidad
abierta se rechaza. Tras confirmar, y sin deshacer nada si falla, escribe en el historial del cliente (`propietario_alta`,
`propietario_baja`) con enlace al lote.

## MCP (agentes)

`list_developments`, `search_lots`, `get_lot`, `search_owners`, `get_owner` (todas `propietarios.view`, solo lectura),
`register_ownership_change` y `add_lot` (`propietarios.manage`). Cada herramienta respeta los permisos en vivo del usuario y usa las mismas
reglas que las pantallas. Ninguna recibe credenciales. Los adjuntos solo se gestionan desde la app. Las extensiones de importación suman sus
herramientas con `PropietariosImportSource.mcpTools`.

## Importación

Las extensiones (por ejemplo `propietarios_monday`) implementan `PropietariosImportSource` y entregan `ImportedLot` a
`PropietariosImportApply`: `summary` (vista previa pura), `ensureDevelopment`, `apply` (una transacción por fila, candado por `import_key`,
idempotente) y `attachFile`. Reglas de `apply`: lote por `import_key`; un lote del mismo número sin clave se adopta; propietarios por correo
(cliente visible) o por nombre exacto entre clientes de tipo `propietarios`, y si no existen se crean; si los porcentajes suman más de 100 la
fila se aplica sin porcentajes y se anota en el historial. Tras confirmar escribe el historial del cliente y avisa a Clientes de los clientes
nuevos. El trabajo `import-runner` corre cada minuto con un presupuesto de 40 segundos.

Nota: quien tiene `propietarios.import` crea clientes nuevos en Clientes al aplicar una importación (igual que Membresía), sin necesitar
permisos de Clientes. Un traspaso no cobra cuotas ni cargos: eso queda fuera de 0.1.0.

## Mapa

No hay una pieza nativa de mapa en la interfaz del Core: **Ver en mapa** abre un enlace externo (el `map_url` del lote si empieza por
`https://`; si no, `https://www.google.com/maps?q=<lat>,<lng>`). Pieza faltante: un mapa interactivo con los lotes (pendiente en el Core).

## Fuera de 0.1.0: cuotas y cargos

Las tablas previstas para una versión posterior, sin cambios en V001: `propietarios_fee_plans`, `propietarios_charges (lot_id, customer_id, …)`
y `propietarios_payments`.

## Diferencia con la extensión `inmobiliaria` del CRM

`inmobiliaria` es el inventario comercial de propiedades en venta; Propietarios es el registro de titulares de los lotes de un desarrollo
(quién es dueño hoy y quién lo fue). Integración posible más adelante: al cerrar una venta en `inmobiliaria`, proponer el traspaso del lote.

## Trabajos

`attachments-cleanup` (cada hora): borra adjuntos quitados y subidas cuyo formulario no se guardó en 24 horas. `import-runner` (cada minuto).
