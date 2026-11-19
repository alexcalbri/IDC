package com.ideasdeveloper.idc.modules.empleados

import java.sql.Connection
import java.time.LocalDate
import java.time.LocalTime
import java.util.UUID

/**
 * Un empleado que trabaja en una fecha. Solo datos basicos y horario: nunca telefono, correo, disponibilidad ni notas.
 * [source] es "habitual" (horario habitual) o "shift" (turno concreto). [userId] es la cuenta enlazada: sirve para
 * asignar trabajo a esa persona, no para mostrarse ni exponerse a quien no corresponda.
 */
data class OnShiftEmployee(
    val employeeId: UUID,
    val name: String,
    val jobTitle: String,
    val department: String?,
    val siteId: UUID?,
    val site: String?,
    val userId: UUID?,
    val start: LocalTime,
    val end: LocalTime,
    val source: String,
)

/**
 * Punto de consulta para otros modulos (por ejemplo mantenimiento, para saber quien esta de turno). Es bloqueante: llamarlo
 * dentro de withContext(Dispatchers.IO). No comprueba permisos: quien llama debe comprobar que `empleados` esta activo para
 * la empresa y su propio permiso (ver el README, "Para otros modulos"). El `userId` de [OnShiftEmployee] es para asignar
 * trabajo, no para mostrarlo ni exponerlo.
 */
object EmpleadosExternal {
    // Un usuario sin permisos: sus reglas son las del directorio basico (solo activos, solo columnas basicas).
    private val BasicScope = EmpleadosScope(userId = null, myEmployeeId = null, canShifts = false, canManage = false)

    /**
     * Quien trabaja en [date] (opcionalmente en la sede [siteId] y a la hora [at]). Una entrada por empleado; ver [computeOnShift].
     */
    fun onShift(tenant: Connection, date: LocalDate, siteId: UUID? = null, at: LocalTime? = null): List<OnShiftEmployee> {
        val employees = EmpleadosRepository.list(tenant, BasicScope, EmployeeFilter(status = EmployeeStatus.Active, limit = EmpleadosRepository.MaxEmployeesQuery), FieldLevel.Basic)
        if (employees.isEmpty()) return emptyList()
        val data = EmpleadosRepository.scheduleData(tenant, null, if (at != null) date.minusDays(1) else date, date)
        return computeOnShift(
            date, siteId, at, employees, data,
            EmpleadosRepository.catalogNames(tenant, Catalog.Sites),
            EmpleadosRepository.userIdsOf(tenant, employees.map { it.id }),
        )
    }

    /**
     * El calculo puro de [onShift] sobre datos ya cargados ([data] debe incluir el dia anterior si [at] no es null):
     * - solo empleados activos; una ausencia aprobada del dia lo anula;
     * - sin [at]: la primera franja del dia; con [at]: la franja que contiene esa hora (la nocturna cuenta desde su inicio);
     * - la madrugada de una franja nocturna del dia anterior cuenta con [at] aunque hoy haya una ausencia aprobada
     *   (el empleado trabajo esa noche), pero la preferida es siempre la franja del dia;
     * - [siteId] filtra por la sede de la franja (la suya o, si no tiene, la de la ficha);
     * - una sola entrada por empleado.
     */
    fun computeOnShift(
        date: LocalDate,
        siteId: UUID?,
        at: LocalTime?,
        employees: List<Employee>,
        data: ScheduleData,
        siteNames: Map<UUID, String>,
        users: Map<UUID, UUID>,
    ): List<OnShiftEmployee> {
        val result = mutableListOf<OnShiftEmployee>()
        employees.filter { it.status == EmployeeStatus.Active }.forEach { employee ->
            val today = (data.resolve(employee.id, date, employee.siteId) as? DaySchedule.Working)?.slots.orEmpty()
                .filter { at == null || EmpleadosSchedule.containsFromStart(it, at) }
            val tail = if (at == null) {
                emptyList()
            } else {
                (data.resolve(employee.id, date.minusDays(1), employee.siteId) as? DaySchedule.Working)?.slots.orEmpty()
                    .filter { EmpleadosSchedule.overnightTailContains(it, at) }
            }
            val slot = (today + tail).firstOrNull { siteId == null || it.siteId == siteId } ?: return@forEach
            result += OnShiftEmployee(
                employeeId = employee.id,
                name = employee.fullName,
                jobTitle = employee.jobTitle,
                department = employee.department,
                siteId = slot.siteId,
                site = slot.siteId?.let { siteNames[it] },
                userId = users[employee.id],
                start = slot.start,
                end = slot.end,
                source = slot.source,
            )
        }
        return result.sortedWith(compareBy({ it.start }, { it.name.lowercase() }, { it.employeeId }))
    }

    /** Las sedes no archivadas, por posicion. */
    fun sites(tenant: Connection): List<Pair<UUID, String>> = EmpleadosRepository.listCatalog(tenant, Catalog.Sites).map { it.id to it.name }

    /** La ficha enlazada a la cuenta, o null. */
    fun employeeForUser(tenant: Connection, userId: UUID): UUID? = EmpleadosRepository.employeeIdForUser(tenant, userId)
}
