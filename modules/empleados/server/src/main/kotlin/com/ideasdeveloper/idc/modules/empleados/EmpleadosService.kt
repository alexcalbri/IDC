package com.ideasdeveloper.idc.modules.empleados

import com.ideasdeveloper.idc.server.modules.ModuleAccessService
import com.ideasdeveloper.idc.server.modules.ModulePermissions
import java.sql.Connection
import java.sql.SQLException
import java.time.LocalDate
import java.time.LocalTime
import java.util.UUID

/**
 * Operaciones del negocio que comparten las pantallas y las herramientas del agente, para que las reglas vivan en un solo
 * lugar. Cada una carga el alcance del usuario, comprueba permiso y visibilidad, escribe dentro de una transaccion y, ya
 * confirmada, envia los avisos (que nunca rompen la operacion). Una ficha que el usuario no ve se informa como no encontrada.
 *
 * Las columnas que guardan usuarios (autor, quien pidio, quien decidio) reciben [EmpleadosScope.userId], que es null para
 * el dueno del servidor (un usuario central que no esta entre los usuarios de la empresa).
 */
object EmpleadosService {
    private const val EmployeeNotFound = "Empleado no encontrado."
    private const val ShiftNotFound = "Turno no encontrado."
    private const val AbsenceNotFound = "Ausencia no encontrada."
    private const val NeedShifts = "No tienes permiso para gestionar turnos."
    private const val NeedManage = "No tienes permiso para administrar fichas."
    const val SelfApproval = "No puedes aprobar tus propias ausencias."
    const val NoRecord = "Tu usuario no tiene ficha de empleado; pide a quien administra fichas que la enlace."

    fun scope(tenant: Connection, permissions: ModulePermissions): EmpleadosScope =
        EmpleadosScope.from(permissions, permissions.userId?.takeUnless { permissions.serverOwner }?.let { EmpleadosRepository.employeeIdForUser(tenant, it) })

    /** La ficha cuando el usuario la ve, con los campos que le corresponden. */
    fun visibleEmployee(tenant: Connection, scope: EmpleadosScope, id: UUID): Employee =
        EmpleadosRepository.get(tenant, scope, id) ?: throw EmpleadosValidationException(EmployeeNotFound)

    private fun requireShifts(scope: EmpleadosScope) {
        if (!(scope.canShifts || scope.canManage)) throw EmpleadosValidationException(NeedShifts)
    }

    private fun requireManage(scope: EmpleadosScope) {
        if (!scope.canManage) throw EmpleadosValidationException(NeedManage)
    }

    private fun failIfAny(problems: List<String>) {
        if (problems.isNotEmpty()) throw EmpleadosValidationException(problems.joinToString(" "))
    }

    private fun uniqueGuard(message: String, block: () -> Unit) {
        try {
            block()
        } catch (failure: SQLException) {
            if (EmpleadosRepository.isUniqueViolation(failure)) throw EmpleadosValidationException(message) else throw failure
        }
    }

    // ---- fichas ----

    private fun checkRefs(tenant: Connection, input: EmployeeInput, existing: Employee?) {
        input.departmentId?.takeIf { it != existing?.departmentId }?.let { id ->
            EmpleadosRepository.getCatalog(tenant, Catalog.Departments, id)?.takeIf { !it.archived } ?: throw EmpleadosValidationException("Elige un departamento de la lista.")
        }
        input.siteId?.takeIf { it != existing?.siteId }?.let { id ->
            EmpleadosRepository.getCatalog(tenant, Catalog.Sites, id)?.takeIf { !it.archived } ?: throw EmpleadosValidationException("Elige una sede de la lista.")
        }
        input.userId?.takeIf { it != existing?.userId }?.let { id ->
            if (!EmpleadosRepository.canLink(tenant, id, existing?.id)) throw EmpleadosValidationException("Ese usuario no está activo o ya tiene otra ficha.")
        }
    }

    fun createEmployee(tenant: Connection, scope: EmpleadosScope, input: EmployeeInput): UUID {
        requireManage(scope)
        failIfAny(input.validate(EmpleadosRepository.today()))
        checkRefs(tenant, input, null)
        var id: UUID? = null
        uniqueGuard("Ese usuario ya tiene otra ficha.") { id = EmpleadosRepository.insertEmployee(tenant, input, scope.userId) }
        return id ?: throw EmpleadosValidationException("No se pudo guardar la ficha.")
    }

    fun updateEmployee(tenant: Connection, scope: EmpleadosScope, id: UUID, input: EmployeeInput) {
        requireManage(scope)
        val existing = visibleEmployee(tenant, scope, id)
        failIfAny(input.validate(EmpleadosRepository.today()))
        checkRefs(tenant, input, existing)
        uniqueGuard("Ese usuario ya tiene otra ficha.") {
            if (!EmpleadosRepository.updateEmployee(tenant, id, input)) throw EmpleadosValidationException(EmployeeNotFound)
        }
    }

    /** Da de baja (con la fecha de hoy) o reactiva una ficha. */
    fun setActive(tenant: Connection, scope: EmpleadosScope, id: UUID, active: Boolean) {
        requireManage(scope)
        visibleEmployee(tenant, scope, id)
        EmpleadosRepository.setStatus(tenant, id, if (active) EmployeeStatus.Active else EmployeeStatus.Inactive, EmpleadosRepository.today())
    }

    // ---- sedes y departamentos ----

    fun addCatalog(tenant: Connection, scope: EmpleadosScope, catalog: Catalog, name: String): UUID {
        requireManage(scope)
        return EmpleadosRepository.addCatalog(tenant, catalog, name)
    }

    fun renameCatalog(tenant: Connection, scope: EmpleadosScope, catalog: Catalog, id: UUID, name: String) {
        requireManage(scope)
        if (!EmpleadosRepository.renameCatalog(tenant, catalog, id, name)) throw EmpleadosValidationException("No se encontró la ${catalog.singular}.")
    }

    fun moveCatalog(tenant: Connection, scope: EmpleadosScope, catalog: Catalog, id: UUID, up: Boolean) {
        requireManage(scope)
        if (!EmpleadosRepository.moveCatalog(tenant, catalog, id, up)) throw EmpleadosValidationException("No se encontró la ${catalog.singular}.")
    }

    fun archiveCatalog(tenant: Connection, scope: EmpleadosScope, catalog: Catalog, id: UUID) {
        requireManage(scope)
        if (!EmpleadosRepository.archiveCatalog(tenant, catalog, id)) throw EmpleadosValidationException("No se encontró la ${catalog.singular}.")
    }

    // ---- horario habitual ----

    private fun activeSite(tenant: Connection, siteId: UUID?) {
        if (siteId == null) return
        EmpleadosRepository.getCatalog(tenant, Catalog.Sites, siteId)?.takeIf { !it.archived } ?: throw EmpleadosValidationException("Elige una sede de la lista.")
    }

    /** Anade una franja al horario habitual: maximo 4 por dia, horas distintas y sin solapes. */
    fun addWeeklySlot(tenant: Connection, access: ModuleAccessService, scope: EmpleadosScope, employeeId: UUID, weekday: Int, start: LocalTime, end: LocalTime, siteId: UUID?) {
        requireShifts(scope)
        if (weekday !in 1..7) throw EmpleadosValidationException("El día de la semana no es válido.")
        EmpleadosRepository.transaction(tenant) {
            // El candado va primero: validar y escribir sin que otra peticion cambie las franjas del empleado en medio.
            EmpleadosRepository.lockEmployee(tenant, employeeId)
            visibleEmployee(tenant, scope, employeeId)
            activeSite(tenant, siteId)
            val all = EmpleadosRepository.weekly(tenant, listOf(employeeId))
            val ofDay = all.filter { it.weekday == weekday }
            EmpleadosSchedule.slotsProblem(ofDay.map { it.start to it.end } + (start to end), EmpleadosRepository.MaxSlotsPerDay)?.let { throw EmpleadosValidationException(it) }
            // Dias vecinos (el domingo es vecino del lunes): la madrugada de una franja nocturna no puede pisar a la otra.
            fun slotsOf(day: Int) = all.filter { it.weekday == day }.map { DaySlot(it.start, it.end, it.siteId, EmpleadosSchedule.SourceHabitual) }
            EmpleadosSchedule.crossDayProblem(start, end, slotsOf((weekday + 5) % 7 + 1), slotsOf(weekday % 7 + 1))?.let { throw EmpleadosValidationException(it) }
            EmpleadosRepository.insertWeeklySlot(tenant, employeeId, weekday, start, end, siteId)
        }
        EmpleadosNotify.weeklyChanged(tenant, access.notificationsFor(EmpleadosModule.id), employeeId, scope.userId, EmpleadosRepository.today())
    }

    fun removeWeeklySlot(tenant: Connection, access: ModuleAccessService, scope: EmpleadosScope, slotId: UUID) {
        requireShifts(scope)
        val slot = EmpleadosRepository.getWeeklySlot(tenant, slotId) ?: throw EmpleadosValidationException("Franja no encontrada.")
        EmpleadosRepository.transaction(tenant) {
            EmpleadosRepository.lockEmployee(tenant, slot.employeeId)
            visibleEmployee(tenant, scope, slot.employeeId)
            if (!EmpleadosRepository.deleteWeeklySlot(tenant, slotId)) throw EmpleadosValidationException("Franja no encontrada.")
        }
        EmpleadosNotify.weeklyChanged(tenant, access.notificationsFor(EmpleadosModule.id), slot.employeeId, scope.userId, EmpleadosRepository.today())
    }

    // ---- turnos ----

    private fun activeEmployee(tenant: Connection, scope: EmpleadosScope, id: UUID): Employee {
        val employee = visibleEmployee(tenant, scope, id)
        if (employee.status != EmployeeStatus.Active) throw EmpleadosValidationException("${employee.fullName} está de baja.")
        return employee
    }

    /**
     * Solapes con la madrugada del dia anterior y con el inicio del dia siguiente, segun el horario vigente de esos dias
     * (turnos o habitual; una ausencia aprobada del dia vecino lo anula). [exceptShiftId] es el turno que se esta moviendo.
     */
    private fun crossDayCheck(tenant: Connection, employeeId: UUID, date: LocalDate, start: LocalTime, end: LocalTime, exceptShiftId: UUID?) {
        val loaded = EmpleadosRepository.scheduleData(tenant, listOf(employeeId), date.minusDays(1), date.plusDays(1))
        val data = ScheduleData(loaded.weekly, loaded.shifts.mapValues { (_, list) -> list.filter { it.id != exceptShiftId } }, loaded.absences)
        fun slotsOf(day: LocalDate) = (data.resolve(employeeId, day, null) as? DaySchedule.Working)?.slots.orEmpty()
        EmpleadosSchedule.crossDayProblem(start, end, slotsOf(date.minusDays(1)), slotsOf(date.plusDays(1)))?.let { throw EmpleadosValidationException(it) }
    }

    /** Crea un turno concreto (o un dia libre). Un turno reemplaza al horario habitual de ese dia; un dia libre borra los demas turnos del dia. */
    fun createShift(tenant: Connection, access: ModuleAccessService, scope: EmpleadosScope, input: ShiftInput): UUID {
        requireShifts(scope)
        failIfAny(input.validate())
        val id = EmpleadosRepository.transaction(tenant) {
            EmpleadosRepository.lockEmployee(tenant, input.employeeId)
            activeEmployee(tenant, scope, input.employeeId)
            activeSite(tenant, input.siteId)
            if (input.kind == ShiftKind.Off) {
                EmpleadosRepository.deleteShiftsOfDay(tenant, input.employeeId, input.date)
            } else {
                EmpleadosRepository.deleteOffShiftsOfDay(tenant, input.employeeId, input.date)
                val others = EmpleadosRepository.shiftsOfDay(tenant, input.employeeId, input.date).mapNotNull { shift -> shift.start?.let { s -> shift.end?.let { e -> s to e } } }
                val start = input.start
                val end = input.end
                if (start != null && end != null) {
                    EmpleadosSchedule.slotsProblem(others + (start to end), ShiftInput.MaxShiftsPerDay)?.let { throw EmpleadosValidationException(it) }
                    crossDayCheck(tenant, input.employeeId, input.date, start, end, null)
                }
            }
            EmpleadosRepository.insertShift(tenant, input, scope.userId)
        }
        EmpleadosNotify.shiftChanged(tenant, access.notificationsFor(EmpleadosModule.id), input.employeeId, listOf(input.date), scope.userId, EmpleadosRepository.today())
        return id
    }

    fun setDayOff(tenant: Connection, access: ModuleAccessService, scope: EmpleadosScope, employeeId: UUID, date: LocalDate): UUID =
        createShift(tenant, access, scope, ShiftInput(employeeId, date, ShiftKind.Off))

    /** Quien gestiona turnos ve el turno aunque el empleado este de baja. */
    private fun visibleShift(tenant: Connection, scope: EmpleadosScope, id: UUID): Shift {
        val shift = EmpleadosRepository.getShift(tenant, id) ?: throw EmpleadosValidationException(ShiftNotFound)
        visibleEmployee(tenant, scope, shift.employeeId)
        return shift
    }

    fun getShift(tenant: Connection, scope: EmpleadosScope, id: UUID): Shift {
        requireShifts(scope)
        return visibleShift(tenant, scope, id)
    }

    /** Mueve un turno (fecha, horas, sede o nota); lo que el cambio deja null se conserva. */
    fun moveShift(tenant: Connection, access: ModuleAccessService, scope: EmpleadosScope, id: UUID, patch: ShiftPatch) {
        requireShifts(scope)
        val employeeId = visibleShift(tenant, scope, id).employeeId
        var changed: Pair<Shift, Shift>? = null
        EmpleadosRepository.transaction(tenant) {
            // Candado del empleado primero; despues se vuelve a leer el turno (otra peticion pudo cambiarlo o borrarlo).
            EmpleadosRepository.lockEmployee(tenant, employeeId)
            val shift = visibleShift(tenant, scope, id)
            if (shift.kind == ShiftKind.Off && (patch.start != null || patch.end != null)) throw EmpleadosValidationException("Un día libre no tiene horas.")
            val updated = shift.copy(
                date = patch.date ?: shift.date,
                start = if (shift.kind == ShiftKind.Work) patch.start ?: shift.start else null,
                end = if (shift.kind == ShiftKind.Work) patch.end ?: shift.end else null,
                siteId = if (patch.setSite) patch.siteId else shift.siteId,
                note = patch.note?.trim() ?: shift.note,
            )
            val start = updated.start
            val end = updated.end
            val problems = buildList {
                if (updated.date.isBefore(ShiftInput.MinDate) || updated.date.isAfter(ShiftInput.MaxDate)) add("La fecha debe estar entre ${ShiftInput.MinDate} y ${ShiftInput.MaxDate}.")
                if (updated.note.length > ShiftInput.MaxNote) add("La nota admite hasta ${ShiftInput.MaxNote} caracteres.")
                if (updated.kind == ShiftKind.Work && (start == null || end == null || start == end)) add("El turno necesita hora de inicio y de fin distintas (HH:mm).")
            }
            failIfAny(problems)
            if (updated.siteId != shift.siteId) activeSite(tenant, updated.siteId)
            val others = EmpleadosRepository.shiftsOfDay(tenant, shift.employeeId, updated.date).filter { it.id != shift.id }
            if (updated.kind == ShiftKind.Off) {
                if (others.isNotEmpty()) throw EmpleadosValidationException("Ese día ya tiene turnos.")
            } else if (start != null && end != null) {
                val works = others.filter { it.kind == ShiftKind.Work }.mapNotNull { other -> other.start?.let { s -> other.end?.let { e -> s to e } } }
                EmpleadosSchedule.slotsProblem(works + (start to end), ShiftInput.MaxShiftsPerDay)?.let { throw EmpleadosValidationException(it) }
                crossDayCheck(tenant, shift.employeeId, updated.date, start, end, shift.id)
                if (others.any { it.kind == ShiftKind.Off }) EmpleadosRepository.deleteOffShiftsOfDay(tenant, shift.employeeId, updated.date)
            }
            if (!EmpleadosRepository.updateShift(tenant, updated)) throw EmpleadosValidationException(ShiftNotFound)
            changed = shift to updated
        }
        changed?.let { (shift, updated) ->
            EmpleadosNotify.shiftChanged(tenant, access.notificationsFor(EmpleadosModule.id), shift.employeeId, listOf(shift.date, updated.date), scope.userId, EmpleadosRepository.today())
        }
    }

    fun deleteShift(tenant: Connection, access: ModuleAccessService, scope: EmpleadosScope, id: UUID) {
        requireShifts(scope)
        val shift = visibleShift(tenant, scope, id)
        EmpleadosRepository.transaction(tenant) {
            EmpleadosRepository.lockEmployee(tenant, shift.employeeId)
            if (!EmpleadosRepository.deleteShift(tenant, id)) throw EmpleadosValidationException(ShiftNotFound)
        }
        EmpleadosNotify.shiftChanged(tenant, access.notificationsFor(EmpleadosModule.id), shift.employeeId, listOf(shift.date), scope.userId, EmpleadosRepository.today())
    }

    // ---- ausencias ----

    private fun checkNoOverlap(tenant: Connection, input: AbsenceInput) {
        if (EmpleadosRepository.hasOverlappingAbsence(tenant, input.employeeId, input.startDate, input.endDate)) {
            throw EmpleadosValidationException("Ya hay una ausencia pendiente o aprobada que toca esas fechas.")
        }
    }

    /** El empleado con ficha enlazada pide una ausencia (queda pendiente) y se avisa a quien la decide. */
    fun requestAbsence(tenant: Connection, access: ModuleAccessService, scope: EmpleadosScope, kind: AbsenceKind, from: LocalDate, to: LocalDate, reason: String): UUID {
        val employeeId = scope.myEmployeeId ?: throw EmpleadosValidationException(NoRecord)
        val input = AbsenceInput(employeeId, kind, from, to, reason)
        failIfAny(input.validate())
        var name = ""
        val id = EmpleadosRepository.transaction(tenant) {
            EmpleadosRepository.lockEmployee(tenant, employeeId)
            name = activeEmployee(tenant, scope, employeeId).fullName
            checkNoOverlap(tenant, input)
            EmpleadosRepository.insertAbsence(tenant, input, AbsenceStatus.Pending, scope.userId)
        }
        EmpleadosNotify.absenceRequested(tenant, access.notificationsFor(EmpleadosModule.id), name, input, scope.userId)
        return id
    }

    /** El empleado cancela su propia solicitud mientras sigue pendiente. */
    fun cancelAbsence(tenant: Connection, scope: EmpleadosScope, id: UUID) {
        val absence = EmpleadosRepository.getAbsence(tenant, id)?.takeIf { it.employeeId == scope.myEmployeeId } ?: throw EmpleadosValidationException(AbsenceNotFound)
        if (absence.status != AbsenceStatus.Pending) throw EmpleadosValidationException("Solo se pueden cancelar solicitudes pendientes.")
        if (!EmpleadosRepository.cancelAbsence(tenant, id)) throw EmpleadosValidationException("Esa solicitud ya fue decidida.")
    }

    /** Quien gestiona turnos registra una ausencia ya aprobada; se avisa al empleado. */
    fun registerAbsence(tenant: Connection, access: ModuleAccessService, scope: EmpleadosScope, input: AbsenceInput): UUID {
        requireShifts(scope)
        failIfAny(input.validate())
        if (!scope.mayDecideAbsenceOf(input.employeeId)) throw EmpleadosValidationException(SelfApproval)
        val id = EmpleadosRepository.transaction(tenant) {
            EmpleadosRepository.lockEmployee(tenant, input.employeeId)
            activeEmployee(tenant, scope, input.employeeId)
            checkNoOverlap(tenant, input)
            // decided_at queda puesto: la tabla exige fecha de decision en las aprobadas.
            EmpleadosRepository.insertAbsence(tenant, input, AbsenceStatus.Approved, scope.userId)
        }
        EmpleadosRepository.getAbsence(tenant, id)?.let { EmpleadosNotify.absenceDecided(tenant, access.notificationsFor(EmpleadosModule.id), it, true, "", scope.userId) }
        return id
    }

    /** Aprueba o rechaza una solicitud pendiente y avisa al empleado. */
    fun decideAbsence(tenant: Connection, access: ModuleAccessService, scope: EmpleadosScope, id: UUID, approve: Boolean, note: String) {
        requireShifts(scope)
        if (note.trim().length > AbsenceInput.MaxReason) throw EmpleadosValidationException("La nota admite hasta ${AbsenceInput.MaxReason} caracteres.")
        val employeeId = (EmpleadosRepository.getAbsence(tenant, id) ?: throw EmpleadosValidationException(AbsenceNotFound)).employeeId
        if (!scope.mayDecideAbsenceOf(employeeId)) throw EmpleadosValidationException(SelfApproval)
        var decided: Absence? = null
        EmpleadosRepository.transaction(tenant) {
            EmpleadosRepository.lockEmployee(tenant, employeeId)
            val absence = EmpleadosRepository.getAbsence(tenant, id) ?: throw EmpleadosValidationException(AbsenceNotFound)
            visibleEmployee(tenant, scope, absence.employeeId)
            if (absence.status != AbsenceStatus.Pending) throw EmpleadosValidationException("Esa solicitud ya fue decidida.")
            val status = if (approve) AbsenceStatus.Approved else AbsenceStatus.Rejected
            if (!EmpleadosRepository.decideAbsence(tenant, id, status, scope.userId, note)) throw EmpleadosValidationException("Esa solicitud ya fue decidida.")
            decided = absence
        }
        val absence = decided ?: return
        EmpleadosNotify.absenceDecided(tenant, access.notificationsFor(EmpleadosModule.id), absence, approve, note, scope.userId)
    }
}
