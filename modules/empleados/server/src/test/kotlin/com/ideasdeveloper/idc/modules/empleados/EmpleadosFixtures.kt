package com.ideasdeveloper.idc.modules.empleados

import java.lang.reflect.Proxy
import java.sql.Connection
import java.time.LocalDate
import java.time.LocalTime
import java.util.UUID

/** Constructores compartidos por las pruebas puras de Empleados (sin base de datos). */
object EmpleadosFixtures {
    fun employee(
        id: UUID = UUID.randomUUID(),
        name: String = "Ana Ruiz",
        status: EmployeeStatus = EmployeeStatus.Active,
        level: FieldLevel = FieldLevel.Basic,
        siteId: UUID? = null,
        site: String? = null,
        jobTitle: String = "Técnico",
    ) = Employee(
        id = id,
        fullName = name,
        jobTitle = jobTitle,
        departmentId = null,
        department = null,
        siteId = siteId,
        site = site,
        status = status,
        level = level,
        phone = if (level.contact) "555-0100" else null,
        email = if (level.contact) "ana@example.com" else null,
        availability = if (level.contact) "No puede los domingos" else null,
        hireDate = if (level.hireDate) LocalDate.of(2024, 3, 1) else null,
        endedOn = null,
        notes = if (level.internal) "Nota privada" else null,
        userId = null,
    )

    fun slot(employeeId: UUID, weekday: Int, start: String, end: String, siteId: UUID? = null) =
        WeeklySlot(UUID.randomUUID(), employeeId, weekday, LocalTime.parse(start), LocalTime.parse(end), siteId)

    fun work(employeeId: UUID, date: LocalDate, start: String, end: String, siteId: UUID? = null) =
        Shift(UUID.randomUUID(), employeeId, date, ShiftKind.Work, LocalTime.parse(start), LocalTime.parse(end), siteId)

    fun off(employeeId: UUID, date: LocalDate) = Shift(UUID.randomUUID(), employeeId, date, ShiftKind.Off, null, null, null)

    fun absence(
        employeeId: UUID,
        from: LocalDate,
        to: LocalDate,
        status: AbsenceStatus = AbsenceStatus.Approved,
        kind: AbsenceKind = AbsenceKind.Vacation,
    ) = Absence(UUID.randomUUID(), employeeId, kind, from, to, status)

    fun scope(
        userId: UUID? = UUID.randomUUID(),
        myEmployeeId: UUID? = null,
        shifts: Boolean = false,
        manage: Boolean = false,
        owner: Boolean = false,
    ) = EmpleadosScope(userId, myEmployeeId, canShifts = shifts || manage || owner, canManage = manage || owner, owner = owner)

    /** Una conexion que falla ante cualquier uso: las reglas que validan antes de tocar la base de datos no deben llegar a ella. */
    fun noDatabase(): Connection = Proxy.newProxyInstance(EmpleadosFixtures::class.java.classLoader, arrayOf(Connection::class.java)) { _, method, _ ->
        throw AssertionError("No debe usar la base de datos: ${method.name}")
    } as Connection
}
