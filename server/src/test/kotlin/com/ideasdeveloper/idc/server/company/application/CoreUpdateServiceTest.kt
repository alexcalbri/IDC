package com.ideasdeveloper.idc.server.company.application

import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CoreUpdateServiceTest {
    private val now = Instant.parse("2026-01-01T12:00:00Z")

    private class FakeStore(var last: CoreUpdateRow? = null, var active: Boolean = false) : CoreUpdateStore {
        var inserted: Triple<String, String, String>? = null

        override fun latest() = last

        override fun insertPending(targetVersion: String, fromVersion: String, requestedBy: String): CoreUpdateRow {
            if (active) throw CoreUpdateActiveException()
            inserted = Triple(targetVersion, fromVersion, requestedBy)
            return row(status = "pending", target = targetVersion, from = fromVersion, requestedAt = Instant.parse("2026-01-01T12:00:00Z"))
        }
    }

    private fun service(store: FakeStore, current: String = "0.3.0", latest: String? = "0.4.0") =
        CoreUpdateService(store, current, { latest }, { now })

    @Test
    fun `solo ofrece actualizar si la ultima es semver valida y mayor`() {
        assertTrue(CoreUpdateService.isUpdateAvailable("0.3.0", "0.4.0"))
        assertTrue(CoreUpdateService.isUpdateAvailable("0.3.9", "0.10.0"))
        assertFalse(CoreUpdateService.isUpdateAvailable("0.3.0", "0.3.0"))
        assertFalse(CoreUpdateService.isUpdateAvailable("0.4.0", "0.3.0"))
        assertFalse(CoreUpdateService.isUpdateAvailable("0.3.0", null))
        assertFalse(CoreUpdateService.isUpdateAvailable("0.3.0", ""))
        assertFalse(CoreUpdateService.isUpdateAvailable("0.3.0", "0.4"))
        assertFalse(CoreUpdateService.isUpdateAvailable("0.3.0", "0.4.0-beta"))
        assertFalse(CoreUpdateService.isUpdateAvailable("0.3.0", "latest"))
    }

    @Test
    fun `solicitar con catalogo no disponible no inserta`() {
        val store = FakeStore()
        assertEquals(CoreUpdateOutcome.CatalogUnavailable, service(store, latest = null).request("owner"))
        assertEquals(CoreUpdateOutcome.CatalogUnavailable, service(store, latest = "  ").request("owner"))
        assertNull(store.inserted)
    }

    @Test
    fun `solicitar con el servidor al dia responde UpToDate`() {
        val store = FakeStore()
        assertEquals(CoreUpdateOutcome.UpToDate, service(store, latest = "0.3.0").request("owner"))
        assertEquals(CoreUpdateOutcome.UpToDate, service(store, latest = "0.2.0").request("owner"))
        assertNull(store.inserted)
    }

    @Test
    fun `solicitar con version nueva inserta pending con versiones y solicitante`() {
        val store = FakeStore()
        val outcome = service(store).request("owner")
        val accepted = assertIs<CoreUpdateOutcome.Accepted>(outcome)
        assertEquals(Triple("0.4.0", "0.3.0", "owner"), store.inserted)
        assertEquals("pending", accepted.request.status)
        assertEquals("0.4.0", accepted.request.targetVersion)
        assertEquals("0.3.0", accepted.request.fromVersion)
    }

    @Test
    fun `solicitar con una activa responde InProgress`() {
        assertEquals(CoreUpdateOutcome.InProgress, service(FakeStore(active = true)).request("owner"))
    }

    @Test
    fun `stalePending solo si sigue pending pasados 3 minutos`() {
        val at = Instant.parse("2026-01-01T12:00:00Z")
        assertFalse(CoreUpdateService.isStalePending("pending", at, at.plusSeconds(180)))
        assertTrue(CoreUpdateService.isStalePending("pending", at, at.plusSeconds(181)))
        assertFalse(CoreUpdateService.isStalePending("running", at, at.plusSeconds(3600)))
        assertFalse(CoreUpdateService.isStalePending("error", at, at.plusSeconds(3600)))
    }

    @Test
    fun `estado mapea la solicitud mas reciente a DTO`() {
        val requestedAt = Instant.parse("2026-01-01T11:50:00Z")
        val store = FakeStore(
            last = row(
                status = "pending",
                requestedAt = requestedAt,
                startedAt = Instant.parse("2026-01-01T11:51:00Z"),
                message = "m",
                logTail = "log",
            )
        )
        val status = service(store).status()
        assertEquals("0.3.0", status.currentVersion)
        assertEquals("0.4.0", status.latestVersion)
        assertTrue(status.updateAvailable)
        val dto = status.request!!
        assertEquals("2026-01-01T11:50:00Z", dto.requestedAt)
        assertEquals("2026-01-01T11:51:00Z", dto.startedAt)
        assertNull(dto.finishedAt)
        assertEquals("m", dto.message)
        assertEquals("log", dto.logTail)
        assertTrue(dto.stalePending)
    }

    @Test
    fun `estado cachea el catalogo y conserva la ultima lectura buena si falla`() {
        var clock = now
        var fetches = 0
        var response: String? = "0.4.0"
        val service = CoreUpdateService(FakeStore(), "0.3.0", { fetches++; response }, { clock })
        assertEquals("0.4.0", service.status().latestVersion)
        clock = clock.plusSeconds(30)
        service.status()
        assertEquals(1, fetches)
        // Pasado el minuto se refresca; si falla, se conserva la ultima buena.
        clock = clock.plusSeconds(60)
        response = null
        assertEquals("0.4.0", service.status().latestVersion)
        assertEquals(2, fetches)
        // Pasados 10 minutos sin lectura buena, se descarta.
        clock = clock.plusSeconds(11 * 60)
        assertNull(service.status().latestVersion)
    }

    @Test
    fun `estado no consulta el catalogo con una solicitud activa`() {
        var fetches = 0
        val store = FakeStore(last = row(status = "running", target = "0.5.0", requestedAt = now))
        val status = CoreUpdateService(store, "0.3.0", { fetches++; "0.4.0" }, { now }).status()
        assertEquals(0, fetches)
        assertEquals("0.5.0", status.latestVersion)
    }

    @Test
    fun `estado sin solicitudes ni catalogo`() {
        val status = service(FakeStore(), latest = null).status()
        assertNull(status.request)
        assertNull(status.latestVersion)
        assertFalse(status.updateAvailable)
    }

    private companion object {
        fun row(
            status: String,
            target: String = "0.4.0",
            from: String = "0.3.0",
            requestedAt: Instant,
            startedAt: Instant? = null,
            message: String? = null,
            logTail: String? = null,
        ) = CoreUpdateRow(1, target, from, status, "owner", requestedAt, startedAt, null, message, logTail)
    }
}
