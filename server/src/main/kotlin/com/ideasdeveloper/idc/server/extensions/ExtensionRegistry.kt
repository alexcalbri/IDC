package com.ideasdeveloper.idc.server.extensions

import com.ideasdeveloper.idc.server.modules.ModuleAccessErrorResponse
import com.ideasdeveloper.idc.server.modules.ModuleAccessService
import com.ideasdeveloper.idc.server.modules.ServerModule
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.createRouteScopedPlugin
import io.ktor.server.application.install
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.route
import java.sql.Connection
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private val log = org.slf4j.LoggerFactory.getLogger(ExtensionRegistry::class.java)

private val CompanyCodePattern = Regex("[a-z][a-z0-9_]{0,62}")

/**
 * Every extension the server knows: the ones bundled in the compiled modules plus those loaded from
 * packages. Routes are mounted once at startup (Ktor cannot add them later); the gate decides per
 * request, from tenant_extensions, whether the company may use them.
 */
class ExtensionRegistry(extensions: List<ServerExtension>) {
    private val byId: Map<String, ServerExtension>

    init {
        // A duplicated or malformed id never stops the server: the first one wins (bundled extensions
        // come before packages) and the rest are skipped with an error in the log.
        val accepted = LinkedHashMap<String, ServerExtension>()
        for (extension in extensions) {
            val id = extension.manifest.id
            when {
                !ExtensionIdPattern.matches(id) -> log.error("Extension skipped: invalid id '{}'", id)
                accepted.containsKey(id) -> log.error("Extension '{}' of module {} skipped: the id is already used by another extension", id, extension.manifest.module)
                else -> accepted[id] = extension
            }
        }
        byId = accepted
    }

    val all: List<ServerExtension> get() = byId.values.sortedBy { it.manifest.id }

    fun get(extensionId: String): ServerExtension? = byId[extensionId]

    fun forModule(moduleId: String): List<ServerExtension> = all.filter { it.manifest.module == moduleId }

    /**
     * Mounts the routes of every extension under /modules/<module>/extensions/<id>/ behind the gate.
     * [route] is the application's root route.
     */
    fun installRoutes(route: Route, access: ModuleAccessService, storage: ExtensionStorage) {
        val gate = ExtensionGate(
            moduleInstalled = access::isInstalled,
            stateOf = { company, extensionId, moduleId ->
                access.tenantConnectionForCompany(company)?.use { tenant ->
                    ExtensionTenantState(storage.status(tenant, extensionId), moduleEnabled(tenant, moduleId))
                }
            },
        )
        for (extension in all) {
            val manifest = extension.manifest
            route.route("/modules/${manifest.module}/extensions/${manifest.id}") {
                // Responding in onCall marks the call handled, so the extension's handler never runs.
                install(createRouteScopedPlugin("ExtensionGate-${manifest.module}-${manifest.id}") {
                    onCall { call ->
                        // A company-scoped route names it in the path ({company}), never in the query string.
                        // The others use X-Company-Code or the cookie. When both come they must match.
                        val routingCall = call as? io.ktor.server.routing.RoutingPipelineCall
                        val pathCompany = routingCall?.pathParameters?.get("company")
                        // Falla cerrado: si la ruta resuelta declara {company} y no se pudo leer (o no hay
                        // llamada de enrutamiento), no se abre la compuerta como si fuera una ruta de servidor.
                        val companyUnreadable = routingCall == null ||
                            (pathCompany.isNullOrBlank() && routingCall.route.toString().contains("{company}"))
                        val decision = if (companyUnreadable) {
                            ExtensionGateDecision.Closed(
                                HttpStatusCode.Forbidden,
                                ModuleAccessErrorResponse("company_unreadable", "No se pudo determinar la empresa de la ruta."),
                            )
                        } else {
                            withContext(Dispatchers.IO) {
                                gate.decide(manifest.module, manifest.id, pathCompany, access.companyCode(call))
                            }
                        }
                        if (decision is ExtensionGateDecision.Closed) {
                            call.respond(decision.status, decision.body)
                        }
                    }
                })
                extension.routes(this, ExtensionAccessImpl(manifest, access, storage))
            }
        }
    }

    companion object {
        /** Bundled extensions of the compiled modules plus [packages] (already validated and loaded). */
        fun of(modules: List<ServerModule>, packages: List<ServerExtension> = emptyList()): ExtensionRegistry =
            ExtensionRegistry(modules.flatMap { it.bundledExtensions } + packages)
    }
}

/** What the gate reads in a company: the extension's status (null = no row) and whether its module is enabled. */
data class ExtensionTenantState(val status: String?, val moduleEnabled: Boolean)

/**
 * Answers for the routes of one extension. Server-level routes (no company, such as the Meta
 * webhook of the server) pass when the module is installed; the extension then resolves the company
 * itself and uses ExtensionAccess.enabledTenant.
 */
class ExtensionGate(
    private val moduleInstalled: (String) -> Boolean,
    private val stateOf: (companyCode: String, extensionId: String, moduleId: String) -> ExtensionTenantState?,
) {
    /**
     * [pathCompany] is the {company} path parameter; [headerCompany] comes from X-Company-Code or
     * the cookie. The path wins, but a header naming another company closes the route.
     */
    fun decide(moduleId: String, extensionId: String, pathCompany: String?, headerCompany: String? = null): ExtensionGateDecision {
        if (!moduleInstalled(moduleId)) {
            return ExtensionGateDecision.Closed(
                HttpStatusCode.Forbidden,
                ModuleAccessErrorResponse("module_not_installed", "El modulo no esta instalado en el servidor."),
            )
        }
        val path = pathCompany?.trim()?.takeIf { it.isNotEmpty() }
        val header = headerCompany?.trim()?.takeIf { it.isNotEmpty() }
        if (path != null && header != null && path != header) {
            return ExtensionGateDecision.Closed(
                HttpStatusCode.Forbidden,
                ModuleAccessErrorResponse("company_mismatch", "La empresa de la ruta no coincide con la de la sesion."),
            )
        }
        val company = path ?: header ?: return ExtensionGateDecision.Open
        if (!CompanyCodePattern.matches(company)) return ExtensionGateDecision.forStatus(null)
        val state = stateOf(company, extensionId, moduleId) ?: return ExtensionGateDecision.forStatus(null)
        val decision = ExtensionGateDecision.forStatus(state.status)
        if (decision is ExtensionGateDecision.Open && !state.moduleEnabled) {
            return ExtensionGateDecision.Closed(
                HttpStatusCode.Forbidden,
                ModuleAccessErrorResponse("module_disabled", "El modulo no esta activo para esta empresa."),
            )
        }
        return decision
    }
}

private fun moduleEnabled(tenant: Connection, moduleId: String): Boolean =
    tenant.prepareStatement("SELECT 1 FROM tenant_modules WHERE module_id = ? AND status = 'enabled'").use { query ->
        query.setString(1, moduleId)
        query.executeQuery().use { it.next() }
    }

class ExtensionAccessImpl(
    private val manifest: ExtensionManifest,
    override val modules: ModuleAccessService,
    private val storage: ExtensionStorage,
) : ExtensionAccess {
    override fun enabledTenant(companyCode: String): Connection? {
        val tenant = modules.tenantConnectionForWebhook(companyCode, manifest.module) ?: return null
        return try {
            if (storage.status(tenant, manifest.id) == ExtensionStatus.Enabled) {
                tenant
            } else {
                tenant.close()
                null
            }
        } catch (failure: Exception) {
            tenant.close()
            throw failure
        }
    }

    override fun settings(tenant: Connection): ExtensionSettings = storage.settings(tenant, manifest.id)

    override fun enabledCompanies(): List<String> = modules.companiesWithModule(manifest.module).filter { company ->
        runCatching { modules.tenantConnectionForCompany(company)?.use { storage.status(it, manifest.id) } }.getOrNull() == ExtensionStatus.Enabled
    }

    override fun recordObject(tenant: Connection, kind: String, objectRef: String, optionId: String?) =
        storage.recordObject(tenant, manifest.id, kind, objectRef, optionId)
}
