# Extension `mantenimiento_monday`

Importa tableros de [monday.com](https://monday.com) al modulo **Mantenimiento** (version minima 0.1.0). Es una fuente de
importacion del modulo (`MantenimientoImportSource`) y no tiene rutas propias. Al elegir el tablero se elige el destino:

- **Plantillas preventivas (planes)**: cada elemento es un plan.
- **Ordenes / proyectos**: cada elemento es una orden; el tipo por defecto es averia (`corrective`) o proyecto (`project`).

La sugerencia sale del nombre del tablero: "Solicitud" o "Proyecto" -> ordenes de proyecto; "Mantenimiento" o "Tareas" -> planes.

## Que importa

| monday | Plan | Orden |
|---|---|---|
| Elemento | Plan (clave `monday:<id>`) | Orden (clave `monday:<id>`) |
| Grupo | Frecuencia: Anual/Anuales/Yearly = anual; Trimestral/Trimestrales/Quarterly = trimestral; Mensual/Mensuales/Monthly = mensual; Semanal/Weekly = cada 7 dias; "Cada N dias" = cada N dias; sin coincidencia = mensual y se anota el problema | Estado si no hay columna de estado |
| Columna de estado ("Estado de aprobacion", "Estado", Status) | Se anota en la descripcion | Estado: Pendiente de aprobacion = pendiente; En cotizacion = en cotizacion; Aprobado = abierta; Aprobado - En progreso / En curso / Working on it = en curso; Completado / Listo / Done = completada; Rechazado = rechazada; Stuck / Detenido = en espera; Cancelado = cancelada. En ordenes que no son proyecto, pendiente y en cotizacion pasan a abierta y rechazada a cancelada |
| Fecha, o fin de una linea de tiempo | Proxima fecha | Fecha limite |
| Personas (Asignado a / Responsable / Owner) | Responsable | Responsable |
| Prioridad / urgencia | Prioridad (urgente, alta, media, baja) | Prioridad |
| Notas / Descripcion | Descripcion | Descripcion |
| Propiedad, Activo / equipo | Propiedad, activo (se crean si faltan) | Propiedad, activo (se crean si faltan) |
| Departamento | - | Departamento |
| Cotizacion (numeros, con coma o punto) | - | Monto cotizado en USD |
| Cotizado por (personas o texto) | - | Cotizado por |
| Otras columnas con valor | Descripcion, como `Columna: valor` | Descripcion, como `Columna: valor` |
| Subelementos | Lista de comprobacion; clave `monday:sub:<id>` | Lista de comprobacion (hecha si su estado es Done); clave `monday:sub:<id>` |
| Actualizaciones y respuestas | Al final de la descripcion (tope 5000) | Notas `[Autor · aaaa-mm-dd] texto` con la fecha original; clave `monday:upd:<id>` |
| Archivos de columnas y de actualizaciones | - | Adjuntos (25 MB cada uno); clave `monday:asset:<id>` |

Tableros reales con sugerencia probada: "Tareas de Mantenimiento" (grupos Anuales / Trimestrales / Mensuales; columnas Estado,
Asignado a, Prioridad, Fecha limite, Notas) y "Solicitudes de Proyectos - Mantenimiento" (grupos Pendiente de aprobacion, En
cotizacion, Aprobado - En progreso, Completado, Rechazado; columnas Departamento, Propiedad, Descripcion, Cotizacion en
dolares, Cotizado por, Prioridad, Estado de aprobacion).

Las personas se emparejan por **correo** (`users(ids)` de monday) con los usuarios asignables de Mantenimiento. Las que no
tienen usuario quedan anotadas en la descripcion y la vista previa las lista. Una cotizacion que no se puede leer como monto
y un estado desconocido se anotan como problemas en la vista previa.

## Permisos

- Del modulo: `mantenimiento.import` ("Importar datos"), para la pantalla Importar y las herramientas MCP. Cada herramienta
  comprueba el permiso vivo del usuario y que la extension siga activa en la empresa.
- Ademas se pide `mantenimiento.manage` para leer un tablero (crea planes y ordenes de cualquier tipo). Aplicar o cancelar
  una importacion: solo quien la inicio o quien gestiona mantenimiento.
- De la extension (extension.json): `network.outbound:api.monday.com`, `secrets.write`, `mantenimiento.import.write`.

## Flujo (Mantenimiento > Importar > Monday)

1. Guardar el token de la API de monday (campo de contrasena que nunca se rellena; se guarda sellado con la llave de la empresa; solo se muestra "configurado").
2. Cargar tableros y elegir uno.
3. Elegir destino (planes u ordenes y su tipo) y revisar el mapeo de columnas, que ya viene sugerido por titulo y tipo (espanol e ingles).
4. **Leer tablero**: el trabajo `import-runner` del modulo lee una pagina por paso (cursor guardado), hasta 20 000 elementos.
5. Vista previa: planes u ordenes, propiedades y activos que se crearan, lista de comprobacion, notas, archivos, personas sin usuario y problemas.
6. **Aplicar**: 100 filas por corrida (cada minuto) con sus archivos. Muestra el progreso; al terminar, los conteos y las filas con problemas.
7. **Cancelar** en cualquier paso: lo ya importado se conserva.

**Aviso:** reimportar el mismo tablero actualiza los planes y ordenes ya importados y sobrescribe los cambios hechos en
Mantenimiento a esos campos. Solo hay una importacion abierta (leyendo, por revisar o aplicando) por empresa. Los fallos
pasajeros de monday (red, tiempo agotado, 429, 5xx) se reintentan hasta 5 veces; los permanentes dejan la importacion con
error. Cada llamada y descarga respeta el tiempo de la corrida y no empieza con menos de 5 s.

Es idempotente: importar de nuevo el mismo tablero actualiza por la clave `monday:<id>` (subelementos `monday:sub:<id>`,
actualizaciones `monday:upd:<id>`, archivos `monday:asset:<id>`), sin duplicar; los archivos ya importados se omiten. La
importacion no envia avisos de asignacion ni de vencimiento (es de migracion). El trabajo que avanza la importacion es del
modulo: `jobs` de la extension queda vacio.

## Herramientas MCP (las publica el modulo como `mantenimiento_<nombre>`)

`monday_list_boards` (solo lectura), `monday_read_board(board_id, target, default_kind?)` (mapeo sugerido automatico),
`monday_import_status(job_id?)` (solo lectura) y `monday_apply(job_id)`. Ninguna recibe ni devuelve el token; el token se
guarda solo desde la app.

## Tablas

`ext_mantenimiento_monday__settings` (token sellado), `ext_mantenimiento_monday__jobs` (con `target` y `default_kind`),
`ext_mantenimiento_monday__rows` y `ext_mantenimiento_monday__people`. Migracion: `migrations/V001__settings.sql`.

## Desinstalacion

Siempre se borra el token. La opcion `import_history` (por omision, borrar) elimina trabajos, filas y personas leidas; con
"conservar" quedan. Nunca se borran planes, ordenes, notas, propiedades, activos ni adjuntos importados.

## Limites

- Hasta 20 000 elementos por tablero y una importacion en curso a la vez.
- Archivos de hasta 25 MB, solo de servidores de monday (`*.monday.com` y su S3); los de Drive o Dropbox (enlaces) no se importan. Los planes no llevan archivos.
- Solo se leen las primeras 100 actualizaciones de cada elemento; los topes de lista de comprobacion (100) y notas (2000) por fila los aplica el modulo.
