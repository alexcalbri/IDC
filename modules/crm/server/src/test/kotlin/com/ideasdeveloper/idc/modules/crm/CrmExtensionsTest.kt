package com.ideasdeveloper.idc.modules.crm

import com.ideasdeveloper.idc.server.extensions.ExtensionPermissions
import com.ideasdeveloper.idc.server.extensions.ExtensionRegistry
import com.ideasdeveloper.idc.server.security.SecretBox
import com.ideasdeveloper.idc.server.security.SecretsUnavailableException
import java.io.File
import java.sql.Connection
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CrmExtensionsTest {
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

    // Conexion que falla al usarse: demuestra que una ruta no toca la base.
    private val untouchedConnection = java.lang.reflect.Proxy.newProxyInstance(javaClass.classLoader, arrayOf(Connection::class.java)) { _, method, _ ->
        throw AssertionError("No debia usar la conexion: ${method.name}")
    } as Connection

    private val modulePermissions get() = CrmServerModule.definition.extensionPermissions

    @AfterTest
    fun restoreBox() {
        CrmSecrets.box = null
    }

    // ---- Manifiestos ----

    @Test
    fun theCrmShipsNoBundledExtension() {
        // Meta, Cloudbeds, Encuentra24, monday y WordPress ya no vienen incluidas: son paquetes (extensions/meta, extensions/cloudbeds, extensions/encuentra24, extensions/monday, extensions/wordpress).
        assertEquals(emptyList(), CrmServerModule.bundledExtensions)
        assertEquals(0, ExtensionRegistry.of(listOf(CrmServerModule)).forModule("crm").size)
        // El CRM sigue admitiendo extensiones (las de los paquetes).
        assertTrue(CrmServerModule.definition.supportsExtensions)
    }

    @Test
    fun crmDeclaresTheClosedPermissionListForItsExtensions() {
        assertTrue(modulePermissions.isNotEmpty())
        assertTrue(modulePermissions.all { it.startsWith("crm.") })
        // Los permisos de la extension Meta los declara el CRM (se comprueban en extensions/meta).
        assertTrue(modulePermissions.containsAll(listOf(CrmExtensionPermissions.LeadsCreate, CrmExtensionPermissions.LeadsUpdate, CrmExtensionPermissions.MessagesWrite)))
        // Una extension no puede pedir un permiso de otro modulo ni uno inventado.
        assertFalse(ExtensionPermissions.isAllowed("clientes.customers.delete", modulePermissions))
        assertFalse(ExtensionPermissions.isAllowed("crm.leads.delete", modulePermissions))
    }

    @Test
    fun manifestsRequireTheModuleVersionThatShipsThem() {
        assertEquals("0.28.0", CrmServerModule.definition.version)
        // Las extensiones piden la version del CRM que las saco del modulo; el modulo puede ir por delante.
        assertEquals("0.27.0", CrmExtensions.MinModuleVersion)
        assertEquals("0.8.0", CrmServerModule.definition.minCoreVersion)
        assertEquals("0.7.0", CrmServerModule.definition.minAppVersion)
        assertTrue("modules/crm/migrations/V022__extensions_and_secrets.sql" in CrmServerModule.migrationPaths)
    }

    @Test
    fun moduleJsonMatchesTheDefinition() {
        val file = listOf("modules/crm/module.json", "../modules/crm/module.json", "../../modules/crm/module.json").map(::File).firstOrNull { it.isFile } ?: return
        val json = kotlinx.serialization.json.Json.parseToJsonElement(file.readText()) as kotlinx.serialization.json.JsonObject
        fun field(name: String) = (json[name] as kotlinx.serialization.json.JsonPrimitive).content
        assertEquals(CrmServerModule.definition.version, field("version"))
        assertEquals(CrmServerModule.definition.minCoreVersion, field("minCoreVersion"))
        assertEquals(CrmServerModule.definition.minAppVersion, field("minAppVersion"))
    }

    // ---- Rutas nuevas ----

    @Test
    fun routesMovedUnderTheExtensionPrefix() {
        // Las rutas de Meta las sirve el paquete extensions/meta; el CRM solo conoce sus direcciones para mostrarlas.
        assertEquals("/modules/crm/extensions/meta/webhook", MetaPaths.WebhookPath)
        assertEquals("/modules/crm/extensions/meta/deauthorize", MetaPaths.DeauthorizePath)
        assertEquals("/modules/crm/extensions/meta/data-deletion", MetaPaths.DataDeletionPath)
        assertEquals("/modules/crm/extensions/meta/deletion", MetaPaths.DeletionStatusPath)
        assertEquals("/modules/crm/extensions/meta/connect", MetaPaths.ConnectPath)
        assertEquals("/modules/crm/extensions/meta/acme/webhook", MetaPaths.companyWebhookPath("acme"))
        assertEquals("/modules/crm/extensions/meta/connect/acme?t=abc", CrmHost.metaConnectUrl("", "acme", "abc"))
        // La descarga del plugin la sirve el paquete extensions/wordpress; el CRM solo conoce su direccion.
        assertEquals("/modules/crm/extensions/wordpress/idc-connector.zip", CrmWordPressScreens.PluginPath)
        assertEquals(WordPressPluginInfo.Path, CrmWordPressScreens.PluginPath)
    }

    @Test
    fun withoutTheMetaExtensionTheCrmSeesMetaTurnedOff() {
        CrmModuleExtensions.link(emptyList())
        assertNull(CrmModuleExtensions.meta())
        assertNull(CrmHost.metaServerApp())
        assertNull(CrmHost.metaServerWorkspace())
        assertFalse(CrmHost.metaSettings(untouchedConnection).enabled)
        assertFalse(CrmHost.metaActive(untouchedConnection))
        assertTrue(CrmHost.metaRecent(untouchedConnection).isEmpty())
        // Escribir o enviar sin la extension falla con un aviso claro, sin tocar la base.
        assertFailsWith<CrmValidationException> { CrmHost.saveMeta(untouchedConnection, CrmHost.metaSettings(untouchedConnection)) }
        assertFailsWith<CrmMetaException> { CrmHost.metaSendWhatsApp(CrmHost.metaSettings(untouchedConnection), "5051", "hola") }
        CrmHost.metaLog(untouchedConnection, "evento")
    }

    // ---- Estado de la extension ----

    @Test
    fun inactiveExtensionsAnswerWithAClearMessage() {
        assertEquals("La extension Cloudbeds no esta activa en esta empresa.", CrmExtensions.inactiveMessage(CrmExtensionIds.Cloudbeds))
        assertTrue(CrmExtensions.inactiveMessage(CrmExtensionIds.Meta).contains("Meta"))
        CrmExtensionIds.all.forEach { assertTrue(CrmExtensions.displayName(it) != it, it) }
    }

    // ---- Secretos cifrados ----

    @Test
    fun secretsAreSealedOnWriteAndOpenedOnRead() {
        CrmSecrets.box = FakeBox()
        val sealed = assertNotNull(CrmSecrets.seal(untouchedConnection, "token-123"))
        assertTrue(sealed.startsWith("enc:v1:"))
        assertFalse(sealed.contains("token-123"))
        assertEquals("token-123", CrmSecrets.open(untouchedConnection, sealed))
        // Leer dos veces el mismo valor ya sellado o sellar algo ya sellado no lo cifra de nuevo.
        assertEquals(sealed, CrmSecrets.seal(untouchedConnection, sealed))
    }

    @Test
    fun legacyPlainValuesAreReadAsTheyAreUntilTheyAreSealed() {
        CrmSecrets.box = FakeBox()
        assertEquals("plain-secret", CrmSecrets.open(untouchedConnection, "plain-secret"))
        assertNull(CrmSecrets.open(untouchedConnection, null))
        assertEquals("", CrmSecrets.open(untouchedConnection, ""))
        assertNull(CrmSecrets.seal(untouchedConnection, null))
        assertEquals("", CrmSecrets.seal(untouchedConnection, ""))
    }

    @Test
    fun withoutTheMasterKeySecretsAreNotSavedAndSealedOnesAreUnreadable() {
        val sealed = FakeBox().seal(untouchedConnection, "token-123")
        CrmSecrets.box = FakeBox(available = false)
        val failure = assertFailsWith<CrmValidationException> { CrmSecrets.seal(untouchedConnection, "token-123") }
        assertEquals(CrmSecrets.UnavailableMessage, failure.message)
        // Vaciar un secreto no necesita la llave.
        assertNull(CrmSecrets.seal(untouchedConnection, null))
        // Lo sellado no se puede abrir: se lee como ilegible (nunca como vacio), con el crudo dentro.
        val unreadable = assertNotNull(CrmSecrets.open(untouchedConnection, sealed))
        assertTrue(CrmSecrets.isUnreadable(unreadable))
        assertEquals(sealed, unreadable.removePrefix(CrmSecrets.UnreadablePrefix))
        // Lo que aun esta en claro se lee igual.
        assertEquals("plain", CrmSecrets.open(untouchedConnection, "plain"))
    }

    @Test
    fun anUnreadableSecretIsRewrittenAsItWasNeverWipedOrEncryptedTwice() {
        val sealed = FakeBox().seal(untouchedConnection, "token-123")
        // Una caja que no puede abrir (llave distinta) y un guardado posterior del mismo modelo.
        CrmSecrets.box = FakeBox(available = false)
        val loaded = CrmSecrets.open(untouchedConnection, sealed)
        assertEquals(sealed, CrmSecrets.seal(untouchedConnection, loaded))
        // Tambien sin caja de cifrado: el crudo se conserva.
        CrmSecrets.box = null
        assertEquals(sealed, CrmSecrets.seal(untouchedConnection, loaded))
    }

    @Test
    fun screensTellUnreadableFromEmptyAndNeverSendBackThePlaceholders() {
        assertEquals("", CrmSecrets.mask(null))
        assertEquals("", CrmSecrets.mask(" "))
        assertEquals(CrmMail.SecretMask, CrmSecrets.mask("token"))
        assertEquals(CrmSecrets.UnreadableText, CrmSecrets.mask(CrmSecrets.UnreadablePrefix + "enc:v1:abc"))
        assertTrue(CrmSecrets.UnreadableText.contains("IDC_MASTER_KEY"))
        assertTrue(CrmSecrets.isPlaceholder(CrmMail.SecretMask) && CrmSecrets.isPlaceholder(CrmSecrets.UnreadableText))
        assertFalse(CrmSecrets.isPlaceholder("token-nuevo"))
    }

    @Test
    fun withoutASecretBoxNothingIsSavedInPlainText() {
        CrmSecrets.box = null
        assertFailsWith<CrmValidationException> { CrmSecrets.seal(untouchedConnection, "abc") }
        assertNull(CrmSecrets.seal(untouchedConnection, null))
        assertEquals("abc", CrmSecrets.open(untouchedConnection, "abc"))
    }

    @Test
    fun webhookBodiesAreLimitedToOneMegabyte() {
        assertEquals(1024 * 1024, CrmExtensions.MaxWebhookBytes)
    }

    @Test
    fun sealingAllColumnsDoesNothingWithoutTheMasterKey() {
        assertEquals(0, CrmSecrets.sealAll(untouchedConnection, FakeBox(available = false), CrmSecrets.tenantColumns + CrmSecrets.serverColumns))
    }

    @Test
    fun everySecretColumnOfTheCrmIsListedForSealing() {
        val columns = (CrmSecrets.tenantColumns + CrmSecrets.serverColumns).flatMap { column -> column.secretColumns.map { "${column.table}.$it" } }.toSet()
        assertEquals(
            setOf(
                "crm_settings.translation_api_key",
                "crm_mail_settings.imap_password",
            ),
            columns,
        )
        val identifier = Regex("[a-z_][a-z0-9_]*")
        (CrmSecrets.tenantColumns + CrmSecrets.serverColumns).forEach { column ->
            assertTrue(identifier.matches(column.table) && column.idColumns.all(identifier::matches) && column.secretColumns.all(identifier::matches))
        }
        // Los secretos de Meta (y de las demas extensiones) los sella cada extension al guardar: el CRM ya no los lista.
        assertTrue(CrmSecrets.tenantColumns.none { it.table.startsWith("crm_meta") })
        assertTrue(CrmSecrets.serverColumns.isEmpty())
    }

    @Test
    fun theMigrationRemovesTheChecksThatSealedValuesBreak() {
        val sql = listOf("modules/crm/migrations/V022__extensions_and_secrets.sql", "../modules/crm/migrations/V022__extensions_and_secrets.sql")
            .map(::File).firstOrNull { it.isFile }?.readText() ?: return
        assertTrue(sql.trim().startsWith("BEGIN;") && sql.trim().endsWith("COMMIT;"))
        assertFalse(sql.contains("crm_wordpress_settings"), "Los ajustes de WordPress viven en la extension (ext_wordpress__settings)")
        assertTrue(sql.contains("extension_id"))
        // El indice de las rutas de Meta lo crea ahora la extension (ext_meta__routes).
        assertFalse(sql.contains("CREATE UNIQUE INDEX"))
        assertTrue(sql.contains("ext_meta__routes"))
    }
}
