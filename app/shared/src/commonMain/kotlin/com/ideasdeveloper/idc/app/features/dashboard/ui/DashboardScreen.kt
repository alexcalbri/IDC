package com.ideasdeveloper.idc.app.features.dashboard.ui

import com.ideasdeveloper.idc.app.core.layout.AdaptiveLayout
import com.ideasdeveloper.idc.app.core.layout.ContentWidth
import com.ideasdeveloper.idc.app.core.layout.contentWidth
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import com.ideasdeveloper.idc.app.core.theme.accentTextOn
import com.ideasdeveloper.idc.app.core.theme.AppTheme
import com.ideasdeveloper.idc.app.core.theme.accentButtonColors
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
import com.ideasdeveloper.idc.app.features.company.data.CompanyApi
import com.ideasdeveloper.idc.app.features.company.data.CompanySwitch
import com.ideasdeveloper.idc.app.features.company.data.OwnedCompany
import com.ideasdeveloper.idc.app.features.company.data.CompanyException
import com.ideasdeveloper.idc.app.features.company.data.SwitchTargetsResponse
import com.ideasdeveloper.idc.app.core.company.serverIdentity
import com.ideasdeveloper.idc.app.features.shell.ui.TopBarMenuItem
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import com.ideasdeveloper.idc.app.core.version.ClientVersion
import com.ideasdeveloper.idc.app.core.version.VersionApi
import com.ideasdeveloper.idc.app.features.auth.data.LoginResponse
import com.ideasdeveloper.idc.app.features.shell.ui.AuthenticatedScreen
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
    // La sesion paso a otra empresa del mismo business owner (la app recarga su configuracion).
    onCompanySwitched: () -> Unit = {},
) {
    // Determina si el usuario puede entrar a la administracion de empresa.
    val canSeeEmpresa = session?.role in setOf("server_owner", "business_owner")
    val canManageServerModules = session?.role == "server_owner"
    // Obtiene los modulos activos que el servidor envio durante el inicio de sesion.
    val enabledModules = session?.enabledModules.orEmpty()
    // Carga la identidad visual de la empresa para pintar el dashboard; cambia al cambiar de empresa.
    val savedIdentity by CompanyIdentityStore.identity.collectAsState()
    val companyIdentity = savedIdentity ?: CompanyIdentityStore.current()
    val scope = rememberCoroutineScope()
    val companyCode = session?.companyCode
    val isServerOwner = session?.role == "server_owner"
    // Business owners and server owners (also inside a company they entered) can switch.
    val canSwitch = isServerOwner || (session?.role == "business_owner" && companyCode != null)
    var targets by remember(session?.accessToken) { mutableStateOf<SwitchTargetsResponse?>(null) }
    var companyMessage by remember { mutableStateOf<String?>(null) }
    var switching by remember { mutableStateOf(false) }
    var renamingServer by remember { mutableStateOf(false) }

    LaunchedEffect(session?.accessToken) {
        val url = serverUrl ?: return@LaunchedEffect
        val active = session ?: return@LaunchedEffect
        if (!canSwitch) return@LaunchedEffect
        targets = runCatching {
            val api = CompanyApi(url)
            try { api.switchTargets(active.accessToken, companyCode) } finally { api.close() }
        }.getOrNull()
        // Al volver al servidor se muestra el nombre que tiene ahora.
        if (companyCode == null) targets?.serverName?.let { CompanyIdentityStore.save(serverIdentity(it)) }
    }

    fun switchTo(target: OwnedCompany?) {
        val url = serverUrl ?: return
        val active = session ?: return
        switching = true
        companyMessage = "Entrando a ${target?.name ?: targets?.serverName ?: "el servidor"}..."
        scope.launch {
            val failure = CompanySwitch.switchTo(url, active, target)
            switching = false
            companyMessage = failure
            if (failure == null) onCompanySwitched()
        }
    }

    val titleMenu = targets?.takeIf { !switching }?.let { options ->
        val items = buildList {
            options.serverName?.let { name ->
                add(TopBarMenuItem(name, caption = "Servidor", current = companyCode == null) { switchTo(null) })
            }
            options.companies.forEach { company ->
                add(TopBarMenuItem(company.name, caption = company.code, current = company.code == companyCode) { switchTo(company) })
            }
            if (isServerOwner && companyCode == null) add(TopBarMenuItem("Cambiar nombre del servidor...") { renamingServer = true })
        }
        // Una sola empresa sin servidor no tiene a donde cambiar.
        items.takeIf { it.count { item -> !item.current } > 0 }
    }.orEmpty()

    if (renamingServer) {
        ServerNameDialog(
            current = companyIdentity.name,
            onSave = { name ->
                val url = serverUrl ?: return@ServerNameDialog
                val active = session ?: return@ServerNameDialog
                scope.launch {
                    companyMessage = try {
                        val api = CompanyApi(url)
                        val saved = try { api.updateServerName(active.accessToken, name) } finally { api.close() }
                        CompanyIdentityStore.save(serverIdentity(saved.name))
                        targets = targets?.copy(serverName = saved.name)
                        renamingServer = false
                        null
                    } catch (exception: CompanyException) {
                        exception.message
                    }
                }
            },
            onDismiss = { renamingServer = false },
        )
    }
    val colors = AppTheme.colors
    val primaryColor = colors.accent
    var versionNotice by remember { mutableStateOf<String?>(null) }
    var coreUpdateAvailable by remember { mutableStateOf(false) }

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
            coreUpdateAvailable = version?.latestCoreVersion?.let { isNewerVersion(it, version.coreVersion) } == true
            versionNotice = notices.takeIf { it.isNotEmpty() }?.joinToString(prefix = "Actualizaciones disponibles: ")
        }
    }

    // Renderiza el fondo principal con los colores configurados para la empresa.
    AuthenticatedScreen(
        modifier = modifier.background(colors.screenBrush),
        topBar = {
            // Renderiza la barra superior autenticada con acceso a ajustes y cierre de sesion.
            AuthenticatedTopBar(
                title = companyIdentity.name,
                subtitle = "Dashboard principal",
                primaryColor = primaryColor,
                onSettings = { onNavigateTo(NavRoute.Settings) },
                onLogout = onLogout,
                titleMenu = titleMenu,
            )
        },
    ) {

        Column(
            modifier = Modifier
                .contentWidth(ContentWidth.Content, 0.9f)
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

            if (session?.serverOwnerAccess == true) {
                Text(
                    text = "Entraste como dueno del servidor: lo que hagas aqui queda a nombre del business owner de la empresa.",
                    color = AppTheme.colors.onScreen,
                    modifier = Modifier.padding(bottom = 16.dp),
                )
            }

            companyMessage?.let {
                Text(
                    text = it,
                    color = AppTheme.colors.onScreen,
                    modifier = Modifier.padding(bottom = 16.dp),
                )
            }

            versionNotice?.let {
                Row(
                    modifier = Modifier.padding(bottom = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text(
                        text = it,
                        color = AppTheme.colors.onScreen,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    // Lleva a la pantalla que actualiza el Core del servidor.
                    if (coreUpdateAvailable) {
                        Button(
                            onClick = { onNavigateTo(NavRoute.Module("server-update")) },
                            colors = accentButtonColors(),
                        ) { Text("Actualizar") }
                    }
                }
            }

            // Reune las tarjetas de acceso y las reparte en una o dos columnas segun el ancho de la ventana.
            val cards = buildList {
                if (canSeeEmpresa) {
                    add(DashboardCardData("Empresa", "Administra la configuracion base de la empresa", "E", "empresa"))
                }
                if (canManageServerModules) {
                    add(DashboardCardData("Modulos del servidor", "Administra los modulos instalados en este servidor", "M", "server-modules"))
                    add(DashboardCardData("Empresa del servidor", "Configura la empresa del servidor y su personal sin crear una empresa", "S", "server-staff"))
                    add(DashboardCardData("Errores del servidor", "Consulta los errores recientes del servidor para diagnosticar fallos", "!", "server-logs"))
                    add(DashboardCardData("Actualizar servidor", "Actualiza el Core y la webapp del servidor desde la app", "U", "server-update"))
                }
                // Modulos activos recibidos desde el servidor.
                enabledModules
                    .filter { it != "empresa" && it != "server-modules" && it != "server-staff" && it != "server-logs" && it != "server-update" }
                    .forEach { moduleId ->
                        val module = moduleId.toDashboardModule()
                        add(DashboardCardData(module.title, module.description, module.iconText, moduleId))
                    }
            }
            val columns = if (AdaptiveLayout.roomy) 2 else 1
            val gap = if (AdaptiveLayout.roomy) AdaptiveLayout.gap else 12.dp
            cards.chunked(columns).forEach { row ->
                Row(
                    modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Min),
                    horizontalArrangement = Arrangement.spacedBy(gap),
                ) {
                    row.forEach { card ->
                        DashboardActionCard(
                            title = card.title,
                            description = card.description,
                            iconText = card.iconText,
                            primaryColor = primaryColor,
                            onClick = { onNavigateTo(NavRoute.Module(card.moduleId)) },
                            modifier = Modifier.weight(1f).fillMaxHeight(),
                        )
                    }
                    // Completa la ultima fila para que la tarjeta suelta no se estire.
                    repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
                }
                Spacer(modifier = Modifier.height(gap))
            }

            Spacer(modifier = Modifier.height(40.dp))
        }
    }
}

// Verdadero si [latest] es un X.Y.Z valido estrictamente mayor que [current] (comparacion numerica).
private fun isNewerVersion(latest: String, current: String): Boolean {
    fun parts(value: String): List<Int>? {
        val numbers = value.trim().split('.').map { it.toIntOrNull() ?: return null }
        return numbers.takeIf { it.size == 3 && it.all { n -> n >= 0 } }
    }
    val newer = parts(latest) ?: return false
    val old = parts(current) ?: return false
    for (i in 0..2) {
        if (newer[i] != old[i]) return newer[i] > old[i]
    }
    return false
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

// Tarjeta de acceso del dashboard con la ruta del modulo que abre.
private data class DashboardCardData(
    val title: String,
    val description: String,
    val iconText: String,
    val moduleId: String,
)

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
    modifier: Modifier = Modifier,
) {
    // El acento solo se usa como texto si da 4.5:1 sobre la tarjeta; si no, el circulo conserva el color de marca.
    val accentText = AppTheme.colors.accentTextOn(AppTheme.colors.surface, primaryColor)
    // Crea la tarjeta visual y ejecuta la navegacion al hacer click.
    Card(
        onClick = onClick,
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(
            containerColor = AppTheme.colors.surface,
            contentColor = AppTheme.colors.text,
        ),
    ) {
        Row(
            modifier = Modifier.padding(if (AdaptiveLayout.roomy) 12.dp else 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            // Renderiza el circulo con la inicial o icono textual del modulo.
            Box(
                modifier = Modifier
                    .size(42.dp)
                    .background(accentText.copy(alpha = 0.12f), CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = iconText,
                    color = accentText,
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

@Composable
private fun ServerNameDialog(current: String, onSave: (String) -> Unit, onDismiss: () -> Unit) {
    var name by remember { mutableStateOf(current) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Nombre del servidor") },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it.take(80) },
                label = { Text("Nombre") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = { TextButton(onClick = { onSave(name) }, enabled = name.isNotBlank()) { Text("Guardar") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancelar") } },
    )
}
