package com.ideasdeveloper.idc.modules.empresa

import com.ideasdeveloper.idc.server.modules.ModuleDefinition
import com.ideasdeveloper.idc.server.modules.ServerModule
import com.ideasdeveloper.idc.server.modules.ServerModuleProvider

/**
 * Base module that is always installed, like Clientes. Company administration routes and screens
 * belong to Core (company api and the app's Empresa screens), so this module declares no routes;
 * it exists so the server lists Empresa as an installed base module.
 */
object EmpresaServerModule : ServerModule {
    override val definition = ModuleDefinition(
        id = EmpresaModule.id,
        displayName = EmpresaModule.displayName,
        description = "Administracion de empresas, identidad y configuracion de cada empresa.",
        version = "0.1.0",
        locked = true,
    )
}

class EmpresaServerModuleProvider : ServerModuleProvider {
    override fun create(): ServerModule = EmpresaServerModule
}
