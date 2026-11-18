package com.ideasdeveloper.idc.extensions.meta

import com.ideasdeveloper.idc.modules.crm.CrmExtensionIds
import com.ideasdeveloper.idc.modules.crm.CrmExtensionPermissions
import com.ideasdeveloper.idc.modules.crm.CrmExtensions
import com.ideasdeveloper.idc.modules.crm.CrmHost
import com.ideasdeveloper.idc.modules.crm.CrmMail
import com.ideasdeveloper.idc.modules.crm.CrmMetaSettings
import com.ideasdeveloper.idc.modules.crm.CrmSecrets
import com.ideasdeveloper.idc.modules.crm.MetaPaths
import com.ideasdeveloper.idc.server.extensions.ExtensionIdPattern
import com.ideasdeveloper.idc.server.extensions.ExtensionPermissions
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MetaExtensionTest {
    @AfterTest
    fun restoreBox() {
        CrmSecrets.box = null
    }

    private fun readFile(vararg paths: String): String? = paths.map(::File).firstOrNull { it.isFile }?.readText()

    // ---- Manifiesto ----

    @Test
    fun theManifestDeclaresWhatTheExtensionDoes() {
        val manifest = CrmMetaExtension.manifest
        assertEquals(CrmExtensionIds.Meta, manifest.id)
        assertTrue(ExtensionIdPattern.matches(manifest.id))
        assertEquals("crm", manifest.module)
        assertEquals("0.27.0", manifest.minModuleVersion)
        assertTrue(manifest.displayName.isNotBlank() && manifest.description.isNotBlank())
        // Habla con la API de Meta, recibe llamadas publicas y guarda secretos.
        assertTrue("network.outbound:graph.facebook.com" in manifest.permissions)
        assertTrue(ExtensionPermissions.WebhookPublic in manifest.permissions)
        assertTrue(ExtensionPermissions.SecretsWrite in manifest.permissions)
        assertEquals(manifest.permissions.distinct(), manifest.permissions)
        manifest.permissions.forEach { assertTrue(ExtensionPermissions.isAllowed(it, CrmExtensionPermissions.all), it) }
        // La configuracion sigue en las pantallas del CRM: ningun ajuste es obligatorio.
        assertTrue(manifest.settings.none { it.required })
        // Las conexiones siempre se borran y las conversaciones siempre se conservan, asi que no hay nada que preguntar.
        assertTrue(manifest.uninstall.options.isEmpty())
        assertEquals(listOf("extensions/meta/migrations/V001__settings.sql"), CrmMetaExtension.migrationPaths)
        assertEquals(
            listOf("ext_meta__settings", "ext_meta__log", "ext_meta__server_app", "ext_meta__routes", "ext_meta__tickets"),
            CrmMetaExtension.runtimeTables,
        )
    }

    @Test
    fun extensionJsonMatchesTheManifest() {
        val text = readFile("extension.json", "../extension.json", "../../extension.json", "extensions/meta/extension.json") ?: return
        val json = Json.parseToJsonElement(text) as JsonObject
        fun field(name: String) = (json[name] as JsonPrimitive).content
        val manifest = CrmMetaExtension.manifest
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
        val sql = readFile("migrations/V001__settings.sql", "../migrations/V001__settings.sql", "../../migrations/V001__settings.sql", "extensions/meta/migrations/V001__settings.sql") ?: return
        assertTrue(sql.trim().startsWith("BEGIN;") && sql.trim().endsWith("COMMIT;"))
        val created = Regex("CREATE TABLE (\\w+)").findAll(sql).map { it.groupValues[1] }.toList()
        assertEquals(CrmMetaExtension.runtimeTables, created)
        // Los secretos se guardan sellados (sin regla de formato) y la migracion no inserta filas.
        assertFalse(sql.contains("INSERT INTO"))
        assertTrue(sql.contains("ext_meta__routes_asset_idx"))
        assertTrue(sql.contains("connection_mode") && sql.contains("oauth_token") && sql.contains("shared_tokens"))
    }

    // ---- Rutas ----

    @Test
    fun routesLiveUnderTheExtensionPrefix() {
        assertEquals("/modules/crm/extensions/meta/webhook", CrmMetaServer.WebhookPath)
        assertEquals("/modules/crm/extensions/meta/deauthorize", CrmMetaServer.DeauthorizePath)
        assertEquals("/modules/crm/extensions/meta/data-deletion", CrmMetaServer.DataDeletionPath)
        assertEquals("/modules/crm/extensions/meta/deletion", CrmMetaServer.DeletionStatusPath)
        assertEquals("/modules/crm/extensions/meta/connect", CrmMetaServer.ConnectPath)
        assertEquals("/modules/crm/extensions/meta/acme/webhook", CrmMetaServer.companyWebhookPath("acme"))
        assertEquals("/modules/crm/extensions/meta/connect/acme?t=abc", CrmHost.metaConnectUrl("", "acme", "abc"))
        // El CRM muestra las mismas direcciones sin depender de la extension.
        assertEquals(MetaPaths.WebhookPath, CrmMetaServer.WebhookPath)
        assertEquals(MetaPaths.companyWebhookPath("acme"), CrmMetaServer.companyWebhookPath("acme"))
    }

    @Test
    fun serverWebhookWithoutServerWorkspaceIsRejected() {
        // Sin espacio del servidor no hay app ni rutas: 403, y ninguna empresa se abre.
        CrmMetaServer.access = null
        val status = CrmMetaServer.dispatch("""{"object":"page","entry":[]}""".toByteArray(), "sha256=00") { error("no debia abrir ninguna empresa") }
        assertEquals(403, status)
    }

    // ---- Secretos ilegibles ----

    @Test
    fun theMetaFormKeepsAnUnreadableSecretAndNeverSendsBackThePlaceholder() {
        val current = CrmMetaExtension.merge(
            CrmMetaSettings(false, null, null, null, null, null, null, null, null, null, true),
            mapOf("enabled" to "false", "appSecret" to "secreto-1", "verifyToken" to "verify-token-1"),
        )
        val kept = CrmMetaExtension.merge(current, mapOf("enabled" to "false", "appSecret" to CrmSecrets.UnreadableText, "verifyToken" to "verify-token-1"))
        assertEquals("secreto-1", kept.appSecret)
        val unreadableCurrent = current.copy(appSecret = CrmSecrets.UnreadablePrefix + "enc:v1:abc", verifyToken = CrmSecrets.UnreadablePrefix + "enc:v1:def")
        val shown = CrmMetaExtension.toFormValues(unreadableCurrent)
        assertEquals(CrmSecrets.UnreadableText, shown["appSecret"])
        assertEquals(CrmSecrets.UnreadableText, shown["verifyToken"])
        val back = CrmMetaExtension.merge(unreadableCurrent, shown + ("enabled" to "false"))
        assertEquals(unreadableCurrent.appSecret, back.appSecret)
        assertEquals(unreadableCurrent.verifyToken, back.verifyToken)
        assertEquals(CrmMail.SecretMask, CrmMetaExtension.toFormValues(current)["appSecret"])
    }

    @Test
    fun theCrmExplainsWhenTheExtensionIsNotActive() {
        assertTrue(CrmExtensions.inactiveMessage(CrmExtensionIds.Meta).contains("Meta"))
    }
}
