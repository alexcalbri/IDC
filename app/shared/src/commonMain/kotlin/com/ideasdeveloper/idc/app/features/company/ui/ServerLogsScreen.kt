package com.ideasdeveloper.idc.app.features.company.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import com.ideasdeveloper.idc.app.core.theme.AppTheme
import com.ideasdeveloper.idc.app.features.auth.data.LoginResponse
import com.ideasdeveloper.idc.app.features.company.data.CompanyApi
import com.ideasdeveloper.idc.app.features.company.data.CompanyException
import com.ideasdeveloper.idc.app.features.company.data.ServerErrorLogEntry
import com.ideasdeveloper.idc.app.features.shell.ui.AuthenticatedTopBar
import com.ideasdeveloper.idc.app.features.shell.ui.ClosableView
import com.ideasdeveloper.idc.app.features.shell.ui.AuthenticatedTopBarHeight
import kotlinx.coroutines.launch

@Composable
fun ServerLogsScreen(
    serverUrl: String?,
    session: LoginResponse?,
    onSettings: () -> Unit,
    onLogout: () -> Unit,
    onReturnToDashboard: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = AppTheme.colors
    val scope = rememberCoroutineScope()
    var errors by remember { mutableStateOf<List<ServerErrorLogEntry>>(emptyList()) }
    var error by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(false) }

    fun load() {
        val url = serverUrl
        val token = session?.accessToken
        if (url == null || token == null) {
            error = "No hay una sesion de server owner activa."
            return
        }
        loading = true
        error = null
        scope.launch {
            try {
                val api = CompanyApi(url)
                try {
                    errors = api.serverLogs(token).errors
                } finally {
                    api.close()
                }
            } catch (exception: CompanyException) {
                error = exception.message
            } finally {
                loading = false
            }
        }
    }

    LaunchedEffect(serverUrl, session?.accessToken) { load() }

    Box(modifier.fillMaxSize().background(colors.screenBrush)) {
        AuthenticatedTopBar(
            title = "Errores del servidor",
            subtitle = "Ultimos errores registrados desde el inicio del servidor",
            primaryColor = colors.accent,
            onReturnToDashboard = onReturnToDashboard,
            onSettings = onSettings,
            onLogout = onLogout,
            modifier = Modifier.align(Alignment.TopCenter),
        )
        ClosableView(
            onClose = onReturnToDashboard,
            modifier = Modifier
                .align(Alignment.Center)
                .padding(top = AuthenticatedTopBarHeight + 8.dp, bottom = 16.dp)
                .fillMaxWidth(0.94f),
        ) {
            Column(
                modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(18.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    "Se conservan hasta 100 errores en memoria; se pierden al reiniciar el servidor. Los valores que parezcan contrasenas o tokens se ocultan.",
                    color = AppTheme.colors.muted,
                )
                Button(
                    onClick = ::load,
                    enabled = !loading,
                    colors = ButtonDefaults.buttonColors(containerColor = colors.accent),
                ) {
                    Text(if (loading) "Cargando..." else "Actualizar")
                }
                error?.let { Text(it, color = colors.danger) }
                if (!loading && error == null && errors.isEmpty()) {
                    Text("No hay errores registrados desde el inicio del servidor.", color = colors.muted)
                }
                errors.forEach { entry ->
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        color = colors.surface,
                        shape = MaterialTheme.shapes.medium,
                    ) {
                        Column(
                            modifier = Modifier.padding(14.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            Text(entry.timestamp, color = colors.muted, style = MaterialTheme.typography.bodySmall)
                            Text(entry.logger, color = colors.muted, style = MaterialTheme.typography.bodySmall)
                            Text(entry.message, color = colors.text, fontWeight = FontWeight.SemiBold)
                            if (entry.details.isNotBlank()) {
                                Spacer(Modifier.height(2.dp))
                                Text(entry.details, color = colors.text, style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }
            }
        }
    }
}
