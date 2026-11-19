# Empleados

Versión 0.1.0 (en desarrollo). Fichas de empleados, sedes y departamentos, horario habitual con turnos concretos, vista
semanal por sede, solicitudes de ausencia con aprobación y avisos cuando cambia el turno de alguien. Es el primer paso
hacia otros módulos (por ejemplo mantenimiento) que necesitan saber quién está de turno.

Fuera de alcance en 0.1.0 (a propósito): nómina, salarios, documentos de identidad, contacto de emergencia, fecha de
nacimiento, reloj de asistencia, costos laborales, auto-programación, intercambio de turnos y mensajería interna.

## Datos (base de datos de la empresa)

Migración `V001__create_empleados.sql`:

- `empleados_sites`, `empleados_departments`: listas propias del módulo, editables (nombre único sin distinguir
  mayúsculas, posición, archivada). La sede guarda `import_key` cuando la crea una importación.
- `empleados_employees`: nombre, puesto, departamento, sede, teléfono, correo, fecha de ingreso, estado (`active` /
  `inactive`) y fecha de baja, disponibilidad (texto) y notas. `user_id` enlaza la ficha con la cuenta del empleado (una
  cuenta, una ficha; sin clave foránea porque `application_users` es del Core: se comprueba al escribir).
- `empleados_weekly`: horario habitual, franjas por día de la semana (ISO 1 lunes … 7 domingo). Si el fin es menor que
  el inicio la franja pasa de la medianoche. Hasta 4 franjas por día, sin solaparse (se valida al escribir). Sin sede:
  la de la ficha.
- `empleados_shifts`: turnos concretos de una fecha (`work` con horas, `off` día libre). Reemplazan al horario habitual
  de ese día.
- `empleados_absences`: ausencias (`vacation`, `leave`, `sick`, `other`) con estado `pending`, `approved`, `rejected` o
  `cancelled`, motivo y nota de la decisión.

Una empresa duplicada conserva sedes (sin `import_key`) y departamentos; fichas, horarios, turnos y ausencias no se copian.

## Cálculo del horario

`EmpleadosSchedule.resolve` (puro, con pruebas) calcula un día de un empleado:

1. una ausencia **aprobada** que cubre la fecha gana a todo (`Absent`);
2. si hay turnos concretos en la fecha, rigen ellos (los `work` ordenados por hora) o es día libre si solo hay `off`;
3. si no, rigen las franjas del horario habitual de ese día de la semana;
4. si no hay nada, libre.

No se generan turnos cada semana: la vista semanal calcula con estas reglas. Crear un turno `work` en un día con un
`off` borra el `off`; crear un día libre borra los demás turnos del día.

## Permisos

| Permiso | Qué permite |
| --- | --- |
| `empleados.view` | Abrir el módulo, ver **Mi horario**, pedir y cancelar sus ausencias pendientes y ver el directorio básico (nombre, puesto, departamento y sede) de los empleados **activos**. |
| `empleados.shifts` | Vista semanal de todos, horario habitual y turnos de cualquiera, aprobar, rechazar y registrar ausencias, y ver teléfono, correo y disponibilidad. |
| `empleados.manage` | Crear, editar y dar de baja fichas, enlazar usuarios, ver todo (notas, fecha de ingreso, bajas) y administrar sedes y departamentos. Incluye lo de `shifts`. |
| `empleados.import` | Usar la pantalla **Importar** y las herramientas de importación de los agentes (necesita una extensión de importación). |

El dueño del negocio y el dueño del servidor tienen todo. `shifts` y `manage` abren también las pantallas de `view` (los
permisos se incluyen hacia arriba), pero las herramientas del agente exigen el permiso exacto de cada herramienta: se
recomienda dar `view` junto con `shifts` o `manage`.

## Visibilidad

Reglas puras en `EmpleadosScope` (con pruebas) y aplicadas **en SQL** por `EmpleadosRepository` (listas, búsquedas,
desplegables y agentes). La consulta solo selecciona las columnas del nivel del usuario: lo que no puede ver ni se lee.

| Quien mira | Campos de la ficha |
| --- | --- |
| `empleados.manage` / dueño | Todo: + fecha de ingreso, fecha de baja, notas y usuario enlazado. |
| El propio empleado (ficha enlazada) | Datos básicos + teléfono, correo, disponibilidad y fecha de ingreso. **Nunca las notas.** |
| `empleados.shifts` | Datos básicos + teléfono, correo y disponibilidad. |
| Solo `empleados.view` | Datos básicos de empleados activos. Nunca teléfono, correo, disponibilidad, notas ni fecha de ingreso de otros. |

- Las bajas solo las ven `shifts`, `manage` y el propio empleado.
- El horario de un empleado lo ven `shifts`/`manage` y el propio empleado.
- La búsqueda por texto mira nombre y puesto; teléfono y correo solo si el nivel del usuario los incluye.

## Pantallas (nativas, `/modules/empleados/ui`, inicio `mine`)

Navegación: **Mi horario**, **Equipo**, **Semana** (`shifts`), **Ausencias**, **Administrar** (`manage`), **Importar**
(`empleados.import` y una fuente activa).

- `mine?week=`: la semana del empleado (7 días con su horario resuelto), su horario habitual, sus ausencias (cancelar
  las pendientes) y el formulario **Pedir ausencia**. Sin ficha enlazada explica que se la enlace quien administra fichas.
- `team?q=&site=&department=&status=` → `employee?id=`: directorio con filtros y la ficha según el nivel de campos.
  `manage` edita todo, da de baja o reactiva y enlaza el usuario; `shifts` edita el horario habitual y ve los próximos
  14 días con enlace a cada turno concreto.
- `employee-new`: nueva ficha (`manage`).
- `week?start=&site=&department=`: empleados × días por sede (tope de 300 empleados con aviso), `*` marca un turno
  concreto, formulario **Nuevo turno** y la lista de turnos de la semana → `shift?id=` (mover o borrar).
- `absences`: mis solicitudes; con `shifts`: pendientes (aprobar o rechazar con nota), próximas aprobadas (30 días) y
  **Registrar ausencia** (queda aprobada).
- `admin`: sedes y departamentos (añadir, renombrar, subir, bajar, archivar).

Cada pantalla comprueba el permiso antes de abrir la conexión y el servicio aplica después las reglas finas. Las
operaciones son `POST /{pantalla}?op=` con los valores del formulario en JSON, y responden la siguiente pantalla con
su aviso.

## Avisos (centro de notificaciones del Core)

Se crean con el notificador del módulo dentro de `runCatching` (un fallo se registra, nunca rompe el cambio) y el
autor del cambio nunca recibe el aviso.

- **Tu turno cambió** (turno creado, movido o borrado) y **Tu horario habitual cambió**, al empleado con cuenta, solo si
  afecta a hoy o al futuro. Texto: `lun 23 nov: 08:00–16:00 · La Jolla` (o `libre`). Destino: `mine` con la semana.
- **Solicitud de ausencia**, a quien gestiona turnos y a los dueños del negocio (hasta 20). Destino: `absences`.
- **Ausencia aprobada / rechazada**, al empleado enlazado. Destino: `absences`.
- La importación no avisa.

## Herramientas MCP (agentes)

Cada herramienta respeta los permisos en vivo del usuario y usa las mismas reglas que las pantallas. Ninguna maneja
credenciales; las fichas se crean y editan solo desde la app.

| Herramienta | Permiso | Qué hace |
| --- | --- | --- |
| `list_employees` | `view` | Busca empleados (campos según el nivel del usuario). |
| `get_employee` | `view` | Ficha de un empleado visible. |
| `list_sites` | `view` | Sedes y departamentos. |
| `who_is_on_shift` | `shifts` | Quién trabaja en una fecha, sede y hora. |
| `get_schedule` | `view` | Horario día por día (hasta 31 días): el propio, o el de cualquiera con `shifts`. |
| `list_absences` | `view` | Ausencias propias; todas con `shifts`. |
| `create_shift`, `move_shift`, `delete_shift`, `set_day_off` | `shifts` | Turnos concretos y días libres. |
| `request_absence` | `view` | Pide una ausencia propia. |
| `decide_absence` | `shifts` | Aprueba o rechaza una solicitud pendiente. |

Las herramientas de las extensiones de importación (`empleados.import`) se suman a estas.

## Trabajos

- `import-runner` (1 min, presupuesto de 40 s): avanza la lectura y la aplicación de las fuentes de importación activas.

## Importación

Las extensiones (por ejemplo `empleados_monday`) implementan `EmpleadosImportSource`: el módulo lista las fuentes
activas en **Importar**, ejecuta su trabajo pendiente en `import-runner` y suma sus herramientas. Las filas llegan como
`ImportedEmployee` y se escriben con `EmpleadosImportApply.apply` (una transacción por fila, idempotente, sin avisos):

- La ficha se identifica por `import_key` (`monday:<id>`); se crea o se actualiza. Al actualizar solo se pisan los campos
  que la fila trae no vacíos. Nombre, puesto, teléfono, correo, disponibilidad y notas se recortan con «…» a sus límites.
- Sede y departamento se buscan por nombre sin distinguir mayúsculas (también archivados: se reutilizan y desarchivan) y
  se crean si faltan.
- `weekly` no vacío reemplaza el horario habitual de esa ficha (con la sede de la ficha); vacío no lo toca. Las franjas
  inválidas, repetidas o solapadas se descartan y se avisan en la vista previa.
- **Nunca enlaza usuarios**: quien edita el origen podría poner el correo de otra cuenta. La vista previa solo lista las
  «posibles cuentas por correo» (nombre y correo); el enlace se hace a mano en la ficha.
- Un teléfono que no cumple el patrón del módulo se deja vacío y se añade a las notas como «Teléfono en Monday: …».
- Si no cabe una sede o departamento nuevo (tope de 200 activos), la ficha se guarda sin ellos y el resultado trae un
  `warning`. Un error de base de datos que no sea clave repetida (23505) se relanza para que el runner lo cuente como
  fallo y reintente.
- La baja solo se aplica cuando la fila viene inactiva; nunca se reactiva.
- `EmpleadosScheduleText.parseDays` / `parseHours` (puras y públicas) leen textos como «Lunes a Viernes», «L-V»,
  «Mon-Fri», «8:00 a.m. a 5:00 p.m.» o «8-5» en español e inglés; devuelven `null` si no entienden.

## Para otros módulos

`EmpleadosExternal` (paquete `com.ideasdeveloper.idc.modules.empleados`) es el punto de consulta para otros módulos,
por ejemplo mantenimiento para saber quién está de turno.

```kotlin
data class OnShiftEmployee(val employeeId: UUID, val name: String, val jobTitle: String, val department: String?,
    val siteId: UUID?, val site: String?, val userId: UUID?, val start: LocalTime, val end: LocalTime,
    val source: String /* "habitual" | "shift" */)

object EmpleadosExternal {
    fun onShift(tenant: Connection, date: LocalDate, siteId: UUID? = null, at: LocalTime? = null): List<OnShiftEmployee>
    fun sites(tenant: Connection): List<Pair<UUID, String>>   // no archivadas, por posición
    fun employeeForUser(tenant: Connection, userId: UUID): UUID?
}
```

Reglas:

- **Sin comprobación de permisos**: la hace quien llama. Debe comprobar que `empleados` está instalado y activo para la
  empresa y el permiso propio de su pantalla o herramienta antes de mostrar o devolver lo que reciba.
- Solo devuelve datos **básicos y horario** (nombre, puesto, departamento, sede, cuenta enlazada y franja): nunca
  teléfono, correo, disponibilidad, notas ni fecha de ingreso.
- `onShift` devuelve solo empleados **activos** sin ausencia aprobada en la fecha, **una entrada por empleado**, ordenadas
  por hora de inicio. Sin `at`, la primera franja del día; con `at`, la franja que contiene esa hora (se prefiere la del día).
- `siteId` filtra por la sede de la franja (la suya o, si no tiene, la de la ficha).
- Con `at`, una franja nocturna (fin menor que inicio) cuenta desde su inicio en la fecha pedida, y la parte de
  madrugada cuenta con la fecha anterior: `onShift(2026-11-24, at = 03:00)` incluye a quien empezó a las 22:00 del 23.
- **Ausencias y turnos nocturnos:** una ausencia aprobada el día D **no** anula la madrugada de un turno nocturno del día
  D-1 (esa noche la persona trabajó); una ausencia aprobada el día del turno (D-1) sí lo anula entero.
- El cálculo puro es `EmpleadosExternal.computeOnShift` (datos ya cargados → lista), con pruebas.
- Es **bloqueante** (JDBC): llamarlo dentro de `withContext(Dispatchers.IO)`.
- `userId` es la cuenta enlazada a la ficha (puede ser `null`): está **para asignar trabajo** a esa persona, no para
  mostrarlo ni exponerlo a quien no corresponda.

## Límites conocidos

- La fecha de «hoy» usa la zona horaria del servidor (igual que Tareas); una empresa en otra zona verá el cambio de día
  a la hora del servidor.
- `empleados.shifts` ve también las fichas de baja (con contacto) y `empleados.manage` las edita.
- Las ausencias por enfermedad (`sick`): su motivo y la nota de la decisión solo los ven `manage`/dueños y el propio
  empleado, en pantallas y herramientas MCP; a `shifts` le llegan vacíos (la consulta no los lee).
- Nadie aprueba, rechaza ni registra una ausencia propia (salvo el dueño del negocio o del servidor): «No puedes aprobar
  tus propias ausencias».
- Cambios de franjas, turnos y ausencias de un mismo empleado se serializan con un candado de la transacción; las
  franjas nocturnas se validan también contra la madrugada del día anterior y el inicio del día siguiente.
