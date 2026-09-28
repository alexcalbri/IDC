package com.ideasdeveloper.idc.app.features.settings.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.ideasdeveloper.idc.app.core.company.CompanyIdentityStore
import com.ideasdeveloper.idc.app.core.config.ClientConfiguration
import com.ideasdeveloper.idc.app.core.version.ClientVersion
import com.ideasdeveloper.idc.app.core.version.VersionApi
import com.ideasdeveloper.idc.app.features.auth.data.LoginResponse
import com.ideasdeveloper.idc.app.features.shell.ui.AuthenticatedTopBar
import com.ideasdeveloper.idc.app.features.shell.ui.toComposeColor
import kotlinx.coroutines.launch

@Composable
// Muestra ajustes locales seguros del cliente; los cambios sensibles siguen viviendo en el servidor.
fun SettingsScreen(
    configuration: ClientConfiguration?,
    session: LoginResponse?,
    onReturnToDashboard: () -> Unit,
    onLogout: () -> Unit,
    onClearLocalConfiguration: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val companyIdentity = remember { CompanyIdentityStore.current() }
    val primaryColor = remember(companyIdentity.primaryColor) {
        companyIdentity.primaryColor.toComposeColor(Color(0xFF667EEA))
    }
    val secondaryColor = remember(companyIdentity.secondaryColor) {
        companyIdentity.secondaryColor.toComposeColor(Color(0xFF764BA2))
    }
    val scope = rememberCoroutineScope()
    var versionStatus by remember { mutableStateOf<String?>(null) }
    var checkingVersion by remember { mutableStateOf(false) }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Brush.horizontalGradient(listOf(primaryColor, secondaryColor))),
    ) {
        AuthenticatedTopBar(
            title = "Ajustes",
            subtitle = "Cliente",
            primaryColor = primaryColor,
            onReturnToDashboard = onReturnToDashboard,
            onSettings = {},
            onLogout = onLogout,
            modifier = Modifier.align(Alignment.TopCenter),
        )

        Column(
            modifier = Modifier
                .fillMaxWidth(0.9f)
                .align(Alignment.Center)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            SettingsCard(primaryColor = primaryColor) {
                Text("Conexion", fontWeight = FontWeight.Bold, color = primaryColor)
                Spacer(Modifier.height(10.dp))
                SettingRow("Servidor", configuration?.serverUrl ?: "Sin configurar")
                SettingRow("Empresa", configuration?.companyCode ?: "Administracion del servidor")
            }

            SettingsCard(primaryColor = primaryColor) {
                Text("Sesion", fontWeight = FontWeight.Bold, color = primaryColor)
                Spacer(Modifier.height(10.dp))
                SettingRow("Usuario", session?.username ?: "Sin sesion")
                SettingRow("Rol", session?.role ?: "Sin rol")
                SettingRow("Empresa activa", session?.companyCode ?: "Servidor")
            }

            SettingsCard(primaryColor = primaryColor) {
                Text("Version", fontWeight = FontWeight.Bold, color = primaryColor)
                Spacer(Modifier.height(10.dp))
                SettingRow("Cliente", ClientVersion)
                versionStatus?.let {
                    Spacer(Modifier.height(8.dp))
                    Text(it, color = Color(0xFF444444))
                }
                Spacer(Modifier.height(12.dp))
                Button(
                    onClick = {
                        val serverUrl = configuration?.serverUrl.orEmpty()
                        if (serverUrl.isBlank()) {
                            versionStatus = "No hay servidor configurado para revisar actualizaciones."
                            return@Button
                        }
                        checkingVersion = true
                        scope.launch {
                            val version = VersionApi(serverUrl).use { it.version() }
                            versionStatus = when {
                                version == null -> "No se pudo consultar la version del servidor."
                                !version.latestAppVersion.isNullOrBlank() && version.latestAppVersion != ClientVersion ->
                                    "Nueva version del cliente disponible: ${version.latestAppVersion}."
                                else -> "El cliente esta actualizado."
                            }
                            checkingVersion = false
                        }
                    },
                    enabled = !checkingVersion,
                    colors = ButtonDefaults.buttonColors(containerColor = primaryColor),
                ) {
                    Text(if (checkingVersion) "Revisando..." else "Revisar actualizaciones")
                }
            }

            SettingsCard(primaryColor = primaryColor) {
                Text("Datos locales", fontWeight = FontWeight.Bold, color = primaryColor)
                Spacer(Modifier.height(10.dp))
                Text(
                    text = "Limpia la URL, empresa guardada y sesion local. No modifica datos del servidor.",
                    color = Color(0xFF555555),
                )
                Spacer(Modifier.height(12.dp))
                OutlinedButton(onClick = onClearLocalConfiguration) {
                    Text("Limpiar configuracion local")
                }
            }
        }
    }
}

@Composable
private fun SettingsCard(
    primaryColor: Color,
    content: @Composable Column.() -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = Color.White, contentColor = primaryColor),
        shape = MaterialTheme.shapes.medium,
    ) {
        Column(modifier = Modifier.padding(16.dp), content = content)
    }
}

@Composable
private fun SettingRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, color = Color(0xFF666666))
        Text(value, color = Color(0xFF222222), fontWeight = FontWeight.SemiBold)
    }
}

private suspend inline fun <T> VersionApi.use(block: suspend (VersionApi) -> T): T {
    return try {
        block(this)
    } finally {
        close()
    }
}
