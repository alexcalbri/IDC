package com.ideasdeveloper.idc.server.auth.application

import com.ideasdeveloper.idc.server.auth.domain.LoginCredentials
import com.ideasdeveloper.idc.server.auth.domain.LoginScope
import com.ideasdeveloper.idc.server.auth.domain.LoginSuccessResponse
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame

class ScopedLoginServiceTest {
    @Test
    fun `server login does not inspect company databases`() {
        val expected = LoginSuccessResponse("id", "owner", "token", 60)
        val service = ScopedLoginService(LoginScope { expected },
            { error("No company lookup allowed") }, { error("No tenant connection allowed") })
        assertSame(expected, service.login(LoginCredentials("owner", "secret")))
    }

    @Test
    fun `unknown inactive and invalid companies cannot fall back to server login`() {
        var lookups = 0
        val service = ScopedLoginService(LoginScope { error("No fallback allowed") },
            { lookups++; null }, { error("No tenant connection allowed") })
        assertNull(service.login(LoginCredentials("owner", "secret", "disabled")))
        assertNull(service.login(LoginCredentials("owner", "secret", "jdbc:postgresql://attacker/db")))
        assertNull(service.login(LoginCredentials("owner", "secret", "")))
        assertEquals(1, lookups)
    }

    @Test
    fun `tenant selection uses registry database and never falls back after rejection`() {
        val targets = mutableListOf<String>()
        val service = ScopedLoginService(LoginScope { error("No fallback allowed") },
            { code -> if (code == "company_a") "internal_db_a" else "internal_db_b" },
            { database -> targets += database; LoginScope { null } })
        assertNull(service.login(LoginCredentials("user_a", "secret", "company_b")))
        assertEquals(listOf("internal_db_b"), targets)
    }

    @Test
    fun `active state is checked again on every login`() {
        var active = true
        var authentications = 0
        val service = ScopedLoginService(LoginScope { error("No fallback") },
            { if (active) "db" else null },
            { LoginScope { authentications++; null } })
        service.login(LoginCredentials("user", "secret", "company"))
        active = false
        service.login(LoginCredentials("user", "secret", "company"))
        assertEquals(1, authentications)
    }
}
