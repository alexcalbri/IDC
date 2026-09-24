// app/shared/src/commonMain/kotlin/com/ideasdeveloper/idc/navigation/NavRoute.kt
package com.ideasdeveloper.idc.navigation

/**
 * Simple navigation routes for the IdeasCore app.
 * No external navigation library needed — uses Compose state management.
 */
sealed class NavRoute {
    object Login : NavRoute()
    object Dashboard : NavRoute()
    object Empresa : NavRoute()
    object Hostpot : NavRoute()
    object Crm : NavRoute()
    object Clientes : NavRoute()
    object Memberships : NavRoute()
    object Settings : NavRoute()

    companion object {
        fun fromString(route: String): NavRoute = when (route) {
            "login" -> Login
            "dashboard" -> Dashboard
            "empresa" -> Empresa
            "hostpot" -> Hostpot
            "crm" -> Crm
            "clientes" -> Clientes
            "memberships" -> Memberships
            "settings" -> Settings
            else -> Login
        }

        fun NavRoute.routeName(): String = when (this) {
            is Login -> "login"
            is Dashboard -> "dashboard"
            is Empresa -> "empresa"
            is Hostpot -> "hostpot"
            is Crm -> "crm"
            is Clientes -> "clientes"
            is Memberships -> "memberships"
            is Settings -> "settings"
        }
    }
}
