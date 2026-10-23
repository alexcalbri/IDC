package com.ideasdeveloper.idc.app.features.modules.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ideasdeveloper.idc.app.core.platform.EmbeddedWebView
import com.ideasdeveloper.idc.app.core.platform.prepareEmbeddedWebView
import com.ideasdeveloper.idc.app.core.platform.saveTextFile
import com.ideasdeveloper.idc.app.core.platform.supportsEmbeddedWebView
import com.ideasdeveloper.idc.app.features.modules.data.ModuleApi
import com.ideasdeveloper.idc.app.features.modules.data.ModuleException
import com.ideasdeveloper.idc.ui.ModuleUiEntry
import com.ideasdeveloper.idc.ui.UiAction
import com.ideasdeveloper.idc.ui.UiDownload
import com.ideasdeveloper.idc.ui.UiForm
import com.ideasdeveloper.idc.ui.UiNavigate
import com.ideasdeveloper.idc.ui.UiNavigation
import com.ideasdeveloper.idc.ui.UiNode
import com.ideasdeveloper.idc.ui.UiNotice
import com.ideasdeveloper.idc.ui.UiOpenUrl
import com.ideasdeveloper.idc.ui.UiRequest
import com.ideasdeveloper.idc.ui.UiScreen
import com.ideasdeveloper.idc.ui.UiTone
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.decodeToImageBitmap

// Datos de sesion necesarios para llamar rutas de un modulo de empresa.
data class ModuleCallContext(val serverUrl: String, val accessToken: String, val companyCode: String)

/** Dibuja la interfaz nativa de un modulo pidiendo cada pantalla al servidor. */
@Composable
fun NativeModuleHost(ui: ModuleUiEntry, context: ModuleCallContext, companyAccent: Color, modifier: Modifier = Modifier) {
    val scope = rememberCoroutineScope()
    val uriHandler = LocalUriHandler.current
    var screen by remember(ui.entry) { mutableStateOf<UiScreen?>(null) }
    var forms by remember(ui.entry) { mutableStateOf<Map<String, Map<String, String>>>(emptyMap()) }
    var busy by remember(ui.entry) { mutableStateOf(false) }
    var error by remember(ui.entry) { mutableStateOf<String?>(null) }
    var message by remember(ui.entry) { mutableStateOf<UiNotice?>(null) }
    var pendingConfirm by remember(ui.entry) { mutableStateOf<UiRequest?>(null) }
    var lastNavigation by remember(ui.entry) { mutableStateOf(UiNavigate(ui.home)) }

    fun show(next: UiScreen) {
        screen = next
        // Cada pantalla trae los valores iniciales de sus formularios.
        forms = buildMap { collectForms(next.body) { form -> put(form.id, form.values) } }
    }

    fun run(block: suspend (ModuleApi) -> Unit) {
        busy = true
        error = null
        message = null
        scope.launch {
            try {
                val api = ModuleApi(context.serverUrl)
                try {
                    block(api)
                } finally {
                    api.close()
                }
            } catch (exception: ModuleException) {
                error = exception.message
            } finally {
                busy = false
            }
        }
    }

    fun execute(action: UiAction, confirmed: Boolean = false) {
        val formValues = action.formId?.let { forms[it] }
        when (action) {
            is UiNavigate -> {
                lastNavigation = action
                run { api -> show(api.screen(context.accessToken, context.companyCode, ui.entry, action.screen, action.params + formValues.orEmpty())) }
            }
            is UiRequest -> if (action.confirm != null && !confirmed) {
                pendingConfirm = action
            } else {
                run { api -> show(api.request(context.accessToken, context.companyCode, action, formValues)) }
            }
            is UiDownload -> run { api ->
                val text = api.download(context.accessToken, context.companyCode, action.route, action.params + formValues.orEmpty())
                message = UiNotice(saveTextFile(action.fileName, text, "text/csv;charset=utf-8"), UiTone.Success)
            }
            is UiOpenUrl -> runCatching { uriHandler.openUri(action.url) }
            else -> Unit
        }
    }

    LaunchedEffect(ui.entry, context) { execute(UiNavigate(ui.home)) }

    val colors = ModuleColors.from(screen?.theme, companyAccent)
    val renderContext = ModuleRenderContext(
        colors = colors,
        busy = busy,
        formValue = { formId, key -> forms[formId]?.get(key).orEmpty() },
        setFormValue = { formId, key, value -> forms = forms + (formId to (forms[formId].orEmpty() + (key to value))) },
        onAction = { execute(it) },
        loadImage = { url -> ModuleImageCache.load(context, url) },
    )

    ModuleScreenLayout(
        screen = screen,
        context = renderContext,
        message = message,
        error = error,
        onRetry = { execute(lastNavigation) },
        modifier = modifier,
    )

    pendingConfirm?.let { request ->
        AlertDialog(
            onDismissRequest = { pendingConfirm = null },
            text = { Text(request.confirm.orEmpty()) },
            confirmButton = {
                TextButton(onClick = {
                    pendingConfirm = null
                    execute(request, confirmed = true)
                }) { Text("Continuar") }
            },
            dismissButton = { TextButton(onClick = { pendingConfirm = null }) { Text("Cancelar") } },
        )
    }
}

/**
 * Diseno sin estado de una pantalla de modulo: menu lateral en pantallas anchas (>= 840dp)
 * y barra horizontal en telefonos.
 */
@Composable
fun ModuleScreenLayout(
    screen: UiScreen?,
    context: ModuleRenderContext,
    message: UiNotice?,
    error: String?,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = context.colors
    BoxWithConstraints(modifier = modifier.fillMaxSize().background(colors.background)) {
        val wide = maxWidth >= 840.dp
        val navigation = screen?.navigation
        val content: @Composable (Modifier) -> Unit = { contentModifier ->
            Column(
                verticalArrangement = Arrangement.spacedBy(16.dp),
                modifier = contentModifier.verticalScroll(rememberScrollState()).padding(if (wide) 24.dp else 16.dp),
            ) {
                if (context.busy) LinearProgressIndicator(color = colors.accent, trackColor = colors.surfaceAlt, modifier = Modifier.fillMaxWidth())
                screen?.notice?.let { ModuleNoticeBanner(it, colors) }
                message?.let { ModuleNoticeBanner(it, colors) }
                error?.let { failure ->
                    ModuleNoticeBanner(UiNotice(failure, UiTone.Danger), colors)
                    if (screen == null) {
                        Button(onClick = onRetry, colors = ButtonDefaults.buttonColors(containerColor = colors.accent)) { Text("Reintentar") }
                    }
                }
                when (screen) {
                    null -> if (context.busy) CircularProgressIndicator(color = colors.accent)
                    else -> screen.body.forEach { ModuleNode(it, context) }
                }
            }
        }
        when {
            navigation == null -> content(Modifier.fillMaxSize())
            wide -> Row(modifier = Modifier.fillMaxSize()) {
                ModuleSidebar(navigation, colors, enabled = !context.busy, onAction = context.onAction)
                content(Modifier.weight(1f).fillMaxHeight())
            }
            else -> Column(modifier = Modifier.fillMaxSize()) {
                ModuleTopNavigation(navigation, colors, enabled = !context.busy, onAction = context.onAction)
                content(Modifier.weight(1f).fillMaxWidth())
            }
        }
    }
}

@Composable
private fun ModuleSidebar(navigation: UiNavigation, colors: ModuleColors, enabled: Boolean, onAction: (UiAction) -> Unit) {
    Column(
        verticalArrangement = Arrangement.spacedBy(6.dp),
        modifier = Modifier.width(230.dp).fillMaxHeight().background(colors.surface).padding(horizontal = 16.dp, vertical = 20.dp),
    ) {
        if (navigation.title.isNotBlank()) {
            Text(navigation.title.uppercase(), color = colors.text, fontWeight = FontWeight.ExtraBold, letterSpacing = 2.sp, modifier = Modifier.padding(bottom = 12.dp))
        }
        navigation.items.forEach { item ->
            val selected = item.id == navigation.selected
            Text(
                item.label,
                color = if (selected) colors.text else colors.muted,
                fontSize = 14.sp,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                modifier = Modifier
                    .fillMaxWidth()
                    .background(if (selected) colors.surfaceAlt else Color.Transparent, RoundedCornerShape(10.dp))
                    .clickable(enabled = enabled) { onAction(item.action) }
                    .padding(horizontal = 12.dp, vertical = 10.dp),
            )
        }
    }
}

@Composable
private fun ModuleTopNavigation(navigation: UiNavigation, colors: ModuleColors, enabled: Boolean, onAction: (UiAction) -> Unit) {
    Column(modifier = Modifier.fillMaxWidth().background(colors.surface).padding(vertical = 10.dp)) {
        if (navigation.title.isNotBlank()) {
            Text(navigation.title.uppercase(), color = colors.text, fontWeight = FontWeight.ExtraBold, letterSpacing = 2.sp, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
        }
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp),
        ) {
            navigation.items.forEach { item ->
                val selected = item.id == navigation.selected
                Text(
                    item.label,
                    color = if (selected) colors.text else colors.muted,
                    fontSize = 13.sp,
                    modifier = Modifier
                        .background(if (selected) colors.surfaceAlt else Color.Transparent, RoundedCornerShape(10.dp))
                        .clickable(enabled = enabled) { onAction(item.action) }
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                )
            }
        }
    }
}

/**
 * Abre la interfaz web propia de un modulo. La sesion se cambia por un enlace de un solo uso;
 * en Android se muestra dentro de la app y en las demas plataformas en el navegador.
 */
@Composable
fun WebModuleHost(moduleId: String, context: ModuleCallContext, companyAccent: Color, modifier: Modifier = Modifier) {
    val scope = rememberCoroutineScope()
    val uriHandler = LocalUriHandler.current
    var url by remember(moduleId) { mutableStateOf<String?>(null) }
    var error by remember(moduleId) { mutableStateOf<String?>(null) }
    var openedOutside by remember(moduleId) { mutableStateOf(false) }
    var progress by remember(moduleId) { mutableStateOf<String?>(null) }

    fun open() {
        error = null
        scope.launch {
            try {
                // El enlace vence en 60 s: primero se prepara el navegador (puede descargar Chromium).
                val embedded = supportsEmbeddedWebView && prepareEmbeddedWebView { progress = it }
                progress = null
                val api = ModuleApi(context.serverUrl)
                val sessionUrl = try {
                    api.webSession(context.accessToken, context.companyCode, moduleId)
                } finally {
                    api.close()
                }
                if (embedded) {
                    url = sessionUrl
                } else {
                    uriHandler.openUri(sessionUrl)
                    openedOutside = true
                }
            } catch (exception: ModuleException) {
                error = exception.message
            } catch (exception: Exception) {
                error = "No se pudo abrir el modulo: ${exception.message}"
            }
        }
    }

    LaunchedEffect(moduleId, context) { open() }

    val embedded = url
    if (embedded != null) {
        EmbeddedWebView(embedded, modifier.fillMaxSize())
        return
    }
    Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.padding(24.dp)) {
            when {
                error != null -> Text(error.orEmpty(), color = Color(0xFFB00020))
                openedOutside -> Text("El modulo se abrio en tu navegador.", color = Color.White)
                else -> {
                    CircularProgressIndicator(color = Color.White)
                    progress?.let { Text(it, color = Color.White) }
                }
            }
            if (error != null || openedOutside) {
                Button(onClick = { open() }, colors = ButtonDefaults.buttonColors(containerColor = companyAccent)) {
                    Text(if (openedOutside) "Abrir de nuevo" else "Reintentar")
                }
            }
        }
    }
}

private fun collectForms(nodes: List<UiNode>, onForm: (UiForm) -> Unit) {
    nodes.forEach { node ->
        when (node) {
            is UiForm -> onForm(node)
            is com.ideasdeveloper.idc.ui.UiColumn -> collectForms(node.children, onForm)
            is com.ideasdeveloper.idc.ui.UiRow -> collectForms(node.children, onForm)
            is com.ideasdeveloper.idc.ui.UiGrid -> collectForms(node.children, onForm)
            is com.ideasdeveloper.idc.ui.UiCard -> {
                collectForms(node.children, onForm)
                collectForms(node.actions, onForm)
            }
            else -> Unit
        }
    }
}

// Cache en memoria de imagenes de modulos; evita descargarlas de nuevo al cambiar de pantalla.
private object ModuleImageCache {
    private const val MaxEntries = 40
    private val images = LinkedHashMap<String, ImageBitmap>()

    suspend fun load(context: ModuleCallContext, url: String): ImageBitmap? {
        // Las rutas de modulo dependen de la empresa; las URL externas no.
        val key = if (url.startsWith("/")) "${context.serverUrl}|${context.companyCode}|$url" else url
        images[key]?.let { return it }
        val bitmap = try {
            val api = ModuleApi(context.serverUrl)
            val bytes = try {
                api.image(context.accessToken, context.companyCode, url)
            } finally {
                api.close()
            }
            bytes.decodeToImageBitmap()
        } catch (exception: CancellationException) {
            throw exception
        } catch (_: Exception) {
            return null
        }
        if (images.size >= MaxEntries) images.remove(images.keys.first())
        images[key] = bitmap
        return bitmap
    }
}
