package com.ideasdeveloper.idc.server.modules

import java.net.InetAddress
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

// Reglas del SMTP de empresa sin base de datos (el SQL se cubre al aplicar la migracion tenant V007).
class CompanyMailSettingsTest {
    // Cifrado falso y reversible: "enc:v1:" + el texto al reves.
    private val seal: (String) -> String = { "enc:v1:" + it.reversed() }
    private val open: (String) -> String = { it.removePrefix("enc:v1:").reversed() }

    private val publicHost: (String) -> List<InetAddress> = { listOf(InetAddress.getByAddress(byteArrayOf(8, 8, 8, 8))) }
    private val privateHost: (String) -> List<InetAddress> = { listOf(InetAddress.getByAddress(byteArrayOf(10, 0, 0, 5))) }

    private fun input(
        host: String = "smtp.empresa.com",
        port: Int = 587,
        username: String = "usuario",
        from: String = "a@empresa.com",
        security: String = "starttls",
        password: String? = null,
    ) = CompanyMailInput(host, port, username, from, security, password)

    private fun row(passwordEnc: String?, host: String = "smtp.empresa.com") =
        CompanyMail.Row(host, 587, "usuario", passwordEnc, "no-responder@empresa.com", "starttls")

    @Test
    fun passwordIsSealedNeverStoredInClear() {
        val stored = CompanyMail.sealedPassword("secreto", seal)
        assertNotEquals("secreto", stored)
        assertTrue(stored!!.startsWith("enc:v1:"))
    }

    @Test
    fun readOpensThePasswordAndNeverTrustsTheHost() {
        val settings = CompanyMail.toSettings(row(seal("secreto")), open)!!
        assertEquals("secreto", settings.password)
        assertEquals("smtp.empresa.com", settings.host)
        assertEquals("no-responder@empresa.com", settings.from)
        assertFalse(settings.trusted)
        assertTrue(settings.isUsable)
    }

    @Test
    fun blankHostMeansNoSettings() {
        assertNull(CompanyMail.toSettings(row(null, host = ""), open))
        assertNull(CompanyMail.toSettings(row(null, host = "   "), open))
    }

    @Test
    fun emptyPasswordDeletesAndNullIsHandledByTheCaller() {
        assertNull(CompanyMail.sealedPassword("", seal))
        assertNull(CompanyMail.sealedPassword(null, seal))
        // Sin contrasena guardada, el SMTP se lee con contrasena vacia.
        assertEquals("", CompanyMail.toSettings(row(null), open)!!.password)
    }

    @Test
    fun formNeverExposesThePasswordAndReflectsIfItIsSet() {
        val withPassword = CompanyMail.toForm(row(seal("secreto")))
        assertTrue(withPassword.passwordSet)
        assertFalse(withPassword.toString().contains("secreto"))
        assertFalse(withPassword.toString().contains("terces"))
        assertFalse(CompanyMail.toForm(row(null)).passwordSet)
        assertFalse(CompanyMail.toForm(row("")).passwordSet)
    }

    @Test
    fun disallowedPortIsRejectedBeforeSaving() {
        val failure = assertFailsWith<MailException> {
            CompanyMail.validate(input(host = "smtp.empresa.com", port = 2525, from = "a@empresa.com"), publicHost)
        }
        assertTrue(failure.message!!.contains("puerto"))
    }

    @Test
    fun privateHostIsRejected() {
        assertFailsWith<MailException> {
            CompanyMail.validate(input(host = "interno.local", port = 587, from = "a@empresa.com"), privateHost)
        }
    }

    @Test
    fun validInputPassesAndBlankHostClears() {
        CompanyMail.validate(input(host = "smtp.empresa.com", port = 465, from = "a@empresa.com", security = "ssl"), publicHost)
        CompanyMail.validate(input(host = "", port = 587, username = "", from = "", security = "starttls"), privateHost)
    }

    @Test
    fun unknownSecurityOrMissingFromIsRejected() {
        assertFailsWith<MailException> {
            CompanyMail.validate(input(host = "smtp.empresa.com", from = "a@empresa.com", security = "tls13"), publicHost)
        }
        assertFailsWith<MailException> {
            CompanyMail.validate(input(host = "smtp.empresa.com", from = ""), publicHost)
        }
    }

    @Test
    fun reservedSealedPrefixInPasswordIsRejected() {
        assertFailsWith<MailException> { CompanyMail.validate(input(password = "enc:v1:abc"), publicHost) }
    }

    @Test
    fun outOfRangePortIsRejectedEvenWithBlankHost() {
        assertFailsWith<MailException> { CompanyMail.validate(input(host = "", port = 70000, from = ""), privateHost) }
        assertFailsWith<MailException> { CompanyMail.validate(input(host = "", port = 0, from = ""), privateHost) }
    }

    @Test
    fun blankHostClearsAndValidatesWithEmptyFrom() {
        CompanyMail.validate(input(host = "", from = "", username = ""), privateHost)
    }

    @Test
    fun invalidOrMultilineFromAndUsernameAreRejected() {
        assertFailsWith<MailException> { CompanyMail.validate(input(from = "no-es-correo"), publicHost) }
        assertFailsWith<MailException> { CompanyMail.validate(input(from = "a@empresa.com\r\nBcc: x@y.com"), publicHost) }
        assertFailsWith<MailException> { CompanyMail.validate(input(username = "u\nx"), publicHost) }
        CompanyMail.validate(input(from = "Mi Empresa <a@empresa.com>"), publicHost)
    }

    @Test
    fun tooLongFieldsAreRejected() {
        assertFailsWith<MailException> { CompanyMail.validate(input(username = "u".repeat(256)), publicHost) }
    }

    @Test
    fun resolveUsablePrefersTheCompanyThenTheServer() {
        val company = SmtpSettings("smtp.empresa.com", 587, "u", "p", "a@empresa.com")
        val server = SmtpSettings("smtp.servidor.com", 587, "u", "p", "s@servidor.com", trusted = true)
        assertEquals(company, CompanyMail.resolveUsable(company, server))
        assertEquals(server, CompanyMail.resolveUsable(company.copy(host = ""), server))
        assertEquals(server, CompanyMail.resolveUsable(null, server))
        assertNull(CompanyMail.resolveUsable(null, server.copy(from = "")))
    }

    @Test
    fun emptyJsonBodyDoesNotDeserialize() {
        assertFailsWith<kotlinx.serialization.SerializationException> {
            kotlinx.serialization.json.Json.decodeFromString<CompanyMailInput>("{}")
        }
    }

    // Conexion falsa que registra el SQL y los textos que se enlazan.
    private class Recorder {
        val sql = mutableListOf<String>()
        val strings = mutableListOf<String?>()
        val connection = java.lang.reflect.Proxy.newProxyInstance(javaClass.classLoader, arrayOf(java.sql.Connection::class.java)) { _, method, args ->
            if (method.name == "prepareStatement") {
                sql += args!![0] as String
                java.lang.reflect.Proxy.newProxyInstance(javaClass.classLoader, arrayOf(java.sql.PreparedStatement::class.java)) { _, inner, innerArgs ->
                    when (inner.name) {
                        "setString" -> { strings += innerArgs!![1] as String?; null }
                        "executeUpdate" -> 1
                        else -> null
                    }
                }
            } else null
        } as java.sql.Connection
    }

    private val fakeBox = object : com.ideasdeveloper.idc.server.security.SecretBox {
        override val available = true
        override fun seal(tenant: java.sql.Connection, plain: String) = this@CompanyMailSettingsTest.seal(plain)
        override fun open(tenant: java.sql.Connection, value: String) = this@CompanyMailSettingsTest.open(value)
    }

    @Test
    fun savingWithBlankHostForcesPasswordToNull() {
        val recorder = Recorder()
        CompanyMail.save(recorder.connection, fakeBox, input(host = "", from = "", username = "", password = "secreto"))
        assertTrue(recorder.sql.single().contains("password_enc"))
        assertFalse(recorder.strings.any { it?.startsWith("enc:v1:") == true })
        assertTrue(recorder.strings.contains(null))
    }

    @Test
    fun savingWithoutPasswordLeavesTheStoredOneAlone() {
        val recorder = Recorder()
        CompanyMail.save(recorder.connection, fakeBox, input(password = null))
        assertFalse(recorder.sql.single().contains("password_enc"))
    }
}
