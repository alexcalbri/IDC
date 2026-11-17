package com.ideasdeveloper.idc.app.features.notifications.ui

import com.ideasdeveloper.idc.app.features.notifications.data.NotificationApi
import com.ideasdeveloper.idc.app.features.notifications.data.NotificationException
import com.ideasdeveloper.idc.app.features.notifications.data.NotificationItem
import com.ideasdeveloper.idc.app.features.notifications.data.NotificationPage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

// Estado de las notificaciones del usuario conectado, compartido por todas las pantallas (cada una
// tiene su campana): asi el contador no parte de cero al cambiar de pantalla.
data class NotificationState(
    val items: List<NotificationItem> = emptyList(),
    val unread: Int = 0,
    val hasMore: Boolean = false,
    // Ya se trajeron del servidor al menos una vez.
    val loaded: Boolean = false,
    val loadingMore: Boolean = false,
    val error: String? = null,
)

// Con que sesion se consulta: el cliente del servidor, el token y la empresa (o el espacio del servidor).
class NotificationSession(val api: NotificationApi, val accessToken: String, val companyCode: String) {
    internal val key: String get() = "$companyCode/$accessToken"
}

/**
 * Notificaciones del usuario conectado. Todo el estado mutable (cliente, dueno de los datos, generacion
 * y flujos) se cambia solo con [lock] tomado, nunca durante una llamada de red; las respuestas que llegan
 * despues de un cambio local (marcar como leida, cerrar sesion) se descartan por [generation] para que el
 * contador no parpadee.
 */
object NotificationCenter {
    // Cada cuanto se actualiza mientras la app esta abierta.
    const val RefreshSeconds = 60L

    // Cuantas se piden por pagina; "Ver mas" pide la siguiente. El servidor admite hasta MaxPage.
    const val PageSize = 30
    private const val MaxPage = 100

    private val _state = MutableStateFlow(NotificationState())
    val state = _state.asStateFlow()

    private val lock = Mutex()

    // Las llamadas que no deben cortarse al cambiar de pantalla (marcar como leida antes de navegar).
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var api: NotificationApi? = null
    private var apiServerUrl: String? = null

    // Usuario y empresa de los datos en pantalla: si cambian (otra empresa, otra sesion) se parte de cero.
    private var owner: String? = null

    // Sube con cada cambio local; una respuesta pedida antes de ese cambio ya no vale.
    private var generation = 0L

    /**
     * La sesion de consulta. Es idempotente: mientras el servidor sea el mismo se reutiliza el cliente HTTP,
     * que solo se cierra en [clear] (al cerrar sesion de verdad), nunca porque una campana salga de pantalla.
     */
    suspend fun session(serverUrl: String, accessToken: String, companyCode: String): NotificationSession = lock.withLock {
        var client = api
        if (client == null || apiServerUrl != serverUrl) {
            client?.close()
            client = NotificationApi(serverUrl)
            api = client
            apiServerUrl = serverUrl
        }
        NotificationSession(client, accessToken, companyCode)
    }

    // Trae las mas nuevas (al menos una pagina, o las ya cargadas) y el contador.
    suspend fun refresh(session: NotificationSession) {
        val (started, size) = lock.withLock {
            adopt(session)
            generation to _state.value.items.size.coerceIn(PageSize, MaxPage)
        }
        try {
            val page = session.api.list(session.accessToken, session.companyCode, limit = size)
            lock.withLock {
                if (generation == started && owner == session.key) {
                    _state.update { it.copy(items = page.items, unread = page.unread, hasMore = page.hasMore, loaded = true, error = null) }
                }
            }
        } catch (failure: NotificationException) {
            lock.withLock { if (generation == started) _state.update { it.copy(error = failure.message) } }
        }
    }

    // "Ver mas": agrega la pagina siguiente a las ya cargadas.
    suspend fun loadMore(session: NotificationSession) {
        val (started, offset) = lock.withLock {
            if (_state.value.loadingMore || !_state.value.hasMore || owner != session.key) return
            _state.update { it.copy(loadingMore = true) }
            generation to _state.value.items.size
        }
        try {
            val page: NotificationPage = session.api.list(session.accessToken, session.companyCode, limit = PageSize, offset = offset)
            lock.withLock {
                if (owner != session.key) return
                _state.update { current ->
                    // Por si llegaron nuevas mientras tanto, no se repite ninguna.
                    val known = current.items.mapTo(HashSet()) { it.id }
                    val appended = page.items.filter { it.id !in known }
                    if (generation == started) {
                        current.copy(items = current.items + appended, hasMore = page.hasMore, unread = page.unread, loadingMore = false)
                    } else {
                        current.copy(items = current.items + appended, hasMore = page.hasMore, loadingMore = false)
                    }
                }
            }
        } catch (failure: NotificationException) {
            lock.withLock { _state.update { it.copy(loadingMore = false, error = failure.message) } }
        }
    }

    // Marca una como leida de inmediato en pantalla y confirma con el servidor.
    suspend fun markRead(session: NotificationSession, id: String) {
        val mine = lock.withLock {
            val item = _state.value.items.firstOrNull { it.id == id } ?: return
            if (item.read) return
            generation++
            _state.update { current ->
                current.copy(
                    items = current.items.map { if (it.id == id) it.copy(read = true) else it },
                    unread = (current.unread - 1).coerceAtLeast(0),
                )
            }
            generation
        }
        try {
            val change = session.api.markRead(session.accessToken, session.companyCode, id)
            lock.withLock { if (generation == mine) _state.update { it.copy(unread = change.unread) } }
        } catch (failure: NotificationException) {
            refresh(session)
        }
    }

    // Igual que [markRead] pero sobrevive a la pantalla que lo pidio.
    fun markReadInBackground(session: NotificationSession, id: String) {
        scope.launch { markRead(session, id) }
    }

    suspend fun markAllRead(session: NotificationSession) {
        lock.withLock {
            generation++
            _state.update { current -> current.copy(items = current.items.map { it.copy(read = true) }, unread = 0) }
        }
        try {
            session.api.markAllRead(session.accessToken, session.companyCode)
        } catch (failure: NotificationException) {
            refresh(session)
        }
    }

    // Al cerrar sesion no queda nada del usuario anterior; es lo unico que cierra el cliente HTTP.
    suspend fun clear() {
        lock.withLock {
            generation++
            _state.value = NotificationState()
            owner = null
            api?.close()
            api = null
            apiServerUrl = null
        }
    }

    // Con [lock] tomado: si la sesion es otra (otra empresa u otro usuario) se parte de cero.
    private fun adopt(session: NotificationSession) {
        if (owner != session.key) {
            owner = session.key
            generation++
            _state.value = NotificationState()
        }
    }
}

// "ahora", "hace 5 min", "hace 3 h", "hace 2 d"... a partir de los segundos transcurridos.
fun relativeAge(seconds: Long): String {
    val minutes = seconds / 60
    val hours = minutes / 60
    val days = hours / 24
    return when {
        seconds < 60 -> "ahora"
        minutes < 60 -> "hace $minutes min"
        hours < 24 -> "hace $hours h"
        days < 30 -> "hace $days d"
        else -> "hace ${days / 30} " + if (days / 30 == 1L) "mes" else "meses"
    }
}
