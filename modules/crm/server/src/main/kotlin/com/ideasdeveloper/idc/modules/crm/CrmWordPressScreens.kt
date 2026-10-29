package com.ideasdeveloper.idc.modules.crm

import com.ideasdeveloper.idc.ui.UiBadge
import com.ideasdeveloper.idc.ui.UiButton
import com.ideasdeveloper.idc.ui.UiCard
import com.ideasdeveloper.idc.ui.UiField
import com.ideasdeveloper.idc.ui.UiFieldCondition
import com.ideasdeveloper.idc.ui.UiForm
import com.ideasdeveloper.idc.ui.UiList
import com.ideasdeveloper.idc.ui.UiListItem
import com.ideasdeveloper.idc.ui.UiNavigate
import com.ideasdeveloper.idc.ui.UiNode
import com.ideasdeveloper.idc.ui.UiOpenUrl
import com.ideasdeveloper.idc.ui.UiRequest
import com.ideasdeveloper.idc.ui.UiRow
import com.ideasdeveloper.idc.ui.UiStatus
import com.ideasdeveloper.idc.ui.UiText
import com.ideasdeveloper.idc.ui.UiTextStyle
import com.ideasdeveloper.idc.ui.UiTone
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** Items of interest of a lead, the property search of the website and the WordPress settings. */
object CrmWordPressScreens {
    private const val UiRoute = CrmScreens.UiRoute
    const val PluginPath = "/modules/crm/wordpress/idc-connector.zip"
    private const val Any = "Todos"
    private val timeFormat = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")

    private fun statusTone(status: String) = when (status) {
        "sold", "booked" -> UiTone.Success
        "chosen" -> UiTone.Info
        "offered" -> UiTone.Warning
        "discarded" -> UiTone.Neutral
        else -> UiTone.Info
    }

    // ---- Lead detail: properties, rooms and other items ----

    fun items(context: CrmScreens.Context, lead: CrmLead, canEdit: Boolean): UiNode? {
        val items = CrmItems.list(context.tenant, lead.id)
        val wordpress = CrmWordPress.load(context.tenant).ready
        if (items.isEmpty() && !wordpress && !canEdit) return null
        val id = lead.id.toString()
        fun action(op: String, item: CrmItem, extra: Map<String, String> = emptyMap()) =
            UiRequest(UiRoute + "/lead", params = mapOf("id" to id, "op" to op, "item" to item.id.toString()) + extra)
        val rows = items.flatMap { item ->
            val info = listOfNotNull(
                item.subtitle.ifEmpty { null },
                item.startsOn?.let { start -> "Del $start${item.endsOn?.let { " al $it" }.orEmpty()}" },
                item.url.ifEmpty { null },
            ).joinToString("\n")
            val buttons = if (!canEdit) emptyList() else listOfNotNull(
                UiButton("Ofrecida", action("itemStatus", item, mapOf("status" to "offered")), style = "secondary").takeIf { item.status == "interested" },
                UiButton("Elegida", action("itemStatus", item, mapOf("status" to "chosen")), style = "secondary").takeIf { item.status in setOf("interested", "offered") },
                UiButton("Descartar", action("itemStatus", item, mapOf("status" to "discarded")), style = "secondary").takeIf { item.status !in setOf("discarded", "sold", "booked") },
                UiButton("Quitar", action("itemRemove", item, emptyMap()).copy(confirm = "Quitar ${item.title} del lead?"), style = "danger").takeIf { item.status !in setOf("sold", "booked") },
            )
            val sale = if (canEdit && item.source == CrmWordPress.Source && item.status !in setOf("sold", "discarded")) {
                UiForm(
                    "sold_${item.id}",
                    listOf(
                        UiField("price", "Precio de venta", type = "number"),
                        UiField("date", "Fecha de venta", type = "date"),
                    ),
                    mapOf("price" to item.price?.toPlainString().orEmpty(), "date" to context.today.toString()),
                    submit = UiButton(
                        "Cerrar venta",
                        UiRequest(UiRoute + "/lead", params = mapOf("id" to id, "op" to "itemSold", "item" to item.id.toString()), formId = "sold_${item.id}",
                            confirm = "Se marca la propiedad como vendida en el sitio y el lead pasa a ganado. Continuar?"),
                    ),
                    minFieldWidth = 180,
                )
            } else null
            listOfNotNull(
                UiList(listOf(UiListItem(item.title, info, badges = listOf(UiBadge(CrmItems.statusLabels[item.status] ?: item.status, statusTone(item.status)))))),
                buttons.takeIf { it.isNotEmpty() }?.let { UiRow(it) },
                sale,
            )
        }
        val manual = if (canEdit) UiForm(
            "manualItem",
            listOf(UiField("title", "Agregar a mano", placeholder = "Apto 3B, Torre Mar"), UiField("subtitle", "Detalle")),
            submit = UiButton("Agregar", UiRequest(UiRoute + "/lead", params = mapOf("id" to id, "op" to "itemManual"), formId = "manualItem"), style = "secondary"),
            minFieldWidth = 220,
        ) else null
        return UiCard(
            rows.ifEmpty { listOf(UiText("Sin propiedades ni habitaciones vinculadas.", UiTextStyle.Caption)) } + listOfNotNull(manual),
            title = "Intereses del lead",
            actions = listOfNotNull(UiButton("Buscar propiedades", UiNavigate("properties", mapOf("lead" to id)), style = "secondary").takeIf { wordpress && canEdit }),
        )
    }

    // ---- Property search on the website ----

    fun properties(context: CrmScreens.Context, lead: CrmLead, params: Map<String, String>): List<UiNode> {
        val taxonomies = try {
            CrmWordPress.taxonomies(context.tenant)
        } catch (failure: CrmWordPressException) {
            return listOf(UiText(failure.message.orEmpty(), tone = UiTone.Danger))
        }
        val filterKeys = listOf("property_type" to "Tipo", "ct_status" to "Estado", "city" to "Ciudad", "beds" to "Habitaciones")
        val fields = listOf(UiField("q", "Buscar", placeholder = "Direccion, titulo o MLS")) +
            filterKeys.filter { taxonomies[it.first].orEmpty().isNotEmpty() }.map { (key, label) ->
                UiField("f_$key", label, type = "dropdown", options = listOf(Any) + taxonomies[key].orEmpty())
            } + listOf(UiField("min", "Precio desde", type = "number"), UiField("max", "Precio hasta", type = "number"))
        val values = fields.associate { it.key to (params[it.key] ?: if (it.type == "dropdown") Any else "") }
        val page = params["page"]?.toIntOrNull()?.coerceAtLeast(1) ?: 1
        val filters = filterKeys.mapNotNull { (key, _) -> params["f_$key"]?.takeIf { it.isNotBlank() && it != Any }?.let { key to it } }.toMap()
        val linked = CrmItems.list(context.tenant, lead.id, CrmWordPress.Source).map { it.externalId }.toSet()
        val result = try {
            CrmWordPress.search(context.tenant, params["q"].orEmpty(), filters, params["min"].orEmpty(), params["max"].orEmpty(), page)
        } catch (failure: CrmWordPressException) {
            return listOf(UiText(failure.message.orEmpty(), tone = UiTone.Danger))
        }
        val keep = params.filterKeys { it == "lead" || it == "q" || it == "min" || it == "max" || it.startsWith("f_") }
        val list = UiList(
            result.items.map { listing ->
                val isLinked = listing.id.toString() in linked
                UiListItem(
                    title = listing.title,
                    subtitle = listOf(listing.priceText, listing.summary, listing.agentName.ifEmpty { null }?.let { "Agente: $it" }.orEmpty()).filter { it.isNotEmpty() }.joinToString(" - "),
                    trailing = if (isLinked) "Vinculada" else "Vincular",
                    badges = listOfNotNull(UiBadge("Vinculada", UiTone.Success).takeIf { isLinked }),
                    action = if (isLinked) null else UiRequest(UiRoute + "/properties", params = keep + mapOf("op" to "link", "listing" to listing.id.toString())),
                )
            },
            emptyText = "No hay propiedades con esos filtros.",
        )
        val pages = (result.total + 19) / 20
        return listOf(
            UiRow(listOf(UiButton("Volver al lead", UiNavigate("lead", mapOf("id" to lead.id.toString())), style = "secondary"), UiText("Propiedades para ${lead.name}", UiTextStyle.Subtitle))),
            UiCard(listOf(UiForm("propertySearch", fields, values, submit = UiButton("Buscar", UiNavigate("properties", mapOf("lead" to lead.id.toString()), formId = "propertySearch"), style = "secondary"), minFieldWidth = 180))),
            UiCard(
                listOfNotNull(
                    UiText("Toca una propiedad para vincularla al lead.", UiTextStyle.Caption),
                    list,
                    UiRow(
                        listOfNotNull(
                            UiButton("Anterior", UiNavigate("properties", keep + ("page" to (page - 1).toString())), style = "secondary").takeIf { page > 1 },
                            UiText("Pagina $page de ${maxOf(1, pages)}", UiTextStyle.Caption),
                            UiButton("Siguiente", UiNavigate("properties", keep + ("page" to (page + 1).toString())), style = "secondary").takeIf { page < pages },
                        ),
                    ).takeIf { pages > 1 },
                ),
                title = "${result.total} propiedad(es)",
            ),
        )
    }

    // ---- Settings: Integraciones > WordPress ----

    fun settings(context: CrmScreens.Context, secretShown: String?): List<UiNode> {
        val settings = CrmWordPress.load(context.tenant)
        val on = UiFieldCondition("enabled", listOf("true"))
        val status = when {
            !settings.ready -> UiStatus("WordPress desactivado", UiTone.Neutral)
            settings.lastError.isNotEmpty() -> UiStatus("Error: ${settings.lastError}", UiTone.Danger)
            settings.lastEventAt != null -> UiStatus("Conectado con ${settings.siteName.ifEmpty { settings.siteUrl.orEmpty() }}; ultimo evento ${timeFormat.format(settings.lastEventAt.atZone(ZoneId.systemDefault()))}", UiTone.Success)
            else -> UiStatus("Sin eventos todavia: instala el plugin y prueba la conexion", UiTone.Warning)
        }
        val agents = CrmWordPress.agents(context.tenant)
        val agentForm = if (agents.isEmpty()) UiText("Sincroniza los agentes para elegir el usuario de cada uno.", UiTextStyle.Caption) else UiForm(
            "wpAgents",
            agents.map { agent ->
                UiField("agent_${agent.wpUserId}", "${agent.name} (${agent.email})", type = "dropdown", options = listOf(CrmForms.Unassigned) + context.users.map { it.name })
            },
            agents.associate { agent -> "agent_${agent.wpUserId}" to (agent.userId?.let { id -> context.users.firstOrNull { it.id == id }?.name } ?: CrmForms.Unassigned) },
            submit = UiButton("Guardar agentes", UiRequest(UiRoute + "/integrations", params = mapOf("op" to "wpAgentsSave"), formId = "wpAgents"), style = "secondary"),
            minFieldWidth = 260,
        )
        return listOfNotNull(
            UiText("WordPress (Real Estate 7)", UiTextStyle.Subtitle),
            UiCard(
                listOf(
                    status,
                    UiText(
                        "1. Descarga el plugin IDC Connector ${CrmWordPress.PluginVersion} e instalalo en WordPress (Plugins > Anadir nuevo > Subir plugin). " +
                            "2. Genera el secreto aqui. 3. En WordPress, Ajustes > IDC Connector: direccion de IDC ${context.publicBase}, empresa ${context.permissions.companyCode} y el secreto. " +
                            "Los formularios, consultas de propiedades y visitas crean leads; favoritos, busquedas guardadas y vistas quedan en la actividad del lead.",
                    ),
                    UiForm(
                        "wordpress",
                        listOf(
                            UiField("enabled", "Conectar el sitio", type = "boolean"),
                            UiField("siteUrl", "Direccion del sitio", type = "url", visibleWhen = on, placeholder = "https://mi-inmobiliaria.com"),
                        ),
                        mapOf("enabled" to settings.enabled.toString(), "siteUrl" to settings.siteUrl.orEmpty()),
                        submit = UiButton("Guardar", UiRequest(UiRoute + "/integrations", params = mapOf("op" to "wpSave"), formId = "wordpress")),
                    ),
                ),
                actions = listOf(
                    UiButton("Descargar plugin", UiOpenUrl(context.publicBase + PluginPath), style = "secondary"),
                    UiButton(
                        if (settings.secret == null) "Generar secreto" else "Nuevo secreto",
                        UiRequest(UiRoute + "/integrations", params = mapOf("op" to "wpSecret"), confirm = if (settings.secret == null) null else "El secreto actual deja de funcionar hasta que pegues el nuevo en el plugin. Continuar?"),
                        style = "secondary",
                    ),
                    UiButton("Probar conexion", UiRequest(UiRoute + "/integrations", params = mapOf("op" to "wpPing")), style = "secondary", enabled = settings.ready),
                ),
            ),
            secretShown?.let { UiCard(listOf(UiText("Copia este secreto en Ajustes > IDC Connector. No se vuelve a mostrar."), UiText(it, UiTextStyle.Subtitle)), title = "Secreto del plugin") },
            UiCard(
                listOf(UiText("El agente de la propiedad consultada se sugiere como responsable del lead.", UiTextStyle.Caption), agentForm),
                title = "Agentes del sitio",
                actions = listOf(UiButton("Sincronizar agentes", UiRequest(UiRoute + "/integrations", params = mapOf("op" to "wpAgentsSync")), style = "secondary", enabled = settings.ready)),
            ),
        )
    }

    fun saleDate(raw: String?, today: LocalDate): LocalDate =
        raw?.trim()?.takeIf { it.isNotEmpty() }?.let { runCatching { LocalDate.parse(it) }.getOrNull() ?: throw CrmValidationException("La fecha de venta debe tener el formato AAAA-MM-DD.") } ?: today
}
