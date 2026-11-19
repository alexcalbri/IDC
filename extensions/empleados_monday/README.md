# Extension `empleados_monday`

Importa un tablero de [monday.com](https://monday.com) (por ejemplo "Horario del Equipo de Mantenimiento") al modulo
**Empleados** (version minima 0.1.0). Un elemento del tablero pasa a ser un empleado; es una fuente de importacion del
modulo (`EmpleadosImportSource`) y no tiene rutas propias.

## Que importa

| monday | Empleados |
|---|---|
| Elemento (o columna de texto "Tecnico/Empleado/Nombre/Name") | Empleado (`import_key = monday:<id del elemento>`) |
| Propiedad / Sede / Property / Location (estado, lista o texto) | Sede (se crea por nombre si no existe; con varias, la primera y las otras a las notas) |
| Puesto / Cargo / Rol / Role | Puesto. Sin esa columna y con la columna de nombre llamada "Tecnico", el puesto de todos es "Tecnico" |
| Departamento / Area / Department | Departamento (se crea por nombre). Sin columna: el campo "Departamento para todos", sugerido desde el nombre del tablero ("Horario del Equipo de **Mantenimiento**" -> Mantenimiento) |
| Dias de trabajo + Horario | Horario habitual (una franja por dia) |
| Disponibilidad / Availability | Disponibilidad (nota del empleado) |
| Telefono, Correo | Telefono, correo |
| Notas / Notes | Notas |
| Otras columnas con valor | Notas, como `Columna: valor` |

Dias y horario se leen con el mismo analizador del modulo ("Lunes a Viernes", "Lun-Vie", "L-V", "Mon-Fri", "Todos los dias";
"8:00-17:00", "8am - 5pm", "08:00 - 16:00"). **Si no se entienden**, el empleado se importa sin tocar su horario habitual, el
texto original queda en las notas (`Horario en Monday: <dias> · <horario>`) y la fila cuenta como problema en la vista previa
y en el resumen final.

Importar **no enlaza usuarios**: la vista previa solo lista las "posibles cuentas por correo" (correos de monday que
coinciden con un usuario activo) y se enlazan a mano en la ficha. Importar nunca avisa a nadie, nunca reactiva a un empleado
dado de baja y solo pisa los campos que monday trae con valor.

## Flujo (Empleados > Importar > Monday)

1. Guardar el token de la API de monday (campo de contrasena que nunca se rellena; se guarda sellado con la llave de la empresa; solo se muestra "configurado").
2. Cargar tableros y elegir uno.
3. Revisar el mapeo de columnas, que ya viene sugerido por titulo y tipo (espanol e ingles), y los campos "Departamento para todos" y "Puesto para todos".
4. **Leer tablero**: el trabajo del modulo lee una pagina por paso (cursor guardado), hasta 2 000 elementos.
5. Vista previa: empleados, sedes, departamentos, con horario, posibles cuentas por correo y problemas.
6. **Aplicar** (exige ademas `empleados.manage`): 100 filas por corrida (cada minuto). Muestra el progreso; al terminar, los conteos y las filas con problemas.
7. **Cancelar** en cualquier paso: lo ya importado se conserva.

Es idempotente: importar de nuevo el mismo tablero actualiza a los empleados por su clave, sin duplicar. Reimportar sobrescribe
los campos que traiga monday y, si trae dias y horario entendidos, reemplaza el horario habitual. Solo hay una importacion
abierta (leyendo, por revisar o aplicando) por empresa. Los fallos pasajeros de monday (red, tiempo agotado, 429, 5xx) se
reintentan hasta 5 veces seguidas; los permanentes (token rechazado, tablero inexistente, mas de 2 000 elementos) dejan la
importacion con error. Todo error de una fila queda en la fila y en el registro del servidor.

## Permisos y seguridad

- Del modulo: `empleados.import` ("Importar datos"), para la pantalla Importar y las herramientas MCP. Cada herramienta
  comprueba el permiso vivo del usuario y que la extension siga activa en la empresa. Leer un tablero y ver la vista previa
  piden solo `empleados.import`; **aplicar** (pantalla y `monday_apply`) pide `empleados.import` **y** `empleados.manage`
  (o ser dueno del negocio o del servidor), porque crea y actualiza fichas. Cancelar: quien la inicio o quien tiene
  `empleados.manage`.
- Solo corre una importacion a la vez por empresa (bloqueo `pg_try_advisory_lock` en cada corrida del trabajo).
- Datos personales: al terminar, fallar o cancelarse un trabajo, el contenido de sus filas se vacia (quedan solo el
  resultado y el mensaje de cada fila); los trabajos terminados con mas de 30 dias se borran (hasta 50 por corrida).
- El registro del servidor solo lleva el tipo de error y su `sqlState`, nunca valores ni trazas con datos de empleados. Los
  errores que devuelve monday se muestran en una sola linea de hasta 200 caracteres.
- De la extension (extension.json): `network.outbound:api.monday.com`, `secrets.write`, `empleados.import.write`.
- El token se guarda sellado (`access.secrets`), nunca se muestra, ni pasa por las herramientas MCP ni por el registro. El
  cliente solo habla con `api.monday.com` (IP validada una vez), con tope de tamano (20 MB) y tiempos de espera.

## Herramientas MCP (las publica el modulo como `empleados_<nombre>`)

`monday_list_boards` (solo lectura), `monday_read_board(board_id)` (mapeo sugerido automatico), `monday_import_status(job_id?)`
(solo lectura) y `monday_apply(job_id)`. Ninguna recibe ni devuelve el token; el token se guarda solo desde la app.

## Tablas

`ext_empleados_monday__settings` (token sellado), `ext_empleados_monday__jobs` y `ext_empleados_monday__rows`.
Migracion: `migrations/V001__settings.sql`.

## Desinstalacion

Siempre se borra el token. La opcion `import_history` (por omision, borrar) elimina trabajos y filas (de las terminadas ya
solo queda el resultado, sin datos); con "conservar" quedan. **Nunca** se borran empleados, sedes, departamentos ni horarios importados.

## Limites

- Hasta 2 000 elementos por tablero y una importacion en curso a la vez.
- Solo se importan datos basicos de la ficha y el horario habitual; no hay turnos concretos ni ausencias.
