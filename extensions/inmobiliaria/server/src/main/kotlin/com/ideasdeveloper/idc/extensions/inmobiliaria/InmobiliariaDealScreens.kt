package com.ideasdeveloper.idc.extensions.inmobiliaria

import com.ideasdeveloper.idc.modules.crm.CrmExtensionActionResult
import com.ideasdeveloper.idc.modules.crm.CrmHost
import com.ideasdeveloper.idc.modules.crm.CrmItems
import com.ideasdeveloper.idc.modules.crm.CrmLead
import com.ideasdeveloper.idc.modules.crm.CrmScreens
import com.ideasdeveloper.idc.modules.crm.CrmUser
import com.ideasdeveloper.idc.ui.UiBadge
import com.ideasdeveloper.idc.ui.UiBarChart
import com.ideasdeveloper.idc.ui.UiButton
import com.ideasdeveloper.idc.ui.UiCard
import com.ideasdeveloper.idc.ui.UiChartPoint
import com.ideasdeveloper.idc.ui.UiField
import com.ideasdeveloper.idc.ui.UiForm
import com.ideasdeveloper.idc.ui.UiGrid
import com.ideasdeveloper.idc.ui.UiList
import com.ideasdeveloper.idc.ui.UiListItem
import com.ideasdeveloper.idc.ui.UiMetric
import com.ideasdeveloper.idc.ui.UiNavigate
import com.ideasdeveloper.idc.ui.UiNode
import com.ideasdeveloper.idc.ui.UiRow
import com.ideasdeveloper.idc.ui.UiStatus
import com.ideasdeveloper.idc.ui.UiSuggest
import com.ideasdeveloper.idc.ui.UiTable
import com.ideasdeveloper.idc.ui.UiTableColumn
import com.ideasdeveloper.idc.ui.UiText
import com.ideasdeveloper.idc.ui.UiTextStyle
import com.ideasdeveloper.idc.ui.UiTone
import java.math.BigDecimal
import java.util.UUID

/** Lectura pura de las partes de una transaccion y de las etapas (filas numeradas de un formulario). */
object InmobiliariaDealForms {
    const val NoAgent = "Ninguno"
    const val MaxStages = 30

    /**
     * Partes con algo escrito (filas p1_role, p1_agent, p1_customer, p1_name, p1_share, p1_notes...); las filas vacias se saltan.
     * [keep] relaciona el numero de fila con el id de la parte actual cuyo cliente el usuario no ve: el servicio conserva ese cliente.
     */
    fun parseParties(values: Map<String, String>, agents: List<CrmUser>, maxRows: Int = InmobiliariaService.MaxParties, keep: Map<Int, UUID> = emptyMap()): List<PartyInput> =
        (1..maxRows).mapNotNull { row ->
            fun text(key: String) = values["p${row}_$key"].orEmpty().trim()
            val agent = text("agent").takeIf { it != NoAgent }.orEmpty()
            val customer = text("customer")
            val name = text("name")
            val share = text("share")
            val notes = text("notes")
            if (agent.isEmpty() && customer.isEmpty() && name.isEmpty() && share.isEmpty() && notes.isEmpty()) return@mapNotNull null
            val role = PartyRole.parse(text("role")) ?: throw InmobiliariaException("Elige el rol de la parte $row.")
            val user = if (agent.isEmpty()) null else agents.firstOrNull { it.name == agent }?.id ?: throw InmobiliariaException("La parte $row tiene un agente desconocido.")
            PartyInput(
                role = role,
                userId = user,
                customerId = InmobiliariaInput.parseUuid(customer, "El cliente de la parte $row"),
                name = name,
                sharePct = InmobiliariaInput.parsePercent(share, "El porcentaje de la parte $row") ?: BigDecimal.ZERO,
                notes = notes,
                keepPartyId = keep[row],
            )
        }

    /** Parametro de la peticion con las partes cuyo cliente no se muestra: "fila:idParte,fila:idParte". */
    fun keepParam(rows: Map<Int, UUID>): String = rows.entries.joinToString(",") { "${it.key}:${it.value}" }

    /** Lee [keepParam]; lo que no se entienda se ignora (el servicio solo acepta ids de partes actuales de la transaccion). */
    fun parseKeep(raw: String?): Map<Int, UUID> =
        raw.orEmpty().split(',').mapNotNull { entry ->
            val row = entry.substringBefore(':').trim().toIntOrNull() ?: return@mapNotNull null
            val id = runCatching { UUID.fromString(entry.substringAfter(':', "").trim()) }.getOrNull() ?: return@mapNotNull null
            row to id
        }.toMap()

    /**
     * Cliente elegido en un selector (campo "name" con sugerencias y "customerId"). [current] es el cliente que el formulario traia
     * con el texto con que se mostro. Texto vacio = ninguno; texto igual al mostrado = se conserva el actual (el id del formulario no se
     * toma de ahi); texto distinto sin un cliente nuevo elegido de la lista = error.
     */
    fun pickCustomer(name: String?, customerId: String?, current: Pair<UUID, String>?, label: String): UUID? {
        val typed = name.orEmpty().trim()
        if (typed.isEmpty()) return null
        val picked = InmobiliariaInput.parseUuid(customerId, label)
        if (picked != null && picked != current?.first) return picked
        if (current != null && typed == current.second) return current.first
        throw InmobiliariaException("Elige el cliente de la lista.")
    }

    /** Cuantas filas de partes dibujar: las existentes y tres vacias mas (al menos 4), o las que trajo un formulario devuelto con error. */
    fun partyRows(existing: Int, submitted: Map<String, String>?, maxRows: Int = InmobiliariaService.MaxParties): Int =
        maxOf(existing + 3, 4, sentRows(submitted, "p")).coerceAtMost(maxRows)

    fun stageRows(existing: Int, submitted: Map<String, String>?): Int = maxOf(existing + 3, sentRows(submitted, "s")).coerceAtMost(MaxStages)

    private fun sentRows(submitted: Map<String, String>?, prefix: String): Int {
        val pattern = Regex("^$prefix(\\d+)_")
        return submitted?.keys?.mapNotNull { key -> pattern.find(key)?.groupValues?.get(1)?.toIntOrNull() }?.maxOrNull() ?: 0
    }

    /**
     * Etapas del formulario (s1_name, s1_outcome, s1_order...). [ids] son los ids con que se dibujo cada fila, en orden: la fila i con id
     * es esa etapa existente; una fila con id y sin nombre se quita (el servicio no deja borrar una etapa con transacciones).
     * El resultado va ordenado por el campo orden (y por fila si empatan).
     */
    fun parseStages(values: Map<String, String>, ids: List<UUID>, rows: Int): List<StageInput> {
        val entries = (1..rows).mapNotNull { row ->
            fun text(key: String) = values["s${row}_$key"].orEmpty().trim()
            val name = text("name")
            if (name.isEmpty()) return@mapNotNull null
            val outcome = DealOutcome.parse(text("outcome")) ?: throw InmobiliariaException("Elige el resultado de la etapa '$name' (Abierta, Ganada o Perdida).")
            val order = text("order").ifEmpty { row.toString() }.toIntOrNull() ?: throw InmobiliariaException("El orden de la etapa '$name' debe ser un numero entero.")
            Triple(order, row, StageInput(ids.getOrNull(row - 1), name, outcome))
        }
        return entries.sortedWith(compareBy({ it.first }, { it.second })).map { it.third }
    }
}

/** Pantallas de transacciones: lista con resumen por mes, ficha, alta y edicion, etapas y el panel del lead. */
internal object InmobiliariaDealScreens {
    private const val LeadOutOfScope = "(lead fuera de tu alcance)"
    private const val CustomerOutOfScope = "(cliente fuera de tu alcance)"

    private fun stageTone(outcome: DealOutcome) = InmobiliariaPropertyScreens.dealTone(outcome)

    // ---- Lista de cierres ----

    fun deals(context: CrmScreens.Context, params: Map<String, String>): List<UiNode> {
        val s = InmobiliariaUi.session(context)
        val stages = InmobiliariaService.stages(s)
        val filter = InmobiliariaFilters.deal(params, stages, context.users, context.scope.userId)
        var page = (params["page"]?.toIntOrNull() ?: 1).coerceAtLeast(1)
        var result = InmobiliariaService.listDeals(s, filter, InmobiliariaFilters.PageSize, (page - 1) * InmobiliariaFilters.PageSize)
        if (page > InmobiliariaFilters.pages(result.total)) {
            page = InmobiliariaFilters.pages(result.total)
            result = InmobiliariaService.listDeals(s, filter, InmobiliariaFilters.PageSize, (page - 1) * InmobiliariaFilters.PageSize)
        }
        val buyers = InmobiliariaLookups.leadNames(context, result.items.mapNotNull { it.buyerLeadId })
        val all = InmobiliariaFilters.All
        val fields = listOf(
            UiField("fStage", "Etapa", type = "dropdown", options = listOf(all) + stages.map { it.name }),
            UiField(
                "fAgent", "Agente", type = "dropdown",
                options = listOf(all) + listOfNotNull(InmobiliariaFilters.MineDeals.takeIf { context.scope.userId != null }) + context.users.map { it.name },
            ),
            UiField("fOpen", "Solo abiertas", type = "boolean"),
        )
        val values = fields.associate { it.key to (params[it.key] ?: if (it.type == "dropdown") all else "false") }
        val list = UiList(
            result.items.map { deal ->
                UiListItem(
                    title = "${deal.propertyCode} - ${deal.propertyTitle}",
                    subtitle = listOfNotNull(
                        deal.buyerLeadId?.let { "Comprador: ${buyers[it] ?: LeadOutOfScope}" },
                        "Acordado ${InmobiliariaFormat.money(deal.agreedPrice, deal.currency)} - Comision ${InmobiliariaFormat.money(deal.commissionTotal, deal.currency)}",
                        "Cierre estimado: ${InmobiliariaFormat.date(deal.estimatedCloseOn)} - Cierre real: ${InmobiliariaFormat.date(deal.closedOn)}",
                    ).joinToString("\n"),
                    trailing = InmobiliariaFormat.money(deal.agreedPrice, deal.currency),
                    badges = listOf(UiBadge(deal.stageName, stageTone(deal.stageOutcome))),
                    action = UiNavigate(InmobiliariaScreenIds.Deal, mapOf("id" to deal.id.toString())),
                )
            },
            emptyText = "No hay transacciones con esos filtros.",
        )
        return listOfNotNull(
            InmobiliariaUi.row(
                UiButton("Propiedades", UiNavigate(InmobiliariaScreenIds.Inventory), style = "secondary"),
                UiButton("Etapas", UiNavigate(InmobiliariaScreenIds.Stages), style = "secondary").takeIf { s.access.canEditStages },
            ),
            closingsCard(s, filter.agentId),
            UiCard(
                listOf(
                    UiForm(
                        "filters", fields, values,
                        submit = UiButton("Filtrar", UiNavigate(InmobiliariaScreenIds.Deals, formId = "filters"), style = "secondary"),
                        minFieldWidth = 200,
                    ),
                ),
            ),
            UiCard(listOf(list), title = "${result.total} transaccion(es)"),
            InmobiliariaUi.pager(InmobiliariaScreenIds.Deals, InmobiliariaFilters.keep(params), page, result.total),
        )
    }

    /** Etiquetas del grafico: el mes, y la moneda solo si hay meses en monedas distintas. */
    fun chartPoints(months: List<ClosingMonth>): List<UiChartPoint> {
        val mixed = months.map { it.currency }.toSet().size > 1
        return months.map { UiChartPoint(InmobiliariaFormat.month(it.month) + if (mixed) " (${it.currency})" else "", it.totalCommission.toDouble()) }
    }

    private fun closingsCard(s: InmobiliariaSession, agentId: UUID?): UiNode {
        val months = InmobiliariaService.closingsByMonth(s, agentId)
        if (months.isEmpty()) {
            return UiCard(listOf(InmobiliariaUi.caption("No hay cierres estimados: las transacciones abiertas con fecha de cierre estimado aparecen aqui.")), title = "Cierres estimados por mes")
        }
        return UiCard(
            listOf(
                UiTable(
                    listOf(UiTableColumn("month", "Mes"), UiTableColumn("count", "Transacciones"), UiTableColumn("price", "Precio acordado"), UiTableColumn("commission", "Comision")),
                    months.map { month ->
                        mapOf(
                            "month" to InmobiliariaFormat.month(month.month),
                            "count" to month.count.toString(),
                            "price" to InmobiliariaFormat.money(month.totalPrice, month.currency),
                            "commission" to InmobiliariaFormat.money(month.totalCommission, month.currency),
                        )
                    },
                ),
                UiBarChart(chartPoints(months)),
                InmobiliariaUi.caption("Comision estimada por mes de cierre, de las transacciones abiertas${if (agentId != null) " del agente elegido" else ""}."),
            ),
            title = "Cierres estimados por mes",
        )
    }

    // ---- Formulario de la transaccion ----

    private fun dealFields(creating: Boolean, stageNames: List<String>, agentNames: List<String>, partyRows: Int, defaultPct: String): List<UiField> = buildList {
        add(UiField("lead", "Lead comprador", section = "Comprador", placeholder = "Nombre, correo o id del lead", help = "Solo leads que ves."))
        add(
            UiField(
                "name", "Cliente comprador", section = "Comprador", placeholder = "Escribe para buscar un cliente",
                suggest = UiSuggest(CrmScreens.CustomerSuggestRoute), help = "Opcional: sin cliente se usa el del lead.",
            ),
        )
        if (creating && stageNames.isNotEmpty()) add(UiField("stage", "Etapa", type = "dropdown", options = stageNames, section = "Transaccion"))
        add(UiField("agreedPrice", "Precio acordado", section = "Transaccion", help = "Vacio: el precio de la propiedad."))
        add(UiField("commissionPct", "Comision (%)", section = "Transaccion", help = "Porcentaje sobre el precio acordado. Vacio: $defaultPct %."))
        add(UiField("commissionAmount", "Comision fija (monto)", section = "Transaccion", help = "Opcional: reemplaza el calculo con el porcentaje."))
        add(UiField("estimatedCloseOn", "Cierre estimado", type = "date", section = "Transaccion"))
        add(UiField("comments", "Comentarios", type = "textarea", section = "Transaccion"))
        for (row in 1..partyRows) {
            val section = "Parte $row"
            add(UiField("p${row}_role", "Rol", type = "dropdown", options = PartyRole.entries.map { it.label }, section = section))
            add(UiField("p${row}_agent", "Agente", type = "dropdown", options = listOf(InmobiliariaDealForms.NoAgent) + agentNames, section = section))
            add(UiField("p${row}_name", "Nombre externo", section = section, help = "Abogado, corredor u otra persona que no es usuario."))
            add(UiField("p${row}_customer", "Cliente (id)", section = section, help = "Opcional: id de un cliente que ves."))
            add(UiField("p${row}_share", "% de la comision", section = section, help = "Las partes deberian sumar 100 %."))
            add(UiField("p${row}_notes", "Notas", section = section))
        }
    }

    // El id del cliente de una parte solo se muestra si el usuario lo ve.
    private fun partyValues(row: Int, party: DealParty?, users: List<CrmUser>, visibleCustomers: Set<UUID>): Map<String, String> = mapOf(
        "p${row}_role" to (party?.role ?: PartyRole.Other).label,
        "p${row}_agent" to (party?.userId?.let { id -> users.firstOrNull { it.id == id }?.name } ?: InmobiliariaDealForms.NoAgent),
        "p${row}_name" to party?.name.orEmpty(),
        "p${row}_customer" to party?.customerId?.takeIf { it in visibleCustomers }?.toString().orEmpty(),
        "p${row}_share" to party?.let { InmobiliariaFormat.field(it.sharePct) }.orEmpty(),
        "p${row}_notes" to party?.notes.orEmpty(),
    )

    private fun dealForm(
        context: CrmScreens.Context,
        stageNames: List<String>,
        settings: Settings,
        deal: Deal?,
        base: Map<String, String>,
        submitted: Map<String, String>?,
        submit: UiButton,
        visibleCustomers: Set<UUID> = emptySet(),
    ): UiForm {
        val rows = InmobiliariaDealForms.partyRows(deal?.parties?.size ?: 0, submitted)
        val parties = (1..rows).flatMap { row -> partyValues(row, deal?.parties?.getOrNull(row - 1), context.users, visibleCustomers).toList() }.toMap()
        val sent = submitted.orEmpty()
        val filled = (1..rows).filter { row ->
            deal?.parties?.getOrNull(row - 1) != null ||
                listOf("agent", "name", "customer", "share", "notes").any { key -> sent["p${row}_$key"].orEmpty().let { it.isNotBlank() && it != InmobiliariaDealForms.NoAgent } }
        }
        return UiForm(
            "dealForm",
            dealFields(deal == null, stageNames, context.users.map { it.name }, rows, InmobiliariaFormat.field(settings.defaultCommissionPct)),
            base + parties + submitted.orEmpty(),
            submit = submit,
            collapsibleSections = true,
            openSections = listOf("Comprador", "Transaccion") + filled.map { "Parte $it" },
        )
    }

    // ---- Alta y ficha ----

    fun deal(context: CrmScreens.Context, params: Map<String, String>, submitted: Map<String, String>?): List<UiNode> {
        val s = InmobiliariaUi.session(context)
        val back = InmobiliariaUi.back(InmobiliariaScreenIds.Deals, "Volver a Cierres")
        val id = params["id"]?.takeIf { it.isNotBlank() }
        if (id == null) return creation(context, s, params, submitted, back)
        val dealId = InmobiliariaUi.uuid(id) ?: return InmobiliariaUi.message("Esa transaccion no existe.") + back
        val deal = try {
            InmobiliariaService.getDeal(s, dealId)
        } catch (failure: InmobiliariaException) {
            return InmobiliariaUi.message(failure.message.orEmpty()) + back
        }
        return sheet(context, s, deal, submitted, back)
    }

    private fun creation(context: CrmScreens.Context, s: InmobiliariaSession, params: Map<String, String>, submitted: Map<String, String>?, back: UiButton): List<UiNode> {
        if (!s.access.canCreate) return InmobiliariaUi.message("No tienes permiso para abrir transacciones.") + back
        val ref = params["property"].orEmpty()
        if (ref.isBlank()) {
            return InmobiliariaUi.message("Abre una propiedad y toca Nueva transaccion para elegir de cual es.", UiTone.Info) +
                InmobiliariaUi.back(InmobiliariaScreenIds.Inventory, "Ir a Propiedades")
        }
        val property = try {
            InmobiliariaService.findPropertyByRef(s, ref)
        } catch (failure: InmobiliariaException) {
            return InmobiliariaUi.message(failure.message.orEmpty()) + back
        }
        val stages = InmobiliariaService.stages(s)
        val settings = InmobiliariaService.settings(s)
        val base = mapOf(
            "lead" to "", "name" to "", "customerId" to "",
            "stage" to (stages.firstOrNull { it.outcome == DealOutcome.Open }?.name.orEmpty()),
            "agreedPrice" to InmobiliariaFormat.field(property.price),
            "commissionPct" to InmobiliariaFormat.field(settings.defaultCommissionPct),
            "commissionAmount" to "", "estimatedCloseOn" to "", "comments" to "",
        )
        val form = dealForm(
            context, stages.map { it.name }, settings, null, base, submitted,
            UiButton("Abrir transaccion", InmobiliariaUi.request(InmobiliariaScreenIds.Deal, "create", mapOf("property" to property.id.toString()), formId = "dealForm")),
        )
        return listOf(
            back,
            UiText("Nueva transaccion: ${property.code} - ${property.title}", UiTextStyle.Subtitle),
            UiText("Precio de la propiedad: ${InmobiliariaFormat.money(property.price, property.currency)}", UiTextStyle.Caption),
            UiCard(listOf(form), title = "Datos de la transaccion"),
        )
    }

    private fun sheet(context: CrmScreens.Context, s: InmobiliariaSession, deal: Deal, submitted: Map<String, String>?, back: UiButton): List<UiNode> {
        val canEdit = s.access.canEditDeal(deal)
        val idParams = mapOf("id" to deal.id.toString())
        val customerIds = (deal.parties.mapNotNull { it.customerId } + listOfNotNull(deal.buyerCustomerId)).distinct()
        val customers = InmobiliariaLookups.customerNames(context, customerIds)
        val leads = InmobiliariaLookups.leadNames(context, listOfNotNull(deal.buyerLeadId))
        val buyerLead = deal.buyerLeadId?.let { leads[it] }
        val header = UiRow(
            listOf(
                UiText("${deal.propertyCode} - ${deal.propertyTitle}", UiTextStyle.Title),
                UiStatus(deal.stageName, stageTone(deal.stageOutcome)),
            ),
        )
        val actions = InmobiliariaUi.row(
            back,
            UiButton("Ver propiedad", UiNavigate(InmobiliariaScreenIds.Property, mapOf("id" to deal.propertyId.toString())), style = "secondary"),
            deal.buyerLeadId?.takeIf { buyerLead != null }?.let { UiButton("Ver lead comprador", UiNavigate("lead", mapOf("id" to it.toString())), style = "secondary") },
        )
        val stageCard = if (!canEdit) null else {
            val stages = InmobiliariaService.stages(s)
            UiCard(
                listOf(
                    UiRow(
                        stages.map { stage ->
                            UiButton(
                                stage.name,
                                InmobiliariaUi.request(
                                    InmobiliariaScreenIds.Deal, "move", idParams + ("stage" to stage.id.toString()),
                                    confirm = "Mover la transaccion a ${stage.name}?".takeIf { stage.outcome != DealOutcome.Open },
                                ),
                                style = if (stage.id == deal.stageId) "primary" else "secondary",
                                enabled = stage.id != deal.stageId,
                            )
                        },
                    ),
                    InmobiliariaUi.caption("Una etapa ganada marca la propiedad como Vendida; salir de ella lo deshace."),
                ),
                title = "Mover de etapa",
            )
        }
        val metrics = UiGrid(
            listOf(
                UiMetric("Precio acordado", InmobiliariaFormat.money(deal.agreedPrice, deal.currency)),
                UiMetric("Comision total", InmobiliariaFormat.money(deal.commissionTotal, deal.currency), if (deal.commissionAmount != null) "Monto fijo" else InmobiliariaFormat.percent(deal.commissionPct)),
                UiMetric("Cierre estimado", InmobiliariaFormat.date(deal.estimatedCloseOn)),
                UiMetric("Cierre real", InmobiliariaFormat.date(deal.closedOn)),
                UiMetric("Etapa", deal.stageName, deal.stageOutcome.label),
            ),
            minItemWidth = 180,
        )
        val warning = deal.shareWarning?.let { UiText(it, tone = UiTone.Warning) }
        val partyTable = UiCard(
            listOfNotNull(
                UiTable(
                    listOf(UiTableColumn("role", "Rol"), UiTableColumn("who", "Quien"), UiTableColumn("pct", "% de la comision"), UiTableColumn("amount", "Monto"), UiTableColumn("notes", "Notas")),
                    deal.parties.map { party ->
                        mapOf(
                            "role" to party.role.label,
                            "who" to partyName(context.users, customers, party),
                            "pct" to InmobiliariaFormat.percent(party.sharePct),
                            "amount" to InmobiliariaFormat.money(deal.partyAmount(party), deal.currency),
                            "notes" to party.notes,
                        )
                    },
                    emptyText = "Sin partes: agrega agentes y otras personas con su porcentaje de la comision.",
                ),
                warning,
            ),
            title = "Partes y comision",
        )
        val buyer = listOfNotNull(
            deal.buyerLeadId?.let { "Lead comprador: ${buyerLead ?: LeadOutOfScope}" },
            deal.buyerCustomerId?.let { "Cliente comprador: ${customers[it] ?: CustomerOutOfScope}" },
            deal.comments.takeIf { it.isNotBlank() }?.let { "Comentarios: $it" },
        )
        val details = UiCard(
            if (buyer.isEmpty()) listOf(InmobiliariaUi.caption("Sin comprador ni comentarios.")) else buyer.map { UiText(it) },
            title = "Detalle",
        )
        val edit = if (!canEdit) null else {
            val settings = InmobiliariaService.settings(s)
            val base = mapOf(
                "lead" to if (deal.buyerLeadId == null) "" else (buyerLead ?: LeadOutOfScope),
                "name" to if (deal.buyerCustomerId == null) "" else (customers[deal.buyerCustomerId] ?: CustomerOutOfScope),
                "customerId" to deal.buyerCustomerId?.takeIf { it in customers }?.toString().orEmpty(),
                "agreedPrice" to InmobiliariaFormat.field(deal.agreedPrice),
                "commissionPct" to InmobiliariaFormat.field(deal.commissionPct),
                "commissionAmount" to InmobiliariaFormat.field(deal.commissionAmount),
                "estimatedCloseOn" to deal.estimatedCloseOn?.toString().orEmpty(),
                "comments" to deal.comments,
            )
            // Las partes con cliente fuera de la vista viajan como referencia a la parte (no como id de cliente) para no perderlo al guardar.
            val keep = deal.parties.withIndex().filter { (_, party) -> party.customerId != null && party.customerId !in customers }.associate { (index, party) -> index + 1 to party.id }
            val updateParams = if (keep.isEmpty()) idParams else idParams + ("keep" to InmobiliariaDealForms.keepParam(keep))
            UiCard(
                listOf(
                    dealForm(
                        context, emptyList(), settings, deal, base, submitted,
                        UiButton("Guardar cambios", InmobiliariaUi.request(InmobiliariaScreenIds.Deal, "update", updateParams, formId = "dealForm")),
                        customers.keys,
                    ),
                ),
                title = "Editar transaccion",
                collapsible = true,
                expanded = submitted != null,
            )
        }
        return listOfNotNull(header, actions, stageCard, metrics, details, partyTable, edit)
    }

    private fun partyName(users: List<CrmUser>, customers: Map<UUID, String>, party: DealParty): String = when {
        party.userId != null -> "${InmobiliariaUi.agentName(users, party.userId)} (agente)"
        party.customerId != null -> customers[party.customerId] ?: CustomerOutOfScope
        else -> party.name
    }

    // ---- Etapas y ajustes ----

    fun stages(context: CrmScreens.Context, params: Map<String, String>, submitted: Map<String, String>?): List<UiNode> {
        val s = InmobiliariaUi.session(context)
        if (!s.access.canEditStages) return InmobiliariaUi.message("No tienes permiso para editar las etapas.") + InmobiliariaUi.back(InmobiliariaScreenIds.Deals, "Volver a Cierres")
        val stages = InmobiliariaService.stages(s)
        val settings = InmobiliariaService.settings(s)
        // Los ids con que se dibujo cada fila viajan en la peticion; al devolver un error se conservan los de la primera vez.
        val known = stages.map { it.id }.toSet()
        val ids = params["ids"]?.split(',')?.mapNotNull { InmobiliariaUi.uuid(it) }?.filter { it in known }?.distinct()?.takeIf { it.isNotEmpty() } ?: stages.map { it.id }
        val rows = InmobiliariaDealForms.stageRows(ids.size, submitted)
        val outcomes = DealOutcome.entries.map { it.label }
        val fields = (1..rows).flatMap { row ->
            listOf(
                UiField("s${row}_name", "Etapa $row", section = "Etapa $row", help = if (row <= ids.size) "Vacio: se quita la etapa (solo si no tiene transacciones)." else ""),
                UiField("s${row}_outcome", "Resultado", type = "dropdown", options = outcomes, section = "Etapa $row"),
                UiField("s${row}_order", "Orden", section = "Etapa $row"),
            )
        }
        val saved = (1..rows).flatMap { row ->
            val stage = stages.firstOrNull { it.id == ids.getOrNull(row - 1) }
            listOf(
                "s${row}_name" to stage?.name.orEmpty(),
                "s${row}_outcome" to (stage?.outcome ?: DealOutcome.Open).label,
                "s${row}_order" to (if (stage != null) row.toString() else ""),
            )
        }.toMap()
        val stageForm = UiForm(
            "stagesForm", fields, saved + submitted.orEmpty(),
            submit = UiButton("Guardar etapas", InmobiliariaUi.request(InmobiliariaScreenIds.Stages, "saveStages", mapOf("ids" to ids.joinToString(",")), formId = "stagesForm")),
            minFieldWidth = 220,
        )
        val settingsForm = UiForm(
            "settingsForm",
            listOf(
                UiField("currency", "Moneda", required = true, help = "Codigo de 3 letras (USD). Es la de las propiedades nuevas."),
                UiField("commission", "Comision por defecto (%)", required = true, help = "Se usa al abrir una transaccion sin porcentaje."),
            ),
            mapOf("currency" to settings.currency, "commission" to InmobiliariaFormat.field(settings.defaultCommissionPct)) + submitted.orEmpty().filterKeys { it == "currency" || it == "commission" },
            submit = UiButton("Guardar ajustes", InmobiliariaUi.request(InmobiliariaScreenIds.Stages, "saveSettings", formId = "settingsForm"), style = "secondary"),
            minFieldWidth = 220,
        )
        return listOf(
            InmobiliariaUi.back(InmobiliariaScreenIds.Deals, "Volver a Cierres"),
            UiCard(
                listOf(
                    InmobiliariaUi.caption("Las transacciones pasan por estas etapas en orden. Debe haber al menos una abierta y una ganada. Escribe un nombre en una fila vacia para agregar una etapa."),
                    stageForm,
                ),
                title = "Etapas de las transacciones",
            ),
            UiCard(listOf(settingsForm), title = "Ajustes"),
        )
    }

    // ---- Acciones ----

    private fun requireDealId(params: Map<String, String>): UUID = InmobiliariaUi.uuid(params["id"]) ?: throw InmobiliariaException("Esa transaccion no existe.")

    private fun buyerCustomer(values: Map<String, String>, current: Pair<UUID, String>? = null): UUID? =
        InmobiliariaDealForms.pickCustomer(values["name"], values["customerId"], current, "El cliente comprador")

    fun dealAction(context: CrmScreens.Context, s: InmobiliariaSession, op: String, params: Map<String, String>, values: Map<String, String>): CrmExtensionActionResult {
        val screen = InmobiliariaScreenIds.Deal
        val clean = params - "op"
        return when (op) {
            "create" -> {
                val propertyId = InmobiliariaUi.uuid(params["property"]) ?: throw InmobiliariaException("Elige la propiedad de la transaccion.")
                val stageName = values["stage"].orEmpty().trim()
                val draft = DealDraft(
                    propertyId = propertyId,
                    buyerLeadId = InmobiliariaLookups.resolveLead(context, values["lead"].orEmpty()),
                    buyerCustomerId = buyerCustomer(values),
                    stageId = if (stageName.isEmpty()) null else InmobiliariaService.findStage(s, stageName).id,
                    agreedPrice = InmobiliariaInput.parseMoney(values["agreedPrice"], "El precio acordado"),
                    commissionPct = InmobiliariaInput.parsePercent(values["commissionPct"], "La comision"),
                    commissionAmount = InmobiliariaInput.parseMoney(values["commissionAmount"], "La comision fija"),
                    estimatedCloseOn = InmobiliariaInput.parseDate(values["estimatedCloseOn"], "El cierre estimado"),
                    comments = values["comments"].orEmpty(),
                    parties = InmobiliariaDealForms.parseParties(values, context.users),
                )
                val result = InmobiliariaService.createDeal(s, draft)
                InmobiliariaUi.success(screen, "Transaccion abierta.", mapOf("id" to result.deal.id.toString()), result.warnings)
            }
            "update" -> {
                val id = requireDealId(params)
                val current = InmobiliariaService.getDeal(s, id)
                val shown = current.buyerLeadId?.let { InmobiliariaLookups.leadNames(context, listOf(it))[it] ?: LeadOutOfScope }
                val currentCustomer = current.buyerCustomerId?.let { it to (InmobiliariaLookups.customerNames(context, listOf(it))[it] ?: CustomerOutOfScope) }
                val edit = DealEdit(
                    buyerLeadId = InmobiliariaLookups.resolveLead(context, values["lead"].orEmpty(), current.buyerLeadId?.let { it to shown.orEmpty() }),
                    buyerCustomerId = buyerCustomer(values, currentCustomer),
                    agreedPrice = InmobiliariaInput.parseMoney(values["agreedPrice"], "El precio acordado"),
                    commissionPct = InmobiliariaInput.parsePercent(values["commissionPct"], "La comision") ?: current.commissionPct,
                    commissionAmount = InmobiliariaInput.parseMoney(values["commissionAmount"], "La comision fija"),
                    currency = current.currency,
                    estimatedCloseOn = InmobiliariaInput.parseDate(values["estimatedCloseOn"], "El cierre estimado"),
                    closedOn = current.closedOn,
                    comments = values["comments"].orEmpty(),
                )
                val keep = InmobiliariaDealForms.parseKeep(params["keep"])
                InmobiliariaService.updateDeal(s, id, edit, InmobiliariaDealForms.parseParties(values, context.users, keep = keep))
                InmobiliariaUi.success(screen, "Transaccion guardada.", mapOf("id" to id.toString()))
            }
            "move" -> {
                val id = requireDealId(params)
                val stageId = InmobiliariaUi.uuid(params["stage"]) ?: throw InmobiliariaException("Elige la etapa.")
                val result = InmobiliariaService.moveDeal(s, id, stageId)
                InmobiliariaUi.success(screen, "Transaccion en la etapa ${result.deal.stageName}.", mapOf("id" to id.toString()), result.warnings)
            }
            else -> InmobiliariaUi.failure(screen, "Esa accion no existe.", clean, values)
        }
    }

    fun stagesAction(s: InmobiliariaSession, op: String, params: Map<String, String>, values: Map<String, String>): CrmExtensionActionResult {
        val screen = InmobiliariaScreenIds.Stages
        val clean = params - "op"
        return when (op) {
            "saveStages" -> {
                // Solo cuentan los ids de etapas que existen ahora y sin repetirse; los demas se tratan como etapa nueva.
                val known = InmobiliariaService.stages(s).map { it.id }.toSet()
                val seen = mutableSetOf<UUID>()
                val ids = params["ids"]?.split(',')?.mapNotNull { InmobiliariaUi.uuid(it) }.orEmpty()
                val rows = InmobiliariaDealForms.stageRows(ids.size, values)
                val parsed = InmobiliariaDealForms.parseStages(values, ids, rows).map { stage -> if (stage.id != null && (stage.id !in known || !seen.add(stage.id))) stage.copy(id = null) else stage }
                InmobiliariaService.saveStages(s, parsed)
                InmobiliariaUi.success(screen, "Etapas guardadas.")
            }
            "saveSettings" -> {
                val pct = InmobiliariaInput.parsePercent(values["commission"], "La comision por defecto") ?: throw InmobiliariaException("Escribe la comision por defecto.")
                InmobiliariaService.saveSettings(s, Settings(values["currency"].orEmpty().trim().uppercase(), pct))
                InmobiliariaUi.success(screen, "Ajustes guardados.")
            }
            else -> InmobiliariaUi.failure(screen, "Esa accion no existe.", clean, values)
        }
    }

    // ---- Panel de la ficha del lead ----

    fun leadPanel(context: CrmScreens.Context, lead: CrmLead): List<UiNode> {
        val s = InmobiliariaUi.session(context)
        val deals = try {
            InmobiliariaService.listDeals(s, DealFilter(buyerLeadId = lead.id), 20, 0).items
        } catch (failure: InmobiliariaException) {
            return emptyList()
        }
        val items = CrmHost.items(context.tenant, lead.id, InmobiliariaService.Source)
        if (deals.isEmpty() && items.isEmpty()) return emptyList()
        val dealList = UiList(
            deals.map { deal ->
                UiListItem(
                    title = "${deal.propertyCode} - ${deal.propertyTitle}",
                    subtitle = "Acordado ${InmobiliariaFormat.money(deal.agreedPrice, deal.currency)} - Cierre estimado ${InmobiliariaFormat.date(deal.estimatedCloseOn)}",
                    badges = listOf(UiBadge(deal.stageName, stageTone(deal.stageOutcome))),
                    action = UiNavigate(InmobiliariaScreenIds.Deal, mapOf("id" to deal.id.toString())),
                )
            },
            emptyText = "Sin transacciones como comprador.",
        )
        val itemList = UiList(
            items.map { item ->
                UiListItem(
                    title = item.title,
                    subtitle = item.subtitle,
                    trailing = item.price?.let { InmobiliariaFormat.amount(it) }.orEmpty(),
                    badges = listOf(UiBadge(CrmItems.statusLabels[item.status] ?: item.status)),
                    action = InmobiliariaUi.uuid(item.externalId)?.let { UiNavigate(InmobiliariaScreenIds.Property, mapOf("id" to it.toString())) },
                )
            },
            emptyText = "Sin propiedades vinculadas.",
        )
        return listOf(
            UiCard(
                listOfNotNull(
                    InmobiliariaUi.heading("Transacciones como comprador").takeIf { deals.isNotEmpty() },
                    dealList.takeIf { deals.isNotEmpty() },
                    InmobiliariaUi.heading("Propiedades vinculadas").takeIf { items.isNotEmpty() },
                    itemList.takeIf { items.isNotEmpty() },
                ),
                title = "Inmobiliaria",
            ),
        )
    }
}
