package com.ideasdeveloper.idc

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
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
import com.ideasdeveloper.idc.app.features.dashboard.ui.DashboardScreen
import com.ideasdeveloper.idc.app.features.modules.ui.ServerDrivenModuleScreen
import com.ideasdeveloper.idc.app.features.shell.ui.AuthenticatedTopBar
import com.ideasdeveloper.idc.app.features.shell.ui.toComposeColor
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
                if (moduleId == "empresa") {
                    if (currentSession?.role == "server_owner") {
                        CompanyProvisioningScreen(
                            serverUrl = clientConfiguration?.serverUrl,
                            session = currentSession,
                            onSettings = { navController.navigate(NavRoute.Settings.routeName()) },
                            onLogout = ::logout,
                            onMinimize = ::openDashboard,
                        )
                    } else {
                        CompanyManagementScreen(
                            serverUrl = clientConfiguration?.serverUrl,
                            session = currentSession,
                            onSettings = { navController.navigate(NavRoute.Settings.routeName()) },
                            onLogout = ::logout,
                            onMinimize = ::openDashboard,
                        )
                    }
                } else {
                    // Los demas modulos se renderizan desde metadata recibida del servidor.
                    ServerDrivenModuleScreen(
                        moduleId = moduleId,
                        serverUrl = clientConfiguration?.serverUrl,
                        fallbackTitle = moduleName,
                        onSettings = { navController.navigate(NavRoute.Settings.routeName()) },
                        onLogout = ::logout,
                        onMinimize = ::openDashboard,
                    )
                }
            }
            composable("settings") {
                // Mantiene disponible la ruta de ajustes mientras se implementa la pantalla final.
                ModulePlaceholderScreen(
                    moduleName = "Ajustes",
                    viewName = "Preferencias",
                    body = "Ajustes pendientes",
                    onSettings = { navController.navigate(NavRoute.Settings.routeName()) },
                    onLogout = ::logout,
                    onMinimize = ::openDashboard,
                )
            }
        }
    }
}

// Convierte ids de modulo en titulos legibles para las pantallas internas.
private fun String.toModuleTitle(): String = when (this) {
    "empresa" -> "Empresa"
    "clientes" -> "Clientes"
    "hostpot" -> "Hostpot"
    "crm" -> "CRM"
    else -> replace("-", " ").replace("_", " ")
}

@Composable
// Renderiza una pantalla temporal con la barra autenticada y una accion para volver al dashboard.
private fun ModulePlaceholderScreen(
    moduleName: String,
    viewName: String?,
    body: String,
    onSettings: () -> Unit,
    onLogout: () -> Unit,
    onMinimize: () -> Unit,
) {
    val companyIdentity = remember { CompanyIdentityStore.current() }
    val primaryColor = remember(companyIdentity.primaryColor) {
        companyIdentity.primaryColor.toComposeColor(Color(0xFF667EEA))
    }
    val secondaryColor = remember(companyIdentity.secondaryColor) {
        companyIdentity.secondaryColor.toComposeColor(Color(0xFF764BA2))
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                brush = Brush.horizontalGradient(
                    colors = listOf(primaryColor, secondaryColor)
                )
            )
    ) {
        AuthenticatedTopBar(
            title = moduleName,
            subtitle = viewName,
            primaryColor = primaryColor,
            onSettings = onSettings,
            onLogout = onLogout,
            modifier = Modifier.align(Alignment.TopCenter),
        )

        Column(
            modifier = Modifier.align(Alignment.Center),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = body,
                color = Color.White,
                fontWeight = FontWeight.Bold,
            )
            Spacer(modifier = Modifier.height(18.dp))
            Button(
                onClick = onMinimize,
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color.White,
                    contentColor = primaryColor,
                ),
                modifier = Modifier
                    .width(160.dp)
                    .padding(horizontal = 8.dp),
            ) {
                Text("Minimizar")
            }
        }
    }
}
