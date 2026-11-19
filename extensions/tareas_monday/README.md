# Extension `tareas_monday`

Importa tableros de [monday.com](https://monday.com) al modulo **Tareas** (version minima 0.2.0). Un tablero de monday
pasa a ser un tablero de Tareas; es una fuente de importacion del modulo (`TareasImportSource`) y no tiene rutas propias.

## Que importa

| monday | Tareas |
|---|---|
| Tablero | Tablero nuevo (toda la empresa) o uno existente que quien importa ve |
| Grupo | Grupo del tablero |
| Elemento | Tarea (`source = monday`, referencia externa = id del elemento) |
| Columna de estado | Estado (Done/Listo/Hecho/Completed = hecha; Working on it/In progress/En curso/Stuck = en curso; Waiting for review/Review/Revision = en revision; Cancel* = cancelada; vacio, Pending o Not started = pendiente) |
| Columna de prioridad o urgencia | Prioridad (Critical/Urgent = urgente; High/Alta = alta; Low/Baja = baja; resto = media) |
| Fecha, o fin de una linea de tiempo | Fecha limite |
| Personas (Owner/Responsable) y colaboradores | Responsables |
| Personas (Reviewer/Revisor) | Revisor |
| Texto largo (Detail/Detalle/Notes) | Detalle |
| Otras columnas con valor | Detalle, como `Columna: valor` |
| Subelementos | Lista de pasos (hecho si su estado es Done); clave `monday:sub:<id>` |
| Actualizaciones y respuestas | Comentarios `[Autor · aaaa-mm-dd] texto` con la fecha original; clave `monday:upd:<id>` |
| Archivos de columnas y de actualizaciones | Adjuntos (25 MB cada uno); clave `monday:asset:<id>` |

Las personas se emparejan por **correo** (`users(ids)` de monday) con los usuarios de Tareas. Las que no tienen usuario
quedan anotadas en el detalle y la vista previa las lista.

## Permisos

- Del modulo: `tareas.import` ("Importar datos"), para la pantalla Importar y las herramientas MCP. Cada herramienta
  comprueba el permiso vivo del usuario y que la extension siga activa en la empresa.
- Destino: importar a un tablero **nuevo** exige administrar los tableros de Tareas; a uno **existente**, poder editar
  tareas y ver ese tablero. Aplicar o cancelar una importacion: solo quien la inicio o un administrador de tableros.
- De la extension (extension.json): `network.outbound:api.monday.com`, `secrets.write`, `tareas.import.write`.

## Flujo (Tareas > Importar > Monday)

1. Guardar el token de la API de monday (campo de contrasena que nunca se rellena; se guarda sellado con la llave de la empresa; solo se muestra "configurado").
2. Cargar tableros y elegir uno.
3. Elegir destino (tablero nuevo o existente) y revisar el mapeo de columnas, que ya viene sugerido por titulo y tipo (espanol e ingles).
4. **Leer tablero**: el trabajo del modulo lee una pagina por paso (cursor guardado), hasta 20 000 elementos.
5. Vista previa: tareas, grupos, pasos, comentarios, archivos, personas sin usuario y problemas.
6. **Aplicar**: 100 filas por corrida (cada minuto) con sus archivos. Muestra el progreso; al terminar, los conteos y las filas con problemas.
7. **Cancelar** en cualquier paso: lo ya importado se conserva.

**Aviso:** reimportar el mismo tablero actualiza las tareas ya importadas y sobrescribe los cambios hechos en Tareas a esos
campos (titulo, detalle, estado, prioridad, fecha, grupo, responsables y revisor).

Solo hay una importacion abierta (leyendo, por revisar o aplicando) por empresa. El resumen de la vista previa se calcula
una vez, por lotes, al terminar de leer. Los fallos pasajeros de monday (red, tiempo agotado, 429, 5xx) se reintentan hasta
5 veces seguidas; los permanentes (token rechazado, tablero inexistente, mas de 20 000 elementos, lectura sin fin) dejan la
importacion con error. Cada llamada y descarga respeta el tiempo de la corrida y no empieza con menos de 5 s.

Es idempotente: importar de nuevo el mismo tablero actualiza tareas, pasos y comentarios por su clave, sin duplicar; los
archivos ya importados se omiten. Todo error queda en el trabajo o en la fila, y en el registro del servidor.

## Herramientas MCP (las publica el modulo como `tareas_<nombre>`)

`monday_list_boards` (solo lectura), `monday_read_board(board_id, target_board_id?)` (mapeo sugerido automatico),
`monday_import_status(job_id?)` (solo lectura) y `monday_apply(job_id)`. Ninguna recibe ni devuelve el token; el token
se guarda solo desde la app.

## Tablas

`ext_tareas_monday__settings` (token sellado), `ext_tareas_monday__jobs`, `ext_tareas_monday__rows` y
`ext_tareas_monday__people`. Migracion: `migrations/V001__settings.sql`.

## Desinstalacion

Siempre se borra el token. La opcion `import_history` (por omision, borrar) elimina trabajos, filas y personas leidas; con
"conservar" quedan. Nunca se borran tableros, tareas, comentarios ni adjuntos importados.

## Limites

- Hasta 20 000 elementos por tablero y una importacion en curso a la vez.
- Archivos de hasta 25 MB, solo de servidores de monday (`*.monday.com` y su S3); los de Drive o Dropbox (enlaces) no se importan.
- Solo se leen las primeras 100 actualizaciones de cada elemento; los topes de pasos (100) y comentarios (2000) por tarea los aplica el modulo.
