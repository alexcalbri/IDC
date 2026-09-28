package com.ideasdeveloper.idc

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.savedstate.read
import com.ideasdeveloper.idc.app.core.company.CompanyIdentityStore
import com.ideasdeveloper.idc.app.core.config.ClientConfigurationStore
import com.ideasdeveloper.idc.app.core.session.SessionStore
import com.ideasdeveloper.idc.app.features.auth.ui.LoginScreen
import com.ideasdeveloper.idc.app.features.company.ui.CompanyManagementScreen
import com.ideasdeveloper.idc.app.features.company.ui.CompanyProvisioningScreen
import com.ideasdeveloper.idc.app.features.company.ui.ServerModuleManagementScreen
import com.ideasdeveloper.idc.app.features.dashboard.ui.DashboardScreen
import com.ideasdeveloper.idc.app.features.modules.ui.ServerDrivenModuleScreen
import com.ideasdeveloper.idc.app.features.settings.ui.SettingsScreen
import com.ideasdeveloper.idc.navigation.NavRoute
import com.ideasdeveloper.idc.navigation.NavRoute.Companion.routeName

@Composable
// Orquesta la navegacion principal segun exista o no una sesion restaurada.
fun App(initialServerUrl: String = "") {
    MaterialTheme {
        // Restaura la sesion, identidad visual y configuracion local antes de construir rutas.
        val navController = rememberNavController()
        val restoredSession = remember { SessionStore.restore() }
        val session by SessionStore.session.collectAsState()
        val currentSession = session ?: restoredSession
        remember { CompanyIdentityStore.restore() }
        var clientConfiguration by remember { mutableStateOf(ClientConfigurationStore.load()) }

        // Regresa al dashboard limpiando pantallas duplicadas de la pila.
        fun openDashboard() {
            navController.navigate("dashboard") {
                popUpTo("dashboard") {
                    inclusive = true
                }
                launchSingleTop = true
            }
        }

        // Cierra la sesion local y deja al usuario en la pantalla de login.
        fun logout() {
            SessionStore.clear()
            navController.navigate("login") {
                popUpTo("dashboard") {
                    inclusive = true
                }
                launchSingleTop = true
            }
        }

        // Define las rutas activas de la app y decide la pantalla inicial.
        NavHost(
            navController = navController,
            startDestination = if (restoredSession == null) "login" else "dashboard"
        ) {
            composable("login") {
                LoginScreen(
                    initialServerUrl = initialServerUrl,
                    onLoginSuccess = {
                        // Recarga la configuracion guardada por el login antes de entrar al dashboard.
                        clientConfiguration = ClientConfigurationStore.load()
                        navController.navigate("dashboard") {
                            popUpTo("login") {
                                inclusive = true
                            }
                            launchSingleTop = true
                        }
                    },
                )
            }
            composable("dashboard") {
                // Muestra el punto de entrada autenticado con los modulos disponibles.
                DashboardScreen(
                    session = currentSession,
                    serverUrl = clientConfiguration?.serverUrl,
                    onNavigateTo = { route -> navController.navigate(route.routeName()) },
                    onLogout = ::logout,
                )
            }
            composable("module/{moduleId}") { backStackEntry ->
                val moduleId = backStackEntry.arguments?.read {
                    getStringOrNull("moduleId")
                }.orEmpty()
                val moduleName = moduleId.toModuleTitle()
                // La administracion de Empresa usa pantallas propias para dueno del servidor y dueno del negocio.
                if (moduleId == "server-modules" && currentSession?.role == "server_owner") {
                    ServerModuleManagementScreen(
                        serverUrl = clientConfiguration?.serverUrl,
                        session = currentSession,
                        onSettings = { navController.navigate(NavRoute.Settings.routeName()) },
                        onLogout = ::logout,
                        onReturnToDashboard = ::openDashboard,
                    )
                } else if (moduleId == "empresa") {
                    if (currentSession?.role == "server_owner") {
                        CompanyProvisioningScreen(
                            serverUrl = clientConfiguration?.serverUrl,
                            session = currentSession,
                            onSettings = { navController.navigate(NavRoute.Settings.routeName()) },
                            onLogout = ::logout,
                            onReturnToDashboard = ::openDashboard,
                        )
                    } else {
                        CompanyManagementScreen(
                            serverUrl = clientConfiguration?.serverUrl,
                            session = currentSession,
                            onSettings = { navController.navigate(NavRoute.Settings.routeName()) },
                            onLogout = ::logout,
                            onReturnToDashboard = ::openDashboard,
                        )
                    }
                } else {
                    // Los demas modulos se renderizan desde metadata recibida del servidor.
                    ServerDrivenModuleScreen(
                        moduleId = moduleId,
                        serverUrl = clientConfiguration?.serverUrl,
                        session = currentSession,
                        fallbackTitle = moduleName,
                        onSettings = { navController.navigate(NavRoute.Settings.routeName()) },
                        onLogout = ::logout,
                        onReturnToDashboard = ::openDashboard,
                    )
                }
            }
            composable("settings") {
                SettingsScreen(
                    configuration = clientConfiguration,
                    session = currentSession,
                    onLogout = ::logout,
                    onReturnToDashboard = ::openDashboard,
                    onClearLocalConfiguration = {
                        ClientConfigurationStore.clear()
                        CompanyIdentityStore.clear()
                        clientConfiguration = null
                        logout()
                    },
                )
            }
        }
    }
}

// Convierte ids de modulo en titulos legibles para las pantallas internas.
private fun String.toModuleTitle(): String = when (this) {
    "empresa" -> "Empresa"
    "server-modules" -> "Modulos del servidor"
    "clientes" -> "Clientes"
    else -> replace("-", " ")
        .replace("_", " ")
        .split(" ")
        .filter { it.isNotBlank() }
        .joinToString(" ") { segment ->
            segment.replaceFirstChar { char -> char.uppercase() }
        }
}
