package com.ideasdeveloper.idc.server.auth

import com.ideasdeveloper.idc.server.auth.api.authRoutes
import com.ideasdeveloper.idc.server.auth.application.ScopedLoginService
import com.ideasdeveloper.idc.server.auth.domain.LoginScope
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AuthLogoutRouteTest {
    // El servicio de login no se usa en /auth/logout: cualquier acceso falla la prueba.
    private val loginService = ScopedLoginService(
        LoginScope { error("No se debe iniciar sesion") },
        { error("Sin consulta de empresas") },
        { error("Sin conexion de empresa") },
    )

    @Test
    fun `logout con token responde 204 y entrega el token`() = testApplication {
        val llamadas = mutableListOf<Pair<String?, String>>()
        application { install(ContentNegotiation) { json() }; routing { authRoutes(loginService, logout = { empresa, token -> llamadas += empresa to token; true }) } }

        val respuesta = client.post("/auth/logout") { header(HttpHeaders.Authorization, "Bearer token-abc") }

        assertEquals(HttpStatusCode.NoContent, respuesta.status)
        assertEquals(listOf<Pair<String?, String>>(null to "token-abc"), llamadas)
    }

    @Test
    fun `logout es idempotente aunque el token ya no exista`() = testApplication {
        var llamadas = 0
        // Segunda llamada: el token ya estaba cerrado (false), pero la respuesta no lo revela.
        application { install(ContentNegotiation) { json() }; routing { authRoutes(loginService, logout = { _, _ -> llamadas++; llamadas == 1 }) } }

        repeat(2) {
            val respuesta = client.post("/auth/logout") { header(HttpHeaders.Authorization, "Bearer token-abc") }
            assertEquals(HttpStatusCode.NoContent, respuesta.status)
        }
        assertEquals(2, llamadas)
    }

    @Test
    fun `logout con codigo de empresa lo pasa al cierre de sesion`() = testApplication {
        val llamadas = mutableListOf<Pair<String?, String>>()
        application { install(ContentNegotiation) { json() }; routing { authRoutes(loginService, logout = { empresa, token -> llamadas += empresa to token; true }) } }

        val respuesta = client.post("/auth/logout") {
            header(HttpHeaders.Authorization, "Bearer token-abc")
            header("X-Company-Code", "empresa_a")
        }

        assertEquals(HttpStatusCode.NoContent, respuesta.status)
        assertEquals(listOf<Pair<String?, String>>("empresa_a" to "token-abc"), llamadas)
    }

    @Test
    fun `logout sin token responde 400 y no cierra nada`() = testApplication {
        var llamadas = 0
        application { install(ContentNegotiation) { json() }; routing { authRoutes(loginService, logout = { _, _ -> llamadas++; true }) } }

        val respuesta = client.post("/auth/logout")

        assertEquals(HttpStatusCode.BadRequest, respuesta.status)
        assertEquals(0, llamadas)
    }

    @Test
    fun `logout con codigo de empresa invalido responde 400`() = testApplication {
        var llamadas = 0
        application { install(ContentNegotiation) { json() }; routing { authRoutes(loginService, logout = { _, _ -> llamadas++; true }) } }

        val respuesta = client.post("/auth/logout") {
            header(HttpHeaders.Authorization, "Bearer token-abc")
            header("X-Company-Code", "jdbc:postgresql://atacante/db")
        }

        assertEquals(HttpStatusCode.BadRequest, respuesta.status)
        assertEquals(0, llamadas)
    }

    @Test
    fun `logout responde sin cache`() = testApplication {
        application { install(ContentNegotiation) { json() }; routing { authRoutes(loginService, logout = { _, _ -> true }) } }

        val respuesta = client.post("/auth/logout") { header(HttpHeaders.Authorization, "Bearer token-abc") }

        assertTrue(respuesta.headers[HttpHeaders.CacheControl].orEmpty().contains("no-store"))
    }

    // prueba de integración pendiente: revocación real en application_sessions
}
