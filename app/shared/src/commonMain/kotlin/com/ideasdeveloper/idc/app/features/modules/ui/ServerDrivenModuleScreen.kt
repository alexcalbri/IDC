package com.ideasdeveloper.idc.app.features.modules.ui

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
import androidx.compose.material3.Checkbox
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.ideasdeveloper.idc.app.core.company.CompanyIdentityStore
import com.ideasdeveloper.idc.app.features.auth.data.LoginResponse
import com.ideasdeveloper.idc.app.features.modules.data.CreateCustomerFieldRequest
import com.ideasdeveloper.idc.app.features.modules.data.CreateCustomerRequest
import com.ideasdeveloper.idc.app.features.modules.data.CustomerFieldResponse
import com.ideasdeveloper.idc.app.features.modules.data.CustomerResponse
import com.ideasdeveloper.idc.app.features.modules.data.ModuleApi
import com.ideasdeveloper.idc.app.features.modules.data.ModuleDefinition
import com.ideasdeveloper.idc.app.features.modules.data.ModuleException
import com.ideasdeveloper.idc.app.features.shell.ui.AuthenticatedTopBar
import com.ideasdeveloper.idc.app.features.shell.ui.toComposeColor
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

@Composable
// Renderiza un modulo generico usando metadata servida por el backend.
fun ServerDrivenModuleScreen(
    moduleId: String,
    serverUrl: String?,
    session: LoginResponse?,
    fallbackTitle: String,
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
    var metadata by remember(moduleId) { mutableStateOf<ModuleDefinition?>(null) }
    var error by remember(moduleId) { mutableStateOf<String?>(null) }
    var loading by remember(moduleId) { mutableStateOf(false) }

    LaunchedEffect(moduleId, serverUrl) {
        val activeServerUrl = serverUrl
        if (activeServerUrl == null) {
            error = "No hay URL de servidor configurada."
            return@LaunchedEffect
        }
        loading = true
        error = null
        try {
            metadata = ModuleApi(activeServerUrl).use { it.metadata(moduleId) }
        } catch (exception: ModuleException) {
            error = exception.message
        } finally {
            loading = false
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Brush.horizontalGradient(listOf(primaryColor, secondaryColor)))
    ) {
        AuthenticatedTopBar(
            title = metadata?.displayName ?: fallbackTitle,
            subtitle = metadata?.views?.firstOrNull()?.title,
            primaryColor = primaryColor,
            onReturnToDashboard = onReturnToDashboard,
            onSettings = onSettings,
            onLogout = onLogout,
            modifier = Modifier.align(Alignment.TopCenter),
        )

        Surface(
            modifier = Modifier
                .fillMaxWidth(0.9f)
                .align(Alignment.Center)
                .verticalScroll(rememberScrollState()),
            color = Color.White,
            shape = MaterialTheme.shapes.medium,
        ) {
            Column(
                modifier = Modifier.padding(18.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                when {
                    loading -> CircularProgressIndicator(color = primaryColor)
                    error != null -> Text(error.orEmpty(), color = Color(0xFFB00020))
                    metadata != null && moduleId == "clientes" -> CustomerServerDrivenContent(
                        serverUrl = serverUrl,
                        session = session,
                        primaryColor = primaryColor,
                    )
                    metadata != null -> {
                        Text(
                            text = metadata?.description.orEmpty(),
                            color = Color(0xFF333333),
                            fontWeight = FontWeight.SemiBold,
                        )
                        metadata?.views.orEmpty().forEach { view ->
                            Text("${view.title} (${view.kind})", color = Color(0xFF666666))
                        }
                    }
                }
                Spacer(modifier = Modifier.height(8.dp))
                Button(
                    onClick = onReturnToDashboard,
                    colors = ButtonDefaults.buttonColors(containerColor = primaryColor),
                ) {
                    Text("Volver al panel")
                }
            }
        }
    }
}

@Composable
private fun CustomerServerDrivenContent(
    serverUrl: String?,
    session: LoginResponse?,
    primaryColor: Color,
) {
    val scope = rememberCoroutineScope()
    var customers by remember { mutableStateOf<List<CustomerResponse>>(emptyList()) }
    var fields by remember { mutableStateOf<List<CustomerFieldResponse>>(emptyList()) }
    var editingId by remember { mutableStateOf<String?>(null) }
    var name by remember { mutableStateOf("") }
    var email by remember { mutableStateOf("") }
    var phone by remember { mutableStateOf("") }
    var dynamicValues by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    var newFieldKey by remember { mutableStateOf("") }
    var newFieldLabel by remember { mutableStateOf("") }
    var newFieldRequired by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(false) }

    fun activeContext(): Triple<String, String, String>? {
        val activeServerUrl = serverUrl ?: return null
        val activeSession = session ?: return null
        val companyCode = activeSession.companyCode ?: return null
        return Triple(activeServerUrl, activeSession.accessToken, companyCode)
    }

    fun loadAll() {
        val (activeServerUrl, token, companyCode) = activeContext() ?: return
        loading = true
        scope.launch {
            try {
                ModuleApi(activeServerUrl).use { api ->
                    fields = api.customerFields(token, companyCode).fields
                    customers = api.customers(token, companyCode).customers
                }
                error = null
            } catch (exception: ModuleException) {
                error = exception.message
            } finally {
                loading = false
            }
        }
    }

    fun clearForm() {
        editingId = null
        name = ""
        email = ""
        phone = ""
        dynamicValues = emptyMap()
    }

    fun request(): CreateCustomerRequest = CreateCustomerRequest(
        displayName = name,
        primaryEmail = email,
        primaryPhone = phone,
        dynamicFields = JsonObject(dynamicValues.mapValues { JsonPrimitive(it.value) }),
    )

    LaunchedEffect(serverUrl, session?.accessToken, session?.companyCode) {
        loadAll()
    }

    Text(if (editingId == null) "Crear cliente" else "Editar cliente", style = MaterialTheme.typography.titleMedium, color = primaryColor)
    OutlinedTextField(name, { name = it }, label = { Text("Nombre *") }, singleLine = true, modifier = Modifier.fillMaxWidth())
    OutlinedTextField(email, { email = it }, label = { Text("Correo *") }, singleLine = true, modifier = Modifier.fillMaxWidth())
    OutlinedTextField(phone, { phone = it }, label = { Text("Telefono *") }, singleLine = true, modifier = Modifier.fillMaxWidth())
    fields.forEach { field ->
        OutlinedTextField(
            value = dynamicValues[field.fieldKey].orEmpty(),
            onValueChange = { value -> dynamicValues = dynamicValues + (field.fieldKey to value) },
            label = { Text(field.label + if (field.required) " *" else "") },
            singleLine = field.fieldType != "textarea",
            modifier = Modifier.fillMaxWidth(),
        )
    }
    Button(
        enabled = !loading && name.isNotBlank() && email.isNotBlank() && phone.isNotBlank(),
        onClick = {
            val (activeServerUrl, token, companyCode) = activeContext() ?: return@Button
            loading = true
            message = null
            error = null
            scope.launch {
                try {
                    ModuleApi(activeServerUrl).use { api ->
                        val id = editingId
                        if (id == null) api.createCustomer(token, companyCode, request()) else api.updateCustomer(token, companyCode, id, request())
                    }
                    message = if (editingId == null) "Cliente creado." else "Cliente actualizado."
                    clearForm()
                    loadAll()
                } catch (exception: ModuleException) {
                    error = exception.message
                } finally {
                    loading = false
                }
            }
        },
        colors = ButtonDefaults.buttonColors(containerColor = primaryColor),
    ) { Text(if (editingId == null) "Crear cliente" else "Guardar cambios") }
    if (editingId != null) {
        Button(onClick = { clearForm() }, colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFE9E9EF), contentColor = primaryColor)) { Text("Cancelar edicion") }
    }

    Text("Campos dinamicos de Clientes", style = MaterialTheme.typography.titleMedium, color = primaryColor)
    OutlinedTextField(newFieldKey, { newFieldKey = it }, label = { Text("Clave del campo, ej: direccion") }, singleLine = true, modifier = Modifier.fillMaxWidth())
    OutlinedTextField(newFieldLabel, { newFieldLabel = it }, label = { Text("Etiqueta, ej: Direccion") }, singleLine = true, modifier = Modifier.fillMaxWidth())
    androidx.compose.foundation.layout.Row(verticalAlignment = Alignment.CenterVertically) {
        Checkbox(checked = newFieldRequired, onCheckedChange = { newFieldRequired = it })
        Text("Requerido")
    }
    Button(
        enabled = !loading && newFieldKey.isNotBlank() && newFieldLabel.isNotBlank(),
        onClick = {
            val (activeServerUrl, token, companyCode) = activeContext() ?: return@Button
            loading = true
            scope.launch {
                try {
                    ModuleApi(activeServerUrl).use { api ->
                        api.createCustomerField(token, companyCode, CreateCustomerFieldRequest(newFieldKey, newFieldLabel, "text", required = newFieldRequired))
                    }
                    newFieldKey = ""
                    newFieldLabel = ""
                    newFieldRequired = false
                    message = "Campo dinamico creado."
                    loadAll()
                } catch (exception: ModuleException) {
                    error = exception.message
                } finally {
                    loading = false
                }
            }
        },
        colors = ButtonDefaults.buttonColors(containerColor = primaryColor),
    ) { Text("Agregar campo") }

    message?.let { Text(it, color = Color(0xFF176B3A)) }
    error?.let { Text(it, color = Color(0xFFB00020)) }
    Text("Clientes", style = MaterialTheme.typography.titleMedium, color = primaryColor)
    if (customers.isEmpty()) {
        Text("Aun no hay clientes.", color = Color(0xFF666666))
    } else {
        customers.forEach { customer ->
            Column(modifier = Modifier.fillMaxWidth().background(Color(0xFFF6F6FA), MaterialTheme.shapes.small).padding(10.dp)) {
                Text("${customer.displayName} - ${customer.primaryEmail} - ${customer.primaryPhone}", color = Color(0xFF333333), fontWeight = FontWeight.SemiBold)
                androidx.compose.foundation.layout.Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = {
                        editingId = customer.id
                        name = customer.displayName
                        email = customer.primaryEmail
                        phone = customer.primaryPhone
                        dynamicValues = customer.dynamicFields.mapValues { it.value.toString().trim('"') }
                    }, colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFE9E9EF), contentColor = primaryColor)) { Text("Editar") }
                    Button(onClick = {
                        val (activeServerUrl, token, companyCode) = activeContext() ?: return@Button
                        loading = true
                        scope.launch {
                            try {
                                ModuleApi(activeServerUrl).use { api -> api.deleteCustomer(token, companyCode, customer.id) }
                                message = "Cliente eliminado."
                                if (editingId == customer.id) clearForm()
                                loadAll()
                            } catch (exception: ModuleException) {
                                error = exception.message
                            } finally {
                                loading = false
                            }
                        }
                    }, colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFB00020))) { Text("Eliminar") }
                }
            }
        }
    }
}
private suspend inline fun <T> ModuleApi.use(block: suspend (ModuleApi) -> T): T {
    return try {
        block(this)
    } finally {
        close()
    }
}
