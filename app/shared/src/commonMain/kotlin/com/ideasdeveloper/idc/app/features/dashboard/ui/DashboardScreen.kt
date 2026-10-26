package com.ideasdeveloper.idc.app.features.dashboard.ui

import com.ideasdeveloper.idc.app.core.theme.AppTheme
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.unit.sp
import com.ideasdeveloper.idc.app.core.company.CompanyIdentityStore
import com.ideasdeveloper.idc.app.core.version.ClientVersion
import com.ideasdeveloper.idc.app.core.version.VersionApi
import com.ideasdeveloper.idc.app.features.auth.data.LoginResponse
import com.ideasdeveloper.idc.app.features.shell.ui.AuthenticatedTopBar
import com.ideasdeveloper.idc.app.features.shell.ui.toComposeColor
import com.ideasdeveloper.idc.navigation.NavRoute

@Composable
// Renderiza el dashboard principal con las acciones disponibles para la sesion actual.
fun DashboardScreen(
    session: LoginResponse?,
    serverUrl: String?,
    onNavigateTo: (NavRoute) -> Unit,
    onLogout: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // Determina si el usuario puede entrar a la administracion de empresa.
    val canSeeEmpresa = session?.role in setOf("server_owner", "business_owner")
    val canManageServerModules = session?.role == "server_owner"
    // Obtiene los modulos activos que el servidor envio durante el inicio de sesion.
    val enabledModules = session?.enabledModules.orEmpty()
    // Carga la identidad visual de la empresa para pintar el dashboard.
    val companyIdentity = remember { CompanyIdentityStore.current() }
    val colors = AppTheme.colors
    val primaryColor = colors.accent
    var versionNotice by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(serverUrl, session?.role) {
        if (session?.role == "server_owner" && !serverUrl.isNullOrBlank()) {
            val version = VersionApi(serverUrl).use { it.version() }
            val notices = buildList {
                version?.latestCoreVersion?.takeIf { it.isNotBlank() && it != version.coreVersion }?.let { add("Core $it") }
                version?.latestAppVersion?.takeIf { it.isNotBlank() && it != ClientVersion }?.let { add("App $it") }
                version?.moduleUpdates
                    ?.takeIf { it.isNotEmpty() }
                    ?.joinToString { update -> "${update.displayName} ${update.latestVersion}" }
                    ?.let { add("Modulos: $it") }
            }
            versionNotice = notices.takeIf { it.isNotEmpty() }?.joinToString(prefix = "Actualizaciones disponibles: ")
        }
    }

    // Renderiza el fondo principal con los colores configurados para la empresa.
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(colors.screenBrush)
    ) {
        // Renderiza la barra superior autenticada con acceso a ajustes y cierre de sesion.
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
                color = AppTheme.colors.onScreen,
            )

            Text(
                text = companyIdentity.name,
                fontSize = 16.sp,
                color = AppTheme.colors.onScreenMuted,
                modifier = Modifier.padding(vertical = 8.dp).padding(bottom = 24.dp)
            )

            versionNotice?.let {
                Text(
                    text = it,
                    color = AppTheme.colors.onScreen,
                    modifier = Modifier.padding(bottom = 16.dp),
                )
            }

            // Renderiza la tarjeta de Empresa para roles autorizados.
            if (canSeeEmpresa) {
                DashboardActionCard(
                    title = "Empresa",
                    description = "Administra la configuracion base de la empresa",
                    iconText = "E",
                    primaryColor = primaryColor,
                    onClick = { onNavigateTo(NavRoute.Module("empresa")) }
                )
                Spacer(modifier = Modifier.height(12.dp))
            }

            if (canManageServerModules) {
                DashboardActionCard(
                    title = "Modulos del servidor",
                    description = "Administra los modulos instalados en este servidor",
                    iconText = "M",
                    primaryColor = primaryColor,
                    onClick = { onNavigateTo(NavRoute.Module("server-modules")) }
                )
                Spacer(modifier = Modifier.height(12.dp))
                DashboardActionCard(
                    title = "Empresa del servidor",
                    description = "Configura la empresa del servidor y su personal sin crear una empresa",
                    iconText = "S",
                    primaryColor = primaryColor,
                    onClick = { onNavigateTo(NavRoute.Module("server-staff")) }
                )
                Spacer(modifier = Modifier.height(12.dp))
            }

            // Renderiza los modulos activos recibidos desde el servidor.
            enabledModules
                .filter { it != "empresa" && it != "server-modules" && it != "server-staff" }
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

private suspend inline fun <T> VersionApi.use(block: suspend (VersionApi) -> T): T {
    return try {
        block(this)
    } finally {
        close()
    }
}

// Convierte cualquier id tecnico recibido desde el servidor en textos genericos para el dashboard.
private fun String.toDashboardModule(): DashboardModule {
    val title = toDisplayName()
    return DashboardModule(
        title = title,
        description = "Abrir modulo $title",
        iconText = title.take(1).uppercase(),
    )
}

// Convierte ids como customer-module o customer_module en nombres legibles para el usuario.
private fun String.toDisplayName(): String = split('-', '_')
    .filter { it.isNotBlank() }
    .joinToString(" ") { word -> word.replaceFirstChar { char -> char.uppercase() } }
    .ifBlank { "Modulo" }

// Representa la informacion visual minima de una tarjeta de modulo.
private data class DashboardModule(
    val title: String,
    val description: String,
    val iconText: String,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
// Renderiza una tarjeta clickeable para navegar a una accion o modulo.
private fun DashboardActionCard(
    title: String,
    description: String,
    iconText: String,
    primaryColor: Color,
    onClick: () -> Unit,
) {
    // Crea la tarjeta visual y ejecuta la navegacion al hacer click.
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(
            containerColor = AppTheme.colors.surface,
            contentColor = primaryColor,
        ),
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            // Renderiza el circulo con la inicial o icono textual del modulo.
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
            // Renderiza el titulo y la descripcion de la accion.
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = description,
                    fontSize = 14.sp,
                    color = AppTheme.colors.muted
                )
            }
        }
    }
}
