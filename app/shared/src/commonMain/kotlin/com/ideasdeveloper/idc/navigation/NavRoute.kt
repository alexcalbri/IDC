// app/shared/src/commonMain/kotlin/com/ideasdeveloper/idc/navigation/NavRoute.kt
package com.ideasdeveloper.idc.navigation

/**
 * Simple navigation routes for the IdeasCore app.
 * No external navigation library needed — uses Compose state management.
 */
sealed class NavRoute {
    object Login : NavRoute()
    object Dashboard : NavRoute()
    data class Module(val moduleId: String) : NavRoute()
    // Extensiones de un modulo (pantalla nativa filtrada); se abre desde su Configuracion.
    data class ModuleExtensions(val moduleId: String) : NavRoute()
    object Settings : NavRoute()

    companion object {
        // Reconstruye una ruta tipada desde el nombre usado por Compose Navigation.
        fun fromString(route: String): NavRoute = when (route) {
            "login" -> Login
            "dashboard" -> Dashboard
            "settings" -> Settings
            else -> if (route.startsWith("module/") && route.endsWith("/extensions")) {
                ModuleExtensions(route.removePrefix("module/").removeSuffix("/extensions"))
            } else if (route.startsWith("module/")) {
                Module(route.removePrefix("module/"))
            } else {
                Login
            }
        }

        // Convierte una ruta tipada al formato string que consume NavHost.
        fun NavRoute.routeName(): String = when (this) {
            is Login -> "login"
            is Dashboard -> "dashboard"
            is Module -> "module/$moduleId"
            is ModuleExtensions -> "module/$moduleId/extensions"
            is Settings -> "settings"
        }
    }
}
