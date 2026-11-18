package com.ideasdeveloper.idc.modules.crm

import com.ideasdeveloper.idc.server.modules.CompanyMail
import com.ideasdeveloper.idc.server.modules.SmtpSettings
import com.ideasdeveloper.idc.server.security.SecretBox
import com.ideasdeveloper.idc.server.security.SecretsUnavailableException
import java.sql.Connection
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CrmMailMigrationTest {
    /** Cifrado falso y reversible: "enc:v1:" + el texto al reves. Sin llave, seal y open fallan como el real. */
    private class FakeBox(override val available: Boolean = true) : SecretBox {
        override fun seal(tenant: Connection, plain: String): String {
            if (!available) throw SecretsUnavailableException()
            return if (isSealed(plain)) plain else SecretBox.SealedPrefix + plain.reversed()
        }

        override fun open(tenant: Connection, value: String): String {
            if (!isSealed(value)) return value
            if (!available) throw SecretsUnavailableException()
            return value.removePrefix(SecretBox.SealedPrefix).reversed()
        }
    }

    private val untouched = java.lang.reflect.Proxy.newProxyInstance(javaClass.classLoader, arrayOf(Connection::class.java)) { _, method, _ ->
        throw AssertionError("No debia usar la conexion: ${method.name}")
    } as Connection

    private val own = CrmMailMigration.Legacy("empresa", "smtp.crm.test", 587, "ventas", FakeBox().seal(untouched, "clave-crm"), "ventas@hotel.test", "starttls")

    @AfterTest
    fun restoreBox() {
        CrmSecrets.box = null
    }

    // ---- Envio: SMTP de la empresa y, sin el, el del servidor ----

    @Test
    fun theCrmSendsWithTheCompanySmtpAndFallsBackToTheServerOne() {
        val server = SmtpSettings("smtp.server", 587, "", "", "no-reply@server.test", trusted = true)
        val company = SmtpSettings("smtp.company", 587, "u", "p", "crm@company.test")
        assertEquals("smtp.company", CrmMail.resolveSmtp(company, server)?.host)
        assertEquals(server, CrmMail.resolveSmtp(null, server))
        assertNull(CrmMail.resolveSmtp(null, null))
        assertNull(CrmMail.resolveSmtp(company.copy(host = ""), null))
        // SMTP de empresa incompleto (host sin remitente): cae al del servidor.
        assertEquals(server, CrmMail.resolveSmtp(company.copy(from = ""), server))
    }

    @Test
    fun theMigrationNeverOverwritesAnSmtpTheCompanyAlreadyHas() {
        val box = FakeBox()
        assertEquals(CrmMailMigration.Plan.KeptCompany, CrmMailMigration.plan(own, true, box.available) { box.open(untouched, it) })
        // Aunque falte la llave: no hay nada que copiar, solo se descarta lo viejo.
        assertEquals(CrmMailMigration.Plan.KeptCompany, CrmMailMigration.plan(own, true, false) { throw AssertionError("no debe abrir nada") })
    }

    @Test
    fun invalidLegacyDataIsNormalizedBeforeCopying() {
        val box = FakeBox()
        val dirty = own.copy(security = " SSL ", host = "  smtp.crm.test  ", port = 0, from = "x".repeat(400))
        val copy = CrmMailMigration.plan(dirty, false, box.available) { box.open(untouched, it) } as CrmMailMigration.Plan.Copy
        assertEquals("ssl", copy.input.security)
        assertEquals("smtp.crm.test", copy.input.host)
        assertEquals(587, copy.input.port)
        assertEquals(255, copy.input.from.length)
        val unknown = CrmMailMigration.plan(own.copy(security = "tls13"), false, true) { it } as CrmMailMigration.Plan.Copy
        assertEquals("starttls", unknown.input.security)
    }

    @Test
    fun withoutSecretsTheCompanySmtpIsNotReadAndTheServerOneIsUsed() {
        val server = SmtpSettings("smtp.server", 587, "", "", "no-reply@server.test", trusted = true)
        assertNull(CrmMail.companySmtp(untouched, null))
        assertEquals(server, CrmMail.smtp(untouched, server, null))
        // Sin caja global (pruebas sin cifrado) tampoco toca la base.
        CrmSecrets.box = null
        assertEquals(server, CrmMail.smtp(untouched, server))
    }

    // ---- Migracion ----

    @Test
    fun theCrmSmtpIsCopiedAndItsSealedPasswordIsSealedAgainForTheCompany() {
        val box = FakeBox()
        val copy = CrmMailMigration.plan(own, false, box.available) { box.open(untouched, it) } as CrmMailMigration.Plan.Copy
        assertEquals("smtp.crm.test", copy.input.host)
        assertEquals("ventas", copy.input.username)
        assertEquals("ventas@hotel.test", copy.input.from)
        // En claro hasta CompanyMail.save, que la sella con esta misma funcion.
        assertEquals("clave-crm", copy.input.password)
        val sealed = CompanyMail.sealedPassword(copy.input.password) { box.seal(untouched, it) }
        assertTrue(sealed!!.startsWith(SecretBox.SealedPrefix))
        assertEquals("clave-crm", box.open(untouched, sealed))
    }

    @Test
    fun aPlainPasswordFromBeforeSealingIsAlsoMigrated() {
        val box = FakeBox()
        val copy = CrmMailMigration.plan(own.copy(password = "en-claro"), false, box.available) { box.open(untouched, it) } as CrmMailMigration.Plan.Copy
        assertEquals("en-claro", copy.input.password)
    }

    @Test
    fun waitsWhenThePasswordCannotBeOpenedOrThereIsNoMasterKey() {
        val noKey = FakeBox(available = false)
        assertTrue(CrmMailMigration.plan(own, false, noKey.available) { noKey.open(untouched, it) } is CrmMailMigration.Plan.Wait)
        // Una contrasena sellada con otra llave tampoco se puede abrir: se espera, no se pierde.
        val broken = CrmMailMigration.plan(own, false, true) { throw com.ideasdeveloper.idc.server.security.SecretDecryptionException() }
        assertTrue(broken is CrmMailMigration.Plan.Wait)
        // Sin contrasena no hace falta la llave.
        assertTrue(CrmMailMigration.plan(own.copy(password = null), false, false) { it } is CrmMailMigration.Plan.Copy)
    }

    @Test
    fun nothingToMigrateWhenTheCrmUsedTheServerSmtpOrIsAlreadyClean() {
        assertEquals(CrmMailMigration.Plan.Skip, CrmMailMigration.plan(own.copy(mode = "servidor"), false, true) { it })
        assertEquals(CrmMailMigration.Plan.Skip, CrmMailMigration.plan(own.copy(host = null), false, true) { it })
        assertTrue(CrmMailMigration.Legacy("servidor", null, 587, null, null, null, "starttls").isClean)
        assertTrue(!own.isClean)
    }

    @Test
    fun theCrmNoLongerHasItsOwnSmtpSettings() {
        val fields = CrmMailSettings::class.java.declaredFields.map { it.name }
        assertTrue(fields.none { it.startsWith("smtp") }, "CrmMailSettings no debe tener campos SMTP: $fields")
        assertTrue(CrmSecrets.tenantColumns.flatMap { it.secretColumns }.none { it == "smtp_password" })
    }
}
