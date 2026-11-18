package com.ideasdeveloper.idc.app.features.modules.ui

import com.ideasdeveloper.idc.app.core.theme.accentButtonColors
import com.ideasdeveloper.idc.app.core.version.ClientVersion
import com.ideasdeveloper.idc.app.core.version.isAppOlderThan
import androidx.compose.foundation.layout.widthIn
import com.ideasdeveloper.idc.app.core.layout.AdaptiveLayout
import com.ideasdeveloper.idc.app.core.layout.ContentWidth
import com.ideasdeveloper.idc.app.core.layout.contentWidth
import com.ideasdeveloper.idc.app.features.shell.ui.CollapsibleSection
import com.ideasdeveloper.idc.app.core.theme.AppTheme
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import com.ideasdeveloper.idc.app.core.ui.CopyableCode
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonColors
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import com.ideasdeveloper.idc.app.core.ui.DateFieldPicker
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.ideasdeveloper.idc.app.core.company.CompanyIdentityStore
import com.ideasdeveloper.idc.app.features.company.data.CompanyApi
import com.ideasdeveloper.idc.app.features.company.data.CompanyException
import com.ideasdeveloper.idc.app.features.company.data.CompanySwitch
import com.ideasdeveloper.idc.app.features.company.data.OwnedCompany
import com.ideasdeveloper.idc.app.features.company.data.SwitchTargetsResponse
import com.ideasdeveloper.idc.app.features.auth.data.LoginResponse
import com.ideasdeveloper.idc.app.features.modules.data.CreateCustomerFieldRequest
import com.ideasdeveloper.idc.app.features.modules.data.CreateCustomerRequest
import com.ideasdeveloper.idc.app.features.modules.data.CustomerFieldResponse
import com.ideasdeveloper.idc.app.features.modules.data.CustomerResponse
import com.ideasdeveloper.idc.app.features.modules.data.ModuleApi
import com.ideasdeveloper.idc.app.features.modules.data.ModuleDefinition
import com.ideasdeveloper.idc.app.features.modules.data.ModuleException
import com.ideasdeveloper.idc.app.features.shell.ui.AuthenticatedScreen
import com.ideasdeveloper.idc.app.features.shell.ui.AuthenticatedTopBar
import com.ideasdeveloper.idc.app.features.shell.ui.ClosableView
import com.ideasdeveloper.idc.app.features.shell.ui.TopBarMenuItem
import com.ideasdeveloper.idc.app.features.shell.ui.toComposeColor
import kotlinx.coroutines.launch
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.ideasdeveloper.idc.ui.ModuleUiEntry
import com.ideasdeveloper.idc.ui.UiNavigate
import com.ideasdeveloper.idc.ui.UiOpenModule
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

// Tipos de campo dinamico de Clientes que acepta el servidor (clave del servidor, nombre en pantalla).
private val customerFieldTypes = listOf(
    "text" to "Texto",
    "textarea" to "Texto largo",
    "number" to "Numero",
    "date" to "Fecha",
    "datetime" to "Fecha y hora",
    "boolean" to "Si/No",
)

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
    onCompanySwitched: () -> Unit,
    modifier: Modifier = Modifier,
    // Abre una pantalla de otro modulo (UiOpenModule), por ejemplo el lead del CRM de un cliente.
    onOpenModule: (UiOpenModule) -> Unit = {},
    // Abre la pantalla nativa de extensiones de un modulo (entrada de su Configuracion).
    onOpenExtensions: (String) -> Unit = {},
) {
    val companyIdentity = remember { CompanyIdentityStore.current() }
    // Pantalla pedida por otro modulo al abrir este (ModuleDeepLinks).
    val deepLink = remember(moduleId) { ModuleDeepLinks.take(moduleId) }
    val colors = AppTheme.colors
    val primaryColor = colors.accent
    var metadata by remember(moduleId) { mutableStateOf<ModuleDefinition?>(null) }
    var error by remember(moduleId) { mutableStateOf<String?>(null) }
    var loading by remember(moduleId) { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val switchCompanyCode = session?.companyCode
    val canSwitchCompany = session?.role == "server_owner" ||
        (session?.role == "business_owner" && switchCompanyCode != null)
    var switchTargets by remember(serverUrl, session?.accessToken, switchCompanyCode) {
        mutableStateOf<SwitchTargetsResponse?>(null)
    }
    var switchMessage by remember { mutableStateOf<String?>(null) }
    var switchingCompany by remember { mutableStateOf(false) }

    LaunchedEffect(serverUrl, session?.accessToken, switchCompanyCode, canSwitchCompany) {
        switchTargets = null
        switchMessage = null
        if (!canSwitchCompany) return@LaunchedEffect
        val activeServerUrl = serverUrl ?: return@LaunchedEffect
        val activeSession = session ?: return@LaunchedEffect
        val api = CompanyApi(activeServerUrl)
        try {
            switchTargets = api.switchTargets(activeSession.accessToken, switchCompanyCode)
        } catch (exception: CompanyException) {
            switchMessage = exception.message ?: "No se pudieron cargar las empresas."
        } finally {
            api.close()
        }
    }

    fun switchTo(target: OwnedCompany?) {
        val activeServerUrl = serverUrl ?: return
        val activeSession = session ?: return
        switchingCompany = true
        switchMessage = "Entrando a ${target?.name ?: switchTargets?.serverName ?: "el servidor"}..."
        scope.launch {
            val failure = CompanySwitch.switchTo(activeServerUrl, activeSession, target)
            switchingCompany = false
            switchMessage = failure
            if (failure == null) onCompanySwitched()
        }
    }

    val titleMenu = switchTargets?.takeIf { !switchingCompany }?.let { targets ->
        buildList {
            targets.serverName?.let { name ->
                add(TopBarMenuItem(name, caption = "Servidor", current = switchCompanyCode == null) { switchTo(null) })
            }
            targets.companies.forEach { company ->
                add(TopBarMenuItem(company.name, caption = company.code, current = company.code == switchCompanyCode) { switchTo(company) })
            }
        }.takeIf { items -> items.any { !it.current } }
    }.orEmpty()

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

    val definition = metadata
    val ui = definition?.ui
    // El server_owner usa los modulos en el espacio del servidor.
    val companyCode = session?.companyCode ?: session?.workspaceCompanyCode
    val callContext = remember(serverUrl, session?.accessToken, companyCode) {
        if (serverUrl != null && session != null && companyCode != null) ModuleCallContext(serverUrl, session.accessToken, companyCode) else null
    }

    // El modulo pide una app mas nueva que esta: se avisa en lugar de mostrar su interfaz.
    val appTooOld = remember(definition?.minAppVersion) { isAppOlderThan(definition?.minAppVersion) }
    if (definition != null && appTooOld) {
        AuthenticatedScreen(
            modifier = modifier.background(colors.screenBrush),
            topBar = {
                AuthenticatedTopBar(
                    title = definition.displayName,
                    subtitle = switchMessage ?: definition.description,
                    primaryColor = primaryColor,
                    onReturnToDashboard = onReturnToDashboard,
                    onSettings = onSettings,
                    onLogout = onLogout,
                    titleMenu = titleMenu,
                )
            },
        ) {
            Column(
                modifier = Modifier
                    .align(Alignment.Center)
                    .padding(16.dp)
                    .contentWidth(ContentWidth.Max, 0.9f)
                    .background(colors.surface, RoundedCornerShape(18.dp))
                    .padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    text = "Este modulo necesita la app ${definition.minAppVersion} o superior. Tu app es $ClientVersion. Actualiza la app.",
                    style = MaterialTheme.typography.titleMedium,
                    color = colors.text,
                )
                Button(onClick = onReturnToDashboard, colors = accentButtonColors()) {
                    Text("Volver al inicio")
                }
            }
        }
        return
    }

    if (ui != null && callContext != null && (ui.type == "native" || ui.type == "web")) {
        // Los modulos con interfaz propia ocupan toda la pantalla debajo de la barra superior.
        AuthenticatedScreen(
            modifier = modifier.background(colors.screenBrush),
            topBar = {
                AuthenticatedTopBar(
                    title = definition?.displayName ?: fallbackTitle,
                    subtitle = switchMessage ?: definition?.description,
                    primaryColor = primaryColor,
                    onReturnToDashboard = onReturnToDashboard,
                    onSettings = onSettings,
                    onLogout = onLogout,
                    titleMenu = titleMenu,
                )
            },
        ) {
            // El modulo se muestra como un panel con la X en su esquina superior derecha.
            ClosableView(
                onClose = onReturnToDashboard,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .fillMaxHeight()
                    // Todo el ancho menos el margen: el pipeline muestra tantas etapas como quepan.
                    .fillMaxWidth()
                    .padding(
                        start = if (AdaptiveLayout.roomy) 12.dp else 16.dp,
                        end = if (AdaptiveLayout.roomy) 12.dp else 16.dp,
                        bottom = if (AdaptiveLayout.roomy) 12.dp else 16.dp,
                    ),
            ) {
                val panel = Modifier.fillMaxSize().clip(RoundedCornerShape(18.dp))
                if (ui.type == "native") {
                    NativeModuleHost(ui, callContext, primaryColor, panel, initial = deepLink, onOpenModule = onOpenModule, onOpenExtensions = onOpenExtensions)
                } else {
                    // Los navegadores embebidos se dibujan encima de Compose: se deja aire para que no tapen la X.
                    WebModuleHost(moduleId, callContext, primaryColor, Modifier.padding(top = 20.dp).then(panel))
                }
            }
        }
        return
    }

    AuthenticatedScreen(
        modifier = modifier.background(colors.screenBrush),
        topBar = {
            AuthenticatedTopBar(
                title = metadata?.displayName ?: fallbackTitle,
                subtitle = switchMessage ?: metadata?.views?.firstOrNull()?.title,
                primaryColor = primaryColor,
                onReturnToDashboard = onReturnToDashboard,
                onSettings = onSettings,
                onLogout = onLogout,
                titleMenu = titleMenu,
            )
        },
    ) {
        // La vista empieza debajo de la barra superior y lleva la X en su esquina superior derecha.
        ClosableView(
            onClose = onReturnToDashboard,
            modifier = Modifier
                .align(Alignment.Center)
                .padding(bottom = 16.dp)
                .contentWidth(ContentWidth.Max, 0.9f),
        ) {
            // Clientes dibuja una lista larga: la LazyColumn hace el desplazamiento, sin scroll exterior anidado.
            val customersList = !loading && error == null && metadata != null && moduleId == "clientes"
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .then(if (customersList) Modifier else Modifier.verticalScroll(rememberScrollState())),
                color = AppTheme.colors.surface,
                shape = MaterialTheme.shapes.medium,
            ) {
                if (customersList) CustomerServerDrivenContent(
                    serverUrl = serverUrl,
                    session = session,
                    primaryColor = primaryColor,
                    onOpenModule = onOpenModule,
                    onReturnToDashboard = onReturnToDashboard,
                    // "Abrir ficha" desde otro modulo (el lead del CRM) abre la ficha del cliente.
                    initialProfileId = deepLink?.takeIf { it.screen == "customer" }?.params?.get("id"),
                ) else Column(
                    modifier = Modifier.padding(AdaptiveLayout.screenPadding - 2.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    when {
                        loading -> CircularProgressIndicator(color = AppTheme.colors.accentText)
                        error != null -> Text(error.orEmpty(), color = AppTheme.colors.danger)
                        metadata != null && ui != null && callContext == null -> Text(
                            "Este modulo requiere iniciar sesion en una empresa.",
                            color = AppTheme.colors.danger,
                        )
                        metadata != null && ui != null -> Text(
                            "Este modulo usa un tipo de interfaz (${ui.type}) que esta version de la app no conoce.",
                            color = AppTheme.colors.muted,
                        )
                        metadata != null -> {
                            Text(metadata?.description.orEmpty(), color = AppTheme.colors.text, fontWeight = FontWeight.SemiBold)
                            Text("Este modulo no declara una interfaz para la app.", color = AppTheme.colors.muted)
                        }
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    Button(
                        onClick = onReturnToDashboard,
                        colors = accentButtonColors(),
                    ) {
                        Text("Volver al panel")
                    }
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
    onOpenModule: (UiOpenModule) -> Unit,
    onReturnToDashboard: () -> Unit,
    initialProfileId: String? = null,
) {
    val scope = rememberCoroutineScope()
    var customers by remember { mutableStateOf<List<CustomerResponse>>(emptyList()) }
    var fields by remember { mutableStateOf<List<CustomerFieldResponse>>(emptyList()) }
    var editingId by remember { mutableStateOf<String?>(null) }
    var name by remember { mutableStateOf("") }
    var email by remember { mutableStateOf("") }
    var phone by remember { mutableStateOf("") }
    var dynamicValues by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    // Claves que el usuario edito y valores originales del cliente (JSON intacto) para no corromper los que no toco.
    var dynamicEdited by remember { mutableStateOf<Set<String>>(emptySet()) }
    var originalDynamic by remember { mutableStateOf(JsonObject(emptyMap())) }
    var newFieldKey by remember { mutableStateOf("") }
    var newFieldLabel by remember { mutableStateOf("") }
    var newFieldRequired by remember { mutableStateOf(false) }
    var newFieldType by remember { mutableStateOf("text") }
    var message by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(false) }
    // Cliente cuya ficha (notas generales e historial por empresa) esta abierta.
    var profileCustomerId by remember { mutableStateOf(initialProfileId) }
    // Si el formulario de crear cliente esta abierto (al editar se abre solo).
    var customerFormOpen by remember { mutableStateOf(false) }

    fun activeContext(): Triple<String, String, String>? {
        val activeServerUrl = serverUrl ?: return null
        val activeSession = session ?: return null
        val companyCode = activeSession.companyCode ?: activeSession.workspaceCompanyCode ?: return null
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
        dynamicEdited = emptySet()
        originalDynamic = JsonObject(emptyMap())
    }

    // El servidor reemplaza todos los campos dinamicos al editar: las claves que el usuario no toco
    // se reenvian con su valor original (JSON intacto) y solo las editadas se codifican segun su tipo.
    fun request(): CreateCustomerRequest {
        val types = fields.associate { it.fieldKey to it.fieldType }
        val result = originalDynamic.toMutableMap()
        dynamicEdited.forEach { key ->
            val text = dynamicValues[key].orEmpty()
            result[key] = when (types[key]) {
                "boolean" -> JsonPrimitive(text == "true")
                "number" -> text.toLongOrNull()?.let { JsonPrimitive(it) } ?: text.toDoubleOrNull()?.let { JsonPrimitive(it) } ?: JsonPrimitive(text)
                else -> JsonPrimitive(text)
            }
        }
        return CreateCustomerRequest(displayName = name, primaryEmail = email, primaryPhone = phone, dynamicFields = JsonObject(result))
    }

    LaunchedEffect(serverUrl, session?.accessToken, session?.companyCode) {
        loadAll()
    }

    val secondaryButtonColors = ButtonDefaults.buttonColors(containerColor = AppTheme.colors.surfaceAlt, contentColor = AppTheme.colors.accentText)
    val dangerButtonColors = ButtonDefaults.buttonColors(containerColor = AppTheme.colors.danger)
    // Lambdas estables para las filas: llaman a la version mas reciente de lo que capturan.
    val currentDelete by rememberUpdatedState<(String) -> Unit> { customerId ->
        val (activeServerUrl, token, companyCode) = activeContext() ?: return@rememberUpdatedState
        loading = true
        scope.launch {
            try {
                ModuleApi(activeServerUrl).use { api -> api.deleteCustomer(token, companyCode, customerId) }
                message = "Cliente eliminado."
                if (editingId == customerId) clearForm()
                loadAll()
            } catch (exception: ModuleException) {
                error = exception.message
            } finally {
                loading = false
            }
        }
    }
    val onEditCustomer = remember {
        { customer: CustomerResponse ->
            editingId = customer.id
            name = customer.displayName
            email = customer.primaryEmail
            phone = customer.primaryPhone
            originalDynamic = customer.dynamicFields
            dynamicEdited = emptySet()
            // Solo los primitivos se pueden editar como texto; arrays y objetos se muestran de solo lectura.
            dynamicValues = customer.dynamicFields.mapNotNull { (key, element) ->
                (element as? JsonPrimitive)?.let { key to (it.contentOrNull ?: "") }
            }.toMap()
        }
    }
    // Fuera del item de la lista: si la seccion sale de pantalla y se descompone, sigue abierta o cerrada.
    var dynamicFieldsOpen by remember { mutableStateOf(false) }
    val onDeleteCustomer = remember { { customerId: String -> currentDelete(customerId) } }
    val onCustomerProfile = remember { { customerId: String -> profileCustomerId = customerId } }

    LazyColumn(
        modifier = Modifier.fillMaxWidth(),
        contentPadding = PaddingValues(AdaptiveLayout.screenPadding - 2.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        item {
            // El formulario empieza cerrado para ver la lista; Editar lo abre con los datos del cliente.
            CollapsibleSection(
                title = if (editingId == null) "Crear cliente" else "Editar cliente",
                color = AppTheme.colors.accentText,
                open = customerFormOpen || editingId != null,
                onToggle = { if (editingId != null) clearForm() else customerFormOpen = !customerFormOpen },
            ) {
                OutlinedTextField(name, { name = it }, label = { Text("Nombre *") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(email, { email = it }, label = { Text("Correo") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(phone, { phone = it }, label = { Text("Telefono") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                fields.forEach { field ->
                    val fieldLabel = field.label + if (field.required) " *" else ""
                    val fieldValue = dynamicValues[field.fieldKey].orEmpty()
                    val update = { value: String ->
                        dynamicValues = dynamicValues + (field.fieldKey to value)
                        dynamicEdited = dynamicEdited + field.fieldKey
                    }
                    val original = originalDynamic[field.fieldKey]
                    when {
                        original != null && original !is JsonPrimitive -> {
                            Text(field.label, color = AppTheme.colors.muted, style = MaterialTheme.typography.labelMedium)
                            Text(
                                (original as? kotlinx.serialization.json.JsonArray)?.joinToString(", ") { (it as? JsonPrimitive)?.contentOrNull ?: it.toString() } ?: original.toString(),
                                color = AppTheme.colors.text,
                            )
                        }
                        else -> when (field.fieldType) {
                        "date", "datetime" -> DateFieldPicker(
                            value = fieldValue,
                            onValueChange = update,
                            label = fieldLabel,
                            withTime = field.fieldType == "datetime",
                            textFieldColors = OutlinedTextFieldDefaults.colors(),
                            modifier = Modifier.fillMaxWidth(),
                        )
                        "boolean" -> androidx.compose.foundation.layout.Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(checked = fieldValue == "true", onCheckedChange = { update(it.toString()) })
                            Text(fieldLabel)
                        }
                        else -> OutlinedTextField(
                            value = fieldValue,
                            onValueChange = { value -> update(if (field.fieldType == "number") value.filter { it.isDigit() || it == '-' || it == '.' } else value) },
                            label = { Text(fieldLabel) },
                            singleLine = field.fieldType != "textarea",
                            minLines = if (field.fieldType == "textarea") 3 else 1,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        }
                    }
                }
                Button(
                    enabled = !loading && name.isNotBlank(),
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
                    colors = accentButtonColors(),
                ) { Text(if (editingId == null) "Crear cliente" else "Guardar cambios") }
                if (editingId != null) {
                    Button(onClick = { clearForm() }, colors = ButtonDefaults.buttonColors(containerColor = AppTheme.colors.surfaceAlt, contentColor = AppTheme.colors.accentText)) { Text("Cancelar edicion") }
                }
            }
        }
        item {
            // Se configuran una vez: empiezan cerrados.
            CollapsibleSection(
                "Campos dinamicos de Clientes",
                primaryColor,
                open = dynamicFieldsOpen,
                onToggle = { dynamicFieldsOpen = !dynamicFieldsOpen },
                subtitle = "Campos extra de la ficha de todos los clientes",
            ) {
                OutlinedTextField(newFieldKey, { newFieldKey = it }, label = { Text("Clave del campo, ej: direccion") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(newFieldLabel, { newFieldLabel = it }, label = { Text("Etiqueta, ej: Direccion") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                var typeMenuOpen by remember { mutableStateOf(false) }
                Text("Tipo de dato", color = AppTheme.colors.muted, style = MaterialTheme.typography.labelMedium)
                Box {
                    OutlinedButton(onClick = { typeMenuOpen = true }, modifier = Modifier.fillMaxWidth()) {
                        Text(
                            customerFieldTypes.firstOrNull { it.first == newFieldType }?.second ?: "Texto",
                            color = AppTheme.colors.text,
                            modifier = Modifier.weight(1f),
                        )
                        Text("v", color = AppTheme.colors.muted)
                    }
                    DropdownMenu(expanded = typeMenuOpen, onDismissRequest = { typeMenuOpen = false }) {
                        customerFieldTypes.forEach { (type, name) ->
                            DropdownMenuItem(text = { Text(name) }, onClick = { newFieldType = type; typeMenuOpen = false })
                        }
                    }
                }
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
                                    api.createCustomerField(token, companyCode, CreateCustomerFieldRequest(newFieldKey, newFieldLabel, newFieldType, required = newFieldRequired))
                                }
                                newFieldKey = ""
                                newFieldLabel = ""
                                newFieldRequired = false
                                newFieldType = "text"
                                message = "Campo dinamico creado."
                                loadAll()
                            } catch (exception: ModuleException) {
                                error = exception.message
                            } finally {
                                loading = false
                            }
                        }
                    },
                    colors = accentButtonColors(),
                ) { Text("Agregar campo") }
            }
        }
        message?.let { text -> item { Text(text, color = AppTheme.colors.success) } }
        error?.let { text -> item { Text(text, color = AppTheme.colors.danger) } }
        item { Text("Clientes", style = MaterialTheme.typography.titleMedium, color = AppTheme.colors.accentText) }
        if (customers.isEmpty()) {
            item { Text("Aun no hay clientes.", color = AppTheme.colors.muted) }
        } else {
            items(customers, key = { it.id }) { customer ->
                CustomerRow(
                    customer = customer,
                    secondaryButtonColors = secondaryButtonColors,
                    dangerButtonColors = dangerButtonColors,
                    onEdit = onEditCustomer,
                    onDelete = onDeleteCustomer,
                    onProfile = onCustomerProfile,
                )
            }
        }
        item { Spacer(modifier = Modifier.height(8.dp)) }
        item {
            Button(onClick = onReturnToDashboard, colors = accentButtonColors()) { Text("Volver al panel") }
        }
    }
    val profileId = profileCustomerId
    val context = activeContext()
    if (profileId != null && context != null) {
        CustomerProfileDialog(
            customerId = profileId,
            context = ModuleCallContext(context.first, context.second, context.third),
            primaryColor = primaryColor,
            onOpenModule = { action ->
                profileCustomerId = null
                onOpenModule(action)
            },
            onDismiss = {
                profileCustomerId = null
                loadAll()
            },
        )
    }
}

@Composable
private fun CustomerRow(
    customer: CustomerResponse,
    secondaryButtonColors: ButtonColors,
    dangerButtonColors: ButtonColors,
    onEdit: (CustomerResponse) -> Unit,
    onDelete: (String) -> Unit,
    onProfile: (String) -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth().background(AppTheme.colors.surfaceAlt, MaterialTheme.shapes.small).padding(10.dp)) {
        Text(listOf(customer.displayName, customer.primaryEmail, customer.primaryPhone).filter { it.isNotBlank() }.joinToString(" - ") + if (customer.customerType != "general") " (${customer.customerType})" else "", color = AppTheme.colors.text, fontWeight = FontWeight.SemiBold)
        if (customer.customerCode.isNotBlank()) {
            CopyableCode(label = "Codigo de cliente", code = customer.customerCode)
        }
        androidx.compose.foundation.layout.Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { onEdit(customer) }, colors = secondaryButtonColors) { Text("Editar") }
            Button(onClick = { onDelete(customer.id) }, colors = dangerButtonColors) { Text("Eliminar") }
            Button(onClick = { onProfile(customer.id) }, colors = secondaryButtonColors) { Text("Ficha") }
        }
    }
}

/**
 * Ficha del cliente: la dibuja el modulo Clientes desde el servidor (datos, compartir con las
 * empresas del mismo dueno, notas generales y una pestana de historial por empresa), asi que cambia
 * sin actualizar la app. Las entradas con detalle abren el registro en su modulo (el lead del CRM).
 */
@Composable
private fun CustomerProfileDialog(
    customerId: String,
    context: ModuleCallContext,
    primaryColor: Color,
    onOpenModule: (UiOpenModule) -> Unit,
    onDismiss: () -> Unit,
) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(
            modifier = Modifier
                .fillMaxWidth(0.95f)
                .fillMaxHeight(0.9f)
                .clip(RoundedCornerShape(18.dp))
                .background(AppTheme.colors.background),
        ) {
            ClosableView(onClose = onDismiss, modifier = Modifier.fillMaxSize()) {
                NativeModuleHost(
                    ui = ModuleUiEntry(type = "native", entry = "/modules/clientes/ui", home = "customer"),
                    context = context,
                    companyAccent = primaryColor,
                    modifier = Modifier.fillMaxSize(),
                    initial = UiNavigate("customer", mapOf("id" to customerId)),
                    onOpenModule = onOpenModule,
                )
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
