package com.ideasdeveloper.idc.server.modules

import com.ideasdeveloper.idc.ModuleVersion

/** Versiones minimas de Core y app que un modulo necesita; nulo significa sin minimo. */
data class ModuleRequirements(
    val minCoreVersion: String? = null,
    val minAppVersion: String? = null,
)

/** Un minimo no cumplido: [code] es MODULE_NEEDS_CORE o MODULE_NEEDS_APP. */
data class ModuleRequirementProblem(val code: String, val message: String)

/** Regla de versiones minimas, sin estado para poder probarla sola. */
object ModuleRequirementGate {
    const val NeedsCore = "MODULE_NEEDS_CORE"
    const val NeedsApp = "MODULE_NEEDS_APP"

    /** Problema si [coreVersion] es menor que [minCoreVersion]; sin minimo o con minimo vacio no hay problema. */
    fun coreProblem(minCoreVersion: String?, coreVersion: String): ModuleRequirementProblem? {
        val minimum = minCoreVersion?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        if (ModuleVersion.compare(coreVersion, minimum) >= 0) return null
        return ModuleRequirementProblem(
            NeedsCore,
            "Este modulo necesita el Core $minimum o superior; el servidor tiene $coreVersion. Actualiza el servidor primero.",
        )
    }

    /** Problema si [appVersion] es menor que [minAppVersion]; sin version de app (app vieja o MCP) no se bloquea. */
    fun appProblem(minAppVersion: String?, appVersion: String?): ModuleRequirementProblem? {
        val minimum = minAppVersion?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val current = appVersion?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        if (ModuleVersion.compare(current, minimum) >= 0) return null
        return ModuleRequirementProblem(
            NeedsApp,
            "Este modulo necesita la app $minimum o superior; tu app es $current. Actualizala antes de instalar.",
        )
    }

    /** Aviso cuando hay minimo de app pero quien instala no manda su version. */
    fun missingAppVersionWarning(minAppVersion: String?, appVersion: String?): String? =
        if (!minAppVersion.isNullOrBlank() && appVersion.isNullOrBlank()) {
            "No se pudo comprobar la version de la app; este modulo necesita la app $minAppVersion o superior."
        } else {
            null
        }
}
