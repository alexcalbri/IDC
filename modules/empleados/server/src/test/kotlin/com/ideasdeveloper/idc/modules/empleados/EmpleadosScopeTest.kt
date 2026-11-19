package com.ideasdeveloper.idc.modules.empleados

import com.ideasdeveloper.idc.modules.empleados.EmpleadosFixtures.employee
import com.ideasdeveloper.idc.modules.empleados.EmpleadosFixtures.scope
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Reglas de visibilidad de fichas y horarios (`EmpleadosScope`), para cada tipo de usuario. */
class EmpleadosScopeTest {
    private val me = UUID.randomUUID()
    private val other = UUID.randomUUID()

    @Test
    fun viewerSeesOnlyTheBasicFieldsOfOthers() {
        assertEquals(FieldLevel.Basic, scope().fields(other))
        assertEquals(FieldLevel.Basic, scope(myEmployeeId = me).fields(other))
    }

    @Test
    fun shiftManagerSeesContactButNotNotesNorHireDate() {
        val level = scope(shifts = true).fields(other)
        assertEquals(FieldLevel.Contact, level)
        assertTrue(level.contact)
        assertFalse(level.hireDate)
        assertFalse(level.internal)
    }

    @Test
    fun managerSeesEverything() {
        val level = scope(manage = true).fields(other)
        assertEquals(FieldLevel.Full, level)
        assertTrue(level.contact && level.hireDate && level.internal)
    }

    @Test
    fun ownRecordHasContactAndHireDateButNeverNotes() {
        val level = scope(myEmployeeId = me).fields(me)
        assertEquals(FieldLevel.Own, level)
        assertTrue(level.contact && level.hireDate)
        assertFalse(level.internal)
        // Aunque gestione turnos, sus notas siguen siendo de quien administra fichas.
        assertFalse(scope(myEmployeeId = me, shifts = true).fields(me).internal)
        assertEquals(FieldLevel.Own, scope(myEmployeeId = me, shifts = true).fields(me))
    }

    @Test
    fun listsNeverReadMoreThanTheUserMaySeeOfOthers() {
        assertEquals(FieldLevel.Basic, scope(myEmployeeId = me).listFields)
        assertEquals(FieldLevel.Contact, scope(shifts = true).listFields)
        assertEquals(FieldLevel.Full, scope(manage = true).listFields)
    }

    @Test
    fun ficheOfEmployeeObjectFollowsItsId() {
        val own = employee(id = me)
        assertEquals(FieldLevel.Own, scope(myEmployeeId = me).fields(own))
        assertEquals(FieldLevel.Basic, scope(myEmployeeId = me).fields(employee(id = other)))
    }

    @Test
    fun viewerSeesActiveEmployeesAndOwnRecordEvenIfInactive() {
        val viewer = scope(myEmployeeId = me)
        assertTrue(viewer.seesEmployee(other, EmployeeStatus.Active))
        assertFalse(viewer.seesEmployee(other, EmployeeStatus.Inactive))
        assertTrue(viewer.seesEmployee(me, EmployeeStatus.Inactive))
    }

    @Test
    fun shiftManagersAndManagersSeeInactiveRecords() {
        assertTrue(scope(shifts = true).seesEmployee(employee(status = EmployeeStatus.Inactive)))
        assertTrue(scope(manage = true).seesEmployee(employee(status = EmployeeStatus.Inactive)))
    }

    @Test
    fun scheduleBelongsToShiftManagersAndTheEmployee() {
        assertFalse(scope().seesScheduleOf(other))
        assertFalse(scope(myEmployeeId = me).seesScheduleOf(other))
        assertTrue(scope(myEmployeeId = me).seesScheduleOf(me))
        assertTrue(scope(shifts = true).seesScheduleOf(other))
        assertTrue(scope(manage = true).seesScheduleOf(other))
    }

    @Test
    fun requestingAbsencesNeedsALinkedRecord() {
        assertFalse(scope().canRequestAbsence)
        assertTrue(scope(myEmployeeId = me).canRequestAbsence)
    }

    @Test
    fun managerImpliesShifts() {
        val manager = scope(manage = true)
        assertTrue(manager.canShifts && manager.canManage)
        assertFalse(scope(shifts = true).canManage)
    }

    @Test
    fun employeeNotReadOfLevelIsNullNotEmpty() {
        // Lo que la consulta no lee llega como null, no como texto vacio: la pantalla no puede mostrar nada que no se leyo.
        val basic = employee(level = FieldLevel.Basic)
        assertNull(basic.phone)
        assertNull(basic.email)
        assertNull(basic.availability)
        assertNull(basic.hireDate)
        assertNull(basic.notes)
        assertNull(employee(level = FieldLevel.Contact).hireDate)
        assertNull(employee(level = FieldLevel.Own).notes)
    }
}
