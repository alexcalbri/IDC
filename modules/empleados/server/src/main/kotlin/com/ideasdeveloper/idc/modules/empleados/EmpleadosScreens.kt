package com.ideasdeveloper.idc.modules.empleados

import com.ideasdeveloper.idc.ui.UiBadge
import com.ideasdeveloper.idc.ui.UiButton
import com.ideasdeveloper.idc.ui.UiCard
import com.ideasdeveloper.idc.ui.UiColumn
import com.ideasdeveloper.idc.ui.UiDivider
import com.ideasdeveloper.idc.ui.UiField
import com.ideasdeveloper.idc.ui.UiForm
import com.ideasdeveloper.idc.ui.UiList
import com.ideasdeveloper.idc.ui.UiListItem
import com.ideasdeveloper.idc.ui.UiNavItem
import com.ideasdeveloper.idc.ui.UiNavigate
import com.ideasdeveloper.idc.ui.UiNavigation
import com.ideasdeveloper.idc.ui.UiNode
import com.ideasdeveloper.idc.ui.UiNotice
import com.ideasdeveloper.idc.ui.UiRequest
import com.ideasdeveloper.idc.ui.UiRow
import com.ideasdeveloper.idc.ui.UiScreen
import com.ideasdeveloper.idc.ui.UiTable
import com.ideasdeveloper.idc.ui.UiTableColumn
import com.ideasdeveloper.idc.ui.UiText
import com.ideasdeveloper.idc.ui.UiTextStyle
import com.ideasdeveloper.idc.ui.UiTone
import java.time.LocalDate
import java.util.UUID

/**
 * Pantallas nativas del modulo. Cada constructor es puro: recibe datos ya cargados (y filtrados por el alcance del usuario) y
 * devuelve una [UiScreen]; EmpleadosRoutes carga los datos y EmpleadosActions lee lo que envian los formularios.
 */
object EmpleadosScreens {
    const val Route: String = EmpleadosModule.UiRoute
    const val ListLimit = 200
    const val UpcomingDays = 14
    const val ApprovedDays = 30
    private const val AbsencesShown = 50

    /** Lo que toda pantalla necesita: quien mira ([scope]), hoy y si el usuario ve "Importar". */
    data class Context(val scope: EmpleadosScope, val today: LocalDate, val importEnabled: Boolean = false)

    class MineData(
        val employee: Employee?,
        val week: LocalDate,
        val days: List<Pair<LocalDate, DaySchedule>>,
        val weekly: List<WeeklySlot>,
        val absences: List<Absence>,
        val siteNames: Map<UUID, String>,
    )

    class TeamData(val employees: List<Employee>, val sites: List<CatalogItem>, val departments: List<CatalogItem>)

    class EmployeeDetail(
        val employee: Employee,
        val sites: List<CatalogItem>,
        val departments: List<CatalogItem>,
        val users: List<UserRef>,
        val weekly: List<WeeklySlot>,
        val upcoming: List<Pair<LocalDate, DaySchedule>>,
        val siteNames: Map<UUID, String>,
    )

    class WeekData(
        val start: LocalDate,
        val employees: List<Employee>,
        val cut: Boolean,
        val schedules: ScheduleData,
        val sites: List<CatalogItem>,
        val departments: List<CatalogItem>,
        val shifts: List<Shift>,
        val allEmployees: List<Employee>,
        val siteNames: Map<UUID, String>,
    )

    class AbsencesData(
        val hasRecord: Boolean,
        val mine: List<Absence>,
        val pending: List<AbsenceRow>,
        val upcoming: List<AbsenceRow>,
        val employees: List<Employee>,
    )

    // ---- navegacion ----

    fun navigation(scope: EmpleadosScope, selected: String, import: Boolean = false): UiNavigation = UiNavigation(
        title = EmpleadosModule.displayName,
        items = listOfNotNull(
            UiNavItem("mine", "Mi horario", UiNavigate("mine")),
            UiNavItem("team", "Equipo", UiNavigate("team")),
            UiNavItem("week", "Semana", UiNavigate("week")).takeIf { scope.canShifts || scope.canManage },
            UiNavItem("absences", "Ausencias", UiNavigate("absences")),
            UiNavItem("admin", "Administrar", UiNavigate("admin")).takeIf { scope.canManage },
            UiNavItem("importar", "Importar", UiNavigate("importar")).takeIf { import },
        ),
        selected = selected,
    )

    private fun screen(context: Context, title: String, selected: String, notice: UiNotice?, body: List<UiNode>) =
        UiScreen(title = title, navigation = navigation(context.scope, selected, context.importEnabled), notice = notice, body = body)

    private fun hint(text: String, tone: String? = null) = UiText(text, UiTextStyle.Caption, tone)

    private fun sectionTitle(text: String) = UiText(text, UiTextStyle.Subtitle)

    private fun request(screen: String, op: String, params: Map<String, String> = emptyMap(), confirm: String? = null, formId: String? = null) =
        UiRequest("$Route/$screen", params = mapOf("op" to op) + params, confirm = confirm, formId = formId)

    // ---- texto del horario ----

    /** El horario de un dia como texto corto: "08:00–16:00", "Libre" o el tipo de ausencia. */
    fun dayText(schedule: DaySchedule, siteNames: Map<UUID, String> = emptyMap(), showSites: Boolean = false): String = when (schedule) {
        is DaySchedule.Absent -> schedule.kind.label
        is DaySchedule.Off -> "Libre"
        is DaySchedule.Working -> schedule.slots.joinToString(", ") { slot ->
            val range = EmpleadosSchedule.formatRange(slot.start, slot.end)
            val site = slot.siteId?.takeIf { showSites }?.let { siteNames[it] }
            if (site == null) range else "$range · $site"
        }
    }

    /**
     * La celda de la vista semanal: el horario del dia; la sede solo si la franja es de otra que la de la ficha, y un "*" si
     * viene de un turno concreto (no del horario habitual).
     */
    fun weekCell(employee: Employee, schedule: DaySchedule, siteNames: Map<UUID, String>): String = when (schedule) {
        is DaySchedule.Absent -> schedule.kind.label
        is DaySchedule.Off -> if (schedule.source == EmpleadosSchedule.SourceShift) "Libre*" else "Libre"
        is DaySchedule.Working -> {
            val text = schedule.slots.joinToString(", ") { slot ->
                val range = EmpleadosSchedule.formatRange(slot.start, slot.end)
                val other = slot.siteId?.takeIf { it != employee.siteId }?.let { siteNames[it] }
                if (other == null) range else "$range ($other)"
            }
            if (schedule.slots.any { it.source == EmpleadosSchedule.SourceShift }) "$text*" else text
        }
    }

    private fun dayRange(start: LocalDate) = "${start} a ${start.plusDays(6)}"

    private fun badge(status: AbsenceStatus) = UiBadge(
        status.label,
        when (status) {
            AbsenceStatus.Pending -> UiTone.Warning
            AbsenceStatus.Approved -> UiTone.Success
            AbsenceStatus.Rejected -> UiTone.Danger
            AbsenceStatus.Cancelled -> UiTone.Neutral
        },
    )

    private fun absenceTitle(absence: Absence) =
        if (absence.startDate == absence.endDate) "${absence.kind.label} · ${absence.startDate}" else "${absence.kind.label} · ${absence.startDate} a ${absence.endDate}"

    // ---- Mi horario ----

    fun mine(context: Context, data: MineData, notice: UiNotice? = null, submitted: Map<String, String>? = null): UiScreen {
        val employee = data.employee
        if (employee == null) {
            return screen(context, "Mi horario", "mine", notice, listOf(UiCard(listOf(UiText(EmpleadosService.NoRecord)))))
        }
        val weekParams = mapOf("week" to data.week.toString())
        val body = buildList<UiNode> {
            add(
                UiRow(
                    listOf(
                        UiButton("Semana anterior", UiNavigate("mine", mapOf("week" to data.week.minusWeeks(1).toString())), style = "secondary"),
                        UiButton("Hoy", UiNavigate("mine"), style = "secondary"),
                        UiButton("Semana siguiente", UiNavigate("mine", mapOf("week" to data.week.plusWeeks(1).toString())), style = "secondary"),
                    ),
                ),
            )
            add(
                UiCard(
                    listOf(
                        UiList(
                            data.days.map { (date, schedule) ->
                                UiListItem(
                                    title = EmpleadosNotify.dayLabel(date),
                                    trailing = dayText(schedule, data.siteNames, showSites = true),
                                    badges = listOfNotNull(UiBadge("Hoy", UiTone.Info).takeIf { date == context.today }),
                                )
                            },
                        ),
                    ),
                    title = "Semana del ${dayRange(data.week)}",
                ),
            )
            add(
                UiCard(
                    listOf(
                        if (data.weekly.isEmpty()) {
                            UiText("Aún no tienes horario habitual.")
                        } else {
                            UiList(
                                data.weekly.sortedWith(compareBy({ it.weekday }, { it.start })).map { slot ->
                                    UiListItem(
                                        title = EmpleadosActions.WeekdayLabels[slot.weekday - 1],
                                        trailing = dayText(DaySchedule.Working(listOf(DaySlot(slot.start, slot.end, slot.siteId, EmpleadosSchedule.SourceHabitual))), data.siteNames, showSites = true),
                                    )
                                },
                            )
                        },
                    ),
                    title = "Horario habitual",
                ),
            )
            add(absencesCard(data.absences.take(AbsencesShown), "mine", weekParams))
            add(
                UiCard(
                    listOf(
                        absenceForm(
                            id = "absence-request",
                            employees = null,
                            values = submitted.orEmpty(),
                            submit = UiButton("Pedir ausencia", request("mine", "absence-request", weekParams, formId = "absence-request")),
                        ),
                    ),
                    title = "Pedir ausencia",
                ),
            )
        }
        return screen(context, "Mi horario", "mine", notice, body)
    }

    private fun absencesCard(absences: List<Absence>, screen: String, keep: Map<String, String>): UiNode {
        val children = buildList<UiNode> {
            if (absences.isEmpty()) {
                add(UiText("No tienes solicitudes de ausencia."))
            } else {
                add(
                    UiList(
                        absences.map { absence ->
                            UiListItem(
                                title = absenceTitle(absence),
                                subtitle = listOf(absence.reason, absence.decisionNote.takeIf { it.isNotBlank() }?.let { "Respuesta: $it" }).filterNotNull().filter { it.isNotBlank() }.joinToString(" · "),
                                badges = listOf(badge(absence.status)),
                            )
                        },
                    ),
                )
                absences.filter { it.status == AbsenceStatus.Pending }.forEach { absence ->
                    add(
                        UiButton(
                            "Cancelar solicitud del ${absence.startDate}",
                            request(screen, "absence-cancel", keep + ("id" to absence.id.toString()), confirm = "¿Cancelar esta solicitud de ausencia?"),
                            style = "danger",
                        ),
                    )
                }
            }
        }
        return UiCard(children, title = "Mis ausencias")
    }

    private fun absenceForm(id: String, employees: Map<UUID, String>?, values: Map<String, String>, submit: UiButton): UiForm = UiForm(
        id = id,
        fields = buildList {
            if (employees != null) add(UiField("employee", "Empleado", type = "dropdown", required = true, options = employees.values.toList()))
            add(UiField("kind", "Tipo", type = "dropdown", required = true, options = AbsenceKind.entries.map { it.label }))
            add(UiField("from", "Desde", type = "date", required = true))
            add(UiField("to", "Hasta", type = "date", required = true, help = "Un solo día: la misma fecha."))
            add(UiField("reason", "Motivo", type = "textarea"))
        },
        values = mapOf("kind" to AbsenceKind.Vacation.label) + values,
        submit = submit,
    )

    // ---- Equipo ----

    private fun employeeSubtitle(employee: Employee): String =
        listOf(employee.jobTitle, employee.department.orEmpty(), employee.site.orEmpty()).filter { it.isNotBlank() }.joinToString(" · ")

    fun team(context: Context, data: TeamData, params: Map<String, String>, notice: UiNotice? = null): UiScreen {
        val seesStatus = context.scope.canShifts || context.scope.canManage
        val fields = buildList {
            add(UiField("q", "Buscar", placeholder = if (seesStatus) "Nombre, puesto, correo o teléfono" else "Nombre o puesto"))
            add(UiField("site", "Sede", type = "dropdown", options = listOf(EmpleadosActions.AllOption) + data.sites.map { it.name }))
            add(UiField("department", "Departamento", type = "dropdown", options = listOf(EmpleadosActions.AllOption) + data.departments.map { it.name }))
            if (seesStatus) add(UiField("status", "Estado", type = "dropdown", options = listOf(EmpleadosActions.ActiveOption, EmpleadosActions.InactiveOption, EmpleadosActions.AllOption)))
        }
        val values = fields.associate { field ->
            field.key to (params[field.key]?.takeIf { it.isNotBlank() } ?: when (field.key) {
                "status" -> EmpleadosActions.ActiveOption
                "q" -> ""
                else -> EmpleadosActions.AllOption
            })
        }
        val shown = data.employees.take(ListLimit)
        val body = buildList<UiNode> {
            if (context.scope.canManage) add(UiRow(listOf(UiButton("Nueva ficha", UiNavigate("employee-new")))))
            add(
                UiCard(
                    listOf(UiForm("filters", fields, values, submit = UiButton("Filtrar", UiNavigate("team", formId = "filters"), style = "secondary"), minFieldWidth = 200)),
                ),
            )
            if (data.employees.size > ListLimit) add(hint("Se muestran los primeros $ListLimit empleados: usa los filtros para acotar.", UiTone.Warning))
            add(
                UiCard(
                    listOf(
                        UiList(
                            shown.map { employee ->
                                UiListItem(
                                    title = employee.fullName,
                                    subtitle = employeeSubtitle(employee),
                                    // Solo hay telefono cuando el nivel de la lista lo incluye: la consulta no lo lee para el directorio basico.
                                    trailing = employee.phone.orEmpty(),
                                    badges = listOfNotNull(UiBadge("Baja", UiTone.Neutral).takeIf { employee.status == EmployeeStatus.Inactive }),
                                    action = UiNavigate("employee", mapOf("id" to employee.id.toString())),
                                )
                            },
                            emptyText = "No hay empleados con esos filtros.",
                        ),
                    ),
                    title = "Equipo (${shown.size})",
                ),
            )
        }
        return screen(context, "Equipo", "team", notice, body)
    }

    // ---- Ficha ----

    private fun employeeFields(sites: List<CatalogItem>, departments: List<CatalogItem>, users: List<UserRef>?): List<UiField> = buildList {
        add(UiField("full_name", "Nombre", required = true, section = "Datos"))
        add(UiField("job_title", "Puesto", section = "Datos"))
        add(UiField("department", "Departamento", type = "dropdown", options = listOf(EmpleadosActions.NoDepartment) + departments.map { it.name }, section = "Datos"))
        add(UiField("site", "Sede", type = "dropdown", options = listOf(EmpleadosActions.NoSite) + sites.map { it.name }, section = "Datos"))
        add(UiField("phone", "Teléfono", type = "phone", section = "Contacto"))
        add(UiField("email", "Correo", type = "email", section = "Contacto"))
        add(UiField("availability", "Disponibilidad", type = "textarea", help = "Por ejemplo: no puede los domingos. Hasta ${EmployeeInput.MaxAvailability} caracteres.", section = "Contacto"))
        add(UiField("hire_date", "Fecha de ingreso", type = "date", section = "Trabajo"))
        if (users != null) add(UiField("user", "Usuario", type = "dropdown", options = listOf(EmpleadosActions.NoUser) + users.map { it.label }, help = "Enlaza la ficha con la cuenta del empleado: así ve su horario y pide ausencias.", section = "Trabajo"))
        add(UiField("notes", "Notas", type = "textarea", help = "Solo las ve quien administra fichas.", section = "Trabajo"))
    }

    private fun employeeValues(employee: Employee?, users: List<UserRef>): Map<String, String> = mapOf(
        "full_name" to employee?.fullName.orEmpty(),
        "job_title" to employee?.jobTitle.orEmpty(),
        "department" to (employee?.department ?: EmpleadosActions.NoDepartment),
        "site" to (employee?.site ?: EmpleadosActions.NoSite),
        "phone" to employee?.phone.orEmpty(),
        "email" to employee?.email.orEmpty(),
        "availability" to employee?.availability.orEmpty(),
        "hire_date" to (employee?.hireDate?.toString() ?: ""),
        "user" to (employee?.userId?.let { id -> users.firstOrNull { it.id == id }?.label } ?: EmpleadosActions.NoUser),
        "notes" to employee?.notes.orEmpty(),
    )

    private fun line(label: String, value: String?): UiNode? = value?.takeIf { it.isNotBlank() }?.let { UiText("$label: $it") }

    fun employee(context: Context, detail: EmployeeDetail, notice: UiNotice? = null, submitted: Map<String, String>? = null): UiScreen {
        val employee = detail.employee
        val scope = context.scope
        val id = mapOf("id" to employee.id.toString())
        val shifts = scope.canShifts || scope.canManage
        val body = buildList<UiNode> {
            add(UiRow(listOf(UiButton("Volver al equipo", UiNavigate("team"), style = "secondary"))))
            if (scope.canManage) {
                // Quien administra fichas edita todo; el usuario enlazado solo se ofrece a el.
                val form = UiForm(
                    id = "employee",
                    fields = employeeFields(detail.sites, detail.departments, detail.users),
                    values = employeeValues(employee, detail.users) + submitted.orEmpty(),
                    submit = UiButton("Guardar", request("employee", "save", id, formId = "employee")),
                    collapsibleSections = false,
                )
                add(UiCard(listOf(form), title = "Ficha"))
                add(
                    UiRow(
                        listOf(
                            if (employee.status == EmployeeStatus.Active) {
                                UiButton("Dar de baja", request("employee", "status", id + ("active" to "false"), confirm = "¿Dar de baja a ${employee.fullName}? Deja de verse en el directorio."), style = "danger")
                            } else {
                                UiButton("Reactivar", request("employee", "status", id + ("active" to "true")), style = "secondary")
                            },
                        ),
                    ),
                )
            } else {
                add(
                    UiCard(
                        listOfNotNull(
                            UiText(employee.fullName, UiTextStyle.Subtitle),
                            UiText("Ficha de baja", UiTextStyle.Caption, UiTone.Neutral).takeIf { employee.status == EmployeeStatus.Inactive },
                            line("Puesto", employee.jobTitle),
                            line("Departamento", employee.department),
                            line("Sede", employee.site),
                            // Los campos de contacto y de ingreso son null cuando el nivel del usuario no los incluye: ni se leyeron.
                            line("Teléfono", employee.phone),
                            line("Correo", employee.email),
                            line("Disponibilidad", employee.availability),
                            line("Ingreso", employee.hireDate?.toString()),
                        ),
                        title = "Ficha",
                    ),
                )
            }
            if (shifts) {
                add(weeklyCard(detail, id))
                add(upcomingCard(context, detail))
            }
        }
        return screen(context, employee.fullName, "team", notice, body)
    }

    private fun weeklyCard(detail: EmployeeDetail, id: Map<String, String>): UiNode {
        val sorted = detail.weekly.sortedWith(compareBy({ it.weekday }, { it.start }))
        val children = buildList<UiNode> {
            if (sorted.isEmpty()) add(hint("Sin horario habitual: sin turnos concretos, la persona aparece libre."))
            sorted.forEach { slot ->
                add(
                    UiRow(
                        listOf(
                            UiText("${EmpleadosActions.WeekdayLabels[slot.weekday - 1]} ${EmpleadosSchedule.formatRange(slot.start, slot.end)}${slot.siteId?.let { detail.siteNames[it] }?.let { " · $it" }.orEmpty()}"),
                            UiButton("Quitar", request("employee", "slot-remove", id + ("slot" to slot.id.toString()), confirm = "¿Quitar esta franja del horario habitual?"), style = "danger"),
                        ),
                        spacing = 8,
                    ),
                )
            }
            add(UiDivider())
            add(sectionTitle("Añadir franja"))
            add(
                UiForm(
                    id = "slot",
                    fields = listOf(
                        UiField("weekday", "Día", type = "dropdown", required = true, options = EmpleadosActions.WeekdayLabels),
                        UiField("start", "Desde", required = true, placeholder = "08:00"),
                        UiField("end", "Hasta", required = true, placeholder = "16:00", help = "Si termina después de medianoche, escribe la hora del día siguiente (22:00 a 06:00)."),
                        UiField("site", "Sede", type = "dropdown", options = listOf(EmpleadosActions.NoSite) + detail.sites.map { it.name }, help = "Sin sede: la sede de la ficha."),
                    ),
                    values = mapOf("weekday" to EmpleadosActions.WeekdayLabels.first(), "site" to EmpleadosActions.NoSite),
                    submit = UiButton("Añadir franja", request("employee", "slot-add", id, formId = "slot"), style = "secondary"),
                ),
            )
        }
        return UiCard(children, title = "Horario habitual")
    }

    private fun upcomingCard(context: Context, detail: EmployeeDetail): UiNode = UiCard(
        listOf(
            UiList(
                detail.upcoming.map { (date, schedule) ->
                    val shiftId = (schedule as? DaySchedule.Working)?.slots?.firstNotNullOfOrNull { it.shiftId }
                    UiListItem(
                        title = EmpleadosNotify.dayLabel(date),
                        trailing = dayText(schedule, detail.siteNames, showSites = true),
                        badges = listOfNotNull(UiBadge("Hoy", UiTone.Info).takeIf { date == context.today }),
                        action = shiftId?.let { UiNavigate("shift", mapOf("id" to it.toString())) },
                    )
                },
            ),
        ),
        title = "Próximos turnos ($UpcomingDays días)",
    )

    fun employeeNew(context: Context, sites: List<CatalogItem>, departments: List<CatalogItem>, users: List<UserRef>, notice: UiNotice? = null, submitted: Map<String, String>? = null): UiScreen {
        val form = UiForm(
            id = "employee",
            fields = employeeFields(sites, departments, users),
            values = employeeValues(null, users) + submitted.orEmpty(),
            submit = UiButton("Crear ficha", request("employee-new", "create", formId = "employee")),
        )
        return screen(context, "Nueva ficha", "team", notice, listOf(UiCard(listOf(form), title = "Ficha")))
    }

    // ---- Semana ----

    private fun weekParams(start: LocalDate, params: Map<String, String>): Map<String, String> = EmpleadosActions.filterParams(params) + ("start" to start.toString())

    fun week(context: Context, data: WeekData, params: Map<String, String>, notice: UiNotice? = null, submitted: Map<String, String>? = null): UiScreen {
        val start = data.start
        val keep = EmpleadosActions.filterParams(params).filterKeys { it == "site" || it == "department" }
        fun go(date: LocalDate) = UiNavigate("week", keep + ("start" to date.toString()))
        val days = (0..6).map { start.plusDays(it.toLong()) }
        val filterFields = listOf(
            UiField("site", "Sede", type = "dropdown", options = listOf(EmpleadosActions.AllOption) + data.sites.map { it.name }),
            UiField("department", "Departamento", type = "dropdown", options = listOf(EmpleadosActions.AllOption) + data.departments.map { it.name }),
        )
        val filterValues = filterFields.associate { it.key to (params[it.key]?.takeIf { value -> value.isNotBlank() } ?: EmpleadosActions.AllOption) }
        val columns = listOf(UiTableColumn("name", "Empleado")) + days.mapIndexed { index, date -> UiTableColumn("d$index", "${EmpleadosNotify.dayLabel(date).substringBefore(' ').replaceFirstChar { it.uppercase() }} ${date.dayOfMonth}") }
        val order = data.sites.map { it.id }
        fun position(siteId: UUID?): Int = siteId?.let { order.indexOf(it) }?.takeIf { it >= 0 } ?: Int.MAX_VALUE
        val groups = data.employees.groupBy { it.siteId }.entries
            .sortedWith(compareBy<Map.Entry<UUID?, List<Employee>>> { position(it.key) }.thenBy { it.value.first().site.orEmpty().lowercase() })
        val labels = EmpleadosActions.employeeLabels(data.allEmployees)
        val body = buildList<UiNode> {
            add(
                UiRow(
                    listOf(
                        UiButton("Semana anterior", go(start.minusWeeks(1)), style = "secondary"),
                        UiButton("Hoy", go(EmpleadosSchedule.weekStart(context.today)), style = "secondary"),
                        UiButton("Semana siguiente", go(start.plusWeeks(1)), style = "secondary"),
                    ),
                ),
            )
            add(hint("Semana del ${dayRange(start)}. Un * marca un turno concreto que reemplaza al horario habitual."))
            add(UiCard(listOf(UiForm("filters", filterFields, filterValues, submit = UiButton("Filtrar", UiNavigate("week", mapOf("start" to start.toString()), formId = "filters"), style = "secondary"), minFieldWidth = 200))))
            if (data.cut) add(hint("Se muestran los primeros ${EmpleadosRepository.MaxWeekEmployees} empleados: filtra por sede o departamento para ver el resto.", UiTone.Warning))
            if (groups.isEmpty()) add(UiCard(listOf(UiText("No hay empleados con esos filtros."))))
            groups.forEach { (_, employees) ->
                val rows = employees.map { employee ->
                    buildMap<String, String> {
                        put("name", employee.fullName)
                        days.forEachIndexed { index, date -> put("d$index", weekCell(employee, data.schedules.resolve(employee.id, date, employee.siteId), data.siteNames)) }
                    }
                }
                add(UiCard(listOf(UiTable(columns, rows)), title = employees.first().site ?: "Sin sede"))
            }
            add(
                UiCard(
                    listOf(
                        UiForm(
                            id = "shift-new",
                            fields = listOf(
                                UiField("employee", "Empleado", type = "dropdown", required = true, options = labels.values.toList()),
                                UiField("date", "Fecha", type = "date", required = true),
                                UiField("kind", "Tipo", type = "dropdown", options = ShiftKind.entries.map { it.label }, help = "Libre: ese día no trabaja y se borran sus otros turnos."),
                                UiField("start", "Desde", placeholder = "08:00", help = "Solo para turnos."),
                                UiField("end", "Hasta", placeholder = "16:00"),
                                UiField("site", "Sede", type = "dropdown", options = listOf(EmpleadosActions.NoSite) + data.sites.map { it.name }),
                                UiField("note", "Nota"),
                            ),
                            values = mapOf("kind" to ShiftKind.Work.label, "site" to EmpleadosActions.NoSite, "date" to start.toString()) + submitted.orEmpty(),
                            submit = UiButton("Crear turno", request("week", "shift-create", weekParams(start, params), formId = "shift-new")),
                        ),
                    ),
                    title = "Nuevo turno",
                ),
            )
            val names = data.allEmployees.associate { it.id to it.fullName }
            add(
                UiCard(
                    listOf(
                        UiList(
                            data.shifts.sortedWith(compareBy({ it.date }, { names[it.employeeId].orEmpty().lowercase() }, { it.start })).map { shift ->
                                UiListItem(
                                    title = "${names[shift.employeeId] ?: "Empleado"} · ${EmpleadosNotify.dayLabel(shift.date)}",
                                    subtitle = listOf(shift.siteId?.let { data.siteNames[it] }.orEmpty(), shift.note).filter { it.isNotBlank() }.joinToString(" · "),
                                    trailing = if (shift.kind == ShiftKind.Off) "Libre" else EmpleadosSchedule.formatRange(shift.start ?: java.time.LocalTime.MIDNIGHT, shift.end ?: java.time.LocalTime.MIDNIGHT),
                                    action = UiNavigate("shift", mapOf("id" to shift.id.toString())),
                                )
                            },
                            emptyText = "No hay turnos concretos esta semana: rige el horario habitual.",
                        ),
                    ),
                    title = "Turnos de la semana",
                ),
            )
        }
        return screen(context, "Semana", "week", notice, body)
    }

    // ---- Turno ----

    fun shift(context: Context, shift: Shift, employeeName: String, sites: List<CatalogItem>, siteNames: Map<UUID, String>, notice: UiNotice? = null, submitted: Map<String, String>? = null): UiScreen {
        val id = mapOf("id" to shift.id.toString())
        val work = shift.kind == ShiftKind.Work
        val fields = buildList {
            add(UiField("date", "Fecha", type = "date", required = true))
            if (work) {
                add(UiField("start", "Desde", required = true, placeholder = "08:00"))
                add(UiField("end", "Hasta", required = true, placeholder = "16:00"))
                add(UiField("site", "Sede", type = "dropdown", options = listOf(EmpleadosActions.NoSite) + (sites.map { it.name } + listOfNotNull(shift.siteId?.let { siteNames[it] })).distinct()))
            }
            add(UiField("note", "Nota"))
        }
        val values = buildMap {
            put("date", shift.date.toString())
            if (work) {
                put("start", shift.start?.let(EmpleadosSchedule::formatTime).orEmpty())
                put("end", shift.end?.let(EmpleadosSchedule::formatTime).orEmpty())
                put("site", shift.siteId?.let { siteNames[it] } ?: EmpleadosActions.NoSite)
            }
            put("note", shift.note)
        }
        val body = listOf<UiNode>(
            UiRow(listOf(UiButton("Volver a la semana", UiNavigate("week", mapOf("start" to EmpleadosSchedule.weekStart(shift.date).toString())), style = "secondary"))),
            UiCard(
                listOf(
                    hint("${if (work) "Turno" else "Día libre"} de $employeeName"),
                    UiForm("shift", fields, values + submitted.orEmpty(), submit = UiButton("Guardar", request("shift", "move", id, formId = "shift"))),
                    UiButton("Borrar", request("shift", "delete", id, confirm = "¿Borrar este turno? Ese día vuelve a regir el horario habitual."), style = "danger"),
                ),
                title = "${EmpleadosNotify.dayLabel(shift.date)} · $employeeName",
            ),
        )
        return screen(context, "Turno", "week", notice, body)
    }

    // ---- Ausencias ----

    /** [submittedForm] es el formulario ("absence-request" o "absence-register") cuyos valores [submitted] se devuelven tras un error. */
    fun absences(context: Context, data: AbsencesData, notice: UiNotice? = null, submitted: Map<String, String>? = null, submittedForm: String? = null): UiScreen {
        val shifts = context.scope.canShifts || context.scope.canManage
        val body = buildList<UiNode> {
            if (data.hasRecord) {
                add(absencesCard(data.mine.take(AbsencesShown), "absences", emptyMap()))
                add(
                    UiCard(
                        listOf(absenceForm("absence-request", null, submitted.orEmpty().takeIf { submittedForm == "absence-request" }.orEmpty(), UiButton("Pedir ausencia", request("absences", "absence-request", formId = "absence-request")))),
                        title = "Pedir ausencia",
                    ),
                )
            } else {
                add(UiCard(listOf(UiText(EmpleadosService.NoRecord))))
            }
            if (shifts) {
                add(
                    UiCard(
                        if (data.pending.isEmpty()) listOf(UiText("No hay solicitudes pendientes.")) else data.pending.flatMapIndexed { index, row ->
                            val form = "decide-${row.absence.id}"
                            val id = mapOf("id" to row.absence.id.toString())
                            buildList<UiNode> {
                                if (index > 0) add(UiDivider())
                                add(UiText("${row.employeeName} · ${absenceTitle(row.absence)}", UiTextStyle.Body))
                                if (row.absence.reason.isNotBlank()) add(hint(row.absence.reason))
                                add(UiForm(form, listOf(UiField("note", "Nota para el empleado")), submit = UiButton("Aprobar", request("absences", "decide-approve", id, formId = form))))
                                add(UiButton("Rechazar", request("absences", "decide-reject", id, confirm = "¿Rechazar esta solicitud?", formId = form), style = "danger"))
                            }
                        },
                        title = "Pendientes (${data.pending.size})",
                    ),
                )
                add(
                    UiCard(
                        listOf(
                            UiList(
                                data.upcoming.map { row ->
                                    UiListItem(title = row.employeeName, subtitle = row.absence.reason, trailing = absenceTitle(row.absence), badges = listOf(badge(row.absence.status)))
                                },
                                emptyText = "No hay ausencias aprobadas en los próximos $ApprovedDays días.",
                            ),
                        ),
                        title = "Próximas aprobadas ($ApprovedDays días)",
                    ),
                )
                val labels = EmpleadosActions.employeeLabels(data.employees)
                add(
                    UiCard(
                        listOf(
                            absenceForm(
                                "absence-register", labels, submitted.orEmpty().takeIf { submittedForm == "absence-register" }.orEmpty(),
                                UiButton("Registrar ausencia", request("absences", "register", formId = "absence-register")),
                            ),
                        ),
                        title = "Registrar ausencia (queda aprobada)",
                    ),
                )
            }
        }
        return screen(context, "Ausencias", "absences", notice, body)
    }

    // ---- Administrar ----

    fun admin(context: Context, sites: List<CatalogItem>, departments: List<CatalogItem>, notice: UiNotice? = null): UiScreen {
        val body = listOf(
            catalogCard("Sedes", "site", "sede", sites),
            catalogCard("Departamentos", "dept", "departamento", departments),
        )
        return screen(context, "Administrar", "admin", notice, body)
    }

    private fun catalogCard(title: String, prefix: String, singular: String, items: List<CatalogItem>): UiNode {
        val rows = items.map { item ->
            val params = mapOf("id" to item.id.toString())
            val form = "$prefix-${item.id}"
            UiColumn(
                listOf(
                    UiForm(
                        id = form,
                        fields = listOf(UiField("name", "Nombre", required = true)),
                        values = mapOf("name" to item.name),
                        submit = UiButton("Renombrar", request("admin", "$prefix-rename", params, formId = form), style = "secondary"),
                    ),
                    UiRow(
                        listOf(
                            UiButton("Subir", request("admin", "$prefix-up", params), style = "secondary"),
                            UiButton("Bajar", request("admin", "$prefix-down", params), style = "secondary"),
                            UiButton("Archivar", request("admin", "$prefix-archive", params, confirm = "¿Archivar la $singular ${item.name}? Las fichas que la tienen la conservan."), style = "danger"),
                        ),
                        spacing = 8,
                    ),
                ),
                spacing = 8,
            )
        }
        val children = buildList<UiNode> {
            if (rows.isEmpty()) add(hint("Aún no hay ${singular}s."))
            rows.forEachIndexed { index, row ->
                if (index > 0) add(UiDivider())
                add(row)
            }
            add(UiDivider())
            add(sectionTitle("Nueva $singular"))
            add(
                UiForm(
                    id = "$prefix-new",
                    fields = listOf(UiField("name", "Nombre", required = true)),
                    submit = UiButton("Añadir", request("admin", "$prefix-add", formId = "$prefix-new"), style = "secondary"),
                ),
            )
        }
        return UiCard(children, title = title)
    }
}
