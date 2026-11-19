package com.ideasdeveloper.idc.modules.empleados

import com.ideasdeveloper.idc.server.modules.ModuleAccessService
import com.ideasdeveloper.idc.server.modules.ModulePermissions
import com.ideasdeveloper.idc.server.modules.requireModuleAccess
import com.ideasdeveloper.idc.ui.UiButton
import com.ideasdeveloper.idc.ui.UiNavigate
import com.ideasdeveloper.idc.ui.UiNode
import com.ideasdeveloper.idc.ui.UiNotice
import com.ideasdeveloper.idc.ui.UiRow
import com.ideasdeveloper.idc.ui.UiScreen
import com.ideasdeveloper.idc.ui.UiText
import com.ideasdeveloper.idc.ui.UiTextStyle
import com.ideasdeveloper.idc.ui.UiTone
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.receiveText
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import kotlinx.coroutines.CancellationException
import org.slf4j.LoggerFactory
import java.sql.Connection
import java.time.LocalDate
import java.util.UUID

/**
 * Pantallas nativas del modulo: GET y POST de cada pantalla bajo /modules/empleados/ui. El permiso se comprueba en cada
 * ruta antes de abrir la conexion; el servicio aplica despues las reglas finas (visibilidad por usuario incluida).
 */
object EmpleadosRoutes {
    private val log = LoggerFactory.getLogger(EmpleadosRoutes::class.java)
    private const val ImportScreen = "importar"
    private val ScreenIds = setOf("mine", "team", "employee", "employee-new", "week", "shift", "absences", "admin", ImportScreen)

    /** La pantalla que se muestra despues, con los parametros que conserva. */
    private class Outcome(val screen: String, val params: Map<String, String>, val notice: UiNotice)

    private fun success(screen: String, params: Map<String, String>, text: String) = Outcome(screen, params, UiNotice(text, UiTone.Success))

    private fun allowed(permissions: ModulePermissions, screen: String, op: String?): Boolean =
        EmpleadosActions.requiredPermissions(screen, op).any { permissions.has(it) }

    fun screens(route: Route, access: ModuleAccessService) {
        route.get("/{screen}") {
            val permissions = call.requireModuleAccess(access, EmpleadosModule.id) ?: return@get
            val screen = call.parameters["screen"].orEmpty()
            if (screen !in ScreenIds) return@get call.respond(HttpStatusCode.NotFound)
            if (!allowed(permissions, screen, null)) return@get call.forbidden()
            val params = call.request.queryParameters.entries().associate { it.key to it.value.firstOrNull().orEmpty() }
            if (screen == ImportScreen) {
                return@get call.respondEmpleados(access, UiScreen.serializer()) { tenant -> importScreen(tenant, access, permissions, params, null) }
            }
            call.respondEmpleados(access, UiScreen.serializer()) { tenant -> open(tenant, access, permissions, screen, params, null, null, null) }
        }

        route.post("/{screen}") {
            val permissions = call.requireModuleAccess(access, EmpleadosModule.id) ?: return@post
            val screen = call.parameters["screen"].orEmpty()
            if (screen !in ScreenIds) return@post call.respond(HttpStatusCode.NotFound)
            val params = call.request.queryParameters.entries().associate { it.key to it.value.firstOrNull().orEmpty() }
            // El permiso minimo se comprueba antes de abrir la conexion; el servicio aplica las reglas finas despues.
            if (!allowed(permissions, screen, params["op"].orEmpty())) return@post call.forbidden()
            val length = call.request.headers["Content-Length"]?.toLongOrNull()
            val values = try {
                if (length != null && length > 1_000_000L) throw EmpleadosValidationException(EmpleadosActions.InvalidForm)
                EmpleadosActions.formValues(call.receiveText())
            } catch (failure: EmpleadosValidationException) {
                return@post call.respond(HttpStatusCode.BadRequest, EmpleadosErrorResponse("invalid_request", failure.message.orEmpty()))
            }
            if (screen == ImportScreen) {
                return@post call.respondEmpleados(access, UiScreen.serializer()) { tenant -> importAct(tenant, access, permissions, params, values) }
            }
            call.respondEmpleados(access, UiScreen.serializer()) { tenant -> act(tenant, access, permissions, screen, params, values) }
        }
    }

    private suspend fun ApplicationCall.forbidden() =
        respond(HttpStatusCode.Forbidden, EmpleadosErrorResponse("missing_permission", "No tienes permiso para esta accion."))

    // ---- importar (las fuentes son extensiones: ver EmpleadosImportSource) ----

    private fun importContext(tenant: Connection, access: ModuleAccessService, permissions: ModulePermissions) = EmpleadosImportContext(
        access, tenant, permissions.companyCode, permissions, access.userIdentity(tenant, permissions)?.name ?: "Usuario", EmpleadosRepository.today(),
    )

    private fun errorBody(text: String) = listOf<UiNode>(UiText(text, UiTextStyle.Body, UiTone.Danger))

    /** "Importar": la pantalla de una fuente, o los botones para elegir entre varias. Necesita empleados.import (lo comprueba la ruta). */
    private fun importScreen(tenant: Connection, access: ModuleAccessService, permissions: ModulePermissions, params: Map<String, String>, notice: UiNotice?): UiScreen {
        val scope = EmpleadosService.scope(tenant, permissions)
        val sources = EmpleadosImportSources.enabled(tenant, permissions.companyCode)
        fun screen(body: List<UiNode>) = UiScreen(
            title = "Importar",
            navigation = EmpleadosScreens.navigation(scope, ImportScreen, import = sources.isNotEmpty()),
            notice = notice,
            body = body,
        )
        if (sources.isEmpty()) {
            return screen(listOf(UiText("No hay fuentes de importación activas. Instala y activa una extensión de importación de Empleados (por ejemplo Monday) y vuelve aquí.")))
        }
        val chosen = sources.firstOrNull { it.sourceId == params["source"] } ?: sources.singleOrNull()
        val picker = if (sources.size > 1) {
            listOf<UiNode>(UiRow(sources.map { UiButton(it.title, UiNavigate(ImportScreen, mapOf("source" to it.sourceId)), style = if (it === chosen) "primary" else "secondary") }))
        } else {
            emptyList()
        }
        if (chosen == null) return screen(picker + UiText("Elige desde dónde quieres importar.", UiTextStyle.Caption))
        val body = try {
            chosen.screen(importContext(tenant, access, permissions), params)
        } catch (failure: EmpleadosValidationException) {
            errorBody(failure.message.orEmpty())
        } catch (failure: CancellationException) {
            throw failure
        } catch (failure: Exception) {
            log.warn("No se pudo mostrar la fuente de importación {} ({}).", chosen.sourceId, failure.javaClass.simpleName)
            errorBody("No se pudo mostrar esta fuente de importación.")
        }
        return screen(picker + body)
    }

    private fun importAct(tenant: Connection, access: ModuleAccessService, permissions: ModulePermissions, params: Map<String, String>, values: Map<String, String>): UiScreen {
        val source = EmpleadosImportSources.enabled(tenant, permissions.companyCode).firstOrNull { it.sourceId == params["source"] }
            ?: return importScreen(tenant, access, permissions, emptyMap(), UiNotice("Esa fuente de importación no está disponible.", UiTone.Danger))
        val reply = try {
            source.action(importContext(tenant, access, permissions), params["op"].orEmpty(), values)
        } catch (failure: EmpleadosValidationException) {
            EmpleadosImportReply(failure.message.orEmpty(), error = true)
        } catch (failure: CancellationException) {
            throw failure
        } catch (failure: Exception) {
            log.warn("Falló una operación de importación de {} ({}).", source.sourceId, failure.javaClass.simpleName)
            EmpleadosImportReply("No se pudo completar la operación.", error = true)
        }
        return importScreen(tenant, access, permissions, reply.params + ("source" to source.sourceId), UiNotice(reply.notice, if (reply.error) UiTone.Danger else UiTone.Success))
    }

    // ---- cargar pantallas ----

    private fun uuid(params: Map<String, String>, key: String): UUID? = EmpleadosActions.uuid(params[key])

    private fun missing(what: String) = EmpleadosValidationException(what)

    private fun context(tenant: Connection, permissions: ModulePermissions) = EmpleadosScreens.Context(
        scope = EmpleadosService.scope(tenant, permissions),
        today = EmpleadosRepository.today(),
        importEnabled = permissions.has(EmpleadosModule.Import) && EmpleadosImportSources.enabled(tenant, permissions.companyCode).isNotEmpty(),
    )

    /** Construye [screen]. Una regla que falla (una ficha que ya no existe, una pantalla que el usuario no puede abrir) muestra "Mi horario" con el mensaje. */
    private fun open(
        tenant: Connection,
        access: ModuleAccessService,
        permissions: ModulePermissions,
        screen: String,
        params: Map<String, String>,
        notice: UiNotice?,
        submitted: Map<String, String>?,
        submittedForm: String?,
        loaded: EmpleadosScreens.Context? = null,
    ): UiScreen {
        val context = loaded ?: context(tenant, permissions)
        return try {
            build(tenant, context, screen, params, notice, submitted, submittedForm)
        } catch (failure: EmpleadosValidationException) {
            build(tenant, context, "mine", emptyMap(), UiNotice(failure.message.orEmpty(), UiTone.Danger), null, null)
        }
    }

    /** Los elementos activos mas el actual aunque este archivado (una ficha conserva su sede archivada). */
    private fun withCurrent(items: List<CatalogItem>, all: () -> List<CatalogItem>, currentId: UUID?): List<CatalogItem> {
        if (currentId == null || items.any { it.id == currentId }) return items
        return items + all().filter { it.id == currentId }
    }

    private fun build(
        tenant: Connection,
        context: EmpleadosScreens.Context,
        screen: String,
        params: Map<String, String>,
        notice: UiNotice?,
        submitted: Map<String, String>?,
        submittedForm: String?,
    ): UiScreen {
        val scope = context.scope
        val shifts = scope.canShifts || scope.canManage
        fun requireShifts() { if (!shifts) throw missing("No tienes permiso para abrir esa pantalla.") }
        fun requireManage() { if (!scope.canManage) throw missing("Solo quien administra fichas puede abrir esa pantalla.") }
        return when (screen) {
            "team" -> {
                val sites = EmpleadosRepository.listCatalog(tenant, Catalog.Sites)
                val departments = EmpleadosRepository.listCatalog(tenant, Catalog.Departments)
                val filter = EmpleadosActions.filter(params, sites, departments, EmpleadosScreens.ListLimit + 1)
                EmpleadosScreens.team(context, EmpleadosScreens.TeamData(EmpleadosRepository.list(tenant, scope, filter), sites, departments), params, notice)
            }
            "employee" -> {
                val employee = EmpleadosService.visibleEmployee(tenant, scope, uuid(params, "id") ?: throw missing("Empleado no encontrado."))
                val today = context.today
                val siteNames = if (shifts) EmpleadosRepository.catalogNames(tenant, Catalog.Sites) else emptyMap()
                val upcoming = if (shifts) {
                    val data = EmpleadosRepository.scheduleData(tenant, listOf(employee.id), today, today.plusDays(EmpleadosScreens.UpcomingDays - 1L))
                    (0 until EmpleadosScreens.UpcomingDays).map { today.plusDays(it.toLong()) }.map { it to data.resolve(employee.id, it, employee.siteId) }
                } else {
                    emptyList()
                }
                EmpleadosScreens.employee(
                    context,
                    EmpleadosScreens.EmployeeDetail(
                        employee = employee,
                        sites = if (scope.canManage) withCurrent(EmpleadosRepository.listCatalog(tenant, Catalog.Sites), { EmpleadosRepository.listCatalog(tenant, Catalog.Sites, true) }, employee.siteId) else EmpleadosRepository.listCatalog(tenant, Catalog.Sites),
                        departments = if (scope.canManage) withCurrent(EmpleadosRepository.listCatalog(tenant, Catalog.Departments), { EmpleadosRepository.listCatalog(tenant, Catalog.Departments, true) }, employee.departmentId) else emptyList(),
                        users = if (scope.canManage) EmpleadosRepository.linkableUsers(tenant, employee.userId) else emptyList(),
                        weekly = if (shifts) EmpleadosRepository.weekly(tenant, listOf(employee.id)) else emptyList(),
                        upcoming = upcoming,
                        siteNames = siteNames,
                    ),
                    notice, submitted,
                )
            }
            "employee-new" -> {
                requireManage()
                EmpleadosScreens.employeeNew(
                    context,
                    EmpleadosRepository.listCatalog(tenant, Catalog.Sites),
                    EmpleadosRepository.listCatalog(tenant, Catalog.Departments),
                    EmpleadosRepository.linkableUsers(tenant, null),
                    notice, submitted,
                )
            }
            "week" -> {
                requireShifts()
                val start = EmpleadosActions.weekOf(params["start"], context.today)
                val sites = EmpleadosRepository.listCatalog(tenant, Catalog.Sites)
                val departments = EmpleadosRepository.listCatalog(tenant, Catalog.Departments)
                val filter = EmpleadosActions.filter(params + ("status" to EmpleadosActions.ActiveOption), sites, departments, EmpleadosRepository.MaxWeekEmployees + 1)
                val loaded = EmpleadosRepository.list(tenant, scope, filter, FieldLevel.Basic)
                val employees = loaded.take(EmpleadosRepository.MaxWeekEmployees)
                val data = EmpleadosRepository.scheduleData(tenant, employees.map { it.id }, start, start.plusDays(6))
                EmpleadosScreens.week(
                    context,
                    EmpleadosScreens.WeekData(
                        start = start,
                        employees = employees,
                        cut = loaded.size > EmpleadosRepository.MaxWeekEmployees,
                        schedules = data,
                        sites = sites,
                        departments = departments,
                        shifts = data.shifts.values.flatten(),
                        allEmployees = EmpleadosRepository.list(tenant, scope, EmployeeFilter(status = EmployeeStatus.Active, limit = EmpleadosRepository.MaxListed), FieldLevel.Basic),
                        siteNames = EmpleadosRepository.catalogNames(tenant, Catalog.Sites),
                    ),
                    params, notice, submitted,
                )
            }
            "shift" -> {
                val shift = EmpleadosService.getShift(tenant, scope, uuid(params, "id") ?: throw missing("Turno no encontrado."))
                val name = EmpleadosRepository.basics(tenant, listOf(shift.employeeId))[shift.employeeId]?.fullName ?: "Empleado"
                EmpleadosScreens.shift(context, shift, name, EmpleadosRepository.listCatalog(tenant, Catalog.Sites), EmpleadosRepository.catalogNames(tenant, Catalog.Sites), notice, submitted)
            }
            "absences" -> {
                val me = scope.myEmployeeId
                val today = context.today
                EmpleadosScreens.absences(
                    context,
                    EmpleadosScreens.AbsencesData(
                        hasRecord = me != null,
                        mine = me?.let { EmpleadosRepository.listAbsences(tenant, scope, it, null, null, null, 51, newestFirst = true).map { row -> row.absence } }.orEmpty(),
                        pending = if (shifts) EmpleadosRepository.listAbsences(tenant, scope, null, listOf(AbsenceStatus.Pending), null, null, 50) else emptyList(),
                        upcoming = if (shifts) EmpleadosRepository.listAbsences(tenant, scope, null, listOf(AbsenceStatus.Approved), today, today.plusDays(EmpleadosScreens.ApprovedDays.toLong()), 100) else emptyList(),
                        employees = if (shifts) EmpleadosRepository.list(tenant, scope, EmployeeFilter(status = EmployeeStatus.Active, limit = EmpleadosRepository.MaxListed), FieldLevel.Basic) else emptyList(),
                    ),
                    notice, submitted, submittedForm,
                )
            }
            "admin" -> {
                requireManage()
                EmpleadosScreens.admin(context, EmpleadosRepository.listCatalog(tenant, Catalog.Sites), EmpleadosRepository.listCatalog(tenant, Catalog.Departments), notice)
            }
            else -> mine(tenant, context, params, notice, submitted)
        }
    }

    private fun mine(tenant: Connection, context: EmpleadosScreens.Context, params: Map<String, String>, notice: UiNotice?, submitted: Map<String, String>?): UiScreen {
        val week = EmpleadosActions.weekOf(params["week"], context.today)
        val employee = context.scope.myEmployeeId?.let { EmpleadosRepository.get(tenant, context.scope, it) }
        if (employee == null) {
            return EmpleadosScreens.mine(context, EmpleadosScreens.MineData(null, week, emptyList(), emptyList(), emptyList(), emptyMap()), notice, submitted)
        }
        val data = EmpleadosRepository.scheduleData(tenant, listOf(employee.id), week, week.plusDays(6))
        return EmpleadosScreens.mine(
            context,
            EmpleadosScreens.MineData(
                employee = employee,
                week = week,
                days = (0..6).map { week.plusDays(it.toLong()) }.map { it to data.resolve(employee.id, it, employee.siteId) },
                weekly = data.weekly[employee.id].orEmpty(),
                absences = EmpleadosRepository.listAbsences(tenant, context.scope, employee.id, null, null, null, 51, newestFirst = true).map { it.absence },
                siteNames = EmpleadosRepository.catalogNames(tenant, Catalog.Sites),
            ),
            notice, submitted,
        )
    }

    // ---- acciones ----

    /** Parametros de consulta que cada pantalla conserva tras una accion (el resto pertenece a la operacion que acaba de correr). */
    private fun keep(screen: String, params: Map<String, String>): Map<String, String> {
        val keys = when (screen) {
            "mine" -> setOf("week")
            "employee", "shift" -> setOf("id")
            "week" -> setOf("start", "site", "department")
            "team" -> setOf("q", "site", "department", "status")
            else -> emptySet()
        }
        return params.filterKeys { it in keys }
    }

    private fun act(
        tenant: Connection,
        access: ModuleAccessService,
        permissions: ModulePermissions,
        screen: String,
        params: Map<String, String>,
        values: Map<String, String>,
    ): UiScreen {
        val context = context(tenant, permissions)
        val outcome = try {
            perform(tenant, access, context, screen, params, values)
        } catch (failure: EmpleadosValidationException) {
            // La misma pantalla otra vez con el mensaje y lo que el usuario escribio.
            val form = when (params["op"]) {
                "register" -> "absence-register"
                "absence-request" -> "absence-request"
                else -> null
            }
            return open(tenant, access, permissions, screen, keep(screen, params), UiNotice(failure.message.orEmpty(), UiTone.Danger), values, form, context)
        }
        return open(tenant, access, permissions, outcome.screen, outcome.params, outcome.notice, null, null, context)
    }

    private fun perform(
        tenant: Connection,
        access: ModuleAccessService,
        context: EmpleadosScreens.Context,
        screen: String,
        params: Map<String, String>,
        values: Map<String, String>,
    ): Outcome {
        val scope = context.scope
        val today = context.today
        val op = params["op"].orEmpty()
        fun requireId(key: String): UUID = uuid(params, key) ?: throw missing("Falta el elemento sobre el que actuar.")
        val same = keep(screen, params)
        fun absenceDates(): Triple<AbsenceKind, LocalDate, LocalDate> = Triple(
            EmpleadosActions.absenceKind(values["kind"]),
            EmpleadosActions.requiredDate(values["from"], "La fecha inicial"),
            EmpleadosActions.requiredDate(values["to"], "La fecha final"),
        )
        fun activeEmployees() = EmpleadosRepository.list(tenant, scope, EmployeeFilter(status = EmployeeStatus.Active, limit = EmpleadosRepository.MaxListed), FieldLevel.Basic)

        return when (screen) {
            "mine", "absences" -> when (op) {
                "absence-request" -> {
                    val (kind, from, to) = absenceDates()
                    EmpleadosService.requestAbsence(tenant, access, scope, kind, from, to, values["reason"].orEmpty())
                    success(screen, same, "Solicitud enviada: queda pendiente de aprobación.")
                }
                "absence-cancel" -> {
                    EmpleadosService.cancelAbsence(tenant, scope, requireId("id"))
                    success(screen, same, "Solicitud cancelada.")
                }
                "register" -> {
                    if (screen != "absences") throw missing("Acción no disponible.")
                    val (kind, from, to) = absenceDates()
                    val employeeId = EmpleadosActions.employeeId(values["employee"], EmpleadosActions.employeeLabels(activeEmployees()))
                    EmpleadosService.registerAbsence(tenant, access, scope, AbsenceInput(employeeId, kind, from, to, values["reason"].orEmpty()))
                    success(screen, same, "Ausencia registrada y aprobada.")
                }
                "decide-approve", "decide-reject" -> {
                    if (screen != "absences") throw missing("Acción no disponible.")
                    val approve = op == "decide-approve"
                    EmpleadosService.decideAbsence(tenant, access, scope, requireId("id"), approve, values["note"].orEmpty())
                    success(screen, same, if (approve) "Ausencia aprobada." else "Ausencia rechazada.")
                }
                else -> throw missing("Acción no disponible.")
            }

            "employee-new" -> when (op) {
                "create" -> {
                    val input = EmpleadosActions.employeeInput(
                        values, EmpleadosRepository.listCatalog(tenant, Catalog.Sites), EmpleadosRepository.listCatalog(tenant, Catalog.Departments),
                        EmpleadosRepository.linkableUsers(tenant, null), null, today,
                    )
                    val id = EmpleadosService.createEmployee(tenant, scope, input)
                    success("employee", mapOf("id" to id.toString()), "Ficha creada.")
                }
                else -> throw missing("Acción no disponible.")
            }

            "employee" -> {
                val id = requireId("id")
                when (op) {
                    "save" -> {
                        val existing = EmpleadosService.visibleEmployee(tenant, scope, id)
                        val sites = withCurrent(EmpleadosRepository.listCatalog(tenant, Catalog.Sites), { EmpleadosRepository.listCatalog(tenant, Catalog.Sites, true) }, existing.siteId)
                        val departments = withCurrent(EmpleadosRepository.listCatalog(tenant, Catalog.Departments), { EmpleadosRepository.listCatalog(tenant, Catalog.Departments, true) }, existing.departmentId)
                        val input = EmpleadosActions.employeeInput(values, sites, departments, EmpleadosRepository.linkableUsers(tenant, existing.userId), existing, today)
                        EmpleadosService.updateEmployee(tenant, scope, id, input)
                        success("employee", same, "Cambios guardados.")
                    }
                    "status" -> {
                        val active = params["active"].toBoolean()
                        EmpleadosService.setActive(tenant, scope, id, active)
                        success("employee", same, if (active) "Ficha reactivada." else "Ficha dada de baja.")
                    }
                    "slot-add" -> {
                        val sites = EmpleadosRepository.listCatalog(tenant, Catalog.Sites)
                        EmpleadosService.addWeeklySlot(
                            tenant, access, scope, id,
                            EmpleadosActions.weekday(values["weekday"]),
                            EmpleadosActions.requiredTime(values["start"], "La hora de inicio"),
                            EmpleadosActions.requiredTime(values["end"], "La hora de fin"),
                            EmpleadosActions.siteId(values["site"], sites),
                        )
                        success("employee", same, "Franja añadida al horario habitual.")
                    }
                    "slot-remove" -> {
                        EmpleadosService.removeWeeklySlot(tenant, access, scope, requireId("slot"))
                        success("employee", same, "Franja quitada.")
                    }
                    else -> throw missing("Acción no disponible.")
                }
            }

            "week" -> when (op) {
                "shift-create" -> {
                    val employeeId = EmpleadosActions.employeeId(values["employee"], EmpleadosActions.employeeLabels(activeEmployees()))
                    val kind = EmpleadosActions.shiftKind(values["kind"])
                    val date = EmpleadosActions.requiredDate(values["date"], "La fecha")
                    val input = ShiftInput(
                        employeeId = employeeId,
                        date = date,
                        kind = kind,
                        start = if (kind == ShiftKind.Work) EmpleadosActions.time(values["start"]) else null,
                        end = if (kind == ShiftKind.Work) EmpleadosActions.time(values["end"]) else null,
                        siteId = if (kind == ShiftKind.Work) EmpleadosActions.siteId(values["site"], EmpleadosRepository.listCatalog(tenant, Catalog.Sites)) else null,
                        note = values["note"].orEmpty(),
                    )
                    EmpleadosService.createShift(tenant, access, scope, input)
                    success("week", same + ("start" to EmpleadosSchedule.weekStart(date).toString()), "Turno guardado.")
                }
                else -> throw missing("Acción no disponible.")
            }

            "shift" -> {
                val id = requireId("id")
                when (op) {
                    "move" -> {
                        val shift = EmpleadosService.getShift(tenant, scope, id)
                        val sites = withCurrent(EmpleadosRepository.listCatalog(tenant, Catalog.Sites), { EmpleadosRepository.listCatalog(tenant, Catalog.Sites, true) }, shift.siteId)
                        val work = shift.kind == ShiftKind.Work
                        EmpleadosService.moveShift(
                            tenant, access, scope, id,
                            ShiftPatch(
                                date = EmpleadosActions.requiredDate(values["date"], "La fecha"),
                                start = if (work) EmpleadosActions.requiredTime(values["start"], "La hora de inicio") else null,
                                end = if (work) EmpleadosActions.requiredTime(values["end"], "La hora de fin") else null,
                                setSite = work,
                                siteId = if (work) EmpleadosActions.siteId(values["site"], sites) else null,
                                note = values["note"].orEmpty(),
                            ),
                        )
                        success("shift", same, "Turno guardado.")
                    }
                    "delete" -> {
                        val shift = EmpleadosService.getShift(tenant, scope, id)
                        EmpleadosService.deleteShift(tenant, access, scope, id)
                        success("week", mapOf("start" to EmpleadosSchedule.weekStart(shift.date).toString()), "Turno borrado.")
                    }
                    else -> throw missing("Acción no disponible.")
                }
            }

            "admin" -> {
                val catalog = when {
                    op.startsWith("site-") -> Catalog.Sites
                    op.startsWith("dept-") -> Catalog.Departments
                    else -> throw missing("Acción no disponible.")
                }
                when (op.substringAfter('-')) {
                    "add" -> {
                        EmpleadosService.addCatalog(tenant, scope, catalog, values["name"].orEmpty())
                        success("admin", emptyMap(), "Se añadió la ${catalog.singular}.")
                    }
                    "rename" -> {
                        EmpleadosService.renameCatalog(tenant, scope, catalog, requireId("id"), values["name"].orEmpty())
                        success("admin", emptyMap(), "Nombre guardado.")
                    }
                    "up", "down" -> {
                        EmpleadosService.moveCatalog(tenant, scope, catalog, requireId("id"), up = op.endsWith("-up"))
                        success("admin", emptyMap(), "Orden actualizado.")
                    }
                    "archive" -> {
                        EmpleadosService.archiveCatalog(tenant, scope, catalog, requireId("id"))
                        success("admin", emptyMap(), "Se archivó la ${catalog.singular}.")
                    }
                    else -> throw missing("Acción no disponible.")
                }
            }

            else -> throw missing("Acción no disponible.")
        }
    }
}
