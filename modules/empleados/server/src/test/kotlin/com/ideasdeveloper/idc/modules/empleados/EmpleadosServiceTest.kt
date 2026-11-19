package com.ideasdeveloper.idc.modules.empleados

import com.ideasdeveloper.idc.modules.empleados.EmpleadosFixtures.noDatabase
import com.ideasdeveloper.idc.modules.empleados.EmpleadosFixtures.scope
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Permisos del servicio: cada operacion comprueba el permiso antes de abrir la base de datos (la conexion de estas pruebas
 * falla ante cualquier uso, asi que un permiso que se olvide se nota).
 */
class EmpleadosServiceTest {
    private val db = noDatabase()
    private val id = UUID.randomUUID()
    private val input = EmployeeInput(fullName = "Ana Ruiz")

    private val viewer = scope(myEmployeeId = UUID.randomUUID())
    private val shifter = scope(shifts = true)

    private fun denied(block: () -> Unit) {
        val failure = assertFailsWith<EmpleadosValidationException> { block() }
        assertTrue(failure.message.orEmpty().startsWith("No tienes permiso"), failure.message)
    }

    @Test
    fun onlyManagersWriteRecords() {
        listOf(viewer, shifter).forEach { scope ->
            denied { EmpleadosService.createEmployee(db, scope, input) }
            denied { EmpleadosService.updateEmployee(db, scope, id, input) }
            denied { EmpleadosService.setActive(db, scope, id, false) }
        }
    }

    @Test
    fun onlyManagersEditSitesAndDepartments() {
        listOf(viewer, shifter).forEach { scope ->
            Catalog.entries.forEach { catalog ->
                denied { EmpleadosService.addCatalog(db, scope, catalog, "Nueva") }
                denied { EmpleadosService.renameCatalog(db, scope, catalog, id, "Otra") }
                denied { EmpleadosService.moveCatalog(db, scope, catalog, id, true) }
                denied { EmpleadosService.archiveCatalog(db, scope, catalog, id) }
            }
        }
    }

    @Test
    fun aViewerCannotReadAShift() {
        denied { EmpleadosService.getShift(db, viewer, id) }
    }

    @Test
    fun invalidRecordsAreRejectedBeforeTouchingTheDatabase() {
        val manager = scope(manage = true)
        val failure = assertFailsWith<EmpleadosValidationException> {
            EmpleadosService.createEmployee(db, manager, EmployeeInput(fullName = "", phone = "abc"))
        }
        assertTrue("nombre" in failure.message.orEmpty().lowercase())
    }
}
