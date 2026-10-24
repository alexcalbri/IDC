package com.ideasdeveloper.idc.app.features.company.ui

import com.ideasdeveloper.idc.app.core.theme.AppTheme
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.ideasdeveloper.idc.app.core.company.CompanyIdentityStore
import com.ideasdeveloper.idc.app.features.auth.data.LoginResponse
import com.ideasdeveloper.idc.app.features.shell.ui.AuthenticatedTopBar
import com.ideasdeveloper.idc.app.features.shell.ui.AuthenticatedTopBarHeight
import com.ideasdeveloper.idc.app.features.shell.ui.ClosableView
import com.ideasdeveloper.idc.app.features.shell.ui.toComposeColor

@Composable
// Personal del espacio del servidor: usuarios que usan modulos sin pertenecer a una empresa.
fun ServerStaffScreen(
    serverUrl: String?,
    session: LoginResponse?,
    onSettings: () -> Unit,
    onLogout: () -> Unit,
    onReturnToDashboard: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val companyIdentity = remember { CompanyIdentityStore.current() }
    val colors = AppTheme.colors
    val primaryColor = colors.accent
    val workspaceCode = session?.workspaceCompanyCode

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(colors.screenBrush)
    ) {
        AuthenticatedTopBar(
            title = "Personal del servidor",
            subtitle = "Espacio del servidor",
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
                    if (workspaceCode == null) {
                        Text("El espacio del servidor aun no existe", fontWeight = FontWeight.Bold, color = primaryColor)
                        Text(
                            "Activa al menos un modulo con \"Usar en el servidor\" en Modulos del servidor. " +
                                "Eso crea el espacio del servidor y despues podras crear su personal aqui.",
                            color = AppTheme.colors.muted,
                        )
                    } else {
                        Text(
                            "El personal inicia sesion con su usuario, su contrasena y el codigo de empresa \"$workspaceCode\". " +
                                "Solo ve los modulos para los que tiene permiso.",
                            color = AppTheme.colors.muted,
                        )
                        CompanyUsersSection(
                            serverUrl = serverUrl,
                            accessToken = session.accessToken,
                            companyCode = workspaceCode,
                            primaryColor = primaryColor,
                            description = "Usuarios del espacio del servidor. Los permisos disponibles vienen de los modulos activos en el servidor.",
                        )
                    }
                }
            }
        }
    }
}
