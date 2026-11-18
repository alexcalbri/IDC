package com.ideasdeveloper.idc.server.modules

import java.net.InetAddress
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MailHostGuardTest {
    // Resolutor falso: nunca toca la red real.
    private fun resolveTo(vararg ips: String): (String) -> List<InetAddress> = { _ -> ips.map { InetAddress.getByName(it) } }

    @Test
    fun `los puertos de correo permitidos son los esperados`() {
        assertEquals(setOf(25, 465, 587, 993, 143), MailHostGuard.AllowedPorts)
    }

    @Test
    fun `check acepta los puertos de correo con host publico`() {
        listOf(25, 465, 587, 993, 143).forEach { port ->
            MailHostGuard.check("mail.ejemplo.com", port, resolve = resolveTo("8.8.8.8"))
        }
    }

    @Test
    fun `check rechaza puertos que no son de correo`() {
        listOf(80, 8025, 3306).forEach { port ->
            assertFailsWith<MailException> { MailHostGuard.check("mail.ejemplo.com", port, resolve = resolveTo("8.8.8.8")) }
        }
    }

    @Test
    fun `blockedAddress rechaza direcciones no publicas`() {
        listOf("127.0.0.1", "10.0.0.1", "192.168.1.1", "169.254.1.1", "::1", "fe80::1", "0.0.0.0", "224.0.0.1").forEach { ip ->
            assertTrue(MailHostGuard.blockedAddress(InetAddress.getByName(ip)), "$ip debia bloquearse")
        }
    }

    @Test
    fun `blockedAddress rechaza rangos reservados y de documentacion`() {
        listOf("192.0.0.1", "192.0.2.7", "198.18.0.1", "198.19.255.254", "198.51.100.9", "203.0.113.9", "240.0.0.1", "255.255.255.255").forEach { ip ->
            assertTrue(MailHostGuard.blockedAddress(InetAddress.getByName(ip)), "$ip debia bloquearse")
        }
    }

    @Test
    fun `blockedAddress revalida el IPv4 incrustado en NAT64 y 6to4`() {
        // 64:ff9b::7f00:1 = 127.0.0.1 por NAT64; 2002:0a00:0001:: = 10.0.0.1 por 6to4.
        listOf("64:ff9b::7f00:1", "64:ff9b::a00:1", "2002:7f00:1::1", "2002:a00:1::").forEach { ip ->
            assertTrue(MailHostGuard.blockedAddress(InetAddress.getByName(ip)), "$ip debia bloquearse")
        }
        // 8.8.8.8 incrustado es publico.
        assertFalse(MailHostGuard.blockedAddress(InetAddress.getByName("64:ff9b::808:808")))
        assertFalse(MailHostGuard.blockedAddress(InetAddress.getByName("2002:808:808::1")))
    }

    @Test
    fun `blockedAddress acepta direcciones publicas`() {
        listOf("8.8.8.8", "1.1.1.1").forEach { ip ->
            assertFalse(MailHostGuard.blockedAddress(InetAddress.getByName(ip)), "$ip no debia bloquearse")
        }
    }

    @Test
    fun `check bloquea un host que resuelve a una IP privada`() {
        assertFailsWith<MailException> { MailHostGuard.check("interno.ejemplo.com", 587, resolve = resolveTo("10.0.0.5")) }
    }

    @Test
    fun `check bloquea si alguna de las IP resueltas es privada`() {
        assertFailsWith<MailException> { MailHostGuard.check("mixto.ejemplo.com", 587, resolve = resolveTo("8.8.8.8", "127.0.0.1")) }
    }

    @Test
    fun `check con allowPrivate acepta IP privada pero sigue validando el puerto`() {
        MailHostGuard.check("interno.ejemplo.com", 587, allowPrivate = true, resolve = resolveTo("10.0.0.5"))
        assertFailsWith<MailException> { MailHostGuard.check("interno.ejemplo.com", 80, allowPrivate = true, resolve = resolveTo("10.0.0.5")) }
    }

    @Test
    fun `check falla si el host no resuelve`() {
        assertFailsWith<MailException> { MailHostGuard.check("no-existe.ejemplo.com", 587, resolve = { error("sin DNS") }) }
    }
}
