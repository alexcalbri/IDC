package com.ideasdeveloper.idc
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.ideasdeveloper.idc.ui.screens.DashboardScreen
import com.ideasdeveloper.idc.ui.screens.LoginScreen
@Composable
fun App() {
    MaterialTheme {
        val navController = rememberNavController()

        NavHost(
            navController = navController,
            startDestination = "login"
        ) {
            composable("login") {
                LoginScreen(
                    onLoginSuccess = { navController.navigate("dashboard") },
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