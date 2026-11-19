# Extension `propietarios_monday`

Importa un tablero de [monday.com](https://monday.com) al modulo **Propietarios** (version minima 0.1.0). Es una fuente de
importacion del modulo (`PropietariosImportSource`) y no tiene rutas propias. Pensada para tableros como "Lot Owners Group":
cada elemento es un lote y cada grupo, un propietario o una familia.

## Que importa

| monday | Propietarios |
|---|---|
| Tablero | Desarrollo nuevo con el nombre del tablero, o uno existente no archivado (se elige en la pantalla) |
| Elemento | Lote (clave `monday:<id del elemento>`); un elemento ya importado se actualiza |
| Titulo del grupo (por omision), nombre del elemento o una columna | Propietario (cliente de tipo `propietarios`) |
| Columna de lote (Lot Number, Lote, No. lote); si falta, el nombre del elemento | Numero de lote |
| Property / Propiedad | Nombre de la propiedad |
| Register / Registro / Matricula / Folio | Numero de registro |
| Property Type / Tipo | Tipo de propiedad |
| Area / M2 / Superficie | Area en m2 (acepta `1.234,5` y `1234.5`) |
| Correo (email o texto), uno o varios separados por `,` o `;` | Un titular por correo: el primero con el nombre del propietario, los demas con el correo como nombre |
| Telefono | Telefono del primer titular |
| Estado (Sold/Vendido, Available/Disponible, Reserved/Reservado, Under construction/En construccion, Built/Construido) | Estado del lote; vacio = vendido si hay titular, disponible si no |
| Ubicacion (location) | Latitud y longitud |
| Fecha o linea de tiempo | Inicio de la titularidad |
| Porcentaje / Share | Porcentaje de propiedad (solo si el lote tiene un titular) |
| Columnas de archivos | Adjuntos del lote (25 MB cada uno; clave `monday:asset:<id>`) |
| Otras columnas con valor | Notas del lote, como `Columna: valor` |

El mapeo sale sugerido por titulo y tipo de columna (espanol e ingles) y se puede cambiar antes de leer.

## Permisos

- Del modulo: `propietarios.import`, para la pantalla Importar y las herramientas MCP. Cada herramienta comprueba el permiso
  vivo del usuario y que la extension siga activa en la empresa. Aplicar o cancelar: quien inicio la importacion o el dueno del negocio.
- De la extension (extension.json): `network.outbound:api.monday.com`, `secrets.write`, `propietarios.import.write`.

## Flujo (Propietarios > Importar > Monday)

1. Guardar el token de la API de monday (campo de contrasena que nunca se rellena; se guarda sellado con la llave de la empresa; solo se muestra "configurado").
2. Cargar tableros y elegir uno.
3. Elegir el desarrollo destino y revisar el mapeo de columnas.
4. **Leer tablero**: una pagina por paso (cursor guardado), hasta 5 000 elementos.
5. Vista previa: lotes, desarrollos, titulares, titulares sin correo, area total, lotes duplicados y problemas.
6. **Aplicar**: 100 filas por corrida con sus archivos. Muestra el progreso y, al terminar, los conteos y las filas con problemas.
7. **Cancelar** en cualquier paso: lo ya importado se conserva.

Solo hay una importacion abierta (leyendo, por revisar o aplicando) por empresa. Es idempotente: reimportar el mismo tablero
actualiza los lotes por su clave y no duplica nada; los archivos ya importados se omiten. **Aviso:** reimportar sobrescribe los
cambios hechos en Propietarios a los campos importados del lote. Los fallos pasajeros de monday (red, 429, 5xx) se reintentan
hasta 5 veces; los permanentes dejan la importacion con error. Todo error queda en el trabajo o en la fila y en el registro
del servidor, sin el token.

## Herramientas MCP (las publica el modulo)

`monday_list_boards` (solo lectura), `monday_read_board(board_id, target_development_id?)` (mapeo sugerido automatico),
`monday_import_status(job_id?)` (solo lectura) y `monday_apply(job_id)`. Ninguna recibe ni devuelve el token; el token se
guarda solo desde la app.

## Tablas

`ext_propietarios_monday__settings` (token sellado), `ext_propietarios_monday__jobs` y `ext_propietarios_monday__rows`.
Migracion: `migrations/V001__settings.sql`.

## Desinstalacion

Siempre se borra el token. La opcion `import_history` (por omision, borrar) elimina trabajos y filas leidas; con "conservar"
quedan. Nunca se borran desarrollos, lotes, titulares, clientes ni adjuntos importados.

## Lo que no trae

- Automatizaciones de monday.
- Vistas de linea de tiempo, mapa y graficos (la ubicacion solo aporta latitud y longitud al lote).
- Subelementos, actualizaciones (comentarios) ni archivos de Drive o Dropbox (enlaces); solo se importan archivos alojados en monday.
- Mas de 5 000 elementos por tablero.
