package com.ideasdeveloper.idc.server.extensions

import com.ideasdeveloper.idc.server.modules.ModuleAccessService
import com.ideasdeveloper.idc.server.modules.ModuleJob
import io.ktor.server.routing.Route
import java.sql.Connection
import kotlinx.serialization.Serializable

/*
 * Contract shared by the Core (ExtensionService, packages, gate) and every extension (EXTENSIONS.md).
 * An extension depends on one installed module and is installed per company. Its routes are mounted
 * once at startup under /modules/<module>/extensions/<id>/ behind ExtensionGate, which answers from
 * tenant_extensions.status, so installing it in another company never needs a restart.
 */

/** The extension.json manifest, without packageSha256 and signature (they live in extension.sig.json). */
@Serializable
data class ExtensionManifest(
    val id: String,
    val module: String,
    val displayName: String,
    val description: String = "",
    val version: String,
    val minModuleVersion: String? = null,
    val source: ExtensionSource = ExtensionSource(),
    val permissions: List<String> = emptyList(),
    val settings: List<ExtensionSettingDefinition> = emptyList(),
    val migrations: List<String> = emptyList(),
    val uninstall: ExtensionUninstallDefinition = ExtensionUninstallDefinition(),
)

@Serializable
data class ExtensionSource(
    /** "bundled" (shipped inside its module), "github" (folder at a pinned commit), "zip" (uploaded ZIP) or "url" (ZIP from an https URL). */
    val type: String = ExtensionSourceType.Bundled,
    val url: String? = null,
)

object ExtensionSourceType {
    const val Bundled = "bundled"
    const val GitHub = "github"
    const val Zip = "zip"
    const val Url = "url"

    /** Origenes de un paquete externo (los que se instalan desde fuera del modulo). */
    val External = setOf(GitHub, Zip, Url)
}

@Serializable
data class ExtensionSettingDefinition(
    val key: String,
    val label: String = key,
    val secret: Boolean = false,
    val required: Boolean = false,
    val help: String = "",
    /**
     * Meta splits its settings: "app" settings may be copied to linked companies, "connection"
     * settings always belong to one company and are never applied to others.
     */
    val scope: String = ExtensionSettingScope.App,
)

object ExtensionSettingScope {
    const val App = "app"
    const val Connection = "connection"
}

@Serializable
data class ExtensionUninstallDefinition(val options: List<ExtensionUninstallOption> = emptyList())

@Serializable
data class ExtensionUninstallOption(
    val id: String,
    val label: String,
    /** "keep", "delete" or "ask" (the user must answer; uninstall is refused without an answer). */
    val default: String = UninstallDefault.Ask,
)

object UninstallDefault {
    const val Keep = "keep"
    const val Delete = "delete"
    const val Ask = "ask"
}

/** The detached signature file next to extension.json. */
@Serializable
data class ExtensionSignatureFile(
    val packageSha256: String,
    val signature: ExtensionSignature? = null,
)

@Serializable
data class ExtensionSignature(val keyId: String, val value: String)

/** tenant_extensions.status */
object ExtensionStatus {
    const val Installed = "installed"
    const val Enabled = "enabled"
    const val Disabled = "disabled"
    const val Incompatible = "incompatible"
    const val Removed = "removed"
}

/** Permissions any extension may declare; modules add their own with ModuleDefinition.extensionPermissions. */
object ExtensionPermissions {
    const val SecretsRead = "secrets.read"
    const val SecretsWrite = "secrets.write"
    const val WebhookPublic = "webhook.public"
    const val NetworkOutboundPrefix = "network.outbound:"

    private val hostPattern = Regex("[a-z0-9]([a-z0-9-]*[a-z0-9])?(\\.[a-z0-9]([a-z0-9-]*[a-z0-9])?)+")

    fun isAllowed(permission: String, modulePermissions: Collection<String>): Boolean = when {
        permission == SecretsRead || permission == SecretsWrite || permission == WebhookPublic -> true
        permission.startsWith(NetworkOutboundPrefix) -> hostPattern.matches(permission.removePrefix(NetworkOutboundPrefix))
        else -> permission in modulePermissions
    }
}

val ExtensionIdPattern = Regex("[a-z][a-z0-9_]{0,62}")

/** What the Core gives an extension while it runs for one company. */
class ExtensionContext(
    val companyCode: String,
    /** Tenant connection of the company; uninstall runs inside one transaction on it. */
    val tenant: Connection,
    val extensionId: String,
    val settings: ExtensionSettings,
)

/** Settings of one extension in one company; secrets are opened only when read. */
interface ExtensionSettings {
    fun get(key: String): String?
    fun all(includeSecrets: Boolean = false): Map<String, String>
}

/** One line of the uninstall preview: what happens to a group of objects, with its count. */
@Serializable
data class UninstallItem(
    /** The uninstall option it depends on, or null when it always happens (settings are always deleted). */
    val optionId: String?,
    val label: String,
    val count: Int,
    /** "delete" or "keep" for always-happening items; for option items, what the declared default does. */
    val action: String,
)

/** Hands an extension what it needs from the Core while it registers its routes. */
interface ExtensionAccess {
    val modules: ModuleAccessService

    /** Tenant connection of the company when this extension is enabled there, else null. Caller closes it. */
    fun enabledTenant(companyCode: String): Connection?

    /** Settings of this extension in a company (connection opened by the caller). */
    fun settings(tenant: Connection): ExtensionSettings

    /** Companies where the extension is enabled (for jobs and server-level webhooks that route by page/number). */
    fun enabledCompanies(): List<String>

    /** Records an object the extension created, so uninstall knows what to touch. */
    fun recordObject(tenant: Connection, kind: String, objectRef: String, optionId: String?)
}

interface ServerExtension {
    val manifest: ExtensionManifest

    /** Classpath resources of the extension's tenant migrations (only ext_<id>__* tables for packages). */
    val migrationPaths: List<String>
        get() = emptyList()

    val runtimeTables: List<String>
        get() = emptyList()

    /** Run only in companies where the extension is enabled. */
    val jobs: List<ModuleJob>
        get() = emptyList()

    /** Mounted under /modules/<module>/extensions/<id>/ behind ExtensionGate. */
    fun routes(route: Route, access: ExtensionAccess) {
    }

    /** Settings rejected before they are stored (null = valid). */
    fun validateSettings(settings: Map<String, String>): String? = null

    fun onEnable(context: ExtensionContext) {
    }

    /**
     * Counts for the uninstall preview. Settings are always deleted by the Core; list here only
     * what depends on the declared uninstall options, plus anything else always deleted.
     */
    fun uninstallPreview(context: ExtensionContext): List<UninstallItem> = emptyList()

    /**
     * Runs inside the company's uninstall transaction (context.tenant has autoCommit off).
     * choices maps every declared option id to "keep" or "delete". Never delete leads or customers.
     * Throwing rolls the whole uninstall back.
     */
    fun uninstall(context: ExtensionContext, choices: Map<String, String>) {
    }

    /**
     * Runs after the uninstall transaction committed (best effort: a failure is only logged), for
     * work outside the company's database such as releasing a route in the control database.
     */
    fun afterUninstall(companyCode: String) {
    }
}

/** ServiceLoader entry point of an extension package (META-INF/services). */
interface ServerExtensionProvider {
    fun create(): ServerExtension
}
