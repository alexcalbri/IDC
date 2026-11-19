package com.ideasdeveloper.idc.modules.empleados

import com.ideasdeveloper.idc.server.modules.McpSchema
import com.ideasdeveloper.idc.server.modules.McpTool
import com.ideasdeveloper.idc.server.modules.McpToolCall
import com.ideasdeveloper.idc.server.modules.McpToolException
import com.ideasdeveloper.idc.server.modules.ModuleMcp
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import java.time.LocalDate
import java.time.LocalTime
import java.time.temporal.ChronoUnit
import java.util.UUID

/**
 * Herramientas de Empleados para agentes. Toda escritura pasa por [EmpleadosService] (las mismas reglas que las pantallas) y
 * toda lectura por [EmpleadosRepository] con el [EmpleadosScope] vivo del usuario: un agente ve y cambia solo lo que su
 * usuario puede. Los campos que el usuario no puede ver ni se leen de la base de datos. Ninguna herramienta maneja credenciales.
 * Los handlers corren en el hilo de herramientas del puente, que es donde van las llamadas JDBC.
 */
object EmpleadosMcpTools {
    const val Instructions =
        "Empleados guarda las fichas del personal, su horario habitual y sus turnos. Cada empleado tiene sede, departamento y puesto. " +
            "El horario de un dia se calcula asi: una ausencia aprobada que cubre la fecha manda sobre todo; si hay un turno concreto ese dia, reemplaza al horario habitual " +
            "(un turno de tipo libre es un dia libre); si no, rige el horario habitual de ese dia de la semana. Las horas son HH:mm; una franja que termina antes de empezar pasa de la medianoche. " +
            "Solo ves lo que tu usuario puede ver: con solo el permiso de ver empleados recibes nombre, puesto, departamento y sede de los empleados activos y tu propio horario; " +
            "telefono, correo y disponibilidad llegan solo a quien gestiona turnos, y notas, fecha de ingreso y bajas a quien administra fichas. " +
            "Flujo para cambiar turnos: 1) list_employees y list_sites para obtener ids y nombres de sede; 2) get_schedule para ver el horario vigente; " +
            "3) create_shift, move_shift, set_day_off o delete_shift; el empleado con cuenta recibe un aviso. Antes de cambiar muchos turnos muestra el resumen al usuario y espera su confirmacion. " +
            "Ausencias: el empleado las pide con request_absence (quedan pendientes) y quien gestiona turnos las decide con decide_absence (approve true o false); aprobarla anula el horario de esos dias. " +
            "who_is_on_shift dice quien trabaja en una fecha, sede y hora. Las fichas se crean y editan solo desde la app."

    private val absenceKindCodes = AbsenceKind.entries.map { it.code }
    private val absenceStatusCodes = AbsenceStatus.entries.map { it.code }
    private const val MaxRangeDays = 31L

    val tools: List<McpTool> = listOf(
        McpTool(
            name = "list_employees",
            title = "Buscar empleados",
            description = "Lista los empleados que el usuario puede ver, por nombre: id, nombre, puesto, departamento, sede y estado. Quien gestiona turnos recibe ademas telefono, correo y disponibilidad; " +
                "quien administra fichas, tambien notas, fecha de ingreso y bajas. Filtra por texto (nombre, puesto y, si el usuario puede ver contacto, correo o telefono), sede o departamento.",
            permission = EmpleadosModule.View,
            readOnly = true,
            inputSchema = McpSchema.obj(
                mapOf(
                    "q" to McpSchema.string("Texto a buscar."),
                    "site" to McpSchema.string("Solo empleados de esta sede (nombre, ver list_sites)."),
                    "department" to McpSchema.string("Solo empleados de este departamento (nombre, ver list_sites)."),
                    "include_inactive" to McpSchema.boolean("Incluir empleados dados de baja (solo quien gestiona turnos; por defecto no)."),
                    "limit" to McpSchema.integer("Cantidad maxima; por defecto 50.", 1, 200),
                ),
            ),
        ) { call -> guarded { listEmployees(call) } },
        McpTool(
            name = "get_employee",
            title = "Ver empleado",
            description = "Devuelve la ficha de un empleado visible con los campos que el usuario puede ver (los demas no se envian).",
            permission = EmpleadosModule.View,
            readOnly = true,
            inputSchema = McpSchema.obj(mapOf("id" to McpSchema.uuid("Id del empleado.")), required = listOf("id")),
        ) { call -> guarded { getEmployee(call) } },
        McpTool(
            name = "list_sites",
            title = "Sedes y departamentos",
            description = "Lista las sedes (lugares de trabajo) y los departamentos disponibles, con su id y nombre. Usa estos nombres en los filtros y en los turnos.",
            permission = EmpleadosModule.View,
            readOnly = true,
        ) { call -> guarded { listSites(call) } },
        McpTool(
            name = "who_is_on_shift",
            title = "Quien esta de turno",
            description = "Dice quien trabaja en una fecha (hoy por defecto), opcionalmente en una sede y a una hora (HH:mm; incluye turnos nocturnos). Solo empleados activos y sin ausencia aprobada. Necesita gestionar turnos.",
            permission = EmpleadosModule.Shifts,
            readOnly = true,
            inputSchema = McpSchema.obj(
                mapOf(
                    "date" to McpSchema.date("Fecha; por defecto hoy."),
                    "site" to McpSchema.string("Solo esta sede (nombre, ver list_sites)."),
                    "time" to McpSchema.string("Hora HH:mm: solo quien trabaja a esa hora."),
                ),
            ),
        ) { call -> guarded { whoIsOnShift(call) } },
        McpTool(
            name = "get_schedule",
            title = "Horario de un empleado",
            description = "Devuelve el horario dia por dia entre dos fechas (hasta 31 dias): franjas con sede, si viene del horario habitual o de un turno concreto, dias libres y ausencias aprobadas. " +
                "Sin employee_id es el horario del propio usuario; el de otra persona solo lo ve quien gestiona turnos.",
            permission = EmpleadosModule.View,
            readOnly = true,
            inputSchema = McpSchema.obj(
                mapOf(
                    "employee_id" to McpSchema.uuid("Id del empleado; si falta, el propio."),
                    "from" to McpSchema.date("Primer dia."),
                    "to" to McpSchema.date("Ultimo dia (como mucho 31 dias desde from)."),
                ),
                required = listOf("from", "to"),
            ),
        ) { call -> guarded { getSchedule(call) } },
        McpTool(
            name = "list_absences",
            title = "Ausencias",
            description = "Lista ausencias. El usuario recibe las suyas; quien gestiona turnos recibe las de todos. Filtra por estado y por fechas (las que tocan ese rango).",
            permission = EmpleadosModule.View,
            readOnly = true,
            inputSchema = McpSchema.obj(
                mapOf(
                    "status" to McpSchema.string("Solo este estado.", absenceStatusCodes),
                    "from" to McpSchema.date("Solo ausencias que terminan despues de esta fecha."),
                    "to" to McpSchema.date("Solo ausencias que empiezan antes de esta fecha."),
                ),
            ),
        ) { call -> guarded { listAbsences(call) } },
        McpTool(
            name = "create_shift",
            title = "Crear turno",
            description = "Crea un turno concreto de un empleado activo en una fecha; reemplaza al horario habitual de ese dia. Maximo 4 turnos por dia, sin solaparse. " +
                "Si el empleado tiene cuenta y la fecha es hoy o futura, recibe el aviso Tu turno cambio.",
            permission = EmpleadosModule.Shifts,
            inputSchema = McpSchema.obj(
                mapOf(
                    "employee_id" to McpSchema.uuid("Id del empleado (ver list_employees)."),
                    "date" to McpSchema.date("Fecha del turno."),
                    "start" to McpSchema.string("Hora de inicio HH:mm."),
                    "end" to McpSchema.string("Hora de fin HH:mm; menor que el inicio pasa de la medianoche."),
                    "site" to McpSchema.string("Sede (nombre, ver list_sites); si falta, la de la ficha."),
                    "note" to McpSchema.string("Nota (hasta ${ShiftInput.MaxNote} caracteres)."),
                ),
                required = listOf("employee_id", "date", "start", "end"),
            ),
        ) { call -> guarded { createShift(call) } },
        McpTool(
            name = "move_shift",
            title = "Mover turno",
            description = "Cambia la fecha, las horas, la sede o la nota de un turno concreto: solo cambia lo que envias (site vacio quita la sede). El empleado recibe el aviso si afecta a hoy o al futuro.",
            permission = EmpleadosModule.Shifts,
            inputSchema = McpSchema.obj(
                mapOf(
                    "shift_id" to McpSchema.uuid("Id del turno (ver get_schedule)."),
                    "date" to McpSchema.date("Nueva fecha."),
                    "start" to McpSchema.string("Nueva hora de inicio HH:mm."),
                    "end" to McpSchema.string("Nueva hora de fin HH:mm."),
                    "site" to McpSchema.string("Nueva sede (nombre); vacio la quita."),
                    "note" to McpSchema.string("Nueva nota; vacio la borra."),
                ),
                required = listOf("shift_id"),
            ),
        ) { call -> guarded { moveShift(call) } },
        McpTool(
            name = "delete_shift",
            title = "Borrar turno",
            description = "Borra un turno concreto (o un dia libre): ese dia vuelve a regir el horario habitual. El empleado recibe el aviso si afecta a hoy o al futuro.",
            permission = EmpleadosModule.Shifts,
            destructive = true,
            inputSchema = McpSchema.obj(mapOf("shift_id" to McpSchema.uuid("Id del turno.")), required = listOf("shift_id")),
        ) { call -> guarded { deleteShift(call) } },
        McpTool(
            name = "set_day_off",
            title = "Dia libre",
            description = "Marca un dia libre de un empleado activo: borra sus otros turnos de esa fecha y el horario habitual deja de aplicarse ese dia.",
            permission = EmpleadosModule.Shifts,
            inputSchema = McpSchema.obj(
                mapOf("employee_id" to McpSchema.uuid("Id del empleado."), "date" to McpSchema.date("Fecha libre.")),
                required = listOf("employee_id", "date"),
            ),
        ) { call -> guarded { setDayOff(call) } },
        McpTool(
            name = "request_absence",
            title = "Pedir ausencia",
            description = "Pide una ausencia para el propio usuario (necesita una ficha enlazada). Queda pendiente y se avisa a quien gestiona turnos.",
            permission = EmpleadosModule.View,
            inputSchema = McpSchema.obj(
                mapOf(
                    "kind" to McpSchema.string("Tipo de ausencia.", absenceKindCodes),
                    "from" to McpSchema.date("Primer dia."),
                    "to" to McpSchema.date("Ultimo dia (el mismo para un solo dia)."),
                    "reason" to McpSchema.string("Motivo (hasta ${AbsenceInput.MaxReason} caracteres)."),
                ),
                required = listOf("kind", "from", "to"),
            ),
        ) { call -> guarded { requestAbsence(call) } },
        McpTool(
            name = "decide_absence",
            title = "Decidir ausencia",
            description = "Aprueba (approve true) o rechaza (approve false) una solicitud pendiente, con una nota opcional. Se avisa al empleado. Una ausencia aprobada anula su horario esos dias.",
            permission = EmpleadosModule.Shifts,
            inputSchema = McpSchema.obj(
                mapOf(
                    "id" to McpSchema.uuid("Id de la ausencia (ver list_absences)."),
                    "approve" to McpSchema.boolean("true aprueba, false rechaza."),
                    "note" to McpSchema.string("Nota para el empleado."),
                ),
                required = listOf("id", "approve"),
            ),
        ) { call -> guarded { decideAbsence(call) } },
    )

    // Un getter: las herramientas de las extensiones de importacion se leen en cada peticion del puente (las extensiones se enlazan despues de cargar este objeto).
    val mcp: ModuleMcp get() = ModuleMcp(Instructions, tools + EmpleadosImportSources.all.flatMap { it.mcpTools })

    // ---- ayudas (publicas y sin base de datos para poder probarlas) ----

    /** Ejecuta un handler convirtiendo los errores de validacion del modulo en errores de herramienta del agente. */
    private fun guarded(block: () -> JsonElement): JsonElement = try {
        block()
    } catch (failure: EmpleadosValidationException) {
        throw McpToolException(failure.message.orEmpty())
    }

    /** Una hora HH:mm de un argumento; vacio es null y un formato malo es un error para el agente. */
    fun timeArgument(text: String, name: String): LocalTime? {
        if (text.isBlank()) return null
        return EmpleadosSchedule.parseTime(text) ?: throw McpToolException("'$name' debe ser una hora HH:mm (de 00:00 a 23:59).")
    }

    /** Un rango de fechas de [from] a [to] de como mucho 31 dias. */
    fun checkRange(from: LocalDate, to: LocalDate) {
        if (to.isBefore(from)) throw McpToolException("'to' no puede ser anterior a 'from'.")
        if (ChronoUnit.DAYS.between(from, to) >= MaxRangeDays) throw McpToolException("El rango admite hasta $MaxRangeDays dias.")
    }

    /**
     * La ficha como la ve el agente: solo los campos que su nivel incluye (los demas valen null porque nunca se leyeron).
     * La cuenta enlazada no se envia: solo si la ficha tiene una (quien administra fichas).
     */
    fun employeeJson(employee: Employee): JsonObject = buildJsonObject {
        put("id", employee.id.toString())
        put("name", employee.fullName)
        put("job_title", employee.jobTitle)
        put("department", employee.department)
        put("site", employee.site)
        put("status", employee.status.code)
        employee.phone?.let { put("phone", it) }
        employee.email?.let { put("email", it) }
        employee.availability?.let { put("availability", it) }
        employee.hireDate?.let { put("hire_date", it.toString()) }
        if (employee.level.internal) {
            put("ended_on", employee.endedOn?.toString())
            put("notes", employee.notes.orEmpty())
            put("has_user_account", employee.userId != null)
        }
    }

    /** Un dia del horario para el agente. */
    fun dayJson(date: LocalDate, schedule: DaySchedule, siteNames: Map<UUID, String>): JsonObject = buildJsonObject {
        put("date", date.toString())
        when (schedule) {
            is DaySchedule.Absent -> {
                put("status", "absent")
                put("absence_kind", schedule.kind.code)
            }
            is DaySchedule.Off -> {
                put("status", "off")
                put("source", schedule.source ?: "none")
            }
            is DaySchedule.Working -> {
                put("status", "work")
                putJsonArray("slots") {
                    schedule.slots.forEach { slot ->
                        add(
                            buildJsonObject {
                                put("start", EmpleadosSchedule.formatTime(slot.start))
                                put("end", EmpleadosSchedule.formatTime(slot.end))
                                put("site", slot.siteId?.let { siteNames[it] })
                                put("source", slot.source)
                                slot.shiftId?.let { put("shift_id", it.toString()) }
                            },
                        )
                    }
                }
            }
        }
    }

    private fun absenceJson(row: AbsenceRow): JsonObject = buildJsonObject {
        val absence = row.absence
        put("id", absence.id.toString())
        put("employee", row.employeeName)
        put("kind", absence.kind.code)
        put("status", absence.status.code)
        put("from", absence.startDate.toString())
        put("to", absence.endDate.toString())
        put("reason", absence.reason)
        put("decision_note", absence.decisionNote)
    }

    private fun scope(call: McpToolCall): EmpleadosScope = EmpleadosService.scope(call.tenant, call.permissions)

    private fun requireShifts(scope: EmpleadosScope) {
        if (!(scope.canShifts || scope.canManage)) throw McpToolException("El usuario no puede gestionar turnos.")
    }

    /** La sede por nombre o id (sin distinguir mayusculas); desconocida es un error que lista las validas. */
    private fun siteOf(call: McpToolCall, name: String): UUID? {
        if (name.isBlank()) return null
        val sites = EmpleadosRepository.listCatalog(call.tenant, Catalog.Sites)
        return (sites.firstOrNull { it.name.equals(name.trim(), ignoreCase = true) } ?: sites.firstOrNull { it.id.toString().equals(name.trim(), ignoreCase = true) })?.id
            ?: throw McpToolException("La sede \"${name.trim()}\" no existe. Sedes: ${sites.joinToString { it.name }.ifEmpty { "ninguna" }}.")
    }

    private fun departmentOf(call: McpToolCall, name: String): UUID? {
        if (name.isBlank()) return null
        val items = EmpleadosRepository.listCatalog(call.tenant, Catalog.Departments)
        return items.firstOrNull { it.name.equals(name.trim(), ignoreCase = true) }?.id
            ?: throw McpToolException("El departamento \"${name.trim()}\" no existe. Departamentos: ${items.joinToString { it.name }.ifEmpty { "ninguno" }}.")
    }

    // ---- lecturas ----

    private fun listEmployees(call: McpToolCall): JsonElement {
        val scope = scope(call)
        val limit = call.int("limit", 50, 1..200)
        val seesInactive = scope.canShifts || scope.canManage
        val filter = EmployeeFilter(
            query = call.string("q", EmpleadosRepository.MaxQuery).takeIf { it.isNotEmpty() },
            siteId = siteOf(call, call.string("site", 120)),
            departmentId = departmentOf(call, call.string("department", 120)),
            status = if (seesInactive && call.boolean("include_inactive", false)) null else EmployeeStatus.Active,
            limit = limit,
        )
        val employees = EmpleadosRepository.list(call.tenant, scope, filter)
        return buildJsonObject {
            put("count", employees.size)
            if (employees.size >= limit) put("note", "Puede haber mas empleados: afina los filtros o sube limit.")
            put("employees", buildJsonArray { employees.forEach { add(employeeJson(it)) } })
        }
    }

    private fun getEmployee(call: McpToolCall): JsonElement {
        val employee = EmpleadosService.visibleEmployee(call.tenant, scope(call), call.requiredUuid("id"))
        return buildJsonObject { put("employee", employeeJson(employee)) }
    }

    private fun listSites(call: McpToolCall): JsonElement = buildJsonObject {
        putJsonArray("sites") {
            EmpleadosRepository.listCatalog(call.tenant, Catalog.Sites).forEach { add(buildJsonObject { put("id", it.id.toString()); put("name", it.name) }) }
        }
        putJsonArray("departments") {
            EmpleadosRepository.listCatalog(call.tenant, Catalog.Departments).forEach { add(buildJsonObject { put("id", it.id.toString()); put("name", it.name) }) }
        }
    }

    private fun whoIsOnShift(call: McpToolCall): JsonElement {
        requireShifts(scope(call))
        val date = call.date("date") ?: EmpleadosRepository.today()
        val onShift = EmpleadosExternal.onShift(call.tenant, date, siteOf(call, call.string("site", 120)), timeArgument(call.string("time", 10), "time"))
        return buildJsonObject {
            put("date", date.toString())
            put("count", onShift.size)
            putJsonArray("employees") {
                onShift.forEach { person ->
                    add(
                        buildJsonObject {
                            put("employee_id", person.employeeId.toString())
                            put("name", person.name)
                            put("job_title", person.jobTitle)
                            put("department", person.department)
                            put("site", person.site)
                            put("start", EmpleadosSchedule.formatTime(person.start))
                            put("end", EmpleadosSchedule.formatTime(person.end))
                            put("source", person.source)
                        },
                    )
                }
            }
        }
    }

    private fun getSchedule(call: McpToolCall): JsonElement {
        val scope = scope(call)
        val from = call.date("from") ?: throw McpToolException("El argumento 'from' es obligatorio.")
        val to = call.date("to") ?: throw McpToolException("El argumento 'to' es obligatorio.")
        checkRange(from, to)
        val employeeId = call.uuid("employee_id") ?: scope.myEmployeeId ?: throw McpToolException(EmpleadosService.NoRecord)
        if (!scope.seesScheduleOf(employeeId)) throw McpToolException("Solo puedes ver tu propio horario.")
        val employee = EmpleadosService.visibleEmployee(call.tenant, scope, employeeId)
        val data = EmpleadosRepository.scheduleData(call.tenant, listOf(employee.id), from, to)
        val siteNames = EmpleadosRepository.catalogNames(call.tenant, Catalog.Sites)
        return buildJsonObject {
            put("employee", employee.fullName)
            put("employee_id", employee.id.toString())
            putJsonArray("days") {
                generateSequence(from) { it.plusDays(1) }.takeWhile { !it.isAfter(to) }.forEach { date ->
                    add(dayJson(date, data.resolve(employee.id, date, employee.siteId), siteNames))
                }
            }
        }
    }

    private fun listAbsences(call: McpToolCall): JsonElement {
        val scope = scope(call)
        val status = call.string("status", 20).takeIf { it.isNotEmpty() }?.let {
            AbsenceStatus.fromCode(it) ?: throw McpToolException("'status' debe ser ${absenceStatusCodes.joinToString(", ")} (recibido '$it').")
        }
        val all = scope.canShifts || scope.canManage
        val own = scope.myEmployeeId
        if (!all && own == null) {
            return buildJsonObject { put("count", 0); put("note", EmpleadosService.NoRecord); put("absences", JsonArray(emptyList())) }
        }
        val rows = EmpleadosRepository.listAbsences(call.tenant, scope, if (all) null else own, status?.let { listOf(it) }, call.date("from"), call.date("to"), 100, newestFirst = true)
        return buildJsonObject {
            put("count", rows.size)
            put("absences", buildJsonArray { rows.forEach { add(absenceJson(it)) } })
        }
    }

    // ---- escrituras ----

    private fun createShift(call: McpToolCall): JsonElement {
        val scope = scope(call)
        requireShifts(scope)
        val start = timeArgument(call.string("start", 10), "start")
        val end = timeArgument(call.string("end", 10), "end")
        if (start == null || end == null) throw McpToolException("'start' y 'end' son obligatorios (HH:mm).")
        val id = EmpleadosService.createShift(
            call.tenant, call.access, scope,
            ShiftInput(call.requiredUuid("employee_id"), call.date("date") ?: throw McpToolException("El argumento 'date' es obligatorio."), ShiftKind.Work, start, end, siteOf(call, call.string("site", 120)), call.string("note", ShiftInput.MaxNote + 1)),
        )
        return buildJsonObject { put("shift_id", id.toString()); put("created", true) }
    }

    private fun moveShift(call: McpToolCall): JsonElement {
        val scope = scope(call)
        requireShifts(scope)
        val id = call.requiredUuid("shift_id")
        EmpleadosService.moveShift(
            call.tenant, call.access, scope, id,
            ShiftPatch(
                date = call.date("date"),
                start = timeArgument(call.string("start", 10), "start"),
                end = timeArgument(call.string("end", 10), "end"),
                setSite = call.has("site"),
                siteId = siteOf(call, call.string("site", 120)),
                note = if (call.has("note")) call.string("note", ShiftInput.MaxNote + 1) else null,
            ),
        )
        return buildJsonObject { put("shift_id", id.toString()); put("updated", true) }
    }

    private fun deleteShift(call: McpToolCall): JsonElement {
        val scope = scope(call)
        requireShifts(scope)
        val id = call.requiredUuid("shift_id")
        EmpleadosService.deleteShift(call.tenant, call.access, scope, id)
        return buildJsonObject { put("shift_id", id.toString()); put("deleted", true) }
    }

    private fun setDayOff(call: McpToolCall): JsonElement {
        val scope = scope(call)
        requireShifts(scope)
        val id = EmpleadosService.setDayOff(call.tenant, call.access, scope, call.requiredUuid("employee_id"), call.date("date") ?: throw McpToolException("El argumento 'date' es obligatorio."))
        return buildJsonObject { put("shift_id", id.toString()); put("day_off", true) }
    }

    private fun requestAbsence(call: McpToolCall): JsonElement {
        val kind = AbsenceKind.fromCode(call.requiredString("kind", 20)) ?: throw McpToolException("'kind' debe ser ${absenceKindCodes.joinToString(", ")}.")
        val from = call.date("from") ?: throw McpToolException("El argumento 'from' es obligatorio.")
        val to = call.date("to") ?: throw McpToolException("El argumento 'to' es obligatorio.")
        val id = EmpleadosService.requestAbsence(call.tenant, call.access, scope(call), kind, from, to, call.string("reason", AbsenceInput.MaxReason + 1))
        return buildJsonObject { put("absence_id", id.toString()); put("status", AbsenceStatus.Pending.code) }
    }

    private fun decideAbsence(call: McpToolCall): JsonElement {
        val scope = scope(call)
        requireShifts(scope)
        if (!call.has("approve")) throw McpToolException("El argumento 'approve' es obligatorio (true o false).")
        val approve = call.boolean("approve", false)
        val id = call.requiredUuid("id")
        EmpleadosService.decideAbsence(call.tenant, call.access, scope, id, approve, call.string("note", AbsenceInput.MaxReason + 1))
        return buildJsonObject { put("absence_id", id.toString()); put("status", (if (approve) AbsenceStatus.Approved else AbsenceStatus.Rejected).code) }
    }
}
