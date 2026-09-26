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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.ideasdeveloper.idc.app.core.company.CompanyIdentityStore
import com.ideasdeveloper.idc.app.features.auth.data.LoginResponse
import com.ideasdeveloper.idc.app.features.company.data.CompanyApi
import com.ideasdeveloper.idc.app.features.company.data.CompanyException
import com.ideasdeveloper.idc.app.features.company.data.CompanySummaryResponse
import com.ideasdeveloper.idc.app.features.company.data.CreateCompanyRequest
import com.ideasdeveloper.idc.app.features.shell.ui.AuthenticatedTopBar
import com.ideasdeveloper.idc.app.features.shell.ui.toComposeColor
import kotlinx.coroutines.launch

@Composable
fun CompanyProvisioningScreen(
    serverUrl: String?,
    session: LoginResponse?,
    onSettings: () -> Unit,
    onLogout: () -> Unit,
    onMinimize: () -> Unit,
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

    var code by remember { mutableStateOf("") }
    var name by remember { mutableStateOf("") }
    var ownerUsername by remember { mutableStateOf("") }
    var ownerPassword by remember { mutableStateOf("") }
    var ownerPasswordConfirmation by remember { mutableStateOf("") }
    var isLoading by remember { mutableStateOf(false) }
    var isRefreshing by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var companies by remember { mutableStateOf<List<CompanySummaryResponse>>(emptyList()) }
    var companyPendingDeletion by remember { mutableStateOf<String?>(null) }

    fun refreshCompanies() {
        val activeServerUrl = serverUrl ?: return
        val activeSession = session ?: return
        isRefreshing = true
        scope.launch {
            try {
                companies = CompanyApi(activeServerUrl).use { api ->
                    api.listCompanies(activeSession.accessToken)
                }
                error = null
            } catch (exception: CompanyException) {
                error = exception.message
            } finally {
                isRefreshing = false
            }
        }
    }

    LaunchedEffect(serverUrl, session?.accessToken) {
        refreshCompanies()
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Brush.horizontalGradient(listOf(primaryColor, secondaryColor)))
    ) {
        AuthenticatedTopBar(
            title = "Empresa",
            subtitle = "Crear empresa",
            primaryColor = primaryColor,
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
                Text("Nueva empresa", style = MaterialTheme.typography.titleLarge, color = primaryColor)

                OutlinedTextField(
                    value = code,
                    onValueChange = { code = it.trim().lowercase() },
                    label = { Text("Codigo") },
                    singleLine = true,
                    enabled = !isLoading,
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None),
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Nombre") },
                    singleLine = true,
                    enabled = !isLoading,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = ownerUsername,
                    onValueChange = { ownerUsername = it.trim().lowercase() },
                    label = { Text("Usuario business owner") },
                    singleLine = true,
                    enabled = !isLoading,
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None),
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = ownerPassword,
                    onValueChange = { ownerPassword = it },
                    label = { Text("Contrasena business owner") },
                    singleLine = true,
                    enabled = !isLoading,
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = ownerPasswordConfirmation,
                    onValueChange = { ownerPasswordConfirmation = it },
                    label = { Text("Confirmar contrasena") },
                    singleLine = true,
                    enabled = !isLoading,
                    visualTransformation = PasswordVisualTransformation(),
                    isError = ownerPasswordConfirmation.isNotEmpty() && ownerPassword != ownerPasswordConfirmation,
                    supportingText = {
                        if (ownerPasswordConfirmation.isNotEmpty() && ownerPassword != ownerPasswordConfirmation) {
                            Text("Las contrasenas no coinciden.")
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                )

                error?.let { Text(it, color = Color(0xFFB00020)) }
                message?.let { Text(it, color = Color(0xFF176B3A)) }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.End),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Button(
                        onClick = onMinimize,
                        enabled = !isLoading,
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFE9E9EF), contentColor = primaryColor),
                    ) {
                        Text("Cancelar")
                    }
                    Button(
                        enabled = !isLoading &&
                                serverUrl != null &&
                                session?.accessToken?.isNotBlank() == true &&
                                code.isNotBlank() &&
                                name.isNotBlank() &&
                                ownerUsername.isNotBlank() &&
                                ownerPassword.length >= 12 &&
                                ownerPassword == ownerPasswordConfirmation,
                        onClick = {
                            val activeServerUrl = serverUrl ?: return@Button
                            val activeSession = session ?: return@Button
                            isLoading = true
                            error = null
                            message = null
                            scope.launch {
                                try {
                                    val response = CompanyApi(activeServerUrl).use { api ->
                                        api.createCompany(
                                            activeSession.accessToken,
                                            CreateCompanyRequest(
                                                code = code,
                                                name = name,
                                                businessOwnerUsername = ownerUsername,
                                                businessOwnerPassword = ownerPassword,
                                            ),
                                        )
                                    }
                                    message = "Empresa ${response.name} creada. Ya puedes iniciar sesion con codigo ${response.code}."
                                    code = ""
                                    name = ""
                                    ownerUsername = ""
                                    ownerPassword = ""
                                    ownerPasswordConfirmation = ""
                                    refreshCompanies()
                                } catch (exception: CompanyException) {
                                    error = exception.message
                                } finally {
                                    isLoading = false
                                }
                            }
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = primaryColor),
                    ) {
                        if (isLoading) {
                            CircularProgressIndicator(color = Color.White, strokeWidth = 2.dp, modifier = Modifier.height(18.dp))
                        } else {
                            Text("Crear")
                        }
                    }
                }

                Text("Modulos disponibles por empresa", style = MaterialTheme.typography.titleMedium, color = primaryColor)
                Text(
                    "Solo se listan modulos instalados en este servidor. Los modulos externos deben instalarse antes de poder activarlos por empresa.",
                    color = Color(0xFF666666),
                )

                if (isRefreshing) {
                    Text("Cargando empresas...", color = Color(0xFF666666))
                }

                companies.forEach { company ->
                    CompanyModulesRow(
                        company = company,
                        primaryColor = primaryColor,
                        enabled = !isLoading && !isRefreshing && serverUrl != null && session?.accessToken?.isNotBlank() == true,
                        pendingDeletion = companyPendingDeletion == company.code,
                        onChangeStatus = { active ->
                            val activeServerUrl = serverUrl
                            val activeSession = session
                            if (activeServerUrl != null && activeSession != null) {
                                isRefreshing = true
                                error = null
                                message = null
                                companyPendingDeletion = null
                                scope.launch {
                                    try {
                                        val updated = CompanyApi(activeServerUrl).use { api ->
                                            api.updateCompanyStatus(activeSession.accessToken, company.code, active)
                                        }
                                        companies = companies.map {
                                            if (it.code == updated.code) updated else it
                                        }
                                        message = if (updated.isActive) {
                                            "Empresa ${updated.name} reactivada."
                                        } else {
                                            "Empresa ${updated.name} desactivada. Ahora puedes eliminarla si corresponde."
                                        }
                                    } catch (exception: CompanyException) {
                                        error = exception.message
                                    } finally {
                                        isRefreshing = false
                                    }
                                }
                            }
                        },
                        onRequestDelete = {
                            companyPendingDeletion = company.code
                        },
                        onCancelDelete = {
                            companyPendingDeletion = null
                        },
                        onConfirmDelete = {
                            val activeServerUrl = serverUrl
                            val activeSession = session
                            if (activeServerUrl != null && activeSession != null) {
                                isRefreshing = true
                                error = null
                                message = null
                                scope.launch {
                                    try {
                                        CompanyApi(activeServerUrl).use { api ->
                                            api.deleteCompany(activeSession.accessToken, company.code)
                                        }
                                        companies = companies.filterNot { it.code == company.code }
                                        companyPendingDeletion = null
                                        message = "Empresa ${company.name} eliminada junto con su base tenant."
                                    } catch (exception: CompanyException) {
                                        error = exception.message
                                    } finally {
                                        isRefreshing = false
                                    }
                                }
                            }
                        },
                        onChangeModule = { moduleId, enabled ->
                            val activeServerUrl = serverUrl
                            val activeSession = session
                            if (activeServerUrl != null && activeSession != null) {
                                isRefreshing = true
                                error = null
                                message = null
                                scope.launch {
                                    try {
                                        val updated = CompanyApi(activeServerUrl).use { api ->
                                            api.updateModule(activeSession.accessToken, company.code, moduleId, enabled)
                                        }
                                        companies = companies.map {
                                            if (it.code == updated.code) updated else it
                                        }
                                        message = "Modulo actualizado para ${updated.name}."
                                    } catch (exception: CompanyException) {
                                        error = exception.message
                                    } finally {
                                        isRefreshing = false
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
private fun CompanyModulesRow(
    company: CompanySummaryResponse,
    primaryColor: Color,
    enabled: Boolean,
    pendingDeletion: Boolean,
    onChangeStatus: (active: Boolean) -> Unit,
    onRequestDelete: () -> Unit,
    onCancelDelete: () -> Unit,
    onConfirmDelete: () -> Unit,
    onChangeModule: (moduleId: String, enabled: Boolean) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color(0xFFF6F6FA), MaterialTheme.shapes.small)
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(company.name, style = MaterialTheme.typography.titleSmall, color = primaryColor)
        Text("Codigo: ${company.code}", style = MaterialTheme.typography.bodySmall, color = Color(0xFF666666))
        Text(
            "Estado: ${if (company.isActive) "activa" else "desactivada"}",
            style = MaterialTheme.typography.bodySmall,
            color = if (company.isActive) Color(0xFF176B3A) else Color(0xFF8A5A00),
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Button(
                enabled = enabled,
                onClick = { onChangeStatus(!company.isActive) },
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (company.isActive) Color(0xFFE9E9EF) else primaryColor,
                    contentColor = if (company.isActive) primaryColor else Color.White,
                ),
            ) {
                Text(if (company.isActive) "Desactivar" else "Reactivar")
            }
            if (!company.isActive) {
                Button(
                    enabled = enabled,
                    onClick = if (pendingDeletion) onConfirmDelete else onRequestDelete,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (pendingDeletion) Color(0xFFB00020) else Color(0xFFE9E9EF),
                        contentColor = if (pendingDeletion) Color.White else Color(0xFFB00020),
                    ),
                ) {
                    Text(if (pendingDeletion) "Confirmar borrar DB" else "Eliminar")
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

        company.modules.forEach { module ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "${module.displayName}: ${if (module.enabled) "activo" else "inactivo"}",
                    color = Color(0xFF333333),
                )
                Button(
                    enabled = enabled && company.isActive && !module.locked,
                    onClick = { onChangeModule(module.moduleId, !module.enabled) },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (module.enabled) Color(0xFFE9E9EF) else primaryColor,
                        contentColor = if (module.enabled) primaryColor else Color.White,
                    ),
                ) {
                    Text(if (module.enabled) "Desactivar" else "Activar")
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
