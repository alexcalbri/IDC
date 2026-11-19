package com.ideasdeveloper.idc.modules.mantenimiento

import com.ideasdeveloper.idc.server.extensions.ServerExtension
import com.ideasdeveloper.idc.server.modules.ModuleAccessService
import com.ideasdeveloper.idc.server.modules.ModuleDefinition
import com.ideasdeveloper.idc.server.modules.ModuleJob
import com.ideasdeveloper.idc.server.modules.ModuleMcp
import com.ideasdeveloper.idc.server.modules.ModulePermissionDefinition
import com.ideasdeveloper.idc.server.modules.ServerModule
import com.ideasdeveloper.idc.server.modules.ServerModuleProvider
import com.ideasdeveloper.idc.server.modules.SettingsTable
import com.ideasdeveloper.idc.ui.ModuleUiEntry
import io.ktor.server.routing.Route

object MantenimientoServerModule : ServerModule {
    override val migrationPaths = listOf(
        "modules/mantenimiento/migrations/V001__create_mantenimiento.sql",
    )

    // El folio de las órdenes usa una columna de identidad: el permiso de la tabla incluye su secuencia.
    override val runtimeTables = listOf(
        "mantenimiento_properties",
        "mantenimiento_assets",
        "mantenimiento_plans",
        "mantenimiento_plan_checklist",
        "mantenimiento_orders",
        "mantenimiento_order_checklist",
        "mantenimiento_order_notes",
        "mantenimiento_attachments",
        "mantenimiento_reminders",
        "mantenimiento_settings",
    )

    /**
     * Una empresa duplicada conserva propiedades, activos, planes (sin quién los creó, su responsable ni su clave de importación) con su
     * lista de comprobación y los ajustes (sin el tablero de Tareas ni el usuario que publica). Las órdenes y lo que cuelga de ellas
     * se quedan atrás: los usuarios no se copian.
     */
    override val duplicatedSettings = listOf(
        SettingsTable("mantenimiento_properties"),
        SettingsTable("mantenimiento_assets"),
        SettingsTable("mantenimiento_plans", reset = mapOf("created_by" to null, "default_assignee" to null, "import_key" to null, "last_error_at" to null)),
        SettingsTable("mantenimiento_plan_checklist", reset = mapOf("import_key" to null)),
        SettingsTable("mantenimiento_settings", reset = mapOf("tareas_board_id" to null, "tareas_publisher_id" to null)),
    )

    override val jobs: List<ModuleJob> get() = MantenimientoJobs.jobs

    // Las extensiones de importación del módulo (Monday...) ofrecen sus fuentes, pantallas y herramientas por aquí.
    override fun linkExtensions(extensions: List<ServerExtension>) {
        MantenimientoImportSources.link(extensions)
    }

    override val mcp: ModuleMcp
        get() = MantenimientoMcpTools.mcp

    override val definition = ModuleDefinition(
        id = MantenimientoModule.id,
        displayName = MantenimientoModule.displayName,
        description = "Órdenes de trabajo de mantenimiento por propiedad y activo: avería reportada por cualquiera, planes preventivos que generan órdenes, proyectos con aprobación, lista de comprobación, notas, adjuntos, costos, historial por activo y avisos. Se integra con Tareas y Compras si están activos e importa desde Monday.",
        version = "0.1.0",
        minCoreVersion = "0.8.0",
        minAppVersion = "0.7.0",
        permissions = listOf(
            ModulePermissionDefinition(
                MantenimientoModule.View,
                "Ver mantenimiento",
                "Permite abrir el módulo, reportar averías, solicitar proyectos, ver las órdenes que reportó o que tiene asignadas, y ver activos y planes (solo lectura).",
            ),
            ModulePermissionDefinition(
                MantenimientoModule.Execute,
                "Ejecutar órdenes",
                "Permite ver todas las órdenes y trabajar las asignadas a la persona o sin responsable: tomarla, cambiar su estado de trabajo, marcar la lista, anotar, adjuntar archivos, registrar el costo real y la resolución.",
            ),
            ModulePermissionDefinition(
                MantenimientoModule.Manage,
                "Gestionar mantenimiento",
                "Permite todo en cualquier orden: crear, editar, asignar y cancelar, cotizar, aprobar o rechazar proyectos, y administrar planes, activos, propiedades y ajustes.",
            ),
            ModulePermissionDefinition(
                MantenimientoModule.Import,
                "Importar datos",
                "Permite importar planes y órdenes desde otros sistemas (por ejemplo Monday) con las extensiones de importación instaladas.",
            ),
        ),
        supportsExtensions = true,
        extensionPermissions = listOf(MantenimientoModule.ImportWrite),
        ui = ModuleUiEntry(type = "native", entry = MantenimientoModule.UiRoute, home = "upcoming"),
    )

    override fun routes(route: Route, access: ModuleAccessService) {
        MantenimientoRoutes.install(route, access)
    }
}

class MantenimientoServerModuleProvider : ServerModuleProvider {
    override fun create(): ServerModule = MantenimientoServerModule
}
