// app/src/commonMain/kotlin/com/ideasdeveloper/app/ui/screens/DashboardScreen.kt
package com.ideasdeveloper.idc.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ideasdeveloper.idc.navigation.NavRoute

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DashboardScreen(
    onNavigateTo: (NavRoute) -> Unit,
    modifier: Modifier = Modifier,
) {
    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = {
                    Text(
                        text = "IdeasCore Dashboard",
                        fontWeight = FontWeight.Bold
                    )
                },
            )
        }
    ) { padding ->
        Column(
            modifier = modifier
                .fillMaxSize()
                .padding(padding)
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text(
                text = "Bienvenido a IdeasCore",
                fontSize = 24.sp,
                fontWeight = FontWeight.Bold
            )

            Text(
                text = "Selecciona una opción para comenzar:",
                fontSize = 16.sp
            )

            Spacer(modifier = Modifier.height(8.dp))

            // Quick action cards
            DashboardActionCard(
                title = "Clientes",
                description = "Gestiona tu base de clientes",
                onClick = { onNavigateTo(NavRoute.Clientes) }
            )

            DashboardActionCard(
                title = "Memberships",
                description = "Administra planes de membresía",
                onClick = { onNavigateTo(NavRoute.Memberships) }
            )

            DashboardActionCard(
                title = "Ajustes",
                description = "Configuración de la aplicación",
                onClick = { onNavigateTo(NavRoute.Settings) }
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DashboardActionCard(
    title: String,
    description: String,
    onClick: () -> Unit,
) {
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(16.dp)
        ) {
            Text(
                text = title,
                fontSize = 18.sp,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                text = description,
                fontSize = 14.sp,
                color = Color.Gray
            )
        }
    }
}