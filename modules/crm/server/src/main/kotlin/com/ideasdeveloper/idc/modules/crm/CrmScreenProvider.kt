package com.ideasdeveloper.idc.modules.crm

import com.ideasdeveloper.idc.server.modules.McpTool
import com.ideasdeveloper.idc.ui.UiNode
import com.ideasdeveloper.idc.ui.UiNotice
import com.ideasdeveloper.idc.ui.UiText
import com.ideasdeveloper.idc.ui.UiTone
import java.sql.Connection

/** Una pantalla que aporta una extension al CRM; [id] lleva el prefijo "<extensionId>_" y [permission] es un permiso del CRM. */
data class CrmExtensionSection(val id: String, val label: String, val permission: String, val inMenu: Boolean = true)

/** Lo que devuelve una accion de una extension: la pantalla que se muestra despues, con su aviso y sus parametros. */
data class CrmExtensionActionResult(
    val screen: String,
    val notice: UiNotice?,
    val params: Map<String, String> = emptyMap(),
    val submitted: Map<String, String>? = null,
)

/**
 * Una extension que aporta pantallas, acciones, herramientas MCP y un bloque en la ficha del lead.
 * El CRM controla el permiso de cada seccion y que la extension este activa en la empresa; un fallo del proveedor
 * se muestra como aviso y nunca rompe el CRM.
 */
interface CrmScreenProvider {
    val extensionId: String

    /** Secciones con ids que empiezan por "<extensionId>_". */
    val sections: List<CrmExtensionSection>

    fun screen(context: CrmScreens.Context, screen: String, params: Map<String, String>, submitted: Map<String, String>?): List<UiNode>

    /**
     * Los errores de negocio se devuelven como aviso en el resultado ([CrmExtensionActionResult.notice]); una excepcion
     * [CrmValidationException] se muestra tal cual y cualquier otro fallo se reporta al usuario con un aviso generico.
     */
    fun action(
        context: CrmScreens.Context,
        actor: CrmActor,
        screen: String,
        op: String,
        params: Map<String, String>,
        values: Map<String, String>,
    ): CrmExtensionActionResult

    val mcpTools: List<McpTool> get() = emptyList()

    fun leadPanel(context: CrmScreens.Context, lead: CrmLead): List<UiNode> = emptyList()
}

/** Acceso del CRM a los proveedores de pantallas de las extensiones instaladas. */
object CrmScreenProviders {
    private val log = org.slf4j.LoggerFactory.getLogger("CrmScreenProviders")

    val all: List<CrmScreenProvider> get() = CrmModuleExtensions.screenProviders()

    /** Lo validado de una lista de proveedores; se construye una vez por cada `link(...)` y no cambia despues. */
    private class Snapshot(val source: List<CrmScreenProvider>) {
        val byId: Map<String, Pair<CrmScreenProvider, CrmExtensionSection>>
        val sectionsOf: Map<CrmScreenProvider, List<CrmExtensionSection>>

        init {
            val ids = LinkedHashMap<String, Pair<CrmScreenProvider, CrmExtensionSection>>()
            for (provider in source) {
                for (section in provider.sections) {
                    if (CrmScreens.isOwnScreen(section.id) || !section.id.startsWith(provider.extensionId + "_")) continue
                    ids.putIfAbsent(section.id, provider to section)
                }
            }
            byId = ids
            sectionsOf = source.associateWith { provider -> provider.sections.filter { ids[it.id]?.first === provider } }
        }

        // Las herramientas MCP se leen al primer uso: las propias del CRM ya estan listas y asi no hay recursion al construirlo.
        val mcpTools: List<McpTool> by lazy {
            val own = (
                CrmMcpTools.tools + CrmSettingsMcpTools.tools + CrmAutomationMcpTools.tools +
                    CrmIntegrationMcpTools.tools + CrmImportMcpTools.tools + CrmImportSources.mcpTools
                ).map { it.name }.toSet()
            source.flatMap { provider ->
                provider.mcpTools.filter { tool ->
                    val valid = tool.name.startsWith(provider.extensionId + "_") && tool.name !in own
                    if (!valid) log.warn("Herramienta MCP ignorada de la extension {}: nombre sin prefijo o repetido.", provider.extensionId)
                    valid
                }
            }
        }
    }

    @Volatile
    private var snapshot = Snapshot(emptyList())

    // Se recalcula cuando cambia la lista enlazada (la referencia cambia con cada link).
    private fun current(): Snapshot {
        val source = CrmModuleExtensions.screenProviders()
        val cached = snapshot
        if (cached.source === source) return cached
        return Snapshot(source).also { snapshot = it }
    }

    /** La seccion [id] y su proveedor; se ignoran los ids que chocan con pantallas del CRM o no llevan el prefijo de la extension. */
    fun section(id: String): Pair<CrmScreenProvider, CrmExtensionSection>? = current().byId[id]

    fun enabled(tenant: Connection, provider: CrmScreenProvider): Boolean = CrmExtensions.isEnabled(tenant, provider.extensionId)

    /** Los proveedores activos en la empresa; evalua cada uno una sola vez (para el menu y la ficha del lead). */
    fun active(tenant: Connection): List<CrmScreenProvider> = all.filter { enabled(tenant, it) }

    /**
     * Herramientas MCP de los proveedores: solo las que empiezan por "<extensionId>_" y no chocan con los nombres
     * de las herramientas del propio CRM (se leen sin las de proveedores, para no caer en recursion).
     */
    val mcpTools: List<McpTool> get() = current().mcpTools

    /** Secciones validas de los proveedores (con el prefijo y sin chocar con pantallas del CRM). */
    fun sections(provider: CrmScreenProvider): List<CrmExtensionSection> = current().sectionsOf[provider].orEmpty()

    /** Paneles de la ficha del lead de los proveedores activos; un fallo deja un aviso corto y no se propaga. */
    fun leadPanels(context: CrmScreens.Context, lead: CrmLead): List<UiNode> = active(context.tenant).flatMap { provider ->
        try {
            provider.leadPanel(context, lead)
        } catch (failure: Exception) {
            log.warn("Fallo el panel del lead de la extension {}.", provider.extensionId, failure)
            listOf<UiNode>(UiText("No se pudo mostrar el bloque de ${CrmExtensions.displayName(provider.extensionId)}.", tone = UiTone.Warning))
        }
    }
}
