package com.ideasdeveloper.idc.modules.crm

import com.ideasdeveloper.idc.server.extensions.ExtensionIdPattern
import com.ideasdeveloper.idc.server.extensions.ExtensionPermissions
import com.ideasdeveloper.idc.server.extensions.ExtensionRegistry
import com.ideasdeveloper.idc.server.extensions.UninstallDefault
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
    fun theCrmShipsFiveBundledExtensions() {
        val extensions = CrmServerModule.bundledExtensions
        assertEquals(setOf("meta", "monday_import", "wordpress", "encuentra24", "cloudbeds"), extensions.map { it.manifest.id }.toSet())
        assertEquals(CrmExtensionIds.all.toSet(), extensions.map { it.manifest.id }.toSet())
        // El registro del Core las acepta: ids unicos y bien formados.
        assertEquals(5, ExtensionRegistry.of(listOf(CrmServerModule)).forModule("crm").size)
    }

    @Test
    fun manifestsUseTheClosedPermissionListAndTheModulePermissions() {
        CrmServerModule.bundledExtensions.forEach { extension ->
            val manifest = extension.manifest
            assertTrue(ExtensionIdPattern.matches(manifest.id), manifest.id)
            assertEquals("crm", manifest.module)
            assertEquals("bundled", manifest.source.type)
            assertTrue(manifest.displayName.isNotBlank() && manifest.description.isNotBlank(), manifest.id)
            assertTrue(manifest.version.isNotBlank())
            manifest.permissions.forEach { permission ->
                assertTrue(ExtensionPermissions.isAllowed(permission, modulePermissions), "${manifest.id}: permiso no permitido $permission")
            }
            assertEquals(manifest.permissions.distinct(), manifest.permissions, "${manifest.id}: permisos repetidos")
            // La configuracion sigue en las pantallas del CRM: ningun ajuste es obligatorio.
            assertTrue(manifest.settings.none { it.required })
        }
    }

    @Test
    fun crmDeclaresThePermissionsItsExtensionsUse() {
        val used = CrmServerModule.bundledExtensions.flatMap { it.manifest.permissions }.filter { it.startsWith("crm.") }.toSet()
        assertTrue(used.isNotEmpty())
        assertTrue(modulePermissions.containsAll(used))
        assertTrue(modulePermissions.all { it.startsWith("crm.") })
        // Una extension no puede pedir un permiso de otro modulo ni uno inventado.
        assertFalse(ExtensionPermissions.isAllowed("clientes.customers.delete", modulePermissions))
        assertFalse(ExtensionPermissions.isAllowed("crm.leads.delete", modulePermissions))
    }

    @Test
    fun sensitiveExtensionsDeclareHostsSecretsAndWebhooks() {
        fun permissions(id: String) = CrmServerModule.bundledExtensions.first { it.manifest.id == id }.manifest.permissions
        assertTrue("network.outbound:graph.facebook.com" in permissions("meta"))
        assertTrue("network.outbound:api.monday.com" in permissions("monday_import"))
        assertTrue("network.outbound:www.encuentra24.com" in permissions("encuentra24"))
        assertTrue("network.outbound:api.cloudbeds.com" in permissions("cloudbeds"))
        // Los que reciben llamadas publicas lo declaran.
        assertTrue(ExtensionPermissions.WebhookPublic in permissions("meta"))
        assertTrue(ExtensionPermissions.WebhookPublic in permissions("wordpress"))
        CrmExtensionIds.all.forEach { assertTrue(ExtensionPermissions.SecretsWrite in permissions(it), it) }
        assertTrue("crm.fields.create" in permissions("monday_import"))
    }

    @Test
    fun uninstallOptionsAreValidAndMatchTheDecisions() {
        CrmServerModule.bundledExtensions.forEach { extension ->
            val options = extension.manifest.uninstall.options
            assertEquals(options.map { it.id }.distinct(), options.map { it.id }, "${extension.manifest.id}: opciones repetidas")
            options.forEach {
                assertTrue(ExtensionIdPattern.matches(it.id), it.id)
                assertTrue(it.default in setOf(UninstallDefault.Keep, UninstallDefault.Delete, UninstallDefault.Ask), "${it.id}: ${it.default}")
                assertTrue(it.label.isNotBlank())
            }
        }
        fun options(id: String) = CrmServerModule.bundledExtensions.first { it.manifest.id == id }.manifest.uninstall.options.associate { it.id to it.default }
        // monday: los campos creados se preguntan y el historial se borra.
        assertEquals(mapOf("created_fields" to UninstallDefault.Ask, "import_history" to UninstallDefault.Delete), options("monday_import"))
        // Meta: las conexiones siempre se borran y las conversaciones siempre se conservan, asi que no hay nada que preguntar.
        assertTrue(options("meta").isEmpty())
        // Lo que sale de otros sistemas se conserva salvo que el usuario diga lo contrario.
        assertEquals(UninstallDefault.Keep, options("wordpress").getValue("linked_properties"))
        assertEquals(UninstallDefault.Keep, options("encuentra24").getValue("ads"))
        assertEquals(setOf("reservations", "rooms", "default_country"), options("cloudbeds").keys)
        assertEquals(UninstallDefault.Keep, options("cloudbeds").getValue("reservations"))
        assertEquals(UninstallDefault.Keep, options("cloudbeds").getValue("rooms"))
        assertEquals(UninstallDefault.Ask, options("cloudbeds").getValue("default_country"))
    }

    @Test
    fun manifestsRequireTheModuleVersionThatShipsThem() {
        assertEquals("0.25.1", CrmServerModule.definition.version)
        CrmServerModule.bundledExtensions.forEach { assertEquals(CrmServerModule.definition.version, it.manifest.minModuleVersion) }
        assertEquals(CrmExtensions.MinModuleVersion, CrmServerModule.definition.version)
        assertEquals("0.7.0", CrmServerModule.definition.minCoreVersion)
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
        assertEquals("/modules/crm/extensions/meta/webhook", CrmMetaServer.WebhookPath)
        assertEquals("/modules/crm/extensions/meta/deauthorize", CrmMetaServer.DeauthorizePath)
        assertEquals("/modules/crm/extensions/meta/data-deletion", CrmMetaServer.DataDeletionPath)
        assertEquals("/modules/crm/extensions/meta/deletion", CrmMetaServer.DeletionStatusPath)
        assertEquals("/modules/crm/extensions/meta/connect", CrmMetaServer.ConnectPath)
        assertEquals("/modules/crm/extensions/meta/acme/webhook", CrmMetaServer.companyWebhookPath("acme"))
        assertEquals("/modules/crm/extensions/meta/connect/acme?t=abc", CrmMetaServer.connectUrl("", "acme", "abc"))
        assertEquals("/modules/crm/extensions/wordpress/idc-connector.zip", CrmWordPressScreens.PluginPath)
        assertEquals("/modules/crm/extensions/wordpress/acme/hook", CrmWordPressExtension.hookPath("acme"))
    }

    @Test
    fun theWordPressPluginPostsToTheNewAddress() {
        val php = CrmWordPress::class.java.classLoader.getResourceAsStream("modules/crm/wordpress/idc-connector/idc-connector.php")!!.use { String(it.readBytes()) }
        assertTrue(php.contains("'/modules/crm/extensions/wordpress/' . rawurlencode( \$settings['company'] ) . '/hook'"))
        assertFalse(php.contains("/modules/crm/hooks/"))
        assertTrue(php.contains("Version: ${CrmWordPress.PluginVersion}"))
    }

    @Test
    fun serverWebhookWithoutServerWorkspaceIsRejected() {
        // Sin espacio del servidor no hay app ni rutas: 403, y ninguna empresa se abre.
        val status = CrmMetaServer.dispatch("""{"object":"page","entry":[]}""".toByteArray(), "sha256=00") { error("no debia abrir ninguna empresa") }
        assertEquals(403, status)
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
        // El formulario de Meta con el aviso en vez del secreto conserva el guardado.
        val current = CrmMeta.merge(
            CrmMetaSettings(false, null, null, null, null, null, null, null, null, null, true),
            mapOf("enabled" to "false", "appSecret" to "secreto-1", "verifyToken" to "verify-token-1"),
        )
        val kept = CrmMeta.merge(current, mapOf("enabled" to "false", "appSecret" to CrmSecrets.UnreadableText, "verifyToken" to "verify-token-1"))
        assertEquals("secreto-1", kept.appSecret)
        val unreadableCurrent = current.copy(appSecret = CrmSecrets.UnreadablePrefix + "enc:v1:abc", verifyToken = CrmSecrets.UnreadablePrefix + "enc:v1:def")
        val shown = CrmMeta.toFormValues(unreadableCurrent)
        assertEquals(CrmSecrets.UnreadableText, shown["appSecret"])
        assertEquals(CrmSecrets.UnreadableText, shown["verifyToken"])
        val back = CrmMeta.merge(unreadableCurrent, shown + ("enabled" to "false"))
        assertEquals(unreadableCurrent.appSecret, back.appSecret)
        assertEquals(unreadableCurrent.verifyToken, back.verifyToken)
    }

    @Test
    fun withoutASecretBoxNothingIsSavedInPlainText() {
        CrmSecrets.box = null
        assertFailsWith<CrmValidationException> { CrmSecrets.seal(untouchedConnection, "abc") }
        assertNull(CrmSecrets.seal(untouchedConnection, null))
        assertEquals("abc", CrmSecrets.open(untouchedConnection, "abc"))
    }

    // ---- Sitio de WordPress: sin redes privadas ----

    private fun address(text: String) = java.net.InetAddress.getByName(text)

    @Test
    fun wordPressSitesCannotPointInsideTheNetwork() {
        listOf("127.0.0.1", "10.1.2.3", "172.16.0.5", "172.31.255.1", "192.168.1.1", "169.254.169.254", "100.64.0.1", "100.127.255.255", "0.0.0.0", "::1", "fc00::1", "fd12:3456::1", "fe80::1")
            .forEach { assertTrue(CrmWordPress.blockedAddress(address(it)), it) }
        listOf("8.8.8.8", "93.184.216.34", "100.63.255.255", "100.128.0.1", "172.32.0.1", "2606:4700:4700::1111")
            .forEach { assertFalse(CrmWordPress.blockedAddress(address(it)), it) }
        val public = { _: String -> listOf(address("93.184.216.34")) }
        CrmWordPress.checkSite("https://mi-inmobiliaria.com", public)
        // Un solo nombre que resuelva adentro basta para rechazarlo.
        assertFailsWith<CrmWordPressException> { CrmWordPress.checkSite("https://mi-inmobiliaria.com") { listOf(address("93.184.216.34"), address("10.0.0.8")) } }
        assertFailsWith<CrmWordPressException> { CrmWordPress.checkSite("https://intranet", public) }
        assertFailsWith<CrmWordPressException> { CrmWordPress.checkSite("https://localhost", public) }
        assertFailsWith<CrmWordPressException> { CrmWordPress.checkSite("https://servidor.local", public) }
        assertFailsWith<CrmWordPressException> { CrmWordPress.checkSite("https://10.0.0.1") }
        assertFailsWith<CrmWordPressException> { CrmWordPress.checkSite("https://nada.example.com") { throw java.net.UnknownHostException() } }
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
                "crm_meta_settings.app_secret", "crm_meta_settings.verify_token", "crm_meta_settings.system_token", "crm_meta_settings.page_token",
                "crm_meta_settings.messenger_token", "crm_meta_settings.instagram_token", "crm_meta_settings.oauth_token",
                "crm_meta_server_app.app_secret", "crm_meta_server_app.verify_token",
                "crm_wordpress_settings.secret", "crm_encuentra24_settings.api_key", "crm_cloudbeds_settings.api_key",
                "crm_settings.monday_token", "crm_settings.translation_api_key",
                "crm_mail_settings.smtp_password", "crm_mail_settings.imap_password",
            ),
            columns,
        )
        val identifier = Regex("[a-z_][a-z0-9_]*")
        (CrmSecrets.tenantColumns + CrmSecrets.serverColumns).forEach { column ->
            assertTrue(identifier.matches(column.table) && column.idColumns.all(identifier::matches) && column.secretColumns.all(identifier::matches))
        }
        // El espacio del servidor solo cifra su app; el resto va a cada empresa.
        assertTrue(CrmSecrets.tenantColumns.none { it.table == "crm_meta_server_app" })
    }

    @Test
    fun theMigrationRemovesTheChecksThatSealedValuesBreak() {
        val sql = listOf("modules/crm/migrations/V022__extensions_and_secrets.sql", "../modules/crm/migrations/V022__extensions_and_secrets.sql")
            .map(::File).firstOrNull { it.isFile }?.readText() ?: return
        assertTrue(sql.trim().startsWith("BEGIN;") && sql.trim().endsWith("COMMIT;"))
        assertTrue(sql.contains("DROP CONSTRAINT IF EXISTS crm_wordpress_settings_secret_check"))
        assertTrue(sql.contains("DROP CONSTRAINT IF EXISTS crm_settings_monday_token_check"))
        assertTrue(sql.contains("extension_id"))
        assertTrue(sql.contains("crm_meta_routes_asset_idx"))
    }

    @Test
    fun cloudbedsAsksWhetherToKeepTheDefaultCountry() {
        val option = CrmCloudbedsExtension.manifest.uninstall.options.single { it.id == CrmCloudbedsExtension.CountryOption }
        assertEquals(UninstallDefault.Ask, option.default)
    }
}
