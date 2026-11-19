package com.ideasdeveloper.idc.modules.empleados

import com.ideasdeveloper.idc.server.modules.ModulePermissions
import java.util.UUID

/**
 * Lo que el usuario puede hacer y ver, calculado una vez por peticion. [canShifts] incluye a quien administra fichas y a
 * los duenos; [canManage] es empleados.manage o dueno. Las mismas reglas se aplican en SQL en `EmpleadosRepository`
 * (listas, busquedas, desplegables y agentes), de modo que los campos que no se pueden ver ni siquiera se leen.
 */
data class EmpleadosScope(val userId: UUID?, val myEmployeeId: UUID?, val canShifts: Boolean, val canManage: Boolean, val owner: Boolean = false) {
    /** Nadie aprueba sus propias ausencias, salvo los duenos (del negocio o del servidor). */
    fun mayDecideAbsenceOf(employeeId: UUID): Boolean = owner || employeeId != myEmployeeId

    private val shifts: Boolean get() = canShifts || canManage

    /** Campos de la ficha [employeeId] que este usuario ve: administrar ve todo; el propio empleado, su contacto y su ingreso (nunca las notas). */
    fun fields(employeeId: UUID): FieldLevel = when {
        canManage -> FieldLevel.Full
        employeeId == myEmployeeId -> FieldLevel.Own
        shifts -> FieldLevel.Contact
        else -> FieldLevel.Basic
    }

    fun fields(employee: Employee): FieldLevel = fields(employee.id)

    /** Campos que leen las listas: el propio empleado solo recibe mas datos al abrir su ficha. */
    val listFields: FieldLevel
        get() = when {
            canManage -> FieldLevel.Full
            shifts -> FieldLevel.Contact
            else -> FieldLevel.Basic
        }

    /** Quien gestiona turnos ve a todos; los demas solo a los activos y a si mismos. */
    fun seesEmployee(employeeId: UUID, status: EmployeeStatus): Boolean = shifts || status == EmployeeStatus.Active || employeeId == myEmployeeId

    fun seesEmployee(employee: Employee): Boolean = seesEmployee(employee.id, employee.status)

    /** El horario de un empleado lo ve quien gestiona turnos y el propio empleado. */
    fun seesScheduleOf(employeeId: UUID): Boolean = shifts || employeeId == myEmployeeId

    /** Pedir ausencias exige tener una ficha enlazada. */
    val canRequestAbsence: Boolean get() = myEmployeeId != null

    companion object {
        fun from(permissions: ModulePermissions, myEmployeeId: UUID?): EmpleadosScope {
            val owner = permissions.serverOwner || permissions.businessOwner
            val manage = owner || permissions.has(EmpleadosModule.Manage)
            return EmpleadosScope(
                // El dueno del servidor es un usuario central: nunca tiene ficha en la empresa.
                userId = permissions.userId?.takeUnless { permissions.serverOwner },
                myEmployeeId = myEmployeeId.takeUnless { permissions.serverOwner },
                canShifts = manage || permissions.has(EmpleadosModule.Shifts),
                canManage = manage,
                owner = owner,
            )
        }
    }
}
