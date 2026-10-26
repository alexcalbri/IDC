package com.ideasdeveloper.idc.app.features.company.ui

import com.ideasdeveloper.idc.app.core.theme.AppTheme
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.ideasdeveloper.idc.app.core.session.SessionStore
import com.ideasdeveloper.idc.app.features.auth.data.LoginResponse
import com.ideasdeveloper.idc.app.features.company.data.CompanyApi
import com.ideasdeveloper.idc.app.features.company.data.CompanyException
import com.ideasdeveloper.idc.app.features.company.data.CompanyProfileResponse
import com.ideasdeveloper.idc.app.features.company.data.UpdateCompanyProfileRequest
import com.ideasdeveloper.idc.app.features.shell.ui.AuthenticatedTopBar
import com.ideasdeveloper.idc.app.features.shell.ui.AuthenticatedTopBarHeight
import com.ideasdeveloper.idc.app.features.shell.ui.ClosableView
import kotlinx.coroutines.launch

@Composable
// Empresa del servidor: la empresa que el server_owner administra como su business owner sin crear
// una empresa (instalaciones de una sola empresa). Perfil y personal que usa sus modulos.
fun ServerStaffScreen(
    serverUrl: String?,
    session: LoginResponse?,
    onSettings: () -> Unit,
    onLogout: () -> Unit,
    onReturnToDashboard: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    val colors = AppTheme.colors
    val primaryColor = colors.accent
    val workspaceCode = session?.workspaceCompanyCode

    var name by remember { mutableStateOf("") }
    var logoUrl by remember { mutableStateOf("") }
    var profilePrimary by remember { mutableStateOf("#667EEA") }
    var profileSecondary by remember { mutableStateOf("#764BA2") }
    var profileAccent by remember { mutableStateOf("#FFFFFF") }
    var isLoading by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }

    fun applyProfile(profile: CompanyProfileResponse) {
        name = profile.name
        logoUrl = profile.logoUrl.orEmpty()
        profilePrimary = profile.primaryColor
        profileSecondary = profile.secondaryColor
        profileAccent = profile.accentColor
    }

    LaunchedEffect(serverUrl, session?.accessToken, workspaceCode) {
        val activeServerUrl = serverUrl ?: return@LaunchedEffect
        val activeSession = session ?: return@LaunchedEffect
        val code = workspaceCode ?: return@LaunchedEffect
        isLoading = true
        try {
            applyProfile(CompanyApi(activeServerUrl).use { api -> api.companyProfile(activeSession.accessToken, code) })
            error = null
        } catch (exception: CompanyException) {
            error = exception.message
        } finally {
            isLoading = false
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(colors.screenBrush)
    ) {
        AuthenticatedTopBar(
            title = "Empresa del servidor",
            subtitle = "Perfil y personal",
            primaryColor = primaryColor,
            onReturnToDashboard = onReturnToDashboard,
            onSettings = onSettings,
            onLogout = onLogout,
            modifier = Modifier.align(Alignment.TopCenter),
        )

        // La vista empieza debajo de la barra superior y lleva la X en su esquina superior derecha.
        ClosableView(
            onClose = onReturnToDashboard,
            modifier = Modifier
                .align(Alignment.Center)
                .padding(top = AuthenticatedTopBarHeight + 8.dp, bottom = 16.dp)
                .fillMaxWidth(0.92f),
        ) {
            Surface(
                modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
                color = AppTheme.colors.surface,
                shape = MaterialTheme.shapes.medium,
            ) {
                Column(modifier = Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    error?.let { Text(it, color = AppTheme.colors.danger) }
                    message?.let { Text(it, color = AppTheme.colors.success) }
                    if (workspaceCode == null) {
                        Text("La empresa del servidor aun no existe", fontWeight = FontWeight.Bold, color = primaryColor)
                        Text(
                            "Si este servidor atiende a una sola empresa, creala aqui: tu seras su business owner, " +
                                "podras configurarla, crear su personal y activar sus modulos sin crear una empresa aparte. " +
                                "Mas adelante puedes seguir creando otras empresas.",
                            color = AppTheme.colors.muted,
                        )
                        Button(
                            enabled = !isLoading && serverUrl != null && session != null,
                            onClick = {
                                val activeServerUrl = serverUrl ?: return@Button
                                val activeSession = session ?: return@Button
                                isLoading = true
                                error = null
                                scope.launch {
                                    try {
                                        val created = CompanyApi(activeServerUrl).use { api -> api.createServerWorkspace(activeSession.accessToken) }
                                        SessionStore.update { current -> current.withWorkspace(created.code) }
                                        message = "Empresa del servidor creada."
                                    } catch (exception: CompanyException) {
                                        error = exception.message
                                    } finally {
                                        isLoading = false
                                    }
                                }
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = primaryColor),
                        ) { Text("Crear empresa del servidor") }
                    } else {
                        Text("Datos de la empresa", style = MaterialTheme.typography.titleLarge, color = primaryColor)
                        CompanyProfileFields(
                            name = name,
                            logoUrl = logoUrl,
                            primaryColor = profilePrimary,
                            secondaryColor = profileSecondary,
                            accentColor = profileAccent,
                            enabled = !isLoading,
                            onNameChange = { name = it },
                            onLogoUrlChange = { logoUrl = it },
                            onPrimaryColorChange = { profilePrimary = it },
                            onSecondaryColorChange = { profileSecondary = it },
                            onAccentColorChange = { profileAccent = it },
                        )
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                            Button(
                                enabled = !isLoading && serverUrl != null,
                                onClick = {
                                    val activeServerUrl = serverUrl ?: return@Button
                                    val activeSession = session
                                    isLoading = true
                                    error = null
                                    message = null
                                    scope.launch {
                                        try {
                                            val profile = CompanyApi(activeServerUrl).use { api ->
                                                api.updateCompanyProfile(
                                                    activeSession.accessToken,
                                                    workspaceCode,
                                                    UpdateCompanyProfileRequest(
                                                        name = name,
                                                        logoUrl = logoUrl.ifBlank { null },
                                                        primaryColor = profilePrimary,
                                                        secondaryColor = profileSecondary,
                                                        accentColor = profileAccent,
                                                    ),
                                                )
                                            }
                                            applyProfile(profile)
                                            message = "Empresa actualizada."
                                        } catch (exception: CompanyException) {
                                            error = exception.message
                                        } finally {
                                            isLoading = false
                                        }
                                    }
                                },
                                colors = ButtonDefaults.buttonColors(containerColor = primaryColor),
                            ) { Text("Guardar") }
                        }
                        Text(
                            "El personal inicia sesion con su usuario, su contrasena y el codigo de empresa \"$workspaceCode\". " +
                                "Solo ve los modulos para los que tiene permiso. Activa modulos con \"Usar en el servidor\" en Modulos del servidor.",
                            color = AppTheme.colors.muted,
                        )
                        CompanyUsersSection(
                            serverUrl = serverUrl,
                            accessToken = session.accessToken,
                            companyCode = workspaceCode,
                            primaryColor = primaryColor,
                            description = "Usuarios de la empresa del servidor. Los permisos disponibles vienen de los modulos activos en el servidor.",
                        )
                    }
                }
            }
        }
    }
}

// Sesion con el codigo de la empresa del servidor recien creada.
private fun LoginResponse.withWorkspace(code: String) = LoginResponse(
    userId = userId,
    username = username,
    accessToken = accessToken,
    expiresInSeconds = expiresInSeconds,
    scope = scope,
    companyCode = companyCode,
    role = role,
    enabledModules = enabledModules,
    workspaceCompanyCode = code,
)

private suspend inline fun <T> CompanyApi.use(block: suspend (CompanyApi) -> T): T {
    try {
        return block(this)
    } finally {
        close()
    }
}
