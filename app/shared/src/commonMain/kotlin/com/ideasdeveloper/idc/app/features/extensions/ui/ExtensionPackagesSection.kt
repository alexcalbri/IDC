package com.ideasdeveloper.idc.app.features.extensions.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.ideasdeveloper.idc.app.core.layout.AdaptiveGrid
import com.ideasdeveloper.idc.app.core.layout.ContentWidth
import com.ideasdeveloper.idc.app.core.theme.AppTheme
import com.ideasdeveloper.idc.app.core.ui.CopyableCode
import com.ideasdeveloper.idc.app.features.extensions.data.ExtensionPackageInspection
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.platform.LocalUriHandler
import com.ideasdeveloper.idc.app.core.theme.appFieldColors
import com.ideasdeveloper.idc.app.core.theme.accentButtonColors
import com.ideasdeveloper.idc.app.features.auth.data.LoginResponse
import com.ideasdeveloper.idc.app.features.company.data.CompanyException
import com.ideasdeveloper.idc.app.features.extensions.data.ExtensionApi
import com.ideasdeveloper.idc.app.features.extensions.data.ExtensionPackageEntry
import com.ideasdeveloper.idc.app.features.shell.ui.CollapsibleSection
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

// Una accion pendiente de confirmar sobre un paquete.
private sealed interface PackageDialog {
    data class Install(val entry: ExtensionPackageEntry) : PackageDialog
    data class Remove(val entry: ExtensionPackageEntry) : PackageDialog
    data class AuthorizeUnsigned(val entry: ExtensionPackageEntry) : PackageDialog
}

// Estado de un paquete en palabras para el usuario.
private fun packageStatusLabel(entry: ExtensionPackageEntry): String = when (entry.status) {
    "installing" -> "Instalando"
    "error" -> "Error al instalar"
    "pending_restart" -> "Instalado, pendiente de reinicio"
    "installed" -> "Instalado"
    "removed" -> "Quitado"
    else -> "Disponible"
}

/**
 * Seccion "Paquetes de extensiones" del servidor (solo server owner): catalogo, instalar, quitar y
 * autorizar un paquete sin firma. Se carga al abrirse y consulta el estado mientras se compila.
 */
@Composable
fun ExtensionPackagesSection(
    serverUrl: String?,
    session: LoginResponse?,
    modifier: Modifier = Modifier,
) {
    val colors = AppTheme.colors
    CollapsibleSection(
        title = "Paquetes de extensiones",
        color = colors.accentText,
        modifier = modifier,
        subtitle = "Catalogo de extensiones externas del servidor",
    ) {
        ExtensionPackagesContent(serverUrl, session)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ExtensionPackagesContent(serverUrl: String?, session: LoginResponse?) {
    val colors = AppTheme.colors
    val scope = rememberCoroutineScope()
    val token = session?.accessToken
    var entries by remember { mutableStateOf<List<ExtensionPackageEntry>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }
    var busyId by remember { mutableStateOf<String?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var dialog by remember { mutableStateOf<PackageDialog?>(null) }
    // Cambia para recargar el catalogo (boton Actualizar o tras una accion).
    var reloadTick by remember { mutableStateOf(0) }
    val canWork = serverUrl != null && !token.isNullOrBlank()
    val compiling = entries.any { it.status == "installing" }

    // Un solo cliente HTTP por seccion (tambien para el sondeo); se cierra al salir o si cambia el servidor.
    val api = remember(serverUrl) { runCatching { ExtensionApi(serverUrl.orEmpty()) }.getOrNull() }
    DisposableEffect(api) { onDispose { api?.close() } }

    // Ejecuta una llamada con el cliente compartido de la seccion.
    suspend fun <T> withApi(block: suspend ExtensionApi.(String) -> T): T = (api ?: error("URL del servidor invalida")).block(token.orEmpty())

    LaunchedEffect(serverUrl, token, reloadTick) {
        if (!canWork) return@LaunchedEffect
        loading = true
        try {
            entries = withApi { packageCatalog(it) }
            error = null
        } catch (exception: CompanyException) {
            error = exception.message
        } finally {
            loading = false
        }
    }

    // Mientras un paquete se compila se consulta cada pocos segundos; si el servidor se reinicia, se reintenta.
    LaunchedEffect(compiling, serverUrl, token) {
        if (!compiling || !canWork) return@LaunchedEffect
        repeat(120) {
            delay(4_000)
            try {
                val latest = withApi { packageCatalog(it) }
                entries = latest
                if (latest.none { it.status == "installing" }) return@LaunchedEffect
            } catch (_: CompanyException) {
                // El servidor puede estar reiniciandose para cargar el paquete.
            }
        }
    }

    fun act(id: String, success: String, block: suspend ExtensionApi.(String) -> Unit) {
        busyId = id
        error = null
        message = null
        scope.launch {
            try {
                withApi(block)
                message = success
                reloadTick += 1
            } catch (exception: CompanyException) {
                error = exception.message
            } finally {
                busyId = null
            }
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(
            "Los paquetes vienen del catalogo de extensiones configurado en el servidor. Un paquete firmado se instala directamente; uno sin firma necesita tu autorizacion expresa.",
            color = colors.muted,
            modifier = Modifier.widthIn(max = ContentWidth.Form),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Button(
                enabled = canWork && !loading,
                onClick = { reloadTick += 1 },
                colors = ButtonDefaults.buttonColors(containerColor = colors.surfaceAlt, contentColor = colors.accentText),
            ) { Text("Actualizar catalogo") }
            if (loading) Text("Cargando paquetes...", color = colors.muted)
        }
        error?.let { Text(it, color = colors.danger) }
        message?.let { Text(it, color = colors.success) }
        if (!loading && entries.isEmpty() && error == null) Text("El catalogo no tiene paquetes.", color = colors.muted)

        AdaptiveGrid(modifier = Modifier.fillMaxWidth(), minItemWidth = 420.dp, gap = 12.dp, equalHeight = false) {
            entries.forEach { entry ->
                key(entry.id) {
                    PackageCard(
                        entry = entry,
                        canWork = canWork,
                        busy = busyId == entry.id || entry.status == "installing",
                        onAuthorizeUnsigned = { dialog = PackageDialog.AuthorizeUnsigned(entry) },
                        onInstall = { dialog = PackageDialog.Install(entry) },
                        onRemove = { dialog = PackageDialog.Remove(entry) },
                    )
                }
            }
        }
    }

    when (val current = dialog) {
        is PackageDialog.Install -> AlertDialog(
            onDismissRequest = { dialog = null },
            title = { Text("Instalar ${current.entry.displayName}") },
            text = { Text("El servidor se reiniciara para cargar el paquete. Durante unos segundos nadie podra usar la app.", color = colors.text) },
            confirmButton = {
                TextButton(onClick = {
                    dialog = null
                    act(current.entry.id, "Instalando ${current.entry.displayName}: el servidor descarga y compila el paquete y luego se reinicia.") { installPackage(it, current.entry.id) }
                }) { Text("Instalar") }
            },
            dismissButton = { TextButton(onClick = { dialog = null }) { Text("Cancelar") } },
        )
        is PackageDialog.Remove -> AlertDialog(
            onDismissRequest = { dialog = null },
            title = { Text("Quitar ${current.entry.displayName}") },
            text = { Text("El paquete deja de cargarse la proxima vez que arranque el servidor. Los datos de las empresas no se tocan.", color = colors.text) },
            confirmButton = {
                TextButton(onClick = {
                    dialog = null
                    act(current.entry.id, "${current.entry.displayName} quitada del servidor.") { removePackage(it, current.entry.id) }
                }) { Text("Quitar", color = colors.danger) }
            },
            dismissButton = { TextButton(onClick = { dialog = null }) { Text("Cancelar") } },
        )
        is PackageDialog.AuthorizeUnsigned -> AuthorizeUnsignedDialog(
            entry = current.entry,
            inspect = { withApi { inspectPackage(it, current.entry.id) } },
            authorize = { text, sha, commit -> withApi { authorizeUnsigned(it, current.entry.id, text, sha, commit) } },
            onAuthorized = {
                dialog = null
                message = "Paquete ${current.entry.displayName} autorizado sin firma. Ya puedes instalarlo."
                error = null
                reloadTick += 1
            },
            onDismiss = { dialog = null },
        )
        null -> Unit
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
// Tarjeta de un paquete del catalogo con su estado y las acciones que admite.
private fun PackageCard(
    entry: ExtensionPackageEntry,
    canWork: Boolean,
    busy: Boolean,
    onAuthorizeUnsigned: () -> Unit,
    onInstall: () -> Unit,
    onRemove: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = AppTheme.colors
    val installed = entry.status == "installed" || entry.status == "pending_restart"
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(colors.surfaceAlt, MaterialTheme.shapes.small)
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(entry.displayName.ifBlank { entry.id }, style = MaterialTheme.typography.titleSmall, color = colors.accentText, modifier = Modifier.weight(1f))
            Surface(
                shape = RoundedCornerShape(50),
                color = colors.surface,
                border = BorderStroke(1.5.dp, if (entry.signed) colors.success else colors.warning),
            ) {
                Text(
                    if (entry.signed) "Firmado" else "Sin firma",
                    style = MaterialTheme.typography.labelMedium,
                    color = colors.text,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                )
            }
        }
        Text(entry.description, color = colors.muted)
        Text(
            "Modulo ${entry.module} · Version ${entry.version}" + (entry.installedVersion?.let { " · Instalada: $it" } ?: ""),
            style = MaterialTheme.typography.bodySmall,
            color = colors.muted,
        )
        if (entry.permissions.isNotEmpty()) {
            Text("Permisos: ${entry.permissions.joinToString()}", style = MaterialTheme.typography.bodySmall, color = colors.muted)
        }
        if (entry.commit.isNotBlank()) {
            Text("Commit ${entry.commit.take(8)}", style = MaterialTheme.typography.bodySmall, color = colors.muted)
        }
        Text(
            "Estado: ${packageStatusLabel(entry)}" + (entry.message?.takeIf { it.isNotBlank() }?.let { " - $it" } ?: ""),
            style = MaterialTheme.typography.bodySmall,
            color = if (entry.status == "error") colors.danger else colors.text,
        )
        if (entry.status == "pending_restart" || entry.restartRequired) {
            Text("Reinicia el servidor para cargar el paquete.", style = MaterialTheme.typography.bodySmall, color = colors.warning)
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End), verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            if (!entry.signed && !installed) {
                Button(
                    enabled = canWork && !busy,
                    onClick = { onAuthorizeUnsigned() },
                    colors = ButtonDefaults.buttonColors(containerColor = colors.surface, contentColor = colors.warning),
                    border = BorderStroke(1.dp, colors.warning),
                ) { Text("Autorizar paquete sin firma") }
            }
            if (!installed) {
                Button(
                    enabled = canWork && !busy,
                    onClick = { onInstall() },
                    colors = accentButtonColors(),
                ) { Text(if (entry.status == "installing") "Instalando..." else if (entry.status == "error") "Reintentar" else "Instalar") }
            } else {
                Button(
                    enabled = canWork && !busy,
                    onClick = { onRemove() },
                    colors = ButtonDefaults.buttonColors(containerColor = colors.surface, contentColor = colors.danger),
                ) { Text("Quitar") }
            }
        }
    }
}

@Composable
/**
 * Autorizar un paquete sin firma: primero se descarga y se inspecciona (commit y hash reales), y la
 * frase de confirmacion lleva los primeros 8 caracteres del hash para que se confirme lo revisado.
 */
private fun AuthorizeUnsignedDialog(
    entry: ExtensionPackageEntry,
    inspect: suspend () -> ExtensionPackageInspection,
    authorize: suspend (text: String, sha256: String, commit: String) -> Unit,
    onAuthorized: () -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = AppTheme.colors
    val scope = rememberCoroutineScope()
    val uriHandler = LocalUriHandler.current
    var inspection by remember(entry.id) { mutableStateOf<ExtensionPackageInspection?>(null) }
    var loading by remember(entry.id) { mutableStateOf(true) }
    var busy by remember(entry.id) { mutableStateOf(false) }
    var error by remember(entry.id) { mutableStateOf<String?>(null) }
    var notice by remember(entry.id) { mutableStateOf<String?>(null) }
    var typed by remember(entry.id) { mutableStateOf("") }
    // Cambia para volver a inspeccionar (al abrir y cuando el paquete cambio).
    var inspectTick by remember(entry.id) { mutableStateOf(0) }

    LaunchedEffect(entry.id, inspectTick) {
        loading = true
        inspection = null
        typed = ""
        try {
            inspection = inspect()
            error = null
        } catch (exception: CompanyException) {
            error = exception.message
        } finally {
            loading = false
        }
    }

    val current = inspection
    val phrase = current?.let { "autorizar ${entry.id} ${it.packageSha256.take(8).lowercase()}" }.orEmpty()
    val blocked = current == null || current.signatureProblem != null || current.signed
    val matches = !blocked && typed.trim().equals(phrase, ignoreCase = true)

    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text("Autorizar paquete sin firma") },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(
                    "${entry.displayName} no tiene una firma valida, asi que nadie garantiza su origen. Una vez instalado, su codigo corre con los privilegios del servidor: puede leer y escribir datos de todas las empresas.",
                    color = colors.text,
                )
                Text("Autoriza solo paquetes que hayas revisado y de una fuente de confianza.", color = colors.warning)
                if (loading) Text("Descargando y revisando el paquete...", color = colors.muted)
                if (current != null) {
                    Text("Esto es lo que se descargo ahora:", color = colors.text, style = MaterialTheme.typography.titleSmall)
                    Text("Commit ${current.commit.take(8)}", color = colors.text)
                    if (current.commitUrl.isNotBlank()) {
                        TextButton(onClick = { runCatching { uriHandler.openUri(current.commitUrl) } }) {
                            Text("Abrir el commit en el navegador", color = colors.accentText)
                        }
                        CopyableCode(label = "Enlace", code = current.commitUrl)
                    }
                    Text("Hash ${current.packageSha256.take(12)}...", color = colors.text)
                    CopyableCode(label = "Hash completo", code = current.packageSha256)
                    if (current.signed) {
                        Text("El paquete esta firmado: no necesita autorizacion.", color = colors.success)
                    }
                    current.signatureProblem?.let {
                        Text("El paquete trae una firma que no vale ($it). No se puede autorizar.", color = colors.danger)
                    }
                    if (!blocked) {
                        Text("Para confirmar escribe: $phrase", color = colors.text)
                        OutlinedTextField(
                            value = typed,
                            onValueChange = { typed = it },
                            label = { Text("Texto de confirmacion") },
                            singleLine = true,
                            enabled = !busy,
                            colors = appFieldColors(),
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
                notice?.let { Text(it, color = colors.warning) }
                error?.let { Text(it, color = colors.danger) }
            }
        },
        confirmButton = {
            TextButton(
                enabled = matches && !busy,
                onClick = {
                    val reviewed = current ?: return@TextButton
                    busy = true
                    error = null
                    scope.launch {
                        try {
                            authorize(typed.trim(), reviewed.packageSha256, reviewed.commit)
                            onAuthorized()
                        } catch (exception: CompanyException) {
                            if (exception.status == 409) {
                                // PACKAGE_CHANGED: lo descargado ya no es lo revisado; se inspecciona otra vez.
                                notice = "El paquete cambio desde que lo revisaste; revisalo de nuevo."
                                inspectTick += 1
                            } else {
                                error = exception.message
                            }
                            busy = false
                        }
                    }
                },
            ) { Text(if (busy) "Autorizando..." else "Autorizar", color = if (matches) colors.danger else colors.muted) }
        },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !busy) { Text("Cancelar") } },
    )
}
