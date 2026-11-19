package com.ideasdeveloper.idc.extensions.inmobiliaria

import com.ideasdeveloper.idc.modules.crm.CrmExtensionActionResult
import com.ideasdeveloper.idc.modules.crm.CrmHost
import com.ideasdeveloper.idc.modules.crm.CrmScreens
import com.ideasdeveloper.idc.ui.UiBadge
import com.ideasdeveloper.idc.ui.UiButton
import com.ideasdeveloper.idc.ui.UiCard
import com.ideasdeveloper.idc.ui.UiColumn
import com.ideasdeveloper.idc.ui.UiField
import com.ideasdeveloper.idc.ui.UiForm
import com.ideasdeveloper.idc.ui.UiGrid
import com.ideasdeveloper.idc.ui.UiList
import com.ideasdeveloper.idc.ui.UiListItem
import com.ideasdeveloper.idc.ui.UiMetric
import com.ideasdeveloper.idc.ui.UiNavigate
import com.ideasdeveloper.idc.ui.UiNode
import com.ideasdeveloper.idc.ui.UiOpenUrl
import com.ideasdeveloper.idc.ui.UiRow
import com.ideasdeveloper.idc.ui.UiStatus
import com.ideasdeveloper.idc.ui.UiSuggest
import com.ideasdeveloper.idc.ui.UiTable
import com.ideasdeveloper.idc.ui.UiTableColumn
import com.ideasdeveloper.idc.ui.UiText
import com.ideasdeveloper.idc.ui.UiTextStyle
import com.ideasdeveloper.idc.ui.UiTone
import java.util.UUID

/** Lectura pura de los enlaces por portal de un formulario (filas l1_label, l1_url, l2_label...). */
object InmobiliariaLinkForms {
    /** Etiquetas que se sugieren al escribir un enlace. */
    val SuggestedLabels = listOf("Sitio web", "Sitio en espanol", "Craigslist", "Encuentra24", "Google Drive")

    /** Las filas con algo escrito, en orden; las vacias se saltan. El servicio valida etiqueta y direccion. */
    fun parse(values: Map<String, String>, maxRows: Int = InmobiliariaService.MaxLinks): List<LinkInput> =
        (1..maxRows).mapNotNull { row ->
            val label = values["l${row}_label"].orEmpty().trim()
            val url = values["l${row}_url"].orEmpty().trim()
            if (label.isEmpty() && url.isEmpty()) null else LinkInput(label, url)
        }

    /** Cuantas filas dibujar: las que ya hay y tres vacias mas, o las que trajo un formulario devuelto con error. */
    fun rows(existing: Int, submitted: Map<String, String>?, maxRows: Int = InmobiliariaService.MaxLinks): Int {
        val sent = submitted?.keys?.mapNotNull { key -> Regex("^l(\\d+)_").find(key)?.groupValues?.get(1)?.toIntOrNull() }?.maxOrNull() ?: 0
        return maxOf(existing + 3, sent).coerceIn(1, maxRows)
    }
}

/** Pantallas del inventario: lista, ficha y formularios de la propiedad, y captaciones. */
internal object InmobiliariaPropertyScreens {
    const val NoAgent = "Sin agente"
    private const val OutOfScope = "(cliente fuera de tu alcance)"

    private fun statusTone(status: PropertyStatus) = when (status) {
        PropertyStatus.Intake -> UiTone.Info
        PropertyStatus.ForSale -> UiTone.Success
        PropertyStatus.Negotiation -> UiTone.Warning
        PropertyStatus.Sold -> UiTone.Neutral
        PropertyStatus.Withdrawn -> UiTone.Neutral
    }

    // ---- Lista de propiedades ----

    private fun propertyRow(context: CrmScreens.Context, property: Property): UiListItem {
        val perM2 = property.pricePerLotM2?.let { "${InmobiliariaFormat.money(it, property.currency)}/m2 lote" }
        val subtitle = listOfNotNull(
            listOf(property.zone, property.kind.label).filter { it.isNotEmpty() }.joinToString(" - "),
            perM2,
            "Agente: ${InmobiliariaUi.agentName(context.users, property.listingAgentId)}",
        ).filter { it.isNotEmpty() }.joinToString("\n")
        return UiListItem(
            title = "${property.code} - ${property.title}",
            subtitle = subtitle,
            trailing = InmobiliariaFormat.money(property.price, property.currency),
            badges = listOfNotNull(
                UiBadge(property.status.label, statusTone(property.status)),
                UiBadge("Rebajada", UiTone.Warning).takeIf { property.reduced },
            ),
            action = UiNavigate(InmobiliariaScreenIds.Property, mapOf("id" to property.id.toString())),
        )
    }

    fun inventory(context: CrmScreens.Context, params: Map<String, String>): List<UiNode> {
        val s = InmobiliariaUi.session(context)
        val filter = InmobiliariaFilters.property(params, context.users, context.scope.userId)
        val zones = InmobiliariaService.zones(s)
        var page = (params["page"]?.toIntOrNull() ?: 1).coerceAtLeast(1)
        var result = InmobiliariaService.searchProperties(s, filter, InmobiliariaFilters.PageSize, (page - 1) * InmobiliariaFilters.PageSize)
        if (page > InmobiliariaFilters.pages(result.total)) {
            page = InmobiliariaFilters.pages(result.total)
            result = InmobiliariaService.searchProperties(s, filter, InmobiliariaFilters.PageSize, (page - 1) * InmobiliariaFilters.PageSize)
        }
        val all = InmobiliariaFilters.All
        val fields = listOf(
            UiField("q", "Buscar", placeholder = "Codigo, titulo, zona o desarrollo"),
            UiField("fZone", "Zona", type = "dropdown", options = listOf(all) + zones),
            UiField("fKind", "Tipo", type = "dropdown", options = listOf(all) + PropertyKind.entries.map { it.label }),
            UiField("fStatus", "Estado", type = "dropdown", options = listOf(all) + PropertyStatus.entries.map { it.label }),
            UiField(
                "fAgent", "Agente", type = "dropdown",
                options = listOf(all) + listOfNotNull(InmobiliariaFilters.MineListings.takeIf { context.scope.userId != null }) + context.users.map { it.name },
            ),
            UiField("minPrice", "Precio desde"),
            UiField("maxPrice", "Precio hasta"),
        )
        val values = fields.associate { it.key to (params[it.key] ?: if (it.type == "dropdown") all else "") }
        val canCreate = s.access.canCreate
        return listOfNotNull(
            InmobiliariaUi.row(
                UiButton("Nueva captacion", UiNavigate(InmobiliariaScreenIds.Property)).takeIf { canCreate },
                UiButton("Captaciones", UiNavigate(InmobiliariaScreenIds.Intake), style = "secondary"),
            ),
            UiCard(
                listOf(
                    UiForm(
                        "filters", fields, values,
                        submit = UiButton("Filtrar", UiNavigate(InmobiliariaScreenIds.Inventory, formId = "filters"), style = "secondary"),
                        minFieldWidth = 200,
                    ),
                ),
            ),
            UiCard(
                listOf(UiList(result.items.map { propertyRow(context, it) }, emptyText = "No hay propiedades con esos filtros.")),
                title = "${result.total} propiedad(es)",
            ),
            InmobiliariaUi.pager(InmobiliariaScreenIds.Inventory, InmobiliariaFilters.keep(params), page, result.total),
        )
    }

    // ---- Captaciones ----

    fun intake(context: CrmScreens.Context, params: Map<String, String>): List<UiNode> {
        val s = InmobiliariaUi.session(context)
        val filter = InmobiliariaFilters.intake(params, context.scope.userId)
        var page = (params["page"]?.toIntOrNull() ?: 1).coerceAtLeast(1)
        var result = InmobiliariaService.searchProperties(s, filter, InmobiliariaFilters.PageSize, (page - 1) * InmobiliariaFilters.PageSize)
        if (page > InmobiliariaFilters.pages(result.total)) {
            page = InmobiliariaFilters.pages(result.total)
            result = InmobiliariaService.searchProperties(s, filter, InmobiliariaFilters.PageSize, (page - 1) * InmobiliariaFilters.PageSize)
        }
        val fields = listOfNotNull(
            UiField("q", "Buscar", placeholder = "Codigo, titulo, zona o desarrollo"),
            UiField("fMine", "Mis proximos listados", type = "boolean").takeIf { context.scope.userId != null },
        )
        val values = fields.associate { it.key to (params[it.key] ?: if (it.type == "boolean") "false" else "") }
        val keep = InmobiliariaFilters.keep(params)
        val cards = result.items.map { property ->
            UiCard(
                listOf(
                    UiText(
                        listOf(property.zone, property.kind.label, InmobiliariaFormat.money(property.price, property.currency), "Agente: ${InmobiliariaUi.agentName(context.users, property.listingAgentId)}")
                            .filter { it.isNotEmpty() }.joinToString(" - "),
                        UiTextStyle.Caption,
                    ),
                ),
                title = "${property.code} - ${property.title}",
                actions = listOfNotNull(
                    UiButton("Abrir", UiNavigate(InmobiliariaScreenIds.Property, mapOf("id" to property.id.toString())), style = "secondary"),
                    UiButton(
                        "Publicar",
                        InmobiliariaUi.request(InmobiliariaScreenIds.Intake, "publish", keep + ("id" to property.id.toString()), confirm = "Publicar ${property.code}: pasa a En venta. Continuar?"),
                    ).takeIf { s.access.canEditProperty(property) },
                ),
            )
        }
        return listOfNotNull(
            InmobiliariaUi.row(
                UiButton("Nueva captacion", UiNavigate(InmobiliariaScreenIds.Property)).takeIf { s.access.canCreate },
                UiButton("Todas las propiedades", UiNavigate(InmobiliariaScreenIds.Inventory), style = "secondary"),
            ),
            UiCard(
                listOf(
                    UiForm(
                        "filters", fields, values,
                        submit = UiButton("Filtrar", UiNavigate(InmobiliariaScreenIds.Intake, formId = "filters"), style = "secondary"),
                        minFieldWidth = 220,
                    ),
                ),
            ),
            UiText("${result.total} propiedad(es) en captacion, pendientes de publicar.", UiTextStyle.Caption),
        ) + (if (cards.isEmpty()) listOf<UiNode>(UiText("No hay captaciones pendientes.", UiTextStyle.Caption)) else cards) + listOfNotNull(
            InmobiliariaUi.pager(InmobiliariaScreenIds.Intake, keep, page, result.total),
        )
    }

    // ---- Ficha y formularios de la propiedad ----

    private fun dataFields(agentNames: List<String>, creating: Boolean): List<UiField> = buildList {
        add(UiField("title", "Titulo", required = true))
        add(UiField("code", "Codigo", help = "Vacio: se genera uno (P-0001)."))
        add(UiField("kind", "Tipo", type = "dropdown", options = PropertyKind.entries.map { it.label }))
        add(UiField("zone", "Zona"))
        add(UiField("development", "Desarrollo"))
        add(UiField("agent", "Agente del listado", type = "dropdown", options = listOf(NoAgent) + agentNames))
        add(
            UiField(
                "name", "Vendedor (cliente)", placeholder = "Escribe para buscar un cliente",
                suggest = UiSuggest(CrmScreens.CustomerSuggestRoute),
                help = "Solo clientes que ves. Vacio: sin vendedor.",
            ),
        )
        if (creating) add(UiField("price", "Precio", help = "Por ejemplo 125000 o 1,250,000.50."))
        add(UiField("lotArea", "Area del lote (m2)"))
        add(UiField("builtArea", "Area construida (m2)"))
        add(UiField("bedrooms", "Habitaciones"))
        add(UiField("bathrooms", "Banos"))
        add(UiField("currency", "Moneda", help = "Codigo de 3 letras (USD). Vacio: la de los ajustes."))
        add(UiField("publishedOn", "Fecha de publicacion", type = "date"))
        add(UiField("wordpressListing", "Id del listado en el sitio web", help = "Opcional: numero del listado en WordPress."))
        add(UiField("description", "Descripcion", type = "textarea"))
        add(UiField("notes", "Notas internas", type = "textarea"))
    }

    private fun dataValues(context: CrmScreens.Context, property: Property?, settings: Settings): Map<String, String> {
        val ownerId = property?.ownerCustomerId
        val visibleOwner = ownerId?.let { InmobiliariaLookups.customerNames(context, listOf(it))[it] }
        val ownerName = if (ownerId == null) "" else visibleOwner ?: OutOfScope
        val defaultAgent = context.users.firstOrNull { it.id == context.scope.userId }?.name ?: NoAgent
        return mapOf(
            "title" to property?.title.orEmpty(),
            "code" to property?.code.orEmpty(),
            "kind" to (property?.kind ?: PropertyKind.Other).label,
            "zone" to property?.zone.orEmpty(),
            "development" to property?.development.orEmpty(),
            "agent" to if (property == null) defaultAgent else context.users.firstOrNull { it.id == property.listingAgentId }?.name ?: NoAgent,
            "name" to ownerName,
            // El id del vendedor solo viaja en el formulario si el usuario ve a ese cliente.
            "customerId" to if (visibleOwner != null) ownerId.toString() else "",
            "lotArea" to InmobiliariaFormat.field(property?.lotAreaM2),
            "builtArea" to InmobiliariaFormat.field(property?.builtAreaM2),
            "bedrooms" to property?.bedrooms?.toString().orEmpty(),
            "bathrooms" to InmobiliariaFormat.field(property?.bathrooms),
            "currency" to (property?.currency ?: settings.currency),
            "publishedOn" to property?.publishedOn?.toString().orEmpty(),
            "wordpressListing" to property?.wordpressListingId?.toString().orEmpty(),
            "description" to property?.description.orEmpty(),
            "notes" to property?.notes.orEmpty(),
        )
    }

    private fun wordpressCard(propertyId: UUID?, listing: String): UiNode {
        val id = propertyId?.toString()
        return UiCard(
            listOf(
                UiText(
                    if (id == null) "Capta una propiedad nueva con los datos de un listado del sitio web." else "Completa lo que esta vacio con los datos del listado y lo vincula a esta propiedad.",
                    UiTextStyle.Caption,
                ),
                UiForm(
                    "propertyWp",
                    listOf(UiField("listing", "Id del listado en el sitio", required = true, help = "Es el numero (ID) del listado en WordPress.")),
                    mapOf("listing" to listing),
                    submit = UiButton(
                        "Traer datos del sitio",
                        InmobiliariaUi.request(InmobiliariaScreenIds.Property, "import_wp", listOfNotNull(id?.let { "id" to it }).toMap(), formId = "propertyWp"),
                        style = "secondary",
                    ),
                    minFieldWidth = 220,
                ),
            ),
            title = "Traer datos del sitio",
            collapsible = true,
            expanded = false,
        )
    }

    fun property(context: CrmScreens.Context, params: Map<String, String>, submitted: Map<String, String>?): List<UiNode> {
        val s = InmobiliariaUi.session(context)
        val settings = InmobiliariaService.settings(s)
        val wordpress = CrmHost.wordpressActive(context.tenant)
        val sent = submitted.orEmpty()
        val id = params["id"]?.takeIf { it.isNotBlank() }
        if (id == null) {
            if (!s.access.canCreate) return InmobiliariaUi.message("No tienes permiso para captar propiedades.") + InmobiliariaUi.back(InmobiliariaScreenIds.Inventory, "Volver a Propiedades")
            val form = UiForm(
                "propertyData",
                dataFields(context.users.map { it.name }, creating = true),
                dataValues(context, null, settings) + mapOf("price" to "") + sent,
                submit = UiButton("Captar propiedad", InmobiliariaUi.request(InmobiliariaScreenIds.Property, "save", formId = "propertyData")),
            )
            return listOfNotNull(
                InmobiliariaUi.back(InmobiliariaScreenIds.Inventory, "Volver a Propiedades"),
                UiCard(listOf(form), title = "Nueva captacion"),
                wordpressCard(null, sent["listing"].orEmpty()).takeIf { wordpress },
            )
        }
        val propertyId = InmobiliariaUi.uuid(id) ?: return InmobiliariaUi.message("Esa propiedad no existe.") + InmobiliariaUi.back(InmobiliariaScreenIds.Inventory, "Volver a Propiedades")
        val detail = try {
            InmobiliariaService.getProperty(s, propertyId)
        } catch (failure: InmobiliariaException) {
            return InmobiliariaUi.message(failure.message.orEmpty()) + InmobiliariaUi.back(InmobiliariaScreenIds.Inventory, "Volver a Propiedades")
        }
        val property = detail.property
        val canEdit = s.access.canEditProperty(property)
        val view = sheet(context, s, detail)
        val idParams = mapOf("id" to property.id.toString())
        val edit: List<UiNode> = if (!canEdit) emptyList() else {
            val linkRows = InmobiliariaLinkForms.rows(detail.links.size, submitted)
            val linkFields = (1..linkRows).flatMap { row ->
                listOf(
                    UiField(
                        "l${row}_label", "Enlace $row: nombre",
                        help = if (row == 1) "Sugeridos: ${InmobiliariaLinkForms.SuggestedLabels.joinToString(", ")}." else "",
                    ),
                    UiField("l${row}_url", "Enlace $row: direccion", type = "url", placeholder = "https://"),
                )
            }
            val linkValues = (1..linkRows).flatMap { row ->
                val link = detail.links.getOrNull(row - 1)
                listOf("l${row}_label" to link?.label.orEmpty(), "l${row}_url" to link?.url.orEmpty())
            }.toMap()
            val formOpen = submitted != null
            listOf(
                UiCard(
                    listOf(
                        UiForm(
                            "propertyPrice",
                            listOf(UiField("price", "Nuevo precio", required = true), UiField("note", "Nota del cambio")),
                            mapOf("price" to InmobiliariaFormat.field(property.price), "note" to "") + sent.filterKeys { it == "price" || it == "note" },
                            submit = UiButton("Cambiar precio", InmobiliariaUi.request(InmobiliariaScreenIds.Property, "price", idParams, formId = "propertyPrice")),
                            minFieldWidth = 220,
                        ),
                        InmobiliariaUi.caption("El precio anterior queda en el historial y, si baja, la propiedad se ve Rebajada."),
                    ),
                    title = "Precio",
                    collapsible = true,
                    expanded = false,
                ),
                UiCard(
                    listOf(
                        UiForm(
                            "propertyStatus",
                            listOf(UiField("status", "Estado", type = "dropdown", options = PropertyStatus.entries.map { it.label })),
                            mapOf("status" to property.status.label),
                            submit = UiButton("Cambiar estado", InmobiliariaUi.request(InmobiliariaScreenIds.Property, "status", idParams, formId = "propertyStatus")),
                            minFieldWidth = 220,
                        ),
                    ),
                    title = "Estado",
                    collapsible = true,
                    expanded = false,
                ),
                UiCard(
                    listOf(
                        UiForm(
                            "propertyData",
                            dataFields(context.users.map { it.name }, creating = false),
                            dataValues(context, property, settings) + sent.filterKeys { it != "price" && it != "note" },
                            submit = UiButton("Guardar datos", InmobiliariaUi.request(InmobiliariaScreenIds.Property, "save", idParams, formId = "propertyData")),
                        ),
                    ),
                    title = "Datos de la propiedad",
                    collapsible = true,
                    expanded = formOpen && sent.containsKey("title"),
                ),
                UiCard(
                    listOf(
                        UiForm(
                            "propertyLinks", linkFields, linkValues + sent.filterKeys { it.startsWith("l") && it.contains('_') },
                            submit = UiButton("Guardar enlaces", InmobiliariaUi.request(InmobiliariaScreenIds.Property, "links", idParams, formId = "propertyLinks")),
                            minFieldWidth = 320,
                        ),
                        InmobiliariaUi.caption("Deja vacia una fila para quitar ese enlace. Hasta ${InmobiliariaService.MaxLinks} enlaces."),
                    ),
                    title = "Enlaces por portal",
                    collapsible = true,
                    expanded = false,
                ),
                wordpressCard(property.id, sent["listing"].orEmpty()).takeIf { wordpress },
            ).filterNotNull()
        }
        val leadCard = if (!s.access.canCreate) null else UiCard(
            listOf(
                UiForm(
                    "propertyLead",
                    listOf(UiField("lead", "Lead", required = true, placeholder = "Nombre, correo o id del lead", help = "Solo leads que ves.")),
                    mapOf("lead" to sent["lead"].orEmpty()),
                    submit = UiButton("Vincular", InmobiliariaUi.request(InmobiliariaScreenIds.Property, "link_lead", idParams, formId = "propertyLead"), style = "secondary"),
                    minFieldWidth = 260,
                ),
            ),
            title = "Vincular a un lead",
            collapsible = true,
            expanded = false,
        )
        return view + edit + listOfNotNull(leadCard)
    }

    private fun sheet(context: CrmScreens.Context, s: InmobiliariaSession, detail: PropertyDetail): List<UiNode> {
        val property = detail.property
        val canEdit = s.access.canEditProperty(property)
        val idParams = mapOf("id" to property.id.toString())
        val ownerName = property.ownerCustomerId?.let { InmobiliariaLookups.customerNames(context, listOf(it))[it] ?: OutOfScope }
        val header = UiRow(
            listOfNotNull(
                UiText("${property.code} - ${property.title}", UiTextStyle.Title),
                UiStatus(property.status.label, statusTone(property.status)),
                UiStatus("Rebajada", UiTone.Warning).takeIf { property.reduced },
            ),
        )
        val actions = InmobiliariaUi.row(
            UiButton("Volver a Propiedades", UiNavigate(InmobiliariaScreenIds.Inventory), style = "secondary"),
            UiButton("Publicar", InmobiliariaUi.request(InmobiliariaScreenIds.Property, "publish", idParams, confirm = "Publicar ${property.code}: pasa a En venta. Continuar?"))
                .takeIf { canEdit && property.status == PropertyStatus.Intake },
            UiButton(
                "Nueva transaccion", UiNavigate(InmobiliariaScreenIds.Deal, mapOf("property" to property.id.toString())),
                style = "secondary",
            ).takeIf { s.access.canCreate && property.status != PropertyStatus.Sold && property.status != PropertyStatus.Withdrawn },
        )
        val metrics = UiGrid(
            listOfNotNull(
                UiMetric("Precio", InmobiliariaFormat.money(property.price, property.currency)),
                property.previousPrice?.let { UiMetric("Precio anterior", InmobiliariaFormat.money(it, property.currency), if (property.reduced) "Rebajada" else "", UiTone.Warning) },
                UiMetric("Por m2 de lote", property.pricePerLotM2?.let { InmobiliariaFormat.money(it, property.currency) } ?: InmobiliariaFormat.Empty),
                UiMetric("Por m2 construido", property.pricePerBuiltM2?.let { InmobiliariaFormat.money(it, property.currency) } ?: InmobiliariaFormat.Empty),
                UiMetric("Estado", property.status.label),
            ),
            minItemWidth = 180,
        )
        val facts = listOf(
            "Tipo" to property.kind.label,
            "Zona" to property.zone,
            "Desarrollo" to property.development,
            "Agente del listado" to InmobiliariaUi.agentName(context.users, property.listingAgentId),
            "Vendedor" to ownerName.orEmpty(),
            "Area del lote" to property.lotAreaM2?.let { "${InmobiliariaMath.plain(it)} m2" }.orEmpty(),
            "Area construida" to property.builtAreaM2?.let { "${InmobiliariaMath.plain(it)} m2" }.orEmpty(),
            "Habitaciones" to property.bedrooms?.toString().orEmpty(),
            "Banos" to property.bathrooms?.let { InmobiliariaMath.plain(it) }.orEmpty(),
            "Publicada" to property.publishedOn?.toString().orEmpty(),
            "Vendida" to property.soldOn?.toString().orEmpty(),
            "Listado del sitio web" to property.wordpressListingId?.toString().orEmpty(),
            "Descripcion" to property.description,
            "Notas" to property.notes,
        ).filter { it.second.isNotBlank() }
        val data = UiCard(
            listOf(UiColumn(facts.map { (label, value) -> UiText("$label: $value") }, spacing = 4)),
            title = "Datos",
        )
        // Solo enlaces http/https validos: el servicio ya los exige al guardar, pero la ficha no confia en lo guardado.
        val portals = detail.links.filter { InmobiliariaInput.isValidUrl(it.url) }
        val links = UiCard(
            listOf(UiRow(portals.map { UiButton(it.label, UiOpenUrl(it.url), style = "secondary") })),
            title = "Enlaces por portal",
        ).takeIf { portals.isNotEmpty() }
        val history = UiCard(
            listOf(
                UiTable(
                    listOf(UiTableColumn("date", "Fecha"), UiTableColumn("old", "Anterior"), UiTableColumn("new", "Nuevo"), UiTableColumn("by", "Por"), UiTableColumn("note", "Nota")),
                    detail.priceChanges.map { change ->
                        mapOf(
                            "date" to InmobiliariaFormat.timestamp(change.changedAt),
                            "old" to InmobiliariaFormat.money(change.oldPrice, property.currency),
                            "new" to InmobiliariaFormat.money(change.newPrice, property.currency),
                            "by" to InmobiliariaUi.agentName(context.users, change.changedBy),
                            "note" to change.note,
                        )
                    },
                    emptyText = "Sin cambios de precio.",
                ),
            ),
            title = "Historial de precios",
        )
        val buyers = InmobiliariaLookups.leadNames(context, detail.deals.mapNotNull { it.buyerLeadId })
        val deals = UiCard(
            listOf(
                UiList(
                    detail.deals.map { deal ->
                        UiListItem(
                            title = listOfNotNull(deal.stageName, deal.buyerLeadId?.let { buyers[it] }).joinToString(" - "),
                            subtitle = "Acordado ${InmobiliariaFormat.money(deal.agreedPrice, deal.currency)} - Comision ${InmobiliariaFormat.money(deal.commissionTotal, deal.currency)}",
                            trailing = deal.estimatedCloseOn?.let { "Cierre $it" }.orEmpty(),
                            badges = listOf(UiBadge(deal.stageOutcome.label, dealTone(deal.stageOutcome))),
                            action = UiNavigate(InmobiliariaScreenIds.Deal, mapOf("id" to deal.id.toString())),
                        )
                    },
                    emptyText = "Sin transacciones visibles para ti.",
                ),
            ),
            title = "Transacciones",
        )
        return listOfNotNull(header, actions, metrics, data, links, history, deals)
    }

    fun dealTone(outcome: DealOutcome) = when (outcome) {
        DealOutcome.Open -> UiTone.Info
        DealOutcome.Won -> UiTone.Success
        DealOutcome.Lost -> UiTone.Danger
    }

    // ---- Acciones ----

    private fun requireId(params: Map<String, String>): UUID =
        InmobiliariaUi.uuid(params["id"]) ?: throw InmobiliariaException("Esa propiedad no existe.")

    // Convierte el formulario en lo que lee InmobiliariaForms.parseProperty: agente "Sin agente" = ninguno y el vendedor es el cliente elegido.
    // [currentOwner] es el vendedor actual con el texto con que se mostro: si el texto no cambia, se conserva aunque el usuario no lo vea.
    private fun propertyValues(values: Map<String, String>, currentOwner: Pair<UUID, String>?): Map<String, String> {
        val agent = values["agent"].orEmpty().takeIf { it != NoAgent }.orEmpty()
        val owner = InmobiliariaDealForms.pickCustomer(values["name"], values["customerId"], currentOwner, "El vendedor")?.toString().orEmpty()
        return values + mapOf("agent" to agent, "ownerCustomer" to owner)
    }

    // Id de propiedad opcional de la peticion: vacio = ninguna; escrito pero invalido = error (nunca se crea una nueva por un id roto).
    private fun optionalId(params: Map<String, String>): UUID? {
        val raw = params["id"]?.trim().orEmpty()
        if (raw.isEmpty()) return null
        return InmobiliariaUi.uuid(raw) ?: throw InmobiliariaException("Esa propiedad no existe.")
    }

    fun action(context: CrmScreens.Context, s: InmobiliariaSession, screen: String, op: String, params: Map<String, String>, values: Map<String, String>): CrmExtensionActionResult {
        val clean = params - "op"
        if (screen == InmobiliariaScreenIds.Intake) {
            if (op != "publish") return InmobiliariaUi.failure(screen, "Esa accion no existe.", clean, values)
            val property = InmobiliariaService.publish(s, requireId(params))
            return InmobiliariaUi.success(screen, "${property.code} publicada: ahora esta En venta.", clean - "id")
        }
        val screenId = InmobiliariaScreenIds.Property
        return when (op) {
            "save" -> {
                val id = optionalId(params)
                val currentOwner = id?.let { InmobiliariaService.getProperty(s, it).property.ownerCustomerId }?.let { owner ->
                    owner to (InmobiliariaLookups.customerNames(context, listOf(owner))[owner] ?: OutOfScope)
                }
                val fields = InmobiliariaForms.parseProperty(propertyValues(values, currentOwner)) { name -> InmobiliariaService.findAgent(s, name).id }
                if (id == null) {
                    val price = InmobiliariaInput.parseMoney(values["price"], "El precio")
                    val created = InmobiliariaService.createProperty(s, fields, price)
                    InmobiliariaUi.success(screenId, "Propiedad ${created.code} captada.", mapOf("id" to created.id.toString()))
                } else {
                    val updated = InmobiliariaService.updateProperty(s, id, fields)
                    InmobiliariaUi.success(screenId, "Propiedad ${updated.code} guardada.", mapOf("id" to updated.id.toString()))
                }
            }
            "price" -> {
                val price = InmobiliariaInput.parseMoney(values["price"], "El precio") ?: throw InmobiliariaException("Escribe el nuevo precio.")
                val property = InmobiliariaService.changePrice(s, requireId(params), price, values["note"].orEmpty())
                InmobiliariaUi.success(screenId, "Precio actualizado a ${InmobiliariaFormat.money(property.price, property.currency)}.", clean)
            }
            "status" -> {
                val status = PropertyStatus.parse(values["status"]) ?: throw InmobiliariaException("Elige el estado.")
                val property = InmobiliariaService.changeStatus(s, requireId(params), status)
                InmobiliariaUi.success(screenId, "${property.code} ahora esta ${property.status.label}.", clean)
            }
            "publish" -> {
                val property = InmobiliariaService.publish(s, requireId(params))
                InmobiliariaUi.success(screenId, "${property.code} publicada: ahora esta En venta.", clean)
            }
            "links" -> {
                val links = InmobiliariaService.replaceLinks(s, requireId(params), InmobiliariaLinkForms.parse(values))
                InmobiliariaUi.success(screenId, if (links.isEmpty()) "Sin enlaces por portal." else "Enlaces guardados (${links.size}).", clean)
            }
            "link_lead" -> {
                val propertyId = requireId(params)
                val leadId = InmobiliariaLookups.resolveLead(context, values["lead"].orEmpty()) ?: throw InmobiliariaException("Escribe el lead.")
                val created = InmobiliariaService.linkToLead(s, propertyId, leadId)
                InmobiliariaUi.success(screenId, if (created) "Propiedad vinculada al lead." else "Esa propiedad ya estaba vinculada al lead.", clean)
            }
            "import_wp" -> {
                val listing = values["listing"].orEmpty().trim().toLongOrNull()?.takeIf { it > 0 } ?: throw InmobiliariaException("El id del listado debe ser un numero entero positivo.")
                val imported = InmobiliariaService.importFromWordPress(s, listing, optionalId(params))
                InmobiliariaUi.success(screenId, "Datos del sitio traidos a ${imported.property.code}.", mapOf("id" to imported.property.id.toString()), imported.warnings)
            }
            else -> InmobiliariaUi.failure(screenId, "Esa accion no existe.", clean, values)
        }
    }
}
