package com.ideasdeveloper.idc.modules.hotspot

import com.ideasdeveloper.idc.server.modules.CompanyMail
import com.ideasdeveloper.idc.server.security.SecretBox
import com.ideasdeveloper.idc.server.security.SecretsUnavailableException
import java.sql.Connection
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class HotspotMailMigrationTest {
    /** Cifrado falso y reversible: "enc:v1:" + el texto al reves. */
    private class FakeBox(override val available: Boolean = true) : SecretBox {
        override fun seal(tenant: Connection, plain: String): String {
            if (!available) throw SecretsUnavailableException()
            return if (isSealed(plain)) plain else SecretBox.SealedPrefix + plain.reversed()
        }

        override fun open(tenant: Connection, value: String): String =
            if (!isSealed(value)) value else value.removePrefix(SecretBox.SealedPrefix).reversed()
    }

    private val untouched = java.lang.reflect.Proxy.newProxyInstance(javaClass.classLoader, arrayOf(Connection::class.java)) { _, method, _ ->
        throw AssertionError("No debia usar la conexion: ${method.name}")
    } as Connection

    private val own = HotspotMailMigration.Legacy("empresa", "smtp.hotspot.test", 465, "wifi", "clave-en-claro", "wifi@hotel.test", "ssl")

    @Test
    fun seedsTheCompanyWhenItHasNoSmtpAndSealsTheOldPlainPassword() {
        val plan = HotspotMailMigration.plan(own, companyHasHost = false, secretsAvailable = true)
        val copy = plan as HotspotMailMigration.Plan.Copy
        assertEquals("smtp.hotspot.test", copy.input.host)
        assertEquals(465, copy.input.port)
        assertEquals("ssl", copy.input.security)
        assertEquals("wifi@hotel.test", copy.input.from)
        // CompanyMail.save sella con esta misma funcion: la contrasena queda cifrada en company_mail_settings.
        val sealed = CompanyMail.sealedPassword(copy.input.password) { FakeBox().seal(untouched, it) }
        assertTrue(sealed!!.startsWith(SecretBox.SealedPrefix))
        assertEquals("clave-en-claro", FakeBox().open(untouched, sealed))
    }

    @Test
    fun neverOverwritesAnSmtpTheCompanyAlreadyHas() {
        assertEquals(HotspotMailMigration.Plan.CompanyHasMail, HotspotMailMigration.plan(own, companyHasHost = true, secretsAvailable = true))
    }

    @Test
    fun waitsWithoutTheMasterKeyInsteadOfLeavingThePasswordUnsealed() {
        assertTrue(HotspotMailMigration.plan(own, companyHasHost = false, secretsAvailable = false) is HotspotMailMigration.Plan.Wait)
        // Sin contrasena no hace falta la llave.
        assertTrue(HotspotMailMigration.plan(own.copy(password = null), companyHasHost = false, secretsAvailable = false) is HotspotMailMigration.Plan.Copy)
    }

    @Test
    fun nothingToMigrateWhenHotspotUsedTheServerSmtp() {
        val server = own.copy(mode = "servidor")
        assertEquals(HotspotMailMigration.Plan.Skip, HotspotMailMigration.plan(server, companyHasHost = false, secretsAvailable = true))
        assertEquals(HotspotMailMigration.Plan.Skip, HotspotMailMigration.plan(own.copy(host = " "), companyHasHost = false, secretsAvailable = true))
        assertTrue(HotspotMailMigration.Legacy("servidor", null, 587, null, null, null, "starttls").isClean)
        assertTrue(!own.isClean)
    }
}
