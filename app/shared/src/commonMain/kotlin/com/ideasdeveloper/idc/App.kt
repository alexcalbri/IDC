package com.ideasdeveloper.idc
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.ideasdeveloper.idc.app.core.company.CompanyIdentityStore
import com.ideasdeveloper.idc.app.core.session.SessionStore
import com.ideasdeveloper.idc.app.features.auth.ui.LoginScreen
import com.ideasdeveloper.idc.app.features.dashboard.ui.DashboardScreen
import com.ideasdeveloper.idc.navigation.NavRoute.Companion.routeName

@Composable
fun App(initialServerUrl: String = "") {
    MaterialTheme {
        val navController = rememberNavController()
        val restoredSession = remember { SessionStore.restore() }
        val session by SessionStore.session.collectAsState()
        val currentSession = session ?: restoredSession
        remember { CompanyIdentityStore.restore() }

        NavHost(
            navController = navController,
            startDestination = if (restoredSession == null) "login" else "dashboard"
        ) {
            composable("login") {
                LoginScreen(
                    initialServerUrl = initialServerUrl,
                    onLoginSuccess = {
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
                DashboardScreen(
                    session = currentSession,
                    onNavigateTo = { route -> navController.navigate(route.routeName()) },
                )
            }
            composable("empresa") {
                ModulePlaceholderScreen("Empresa")
            }
            composable("hostpot") {
                ModulePlaceholderScreen("Hostpot")
            }
            composable("crm") {
                ModulePlaceholderScreen("CRM")
            }
            composable("settings") {
                ModulePlaceholderScreen("Ajustes")
            }
        }
    }
}

@Composable
private fun ModulePlaceholderScreen(name: String) {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        Text("Modulo $name pendiente")
    }
}
