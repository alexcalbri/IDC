package com.ideasdeveloper.idc.app.core.theme

import com.ideasdeveloper.idc.ui.UiAppTheme
import com.ideasdeveloper.idc.ui.UiAppThemes
import com.ideasdeveloper.idc.ui.UiJson
import com.ideasdeveloper.idc.ui.isValid
import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.isSuccess
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Tema elegido en este dispositivo y temas que aportan los modulos instalados en el servidor.
 * Los temas de modulos se guardan para pintar la app igual al abrirla sin conexion.
 */
object ThemeStore {
    private const val SelectionKey = "theme.selection"
    private const val ModuleThemesKey = "theme.moduleThemes"

    private val _selection = MutableStateFlow(PersistentPreferences.get(SelectionKey) ?: BuiltInThemes.System)
    val selection = _selection.asStateFlow()

    private val _moduleThemes = MutableStateFlow(loadModuleThemes())
    val moduleThemes = _moduleThemes.asStateFlow()

    fun select(themeId: String) {
        _selection.value = themeId
        PersistentPreferences.set(SelectionKey, themeId)
    }

    // Pide al servidor los temas de sus modulos; si falla conserva los guardados.
    suspend fun refresh(serverUrl: String?) {
        val baseUrl = serverUrl?.trim()?.trimEnd('/')?.takeIf { it.isNotBlank() } ?: return
        val client = HttpClient {
            expectSuccess = false
            install(HttpTimeout) {
                requestTimeoutMillis = 8_000
                connectTimeoutMillis = 5_000
            }
        }
        try {
            val response = client.get("$baseUrl/modules/themes")
            if (!response.status.isSuccess()) return
            val themes = UiJson.decodeFromString(UiAppThemes.serializer(), response.bodyAsText()).themes
                .filter { it.colors.isValid() }
            _moduleThemes.value = themes
            PersistentPreferences.set(ModuleThemesKey, UiJson.encodeToString(UiAppThemes.serializer(), UiAppThemes(themes)))
        } catch (exception: CancellationException) {
            throw exception
        } catch (_: Exception) {
            // Servidor antiguo sin /modules/themes o sin conexion: se quedan los temas guardados.
        } finally {
            client.close()
        }
    }

    private fun loadModuleThemes(): List<UiAppTheme> = PersistentPreferences.get(ModuleThemesKey)
        ?.let { saved -> runCatching { UiJson.decodeFromString(UiAppThemes.serializer(), saved).themes }.getOrNull() }
        .orEmpty()
}

// Preferencias sencillas de texto guardadas en el dispositivo.
expect object PersistentPreferences {
    fun get(key: String): String?
    fun set(key: String, value: String)
}
