package com.ideasdeveloper.idc.modules.membresia

import com.ideasdeveloper.idc.server.extensions.ServerExtension
import com.ideasdeveloper.idc.server.modules.CustomerTimeline
import com.ideasdeveloper.idc.server.modules.ModuleAccessService
import com.ideasdeveloper.idc.server.modules.ModuleDefinition
import com.ideasdeveloper.idc.server.modules.ModuleMcp
import com.ideasdeveloper.idc.server.modules.ModulePermissionDefinition
import com.ideasdeveloper.idc.server.modules.ServerModule
import com.ideasdeveloper.idc.server.modules.ServerModuleProvider
import com.ideasdeveloper.idc.server.modules.SettingsTable
import com.ideasdeveloper.idc.ui.ModuleUiEntry
import io.ktor.server.routing.Route

object MembresiaServerModule : ServerModule {
    override val migrationPaths = listOf(
        "modules/membresia/migrations/V001__create_membresia.sql",
        "modules/membresia/migrations/V002__import_keys.sql",
    )

    // La vista es parte del contrato con otros modulos (eventos): el rol de ejecucion tambien la lee.
    override val runtimeTables = listOf(
        "membresia_settings",
        "membresia_plans",
        "membresia_memberships",
        "membresia_visits",
        "membresia_active_members",
    )

    // Una empresa duplicada recibe los mismos planes y ajustes; miembros, membresias y visitas se quedan atras.
    override val duplicatedSettings = listOf(
        SettingsTable("membresia_settings"),
        SettingsTable("membresia_plans"),
    )

    override val definition = ModuleDefinition(
        id = MembresiaModule.Id,
        displayName = MembresiaModule.DisplayName,
        description = "Recepción, planes, membresías, visitas e invitados de un espacio de coworking o club, con avisos de vencimiento y WiFi mientras la membresía está vigente.",
        version = MembresiaModule.Version,
        permissions = listOf(
            ModulePermissionDefinition(MembresiaModule.View, "Ver miembros", "Permite usar la recepción, buscar miembros y ver su estado, las listas y los niveles."),
            ModulePermissionDefinition(MembresiaModule.Visits, "Registrar visitas", "Permite registrar la visita de un miembro y los pases de invitado, incluido registrar igual cuando el plan está agotado o vencido."),
            ModulePermissionDefinition(MembresiaModule.Manage, "Gestionar membresías", "Permite crear, renovar, editar y cancelar membresías, y ver los ingresos."),
            ModulePermissionDefinition(MembresiaModule.Plans, "Administrar planes y ajustes", "Permite crear y editar los planes de membresía y cambiar la moneda, los días de aviso y las fuentes."),
            ModulePermissionDefinition(MembresiaModule.Import, "Importar datos", "Permite ver y usar la pantalla Importar para traer miembros, membresías y visitas desde otra herramienta."),
        ),
        ui = ModuleUiEntry(type = "native", entry = MembresiaModule.UiRoute, home = "recepcion"),
        minCoreVersion = "0.8.0",
        supportsExtensions = true,
        extensionPermissions = listOf(MembresiaModule.ImportWrite),
    )

    // Las extensiones que importan datos (Monday...) implementan MembresiaImportSource.
    override fun linkExtensions(extensions: List<ServerExtension>) = MembresiaImportSources.link(extensions)

    override val jobs = listOf(MembresiaJobs.daily, MembresiaJobs.imports)

    override val mcp: ModuleMcp
        get() = MembresiaMcpTools.mcp

    override val customerTimeline: CustomerTimeline
        get() = MembresiaCustomerTimeline.timeline

    override fun routes(route: Route, access: ModuleAccessService) = MembresiaRoutes.install(route, access)
}

class MembresiaServerModuleProvider : ServerModuleProvider {
    override fun create(): ServerModule = MembresiaServerModule
}
