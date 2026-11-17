package com.ideasdeveloper.idc.server.modules

import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import java.sql.Connection
import java.sql.Timestamp
import java.time.Duration
import java.time.Instant
import java.util.UUID

/**
 * Pantalla que se abre al hacer clic en una notificacion: [screen] es una pantalla del modulo que la
 * creo (la misma que usa UiOpenModule) y [params] sus parametros.
 */
@Serializable
data class NotificationTarget(val screen: String, val params: Map<String, String> = emptyMap())

// Lo que un modulo entrega al Core para avisar a un usuario.
data class NewNotification(
    val userId: UUID,
    // Modulo de origen: el panel lo muestra con su nombre y abre [target] en ese modulo.
    val moduleId: String,
    val title: String,
    val text: String = "",
    val target: NotificationTarget? = null,
)

// Una notificacion guardada de un usuario.
data class StoredNotification(
    val id: UUID,
    val userId: UUID,
    val moduleId: String,
    val title: String,
    val text: String,
    val target: NotificationTarget?,
    val createdAt: Instant,
    val readAt: Instant?,
)

data class NotificationPage(val items: List<StoredNotification>, val unread: Int, val hasMore: Boolean)

/**
 * Almacen de notificaciones de la base de una empresa. Cada operacion recibe el usuario y solo ve sus
 * filas, para que ninguna consulta del servicio pueda mezclar usuarios.
 */
interface NotificationStore {
    fun insert(notification: StoredNotification)
    // Mas nuevas primero; pide uno mas que [limit] para saber si hay otra pagina.
    fun list(userId: UUID, limit: Int, offset: Int, unreadOnly: Boolean): List<StoredNotification>
    fun unreadCount(userId: UUID): Int
    // Falso cuando no existe, es de otro usuario o ya estaba leida.
    fun markRead(userId: UUID, id: UUID, at: Instant): Boolean
    fun markAllRead(userId: UUID, at: Instant): Int
    // Borra hasta [limit] de las creadas antes de [before] (las mas viejas primero, de cualquier usuario); devuelve cuantas.
    fun deleteCreatedBefore(before: Instant, limit: Int): Int
}

/**
 * Servicio del Core para el centro de notificaciones (`access.notifications`). Es la API generica del
 * Core y del puente de agentes (leer y marcar las propias); los modulos crean notificaciones con su
 * notificador ligado ([ModuleNotifier]: `access.notificationsFor(moduleId)`, `ModuleJobContext.notifier`
 * o `McpToolCall.notifier`), que fija el modulo de origen, y nunca tocan la tabla:
 *
 *     access.notificationsFor("crm").notify(tenant, userId, "Te asignaron un lead", "Ana Perez", NotificationTarget("lead", mapOf("id" to leadId)))
 *
 * Se llama con la conexion a la base de la empresa (la del hilo de la ruta, herramienta o tarea). Una
 * notificacion con datos invalidos se ajusta (titulo y texto se recortan); un titulo vacio se rechaza.
 */
class NotificationService(
    private val store: (Connection) -> NotificationStore = ::JdbcNotificationStore,
    private val clock: () -> Instant = Instant::now,
    // La limpieza va por lotes para no bloquear la base de una empresa con muchas filas.
    private val purgeBatchSize: Int = PurgeBatchSize,
    private val maxPurgeBatches: Int = MaxPurgeBatches,
) {
    /** Crea la notificacion y devuelve su id. */
    fun notify(tenant: Connection, notification: NewNotification): UUID {
        val title = notification.title.trim().take(MaxTitleLength)
        require(title.isNotEmpty()) { "El titulo de la notificacion es obligatorio." }
        require(notification.moduleId.isNotBlank()) { "El modulo de origen es obligatorio." }
        val target = notification.target?.takeIf { it.screen.isNotBlank() }?.let {
            NotificationTarget(it.screen.trim().take(MaxScreenLength), it.params.entries.take(MaxParams).associate { (key, value) -> key.take(MaxParamLength) to value.take(MaxParamLength) })
        }
        val id = UUID.randomUUID()
        store(tenant).insert(
            StoredNotification(id, notification.userId, notification.moduleId.trim().take(MaxModuleLength), title, notification.text.trim().take(MaxTextLength), target, clock(), null)
        )
        return id
    }

    /** Las notificaciones del usuario, mas nuevas primero, en paginas de [limit] a partir de [offset]. */
    fun list(tenant: Connection, userId: UUID, limit: Int = DefaultPageSize, offset: Int = 0, unreadOnly: Boolean = false): NotificationPage {
        val size = limit.coerceIn(1, MaxPageSize)
        val rows = store(tenant).list(userId, size + 1, offset.coerceAtLeast(0), unreadOnly)
        return NotificationPage(rows.take(size), store(tenant).unreadCount(userId), rows.size > size)
    }

    fun unreadCount(tenant: Connection, userId: UUID): Int = store(tenant).unreadCount(userId)

    /** Marca una notificacion propia como leida; falso si no existe, es de otro usuario o ya estaba leida. */
    fun markRead(tenant: Connection, userId: UUID, id: UUID): Boolean = store(tenant).markRead(userId, id, clock())

    /** Marca todas las del usuario como leidas y devuelve cuantas eran nuevas. */
    fun markAllRead(tenant: Connection, userId: UUID): Int = store(tenant).markAllRead(userId, clock())

    /**
     * Limpia las de mas de [RetentionDays] dias (las leidas y las no leidas), en lotes de [PurgeBatchSize]
     * y como mucho [MaxPurgeBatches] lotes por pasada (el resto se limpia en la siguiente); devuelve cuantas.
     */
    fun purgeExpired(tenant: Connection): Int {
        val before = clock().minus(Duration.ofDays(RetentionDays))
        val rows = store(tenant)
        var removed = 0
        repeat(maxPurgeBatches) {
            val batch = rows.deleteCreatedBefore(before, purgeBatchSize)
            removed += batch
            if (batch < purgeBatchSize) return removed
        }
        return removed
    }

    companion object {
        const val RetentionDays = 90L
        const val PurgeBatchSize = 5_000
        const val MaxPurgeBatches = 20
        const val DefaultPageSize = 30
        const val MaxPageSize = 100
        const val MaxTitleLength = 160
        const val MaxTextLength = 1_000
        const val MaxModuleLength = 64
        const val MaxScreenLength = 120
        const val MaxParams = 20
        const val MaxParamLength = 200
    }
}

internal class JdbcNotificationStore(private val tenant: Connection) : NotificationStore {
    private val params = MapSerializer(String.serializer(), String.serializer())
    private val columns = "id, user_id, module_id, title, body, target_screen, target_params, created_at, read_at"

    override fun insert(notification: StoredNotification) {
        tenant.prepareStatement(
            "INSERT INTO notifications (id, user_id, module_id, title, body, target_screen, target_params, created_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?)"
        ).use { insert ->
            insert.setObject(1, notification.id)
            insert.setObject(2, notification.userId)
            insert.setString(3, notification.moduleId)
            insert.setString(4, notification.title)
            insert.setString(5, notification.text)
            insert.setString(6, notification.target?.screen)
            insert.setString(7, Json.encodeToString(params, notification.target?.params.orEmpty()))
            insert.setTimestamp(8, Timestamp.from(notification.createdAt))
            insert.executeUpdate()
        }
    }

    override fun list(userId: UUID, limit: Int, offset: Int, unreadOnly: Boolean): List<StoredNotification> =
        tenant.prepareStatement(
            "SELECT $columns FROM notifications WHERE user_id = ?" + (if (unreadOnly) " AND read_at IS NULL" else "") +
                " ORDER BY created_at DESC, id LIMIT ? OFFSET ?"
        ).use { query ->
            query.setObject(1, userId)
            query.setInt(2, limit)
            query.setInt(3, offset)
            query.executeQuery().use { rows -> buildList { while (rows.next()) add(read(rows)) } }
        }

    override fun unreadCount(userId: UUID): Int =
        tenant.prepareStatement("SELECT COUNT(*) FROM notifications WHERE user_id = ? AND read_at IS NULL").use { query ->
            query.setObject(1, userId)
            query.executeQuery().use { rows -> if (rows.next()) rows.getInt(1) else 0 }
        }

    override fun markRead(userId: UUID, id: UUID, at: Instant): Boolean =
        tenant.prepareStatement("UPDATE notifications SET read_at = ? WHERE id = ? AND user_id = ? AND read_at IS NULL").use { update ->
            update.setTimestamp(1, Timestamp.from(at))
            update.setObject(2, id)
            update.setObject(3, userId)
            update.executeUpdate() > 0
        }

    override fun markAllRead(userId: UUID, at: Instant): Int =
        tenant.prepareStatement("UPDATE notifications SET read_at = ? WHERE user_id = ? AND read_at IS NULL").use { update ->
            update.setTimestamp(1, Timestamp.from(at))
            update.setObject(2, userId)
            update.executeUpdate()
        }

    override fun deleteCreatedBefore(before: Instant, limit: Int): Int =
        tenant.prepareStatement(
            "DELETE FROM notifications WHERE id IN (SELECT id FROM notifications WHERE created_at < ? ORDER BY created_at LIMIT ?)"
        ).use { statement ->
            statement.setTimestamp(1, Timestamp.from(before))
            statement.setInt(2, limit)
            statement.executeUpdate()
        }

    private fun read(rows: java.sql.ResultSet): StoredNotification {
        val screen = rows.getString("target_screen")
        val targetParams = runCatching { Json.decodeFromString(params, rows.getString("target_params")) }.getOrDefault(emptyMap())
        return StoredNotification(
            id = rows.getObject("id", UUID::class.java),
            userId = rows.getObject("user_id", UUID::class.java),
            moduleId = rows.getString("module_id"),
            title = rows.getString("title"),
            text = rows.getString("body"),
            target = screen?.takeIf { it.isNotBlank() }?.let { NotificationTarget(it, targetParams) },
            createdAt = rows.getTimestamp("created_at").toInstant(),
            readAt = rows.getTimestamp("read_at")?.toInstant(),
        )
    }
}

/**
 * Notificador de un modulo: crea notificaciones siempre con el id del propio modulo como origen, de modo
 * que un modulo no puede hacerse pasar por otro. Se obtiene con `access.notificationsFor(moduleId)`, o ya
 * ligado en `ModuleJobContext.notifier` y `McpToolCall.notifier`.
 */
class ModuleNotifier internal constructor(val moduleId: String, private val service: NotificationService) {
    /** Crea la notificacion para [userId] y devuelve su id; el clic abre [target] en este modulo. */
    fun notify(tenant: Connection, userId: UUID, title: String, text: String = "", target: NotificationTarget? = null): UUID =
        service.notify(tenant, NewNotification(userId, moduleId, title, text, target))
}
