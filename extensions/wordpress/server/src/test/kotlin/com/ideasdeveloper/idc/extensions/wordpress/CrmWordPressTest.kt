package com.ideasdeveloper.idc.extensions.wordpress

import com.ideasdeveloper.idc.modules.crm.CrmExtensionIds
import com.ideasdeveloper.idc.modules.crm.CrmWordPressException
import com.ideasdeveloper.idc.modules.crm.WordPressPluginInfo
import com.ideasdeveloper.idc.server.extensions.ExtensionIdPattern
import com.ideasdeveloper.idc.server.extensions.ExtensionPermissions
import com.ideasdeveloper.idc.server.extensions.UninstallDefault
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.File
import java.time.Instant
import java.util.zip.ZipInputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CrmWordPressTest {
    private val secret = "SecretSecretSecretSecretSecretSecret12"

    private fun address(text: String) = java.net.InetAddress.getByName(text)

    private fun readFile(vararg paths: String): String? = paths.map(::File).firstOrNull { it.isFile }?.readText()

    @Test
    fun signatureMatchesThePhpPlugin() {
        // hash_hmac('sha256', '1761700000.{"event":"ping"}', secret) computed with PHP 8.2.
        val php = "7cc732526fee9fc5bc6095b82cf66bd3d71f2f468ef9421474876d5abf3519c6"
        val body = """{"event":"ping"}""".toByteArray()
        assertEquals(php, CrmWordPress.sign(secret, "1761700000", body))
        val now = Instant.ofEpochSecond(1761700100)
        assertTrue(CrmWordPress.validSignature(secret, "1761700000", php.uppercase(), body, now))
        // Older than 5 minutes, another secret, a changed body or missing headers are rejected.
        assertFalse(CrmWordPress.validSignature(secret, "1761700000", php, body, now.plusSeconds(400)))
        assertFalse(CrmWordPress.validSignature("other$secret", "1761700000", php, body, now))
        assertFalse(CrmWordPress.validSignature(secret, "1761700000", php, """{"event":"form"}""".toByteArray(), now))
        assertFalse(CrmWordPress.validSignature(secret, null, php, body, now))
        assertFalse(CrmWordPress.validSignature(null, "1761700000", php, body, now))
    }

    @Test
    fun readsListingsSentByThePlugin() {
        val element = Json.parseToJsonElement(
            """
            {"id":42,"title":"Casa Las Colinas","url":"https://site.com/listings/casa","price":"250000","price_prefix":"US$",
             "price_postfix":"","mls":"MLS-9","sqft":"180","taxonomies":{"property_type":["Casa"],"ct_status":["For Sale"],
             "beds":["3"],"baths":["2"],"city":["Managua"]},"agent":{"id":7,"name":"Ana","email":"ana@site.com"}}
            """.trimIndent()
        )
        val listing = CrmWordPress.listing(element)!!
        assertEquals(42L, listing.id)
        assertEquals("US$ 250000", listing.priceText)
        assertEquals("250000".toBigDecimal(), listing.priceValue)
        assertEquals("Casa - For Sale - 3 hab. - 2 banos - 180 m2 - Managua - MLS MLS-9", listing.summary)
        assertEquals(7L, listing.agentId)
        assertNull(CrmWordPress.listing(Json.parseToJsonElement("""{"title":"sin id"}""")))
    }

    @Test
    fun pluginZipHasTheWordPressLayout() {
        val zip = CrmPlugin.zip(CrmPlugin::class.java.classLoader)
        val names = ZipInputStream(zip.inputStream()).use { input -> generateSequence { input.nextEntry?.name }.toList() }
        assertEquals(listOf("idc-connector/idc-connector.php", "idc-connector/readme.txt"), names)
        val php = CrmPlugin::class.java.classLoader.getResourceAsStream("extensions/wordpress/idc-connector/idc-connector.php.txt")!!.use { it.readBytes().decodeToString() }
        assertTrue(php.contains("Version: ${CrmWordPress.PluginVersion}"), "Plugin header and CrmWordPress.PluginVersion must match")
        assertEquals(WordPressPluginInfo.Version, CrmWordPress.PluginVersion)
    }

    @Test
    fun theWordPressPluginPostsToTheExtensionAddress() {
        val php = CrmPlugin::class.java.classLoader.getResourceAsStream("extensions/wordpress/idc-connector/idc-connector.php.txt")!!.use { String(it.readBytes()) }
        assertTrue(php.contains("'/modules/crm/extensions/wordpress/' . rawurlencode( \$settings['company'] ) . '/hook'"))
        assertFalse(php.contains("/modules/crm/hooks/"))
    }

    // ---- Rutas y direcciones ----

    @Test
    fun routesLiveUnderTheExtensionPrefix() {
        assertEquals("/modules/crm/extensions/wordpress/idc-connector.zip", CrmWordPressExtension.PluginPath)
        assertEquals(WordPressPluginInfo.Path, CrmWordPressExtension.PluginPath)
        assertEquals("/modules/crm/extensions/wordpress/acme/hook", CrmWordPressExtension.hookPath("acme"))
    }

    // ---- Manifiesto ----

    @Test
    fun theManifestDeclaresWhatTheExtensionDoes() {
        val manifest = CrmWordPressExtension.manifest
        assertEquals(CrmExtensionIds.WordPress, manifest.id)
        assertTrue(ExtensionIdPattern.matches(manifest.id))
        assertEquals("crm", manifest.module)
        assertEquals("0.27.0", manifest.minModuleVersion)
        // Recibe llamadas publicas del sitio y guarda un secreto.
        assertTrue(ExtensionPermissions.WebhookPublic in manifest.permissions)
        assertTrue(ExtensionPermissions.SecretsWrite in manifest.permissions)
        assertEquals(manifest.permissions.distinct(), manifest.permissions)
        // Lo que sale del sitio se conserva salvo que el usuario diga lo contrario.
        assertEquals(UninstallDefault.Keep, manifest.uninstall.options.associate { it.id to it.default }.getValue("linked_properties"))
        assertEquals(listOf("extensions/wordpress/migrations/V001__settings.sql"), CrmWordPressExtension.migrationPaths)
        assertEquals(listOf("ext_wordpress__settings", "ext_wordpress__agents"), CrmWordPressExtension.runtimeTables)
    }

    @Test
    fun extensionJsonMatchesTheManifest() {
        val text = readFile("extension.json", "../extension.json", "../../extension.json", "extensions/wordpress/extension.json") ?: return
        val json = Json.parseToJsonElement(text) as JsonObject
        fun field(name: String) = (json[name] as JsonPrimitive).content
        val manifest = CrmWordPressExtension.manifest
        assertEquals(manifest.id, field("id"))
        assertEquals(manifest.module, field("module"))
        assertEquals(manifest.displayName, field("displayName"))
        assertEquals(manifest.description, field("description"))
        assertEquals(manifest.version, field("version"))
        assertEquals(manifest.minModuleVersion, field("minModuleVersion"))
        assertEquals(manifest.permissions, (json["permissions"] as JsonArray).map { (it as JsonPrimitive).content })
        assertEquals(manifest.migrations, (json["migrations"] as JsonArray).map { (it as JsonPrimitive).content })
    }

    @Test
    fun theMigrationCreatesOnlyTheTablesOfTheExtension() {
        val sql = readFile("migrations/V001__settings.sql", "../migrations/V001__settings.sql", "../../migrations/V001__settings.sql", "extensions/wordpress/migrations/V001__settings.sql") ?: return
        assertTrue(sql.trim().startsWith("BEGIN;") && sql.trim().endsWith("COMMIT;"))
        val created = Regex("CREATE TABLE (\\w+)").findAll(sql).map { it.groupValues[1] }.toList()
        assertEquals(listOf("ext_wordpress__settings", "ext_wordpress__agents"), created)
        // El secreto se guarda sellado: sin regla de formato; y la migracion no inserta filas.
        assertFalse(sql.contains("[A-Za-z0-9]{32,128}"))
        assertFalse(sql.contains("INSERT INTO"))
    }

    // ---- CT Leads Pro como fuente de importacion ----

    @Test
    fun leadsProIsAnImportSourceOfTheExtension() {
        assertEquals("leadspro", WordPressImport.id)
        assertEquals("CT Leads Pro", WordPressImport.label)
        assertEquals("Leyendo CT Leads Pro", WordPressImport.readingLabel)
        assertEquals(CrmExtensionIds.WordPress, WordPressImport.extensionId)
        // Las operaciones de otras fuentes no son suyas.
        assertNull(WordPressImport.action(UntouchedConnection.connection, "monday", emptyMap(), null))
        assertEquals(listOf("import_leadspro"), WordPressImport.mcpTools.map { it.name })
    }

    // ---- Sitio de WordPress: sin redes privadas ----

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

    // Conexion que falla al usarse: demuestra que una operacion ajena no toca la base.
    private object UntouchedConnection {
        val connection = java.lang.reflect.Proxy.newProxyInstance(UntouchedConnection::class.java.classLoader, arrayOf(java.sql.Connection::class.java)) { _, method, _ ->
            throw AssertionError("No debia usar la conexion: ${method.name}")
        } as java.sql.Connection
    }
}
