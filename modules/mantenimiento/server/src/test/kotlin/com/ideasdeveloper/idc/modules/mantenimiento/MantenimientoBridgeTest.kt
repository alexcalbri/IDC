package com.ideasdeveloper.idc.modules.mantenimiento

import com.ideasdeveloper.idc.modules.mantenimiento.MantenimientoFixtures.permissions
import com.ideasdeveloper.idc.server.modules.McpTool
import com.ideasdeveloper.idc.server.modules.RegisteredMcpTool
import kotlinx.serialization.json.JsonObject
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** La decisión de acceso del puente (`MantenimientoBridge.denial`): sin acceso a ModuleAccessService se prueba solo la parte pura. */
class MantenimientoBridgeTest {
    private val user = UUID.randomUUID()

    private fun tool(permission: String) = RegisteredMcpTool(
        "tareas",
        "Tareas",
        McpTool(name = "create_task", title = "Crear", description = "Crea", permission = permission) { JsonObject(emptyMap()) },
    )

    @Test
    fun missingToolMeansTheModuleIsNotInstalled() {
        val denial = MantenimientoBridge.denial(null, permissions(user, "tareas.manage"))
        assertEquals("módulo no instalado", denial?.reason)
    }

    @Test
    fun missingPermissionsMeansTheModuleIsNotActiveForTheUser() {
        val denial = MantenimientoBridge.denial(tool("tareas.manage"), null)
        assertEquals("módulo no activo o usuario sin acceso", denial?.reason)
    }

    @Test
    fun aUserWithoutThePermissionOfTheToolIsDenied() {
        val denial = MantenimientoBridge.denial(tool("tareas.manage"), permissions(user, "tareas.view"))
        assertEquals("sin permiso", denial?.reason)
    }

    @Test
    fun liveGrantedPermissionOrOwnershipAllowsTheCall() {
        assertNull(MantenimientoBridge.denial(tool("tareas.manage"), permissions(user, "tareas.manage")))
        assertNull(MantenimientoBridge.denial(tool("tareas.manage"), permissions(user, businessOwner = true)))
    }

    @Test
    fun theTareasPublisherMustStillManageMaintenance() {
        assertFalse(MantenimientoTareasSync.canPublish(null))
        assertFalse(MantenimientoTareasSync.canPublish(permissions(user, MantenimientoModule.View, MantenimientoModule.Execute)))
        assertTrue(MantenimientoTareasSync.canPublish(permissions(user, MantenimientoModule.Manage)))
        assertTrue(MantenimientoTareasSync.canPublish(permissions(user, businessOwner = true)))
    }

    @Test
    fun qualifiedNamesUsedByTheIntegrationsMatchTheModulePlusTool() {
        assertEquals("tareas_create_task", tool("x").qualifiedName)
        assertEquals("tareas_create_task", MantenimientoTareasSync.CreateTaskTool)
        assertEquals("tareas_update_task", MantenimientoTareasSync.UpdateTaskTool)
        assertEquals("tareas_list_boards", MantenimientoTareasSync.ListBoardsTool)
        assertEquals("compras_create_request", MantenimientoPurchases.CreateRequestTool)
    }
}
