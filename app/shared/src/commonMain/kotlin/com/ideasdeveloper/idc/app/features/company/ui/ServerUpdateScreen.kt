package com.ideasdeveloper.idc.app.features.company.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.ideasdeveloper.idc.app.core.layout.AdaptiveLayout
import com.ideasdeveloper.idc.app.core.layout.ContentWidth
import com.ideasdeveloper.idc.app.core.layout.contentWidth
import com.ideasdeveloper.idc.app.core.theme.AppTheme
import com.ideasdeveloper.idc.app.core.theme.accentButtonColors
import com.ideasdeveloper.idc.app.features.auth.data.LoginResponse
import com.ideasdeveloper.idc.app.features.company.data.CompanyApi
import com.ideasdeveloper.idc.app.features.company.data.CompanyException
import com.ideasdeveloper.idc.app.features.company.data.CoreUpdateRequestDto
import com.ideasdeveloper.idc.app.features.company.data.CoreUpdateStatusResponse
import com.ideasdeveloper.idc.app.features.shell.ui.AuthenticatedScreen
import com.ideasdeveloper.idc.app.features.shell.ui.AuthenticatedTopBar
import com.ideasdeveloper.idc.app.features.shell.ui.ClosableView
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.time.Duration.Companion.minutes
import kotlin.time.TimeSource

// Segundos entre consultas mientras la actualizacion esta pendiente o en curso.
private const val PollIntervalMillis = 5_000L

// Tiempo maximo sin respuesta del servidor antes de dejar de consultar.
private val RestartTimeout = 5.minutes

private fun CoreUpdateRequestDto.isActive(): Boolean = status == "pending" || status == "running"

// Consulta el estado abriendo y cerrando un cliente propio.
private suspend fun fetchStatus(url: String, token: String): CoreUpdateStatusResponse {
    val api = CompanyApi(url)
    try {
        return api.coreUpdateStatus(token)
    } finally {
        api.close()
    }
}

@Composable
fun ServerUpdateScreen(
    serverUrl: String?,
    session: LoginResponse?,
    onSettings: () -> Unit,
    onLogout: () -> Unit,
    onReturnToDashboard: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = AppTheme.colors
    val scope = rememberCoroutineScope()
    var status by remember { mutableStateOf<CoreUpdateStatusResponse?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(false) }
    var requesting by remember { mutableStateOf(false) }
    var confirming by remember { mutableStateOf(false) }
    // Verdadero cuando la ultima consulta fallo por red (el servicio se esta reiniciando).
    var restarting by remember { mutableStateOf(false) }
    // Verdadero cuando el servidor no respondio durante 5 minutos y se detuvo el sondeo.
    var stalled by remember { mutableStateOf(false) }
    var pollRound by remember { mutableStateOf(0) }

    val token = session?.accessToken
    val request = status?.request
    // Clave del sondeo: cambia solo cuando hay otra solicitud activa o deja de haberla.
    val activeRequestId = request?.takeIf { it.isActive() }?.id

    // Carga inicial del estado.
    LaunchedEffect(serverUrl, token) {
        if (serverUrl == null || token == null) {
            error = "No hay una sesion de server owner activa."
            return@LaunchedEffect
        }
        loading = true
        error = null
        try {
            status = fetchStatus(serverUrl, token)
        } catch (exception: CompanyException) {
            error = exception.message
        } finally {
            loading = false
        }
    }

    // Sondeo cada 5 s mientras haya una solicitud activa; si falla se conserva el ultimo estado.
    // pollRound cambia al pulsar "Reintentar" para reiniciar el sondeo.
    LaunchedEffect(activeRequestId, serverUrl, token, pollRound) {
        if (activeRequestId == null || serverUrl == null || token == null) return@LaunchedEffect
        stalled = false
        var lastSuccess = TimeSource.Monotonic.markNow()
        // Un solo cliente HTTP durante todo el sondeo.
        val api = CompanyApi(serverUrl)
        try {
            while (true) {
                delay(PollIntervalMillis)
                try {
                    status = api.coreUpdateStatus(token)
                    restarting = false
                    error = null
                    lastSuccess = TimeSource.Monotonic.markNow()
                } catch (exception: CompanyException) {
                    val code = exception.status
                    if (exception.network || code in 502..504) {
                        // El servicio se reinicia durante la actualizacion: se sigue consultando, con tope.
                        restarting = true
                        if (lastSuccess.elapsedNow() >= RestartTimeout) {
                            restarting = false
                            stalled = true
                            return@LaunchedEffect
                        }
                    } else if (code != null && code in 400..499) {
                        // Sesion vencida o sin permiso: seguir consultando no sirve.
                        restarting = false
                        error = exception.message
                        return@LaunchedEffect
                    } else {
                        restarting = false
                        error = exception.message
                    }
                }
            }
        } finally {
            api.close()
        }
    }

    fun requestUpdate() {
        val url = serverUrl ?: return
        val accessToken = token ?: return
        requesting = true
        error = null
        scope.launch {
            try {
                val api = CompanyApi(url)
                val created = try {
                    api.requestCoreUpdate(accessToken)
                } finally {
                    api.close()
                }
                restarting = false
                status = status?.copy(request = created)
            } catch (exception: CompanyException) {
                error = exception.message
                // Ya hay una solicitud activa: se recarga el estado para que aparezca y empiece el sondeo.
                if (exception.status == 409) {
                    try {
                        status = fetchStatus(url, accessToken)
                    } catch (_: CompanyException) {
                    }
                }
            } finally {
                requesting = false
            }
        }
    }

    if (confirming) {
        AlertDialog(
            onDismissRequest = { confirming = false },
            title = { Text("Actualizar servidor") },
            text = {
                Text(
                    "Se compilara la nueva version en el servidor (varios minutos) y el servicio se reiniciara; " +
                        "durante el reinicio la app no respondera unos segundos.",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirming = false
                    requestUpdate()
                }) { Text("Actualizar") }
            },
            dismissButton = { TextButton(onClick = { confirming = false }) { Text("Cancelar") } },
        )
    }

    AuthenticatedScreen(
        modifier = modifier.background(colors.screenBrush),
        topBar = {
            AuthenticatedTopBar(
                title = "Actualizar servidor",
                subtitle = "Actualiza el Core y la webapp del servidor",
                primaryColor = colors.accent,
                onReturnToDashboard = onReturnToDashboard,
                onSettings = onSettings,
                onLogout = onLogout,
            )
        },
    ) {
        ClosableView(
            onClose = onReturnToDashboard,
            modifier = Modifier
                .align(Alignment.Center)
                .padding(bottom = 16.dp)
                .contentWidth(ContentWidth.Wide, 0.94f),
        ) {
            Column(
                modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(AdaptiveLayout.screenPadding - 2.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                val current = status
                if (current != null) {
                    Text("Version actual ${current.currentVersion}", color = colors.text, fontWeight = FontWeight.SemiBold)
                    Text(
                        current.latestVersion?.let { "Ultima version $it" } ?: "No se pudo consultar el catalogo",
                        color = colors.muted,
                    )
                } else if (loading) {
                    Text("Cargando...", color = colors.muted)
                }
                error?.let { Text(it, color = colors.danger) }
                if (stalled) {
                    Text(
                        "El servidor no responde despues de 5 minutos. Revisa el servidor por SSH (journalctl -u ideascore).",
                        color = colors.danger,
                    )
                    Button(onClick = { pollRound++ }, colors = accentButtonColors()) { Text("Reintentar") }
                }

                if (current != null) {
                    if (request == null || !request.isActive()) {
                        if (current.updateAvailable && current.latestVersion != null) {
                            Button(
                                onClick = { confirming = true },
                                enabled = !requesting,
                                colors = accentButtonColors(),
                            ) {
                                Text(if (requesting) "Solicitando..." else "Actualizar a ${current.latestVersion}")
                            }
                        } else if (current.latestVersion != null) {
                            Text("El servidor ya tiene la ultima version.", color = colors.muted)
                        }
                    }
                    if (request != null) {
                        UpdateRequestPanel(request = request, restarting = restarting)
                    }
                }
            }
        }
    }
}

// Estado de la solicitud mas reciente con su log.
@Composable
private fun UpdateRequestPanel(request: CoreUpdateRequestDto, restarting: Boolean) {
    val colors = AppTheme.colors
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        when (request.status) {
            "pending" -> Text("Pendiente: esperando al servidor", color = colors.text, fontWeight = FontWeight.SemiBold)
            "running" -> Text("Actualizando...", color = colors.text, fontWeight = FontWeight.SemiBold)
            "done" -> {
                Text("Actualizado a ${request.targetVersion}", color = colors.text, fontWeight = FontWeight.SemiBold)
                Text(
                    "La version de la app de escritorio o movil es independiente y se actualiza por separado.",
                    color = colors.muted,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            "error" -> {
                Text("La actualizacion fallo", color = colors.danger, fontWeight = FontWeight.SemiBold)
                request.message?.takeIf { it.isNotBlank() }?.let { Text(it, color = colors.danger) }
            }
            else -> Text(request.status, color = colors.muted)
        }
        if (request.status == "pending" && request.stalePending) {
            Text(
                "El servidor aun no tiene el actualizador instalado. Ejecuta una vez sudo bash update.sh por SSH; " +
                    "despues las actualizaciones se podran hacer desde aqui.",
                color = colors.danger,
            )
        }
        if (restarting && request.isActive()) {
            Text("El servidor se esta reiniciando...", color = colors.muted)
        }
        request.logTail?.takeIf { it.isNotBlank() }?.let { LogBox(it) }
    }
}

// Log en monoespaciado, seleccionable y con scroll propio de alto limitado.
@Composable
private fun LogBox(text: String) {
    val colors = AppTheme.colors
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = colors.surface,
        shape = MaterialTheme.shapes.medium,
    ) {
        val scroll = rememberScrollState()
        var firstShow by remember { mutableStateOf(true) }
        // Sigue la cola del log salvo que el usuario haya subido; al mostrarse empieza abajo.
        LaunchedEffect(text) {
            val follow = firstShow || scroll.value >= scroll.maxValue - 40
            firstShow = false
            if (follow) {
                // Espera un cuadro para que el layout ya incluya el texto nuevo.
                withFrameNanos { }
                scroll.scrollTo(scroll.maxValue)
            }
        }
        Box(modifier = Modifier.heightIn(max = 280.dp).verticalScroll(scroll).padding(12.dp)) {
            SelectionContainer {
                Text(
                    text,
                    color = colors.text,
                    fontFamily = FontFamily.Monospace,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}
