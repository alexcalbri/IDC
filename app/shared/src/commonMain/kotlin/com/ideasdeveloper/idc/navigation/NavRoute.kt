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
    object Settings : NavRoute()

    companion object {
        fun fromString(route: String): NavRoute = when (route) {
            "login" -> Login
            "dashboard" -> Dashboard
            "settings" -> Settings
            else -> if (route.startsWith("module/")) {
                Module(route.removePrefix("module/"))
            } else {
                Login
            }
        }

        fun NavRoute.routeName(): String = when (this) {
            is Login -> "login"
            is Dashboard -> "dashboard"
            is Module -> "module/$moduleId"
            is Settings -> "settings"
        }
    }
}
