// app/src/commonMain/kotlin/com/ideasdeveloper/app/navigation/NavRoute.kt
package com.ideasdeveloper.app.navigation

/**
 * Simple navigation routes for the IdeasCore app.
 * No external navigation library needed — uses Compose state management.
 */
sealed class NavRoute {
    object Login : NavRoute()
    object Dashboard : NavRoute()
    object Clientes : NavRoute()
    object Memberships : NavRoute()
    object Settings : NavRoute()

    companion object {
        fun fromString(route: String): NavRoute = when (route) {
            "login" -> Login
            "dashboard" -> Dashboard
            "clientes" -> Clientes
            "memberships" -> Memberships
            "settings" -> Settings
            else -> Login
        }

        fun NavRoute.routeName(): String = when (this) {
            is Login -> "login"
            is Dashboard -> "dashboard"
            is Clientes -> "clientes"
            is Memberships -> "memberships"
            is Settings -> "settings"
        }
    }
}
