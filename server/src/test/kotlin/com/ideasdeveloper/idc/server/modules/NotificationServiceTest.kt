package com.ideasdeveloper.idc.server.modules

import java.lang.reflect.Proxy
import java.sql.Connection
import java.time.Duration
import java.time.Instant
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

// Reglas del servicio (propiedad por usuario, paginas, leidas, limpieza) con un almacen en memoria:
// el SQL del almacen real se cubre al aplicar la migracion core V005 en una empresa.
class NotificationServiceTest {
    private class MemoryStore : NotificationStore {
        val rows = mutableListOf<StoredNotification>()
        override fun insert(notification: StoredNotification) {
            rows += notification
        }
        override fun list(userId: UUID, limit: Int, offset: Int, unreadOnly: Boolean) =
            rows.filter { it.userId == userId && (!unreadOnly || it.readAt == null) }
                .sortedByDescending { it.createdAt }.drop(offset).take(limit)
        override fun unreadCount(userId: UUID) = rows.count { it.userId == userId && it.readAt == null }
        override fun markRead(userId: UUID, id: UUID, at: Instant): Boolean {
            val index = rows.indexOfFirst { it.id == id && it.userId == userId && it.readAt == null }
            if (index < 0) return false
            rows[index] = rows[index].copy(readAt = at)
            return true
        }
        override fun markAllRead(userId: UUID, at: Instant): Int {
            var changed = 0
            rows.indices.forEach { index ->
                if (rows[index].userId == userId && rows[index].readAt == null) {
                    rows[index] = rows[index].copy(readAt = at)
                    changed++
                }
            }
            return changed
        }
        var batches = 0
        override fun deleteCreatedBefore(before: Instant, limit: Int): Int {
            batches++
            val expired = rows.filter { it.createdAt < before }.sortedBy { it.createdAt }.take(limit)
            rows.removeAll(expired.toSet())
            return expired.size
        }
    }

    // El almacen en memoria no usa la conexion.
    private val tenant: Connection = Proxy.newProxyInstance(javaClass.classLoader, arrayOf(Connection::class.java)) { _, _, _ -> null } as Connection
    private val store = MemoryStore()
    private var now = Instant.parse("2026-01-01T00:00:00Z")
    private val service = NotificationService({ store }, { now })
    private val ana = UUID.randomUUID()
    private val luis = UUID.randomUUID()

    private fun notify(user: UUID, title: String, target: NotificationTarget? = null) =
        service.notify(tenant, NewNotification(user, "crm", title, "texto de $title", target)).also { now = now.plusSeconds(60) }

    @Test
    fun createdNotificationKeepsItsDataAndStartsUnread() {
        val target = NotificationTarget("lead", mapOf("id" to "42"))
        val id = notify(ana, "  Te asignaron un lead  ", target)
        val stored = store.rows.single()
        assertEquals(id, stored.id)
        assertEquals("Te asignaron un lead", stored.title)
        assertEquals("crm", stored.moduleId)
        assertEquals(target, stored.target)
        assertNull(stored.readAt)
        assertEquals(1, service.unreadCount(tenant, ana))
    }

    @Test
    fun titleIsRequiredAndLongTextIsCut() {
        assertFailsWith<IllegalArgumentException> { service.notify(tenant, NewNotification(ana, "crm", "   ")) }
        notify(ana, "x".repeat(500))
        assertEquals(NotificationService.MaxTitleLength, store.rows.single().title.length)
    }

    @Test
    fun usersOnlySeeTheirOwnNotifications() {
        notify(ana, "de Ana")
        notify(luis, "de Luis")
        assertEquals(listOf("de Ana"), service.list(tenant, ana).items.map { it.title })
        assertEquals(listOf("de Luis"), service.list(tenant, luis).items.map { it.title })
        assertEquals(1, service.unreadCount(tenant, ana))
    }

    @Test
    fun listIsNewestFirstAndPaginated() {
        listOf("uno", "dos", "tres").forEach { notify(ana, it) }
        val first = service.list(tenant, ana, limit = 2)
        assertEquals(listOf("tres", "dos"), first.items.map { it.title })
        assertTrue(first.hasMore)
        val second = service.list(tenant, ana, limit = 2, offset = 2)
        assertEquals(listOf("uno"), second.items.map { it.title })
        assertFalse(second.hasMore)
        assertEquals(3, second.unread)
    }

    @Test
    fun markReadChangesOnlyTheUsersOwnNotification() {
        val id = notify(ana, "uno")
        assertFalse(service.markRead(tenant, luis, id), "otro usuario no puede marcarla")
        assertEquals(1, service.unreadCount(tenant, ana))
        assertTrue(service.markRead(tenant, ana, id))
        assertFalse(service.markRead(tenant, ana, id), "ya estaba leida")
        assertEquals(0, service.unreadCount(tenant, ana))
        assertTrue(service.list(tenant, ana).items.single().readAt != null)
    }

    @Test
    fun markAllReadLeavesOtherUsersAlone() {
        notify(ana, "uno")
        notify(ana, "dos")
        notify(luis, "tres")
        assertEquals(2, service.markAllRead(tenant, ana))
        assertEquals(0, service.unreadCount(tenant, ana))
        assertEquals(1, service.unreadCount(tenant, luis))
        assertEquals(0, service.markAllRead(tenant, ana))
        assertEquals(listOf("tres"), service.list(tenant, luis, unreadOnly = true).items.map { it.title })
    }

    @Test
    fun purgeRemovesOnlyNotificationsOlderThanNinetyDays() {
        now = Instant.parse("2026-01-01T00:00:00Z")
        service.notify(tenant, NewNotification(ana, "crm", "vieja"))
        now = now.plus(Duration.ofDays(30))
        service.notify(tenant, NewNotification(ana, "crm", "media"))
        now = now.plus(Duration.ofDays(60)).plusSeconds(1)
        service.notify(tenant, NewNotification(luis, "crm", "nueva"))
        // La primera tiene 90 dias y un segundo: se limpia; la de 60 dias y la nueva se conservan.
        assertEquals(1, service.purgeExpired(tenant))
        assertEquals(listOf("media"), service.list(tenant, ana).items.map { it.title })
        assertEquals(1, service.list(tenant, luis).items.size)
        assertEquals(0, service.purgeExpired(tenant))
    }

    @Test
    fun targetWithoutScreenIsDropped() {
        notify(ana, "sin destino", NotificationTarget("  "))
        assertNull(store.rows.single().target)
    }

    @Test
    fun purgeWorksInBatchesAndStopsAtTheMaximumPerPass() {
        val batched = NotificationService({ store }, { now }, purgeBatchSize = 2, maxPurgeBatches = 3)
        repeat(8) { index -> store.rows += StoredNotification(UUID.randomUUID(), ana, "crm", "n$index", "", null, now.minus(Duration.ofDays(200)).plusSeconds(index.toLong()), null) }
        // Tres lotes de dos: borra seis y deja las dos mas nuevas para la siguiente pasada.
        assertEquals(6, batched.purgeExpired(tenant))
        assertEquals(3, store.batches)
        assertEquals(listOf("n6", "n7"), store.rows.map { it.title })
        // Un lote lleno obliga a pedir otro; el lote vacio que sigue termina la pasada.
        store.batches = 0
        assertEquals(2, batched.purgeExpired(tenant))
        assertEquals(2, store.batches)
        // Un lote incompleto termina la pasada sin pedir mas.
        store.batches = 0
        store.rows += StoredNotification(UUID.randomUUID(), ana, "crm", "n8", "", null, now.minus(Duration.ofDays(200)), null)
        assertEquals(1, batched.purgeExpired(tenant))
        assertEquals(1, store.batches)
    }

    @Test
    fun moduleNotifierAlwaysUsesItsOwnModuleAsOrigin() {
        val crm = ModuleNotifier("crm", service)
        val hotspot = ModuleNotifier("hotspot", service)
        crm.notify(tenant, ana, "Te asignaron un lead", "Ana", NotificationTarget("lead", mapOf("id" to "7")))
        hotspot.notify(tenant, ana, "Nueva red")
        assertEquals(setOf("crm", "hotspot"), store.rows.map { it.moduleId }.toSet())
        assertEquals("crm", store.rows.first { it.title == "Te asignaron un lead" }.moduleId)
        assertEquals(NotificationTarget("lead", mapOf("id" to "7")), store.rows.first { it.moduleId == "crm" }.target)
    }
}
