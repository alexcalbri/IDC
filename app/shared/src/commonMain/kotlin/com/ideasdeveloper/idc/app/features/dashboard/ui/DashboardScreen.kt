package com.ideasdeveloper.idc.app.features.dashboard.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ideasdeveloper.idc.app.core.company.CompanyIdentityStore
import com.ideasdeveloper.idc.app.features.auth.data.LoginResponse
import com.ideasdeveloper.idc.app.features.shell.ui.AuthenticatedTopBar
import com.ideasdeveloper.idc.app.features.shell.ui.toComposeColor
import com.ideasdeveloper.idc.navigation.NavRoute

@Composable
fun DashboardScreen(
    session: LoginResponse?,
    onNavigateTo: (NavRoute) -> Unit,
    onLogout: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val canSeeEmpresa = session?.role in setOf("server_owner", "business_owner")
    val enabledModules = session?.enabledModules.orEmpty()
    val companyIdentity = remember { CompanyIdentityStore.current() }
    val primaryColor = remember(companyIdentity.primaryColor) {
        companyIdentity.primaryColor.toComposeColor(Color(0xFF667EEA))
    }
    val secondaryColor = remember(companyIdentity.secondaryColor) {
        companyIdentity.secondaryColor.toComposeColor(Color(0xFF764BA2))
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(
                brush = Brush.horizontalGradient(
                    colors = listOf(primaryColor, secondaryColor)
                )
            )
    ) {
        AuthenticatedTopBar(
            title = "Dashboard",
            subtitle = "Principal",
            primaryColor = primaryColor,
            onSettings = { onNavigateTo(NavRoute.Settings) },
            onLogout = onLogout,
            modifier = Modifier.align(Alignment.TopCenter),
        )

        Column(
            modifier = Modifier
                .fillMaxWidth(0.9f)
                .align(Alignment.Center)
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = "Panel de control",
                fontSize = 28.sp,
                fontWeight = FontWeight.Bold,
                color = Color.White,
            )

            Text(
                text = companyIdentity.name,
                fontSize = 16.sp,
                color = Color(0xFFE0E0E0),
                modifier = Modifier.padding(vertical = 8.dp).padding(bottom = 24.dp)
            )

            if (canSeeEmpresa) {
                DashboardActionCard(
                    title = "Empresa",
                    description = "Administra la configuracion base de la empresa",
                    iconText = "E",
                    primaryColor = primaryColor,
                    onClick = { onNavigateTo(NavRoute.Module("empresa")) }
                )
            }

            enabledModules
                .filter { it != "empresa" }
                .forEach { moduleId ->
                    val module = moduleId.toDashboardModule()
                    DashboardActionCard(
                        title = module.title,
                        description = module.description,
                        iconText = module.iconText,
                        primaryColor = primaryColor,
                        onClick = { onNavigateTo(NavRoute.Module(moduleId)) }
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                }

            Spacer(modifier = Modifier.height(40.dp))
        }
    }
}

private fun String.toDashboardModule(): DashboardModule = when (this) {
    "clientes" -> DashboardModule("Clientes", "Gestiona la identidad compartida de clientes", "C")
    "hostpot" -> DashboardModule("Hostpot", "Modulo Hostpot pendiente", "H")
    "crm" -> DashboardModule("CRM", "Modulo CRM pendiente", "C")
    else -> DashboardModule(replace("-", " ").replace("_", " "), "Modulo pendiente", take(1).uppercase())
}

private data class DashboardModule(
    val title: String,
    val description: String,
    val iconText: String,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DashboardActionCard(
    title: String,
    description: String,
    iconText: String,
    primaryColor: Color,
    onClick: () -> Unit,
) {
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(
            containerColor = Color.White,
            contentColor = primaryColor,
        ),
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(42.dp)
                    .background(primaryColor.copy(alpha = 0.12f), CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = iconText,
                    color = primaryColor,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                )
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = description,
                    fontSize = 14.sp,
                    color = Color(0xFF666666)
                )
            }
        }
    }
}
