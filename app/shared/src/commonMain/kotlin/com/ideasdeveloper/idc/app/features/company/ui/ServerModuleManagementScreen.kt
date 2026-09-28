package com.ideasdeveloper.idc.app.features.company.ui

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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.ideasdeveloper.idc.app.core.company.CompanyIdentityStore
import com.ideasdeveloper.idc.app.features.auth.data.LoginResponse
import com.ideasdeveloper.idc.app.features.company.data.CompanyApi
import com.ideasdeveloper.idc.app.features.company.data.CompanyException
import com.ideasdeveloper.idc.app.features.company.data.ServerModuleResponse
import com.ideasdeveloper.idc.app.features.shell.ui.AuthenticatedTopBar
import com.ideasdeveloper.idc.app.features.shell.ui.toComposeColor
import kotlinx.coroutines.launch

private const val DefaultModuleCatalogUrl = "https://github.com/alexcalbri/IDC/tree/master/modules"

@Composable
// Renderiza la administracion del catalogo de modulos instalados en el servidor.
fun ServerModuleManagementScreen(
    serverUrl: String?,
    session: LoginResponse?,
    onSettings: () -> Unit,
    onLogout: () -> Unit,
    onReturnToDashboard: () -> Unit,
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
    var modules by remember { mutableStateOf<List<ServerModuleResponse>>(emptyList()) }
    var catalogUrl by remember { mutableStateOf(DefaultModuleCatalogUrl) }
    var pendingDeletion by remember { mutableStateOf<String?>(null) }
    var isLoading by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }

    fun refreshModules() {
        val activeServerUrl = serverUrl ?: return
        val activeSession = session ?: return
        isLoading = true
        scope.launch {
            try {
                modules = CompanyApi(activeServerUrl).use { api ->
                    api.listServerModules(activeSession.accessToken)
                }
                error = null
            } catch (exception: CompanyException) {
                error = exception.message
            } finally {
                isLoading = false
            }
        }
    }

    fun refreshCatalog() {
        val activeServerUrl = serverUrl ?: return
        val activeSession = session ?: return
        scope.launch {
            try {
                catalogUrl = CompanyApi(activeServerUrl).use { api ->
                    api.serverModuleCatalog(activeSession.accessToken).catalogUrl
                }.ifBlank { DefaultModuleCatalogUrl }
            } catch (_: CompanyException) {
                // La lista de modulos mostrara el error principal si el servidor no responde.
            }
        }
    }

    LaunchedEffect(serverUrl, session?.accessToken) {
        refreshCatalog()
        refreshModules()
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Brush.horizontalGradient(listOf(primaryColor, secondaryColor)))
    ) {
        AuthenticatedTopBar(
            title = "Modulos del servidor",
            subtitle = "Catalogo activo",
            primaryColor = primaryColor,
            onReturnToDashboard = onReturnToDashboard,
            onSettings = onSettings,
            onLogout = onLogout,
            modifier = Modifier.align(Alignment.TopCenter),
        )

        Surface(
            modifier = Modifier
                .fillMaxWidth(0.92f)
                .align(Alignment.Center)
                .verticalScroll(rememberScrollState()),
            shape = MaterialTheme.shapes.medium,
            color = Color.White,
        ) {
            Column(
                modifier = Modifier.padding(18.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text("Modulos instalados", style = MaterialTheme.typography.titleLarge, color = primaryColor)
                Text("Repositorio de modulos", style = MaterialTheme.typography.titleMedium, color = primaryColor)
                OutlinedTextField(
                    value = catalogUrl,
                    onValueChange = { catalogUrl = it },
                    label = { Text("URL del repositorio de modulos") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                ) {
                    Button(
                        enabled = !isLoading && serverUrl != null && session?.accessToken?.isNotBlank() == true,
                        onClick = {
                            val activeServerUrl = serverUrl
                            val activeSession = session
                            if (activeServerUrl != null && activeSession != null) {
                                isLoading = true
                                error = null
                                message = null
                                scope.launch {
                                    try {
                                        catalogUrl = CompanyApi(activeServerUrl).use { api ->
                                            api.updateServerModuleCatalog(activeSession.accessToken, catalogUrl).catalogUrl
                                        }
                                        message = "Catalogo actualizado."
                                        refreshModules()
                                    } catch (exception: CompanyException) {
                                        error = exception.message
                                    } finally {
                                        isLoading = false
                                    }
                                }
                            }
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = primaryColor),
                    ) {
                        Text("Guardar catalogo")
                    }
                    Button(
                        enabled = !isLoading,
                        onClick = {
                            refreshCatalog()
                            refreshModules()
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFE9E9EF), contentColor = primaryColor),
                    ) {
                        Text("Actualizar lista")
                    }
                }
                error?.let { Text(it, color = Color(0xFFB00020)) }
                message?.let { Text(it, color = Color(0xFF176B3A)) }
                if (isLoading) {
                    Text("Cargando modulos...", color = Color(0xFF666666))
                }

                modules.forEach { module ->
                    ServerModuleRow(
                        module = module,
                        primaryColor = primaryColor,
                        enabled = !isLoading && serverUrl != null && session?.accessToken?.isNotBlank() == true,
                        pendingDeletion = pendingDeletion == module.moduleId,
                        onInstall = {
                            val activeServerUrl = serverUrl
                            val activeSession = session
                            if (activeServerUrl != null && activeSession != null) {
                                isLoading = true
                                error = null
                                message = null
                                scope.launch {
                                    try {
                                        val installed = CompanyApi(activeServerUrl).use { api ->
                                            api.installServerModule(activeSession.accessToken, module.moduleId)
                                        }
                                        modules = modules.map {
                                            if (it.moduleId == installed.moduleId) installed else it
                                        }
                                        message = "Modulo ${installed.displayName} instalado."
                                    } catch (exception: CompanyException) {
                                        error = exception.message
                                    } finally {
                                        isLoading = false
                                    }
                                }
                            }
                        },
                        onRequestDelete = { pendingDeletion = module.moduleId },
                        onCancelDelete = { pendingDeletion = null },
                        onConfirmDelete = {
                            val activeServerUrl = serverUrl
                            val activeSession = session
                            if (activeServerUrl != null && activeSession != null) {
                                isLoading = true
                                error = null
                                message = null
                                scope.launch {
                                    try {
                                        CompanyApi(activeServerUrl).use { api ->
                                            api.deleteServerModule(activeSession.accessToken, module.moduleId)
                                        }
                                        modules = modules.map {
                                            if (it.moduleId == module.moduleId) {
                                                it.copy(installed = false, activeCompanyCount = 0)
                                            } else {
                                                it
                                            }
                                        }
                                        pendingDeletion = null
                                        message = "Modulo ${module.displayName} eliminado del servidor."
                                    } catch (exception: CompanyException) {
                                        error = exception.message
                                    } finally {
                                        isLoading = false
                                    }
                                }
                            }
                        },
                    )
                }

                Spacer(modifier = Modifier.height(2.dp))
            }
        }
    }
}

@Composable
// Renderiza un modulo instalado en el servidor y permite eliminarlo si ninguna empresa lo usa.
private fun ServerModuleRow(
    module: ServerModuleResponse,
    primaryColor: Color,
    enabled: Boolean,
    pendingDeletion: Boolean,
    onInstall: () -> Unit,
    onRequestDelete: () -> Unit,
    onCancelDelete: () -> Unit,
    onConfirmDelete: () -> Unit,
) {
    val canInstall = enabled && !module.installed
    val canDelete = enabled && module.installed && !module.locked && module.activeCompanyCount == 0
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color(0xFFF6F6FA), MaterialTheme.shapes.small)
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(module.displayName, style = MaterialTheme.typography.titleSmall, color = primaryColor)
        Text(module.description, color = Color(0xFF666666))
        Text(
            text = buildString {
                append("Version instalada: ${module.version}")
                module.latestVersion
                    ?.takeIf { it.isNotBlank() && it != module.version }
                    ?.let { append(" · Disponible: $it") }
            },
            style = MaterialTheme.typography.bodySmall,
            color = if (!module.latestVersion.isNullOrBlank() && module.latestVersion != module.version) {
                Color(0xFF8A5A00)
            } else {
                Color(0xFF555555)
            },
        )
        Text(
            text = when {
                module.locked -> "Modulo base del sistema"
                !module.installed -> "Disponible para instalar"
                module.activeCompanyCount > 0 -> "Activo en ${module.activeCompanyCount} empresa(s)"
                else -> "Instalado sin empresas activas"
            },
            style = MaterialTheme.typography.bodySmall,
            color = if (module.activeCompanyCount > 0 || module.locked) Color(0xFF8A5A00) else Color(0xFF176B3A),
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (!module.installed) {
                Button(
                    enabled = canInstall,
                    onClick = onInstall,
                    colors = ButtonDefaults.buttonColors(containerColor = primaryColor),
                ) {
                    Text("Instalar")
                }
            }
            Button(
                enabled = canDelete,
                onClick = if (pendingDeletion) onConfirmDelete else onRequestDelete,
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (pendingDeletion) Color(0xFFB00020) else Color(0xFFE9E9EF),
                    contentColor = if (pendingDeletion) Color.White else Color(0xFFB00020),
                ),
            ) {
                Text(if (pendingDeletion) "Confirmar eliminar" else "Eliminar del servidor")
            }
            if (pendingDeletion) {
                Button(
                    enabled = enabled,
                    onClick = onCancelDelete,
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFE9E9EF), contentColor = primaryColor),
                ) {
                    Text("Cancelar")
                }
            }
        }
    }
}

private suspend inline fun <T> CompanyApi.use(block: suspend (CompanyApi) -> T): T {
    return try {
        block(this)
    } finally {
        close()
    }
}
