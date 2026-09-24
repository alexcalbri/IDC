package com.ideasdeveloper.idc.app.features.dashboard.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ideasdeveloper.idc.app.features.auth.data.LoginResponse
import com.ideasdeveloper.idc.navigation.NavRoute

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DashboardScreen(
    session: LoginResponse?,
    onNavigateTo: (NavRoute) -> Unit,
    modifier: Modifier = Modifier,
) {
    val canSeeEmpresa = session?.role in setOf("server_owner", "business_owner")

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

            if (canSeeEmpresa) {
                DashboardActionCard(
                    title = "Empresa",
                    description = "Administra la configuración base de la empresa",
                    onClick = { onNavigateTo(NavRoute.Empresa) }
                )
            }

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
