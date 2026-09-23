package com.ideasdeveloper.idc
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.ideasdeveloper.idc.app.features.auth.ui.LoginScreen
import com.ideasdeveloper.idc.app.features.dashboard.ui.DashboardScreen
@Composable
fun App(initialServerUrl: String = "") {
    MaterialTheme {
        val navController = rememberNavController()

        NavHost(
            navController = navController,
            startDestination = "login"
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
                    onNavigateTo = { navController.navigate("dashboard") },
                )
            }
        }
    }
}
