package com.ideasdeveloper.idc.modules.agentes

import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

// Regla de gracia del reuso de refresh tokens. La rotacion, el historial y la revocacion de la familia en BD
// quedan pendientes de una prueba de integracion (no hay infraestructura de base de datos en este modulo).
class AgentRefreshReuseTest {
    private val now = Instant.parse("2026-01-01T12:00:00Z")

    @Test
    fun `reuso dentro de la gracia de 30 s no revoca`() {
        assertFalse(AgentTokens.isRefreshReuse(now.minusSeconds(5), now))
        assertFalse(AgentTokens.isRefreshReuse(now.minusSeconds(30), now))
    }

    @Test
    fun `el corte que recibe el SQL coincide con la regla de reuso`() {
        val cutoff = AgentTokens.refreshGraceCutoff(now)
        assertTrue(cutoff == now.minus(AgentTokens.RefreshReuseGrace))
        assertFalse(AgentTokens.isRefreshReuse(cutoff, now))
        assertTrue(AgentTokens.isRefreshReuse(cutoff.minusMillis(1), now))
    }

    @Test
    fun `reuso de una generacion rotada hace mas de 30 s es reuso`() {
        assertTrue(AgentTokens.isRefreshReuse(now.minusSeconds(31), now))
        assertTrue(AgentTokens.isRefreshReuse(now.minusSeconds(3600), now))
    }
}
