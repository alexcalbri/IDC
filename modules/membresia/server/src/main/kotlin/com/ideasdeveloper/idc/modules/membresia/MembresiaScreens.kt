package com.ideasdeveloper.idc.modules.membresia

import com.ideasdeveloper.idc.server.modules.ModulePermissions
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
import com.ideasdeveloper.idc.ui.UiNavItem
import com.ideasdeveloper.idc.ui.UiNavigate
import com.ideasdeveloper.idc.ui.UiNavigation
import com.ideasdeveloper.idc.ui.UiNode
import com.ideasdeveloper.idc.ui.UiNotice
import com.ideasdeveloper.idc.ui.UiOpenModule
import com.ideasdeveloper.idc.ui.UiRequest
import com.ideasdeveloper.idc.ui.UiRow
import com.ideasdeveloper.idc.ui.UiScreen
import com.ideasdeveloper.idc.ui.UiStatus
import com.ideasdeveloper.idc.ui.UiSuggest
import com.ideasdeveloper.idc.ui.UiSuggestion
import com.ideasdeveloper.idc.ui.UiSuggestions
import com.ideasdeveloper.idc.ui.UiTable
import com.ideasdeveloper.idc.ui.UiTableColumn
import com.ideasdeveloper.idc.ui.UiText
import com.ideasdeveloper.idc.ui.UiTextStyle
import com.ideasdeveloper.idc.ui.UiTone
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.UUID

/** Lo que el usuario puede hacer en el modulo (permisos efectivos); las pantallas solo ofrecen lo permitido. */
data class MembresiaAccess(val view: Boolean, val visits: Boolean, val manage: Boolean, val plans: Boolean) {
    companion object {
        fun from(permissions: ModulePermissions) = MembresiaAccess(
            view = permissions.has(MembresiaModule.View),
            visits = permissions.has(MembresiaModule.Visits),
            manage = permissions.has(MembresiaModule.Manage),
            plans = permissions.has(MembresiaModule.Plans),
        )
    }
}

/**
 * Pantallas nativas del modulo como funciones puras: reciben datos ya leidos (sin JDBC) y devuelven el UiScreen.
 * Nunca muestran el codigo de acceso WiFi del cliente ni sus campos personalizados.
 */
object MembresiaScreens {
    const val MembersSuggestRoute = "${MembresiaModule.UiRoute}/suggest/members"
    const val CustomersSuggestRoute = "${MembresiaModule.UiRoute}/suggest/customers"

    private data class Section(val id: String, val label: String, val allowed: (MembresiaAccess) -> Boolean)

    private val sections = listOf(
        Section("recepcion", "Recepción") { it.view },
        Section("miembros", "Miembros") { it.view },
        Section("niveles", "Por nivel") { it.view },
        Section("nueva", "Nueva membresía") { it.manage },
        Section("planes", "Planes") { it.plans },
        Section("ingresos", "Ingresos") { it.manage },
        Section("ajustes", "Ajustes") { it.plans },
    )

    // Pantallas a las que se llega desde otras (no son secciones del menu).
    private val inner = listOf(
        Section("miembro", "Miembro") { it.view },
        Section("visitas", "Visitas") { it.view },
        Section("renovar", "Renovar") { it.manage },
        Section("editar", "Editar") { it.manage },
    )

    fun isScreen(id: String): Boolean = (sections + inner).any { it.id == id }

    fun allowed(access: MembresiaAccess, id: String): Boolean =
        (sections + inner).firstOrNull { it.id == id }?.allowed?.invoke(access) ?: false

    /** Primera seccion que el usuario puede abrir; null si no tiene ninguna. */
    fun home(access: MembresiaAccess): String? = sections.firstOrNull { it.allowed(access) }?.id

    fun navigation(access: MembresiaAccess, selected: String): UiNavigation = UiNavigation(
        title = MembresiaModule.DisplayName,
        items = sections.filter { it.allowed(access) }.map { UiNavItem(it.id, it.label, UiNavigate(it.id)) },
        selected = selected,
    )

    // ---------------------------------------------------------------- utilidades

    private val timeFormat = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.systemDefault())

    fun money(amount: BigDecimal, currency: String): String = "${amount.setScale(2, RoundingMode.HALF_UP).toPlainString()} $currency"

    /** Texto con que un plan aparece en los selectores; la ruta lo usa para encontrar el plan elegido. */
    fun planLabel(plan: Plan, currency: String): String = "${plan.name} · ${money(plan.price, currency)}"

    private fun screen(access: MembresiaAccess, selected: String, title: String, notice: UiNotice?, body: List<UiNode>) =
        UiScreen(title = title, navigation = navigation(access, selected), notice = notice, body = body)

    private fun badge(status: DisplayStatus) = UiBadge(status.label, status.tone)

    private fun post(action: String, params: Map<String, String> = emptyMap(), confirm: String? = null, formId: String? = null) =
        UiRequest("${MembresiaModule.UiRoute}/$action", "POST", params, confirm, formId)

    private fun openMember(customerId: UUID) = UiNavigate("miembro", mapOf("customer" to customerId.toString()))

    private fun statusOf(member: MemberSummary, today: LocalDate, expiringDays: Int): DisplayStatus? =
        member.current?.let { MembresiaRules.displayStatus(it, today, expiringDays) }

    private fun memberItem(member: MemberSummary, today: LocalDate, expiringDays: Int): UiListItem {
        val status = statusOf(member, today, expiringDays)
        return UiListItem(
            title = member.customerName.ifBlank { member.email.ifBlank { member.phone } },
            subtitle = listOf(member.email, member.phone).filter { it.isNotBlank() }.joinToString(" · "),
            trailing = member.current?.let { "${it.planName} · hasta ${it.endsOn}" }.orEmpty(),
            badges = listOf(status?.let(::badge) ?: UiBadge("Sin membresía", UiTone.Neutral)),
            action = openMember(member.customerId),
        )
    }

    // ---------------------------------------------------------------- recepcion

    /** Recepcion pensada para tablet: busqueda arriba, resultados y tres metricas del dia. */
    fun recepcion(
        access: MembresiaAccess,
        query: String,
        results: List<MemberSummary>,
        counts: Map<DisplayStatus, Int>,
        visitsToday: Int,
        today: LocalDate,
        expiringDays: Int,
        notice: UiNotice? = null,
    ): UiScreen {
        val body = buildList<UiNode> {
            add(
                UiForm(
                    id = "search",
                    fields = listOf(
                        UiField("name", "Buscar miembro", placeholder = "Nombre, correo o teléfono", suggest = UiSuggest(MembersSuggestRoute, minLength = 2)),
                    ),
                    values = mapOf("name" to query),
                    submit = UiButton("Buscar", UiNavigate("recepcion", formId = "search")),
                ),
            )
            if (query.isNotBlank()) {
                add(UiList(results.map { memberItem(it, today, expiringDays) }, emptyText = "No hay miembros con ese dato."))
            }
            add(
                UiGrid(
                    listOf(
                        UiMetric("Activas", (counts[DisplayStatus.ACTIVA] ?: 0).toString(), "Membresías vigentes", UiTone.Success),
                        UiMetric("Por vencer", (counts[DisplayStatus.POR_VENCER] ?: 0).toString(), "Vencen pronto", UiTone.Warning),
                        UiMetric("Visitas hoy", visitsToday.toString(), "Incluye invitados"),
                    ),
                    minItemWidth = 160,
                ),
            )
            add(UiButton("Ver visitas de hoy", UiNavigate("visitas"), style = "secondary"))
        }
        return screen(access, "recepcion", "Recepción", notice, body)
    }

    // ---------------------------------------------------------------- miembro

    /**
     * Ficha del miembro: estado grande, metricas y acciones segun permisos. [overrideReason] no nulo = el registro de
     * visita pidio confirmacion: se muestra el aviso y el boton "Registrar igual".
     */
    fun miembro(
        access: MembresiaAccess,
        detail: MemberDetail,
        today: LocalDate,
        expiringDays: Int,
        notice: UiNotice? = null,
        overrideReason: String? = null,
    ): UiScreen {
        val member = detail.summary
        val current = member.current
        val status = current?.let { MembresiaRules.displayStatus(it, today, expiringDays) }
        val customerId = member.customerId.toString()
        val shownNotice = notice ?: overrideReason?.let { UiNotice(it, UiTone.Warning) }
        val body = buildList<UiNode> {
            add(UiStatus(status?.label ?: "Sin membresía", status?.tone ?: UiTone.Neutral))
            val contact = listOf(member.email, member.phone).filter { it.isNotBlank() }.joinToString(" · ")
            if (contact.isNotEmpty()) add(UiText(contact, UiTextStyle.Caption))
            if (current != null) {
                add(UiText("${current.planName} · ${current.startsOn} a ${current.endsOn}", UiTextStyle.Subtitle))
                val place = listOf(current.unit.takeIf { it.isNotBlank() }?.let { "Unidad: $it" }, current.hostName.takeIf { it.isNotBlank() }?.let { "Anfitrión: $it" })
                    .filterNotNull().joinToString(" · ")
                if (place.isNotEmpty()) add(UiText(place, UiTextStyle.Caption))
                add(UiGrid(metrics(current, status, today), minItemWidth = 160))
            }

            val buttons = buildList<UiNode> {
                if (access.visits && current != null) {
                    add(UiButton("Registrar visita", post("checkin", mapOf("customer" to customerId))))
                    if (overrideReason != null) {
                        add(UiButton("Registrar igual", post("checkin", mapOf("customer" to customerId, "override" to "true")), style = "danger"))
                    }
                }
                if (access.manage) {
                    if (current != null && current.status != StoredStatus.Cancelada) {
                        add(UiButton("Renovar", UiNavigate("renovar", mapOf("id" to current.id.toString())), style = "secondary"))
                        add(UiButton("Editar", UiNavigate("editar", mapOf("id" to current.id.toString())), style = "secondary"))
                    }
                    if (status?.current != true) {
                        add(UiButton("Nueva membresía", UiNavigate("nueva", mapOf("customer" to customerId)), style = "secondary"))
                    }
                }
                add(UiButton("Abrir ficha del cliente", UiOpenModule("clientes", "customer", mapOf("id" to customerId)), style = "secondary"))
            }
            add(UiRow(buttons, fill = true))

            if (access.visits && current != null && status?.current == true && current.guestPassesTotal > 0) {
                val left = MembresiaRules.guestPassesLeft(current)
                add(
                    UiCard(
                        title = "Invitado ($left de ${current.guestPassesTotal} pases)",
                        children = if (left > 0) {
                            listOf(
                                UiForm(
                                    id = "guest",
                                    fields = listOf(UiField("guestName", "Nombre del invitado", required = true)),
                                    submit = UiButton("Registrar invitado", post("guest", mapOf("customer" to customerId), formId = "guest")),
                                ),
                            )
                        } else {
                            listOf(UiText("No quedan pases de invitado en este período.", UiTextStyle.Caption))
                        },
                    ),
                )
            }

            if (access.manage && current != null && current.status != StoredStatus.Cancelada) {
                add(
                    UiCard(
                        title = "Cancelar membresía",
                        collapsible = true,
                        expanded = false,
                        children = listOf(
                            UiForm(
                                id = "cancel",
                                fields = listOf(UiField("reason", "Motivo", type = "textarea")),
                                submit = UiButton(
                                    "Cancelar membresía",
                                    post("cancelar", mapOf("id" to current.id.toString()), confirm = "¿Cancelar esta membresía? No se puede deshacer.", formId = "cancel"),
                                    style = "danger",
                                ),
                            ),
                        ),
                    ),
                )
            }

            add(
                UiCard(
                    title = "Membresías",
                    children = listOf(
                        UiTable(
                            columns = listOf(
                                UiTableColumn("plan", "Plan"), UiTableColumn("starts", "Inicio"), UiTableColumn("ends", "Fin"),
                                UiTableColumn("status", "Estado"), UiTableColumn("amount", "Monto"),
                            ),
                            rows = detail.memberships.map {
                                mapOf(
                                    "plan" to it.planName,
                                    "starts" to it.startsOn.toString(),
                                    "ends" to it.endsOn.toString(),
                                    "status" to MembresiaRules.displayStatus(it, today, expiringDays).label,
                                    "amount" to money(it.amount, it.currency),
                                )
                            },
                            emptyText = "Sin membresías.",
                        ),
                    ),
                ),
            )
            add(UiCard(title = "Últimas visitas", children = listOf(visitsTable(detail.visits, withCustomer = false))))
        }
        return screen(access, "recepcion", member.customerName, shownNotice, body)
    }

    private fun metrics(current: Membership, status: DisplayStatus?, today: LocalDate): List<UiNode> {
        val days = MembresiaRules.daysLeft(current.endsOn, today)
        val daysCaption = when (status) {
            DisplayStatus.PROGRAMADA -> "Empieza el ${current.startsOn}"
            DisplayStatus.VENCIDA -> "Venció el ${current.endsOn}"
            DisplayStatus.CANCELADA -> "Cancelada"
            else -> "Hasta el ${current.endsOn}"
        }
        val visitsLeft = MembresiaRules.visitsLeft(current)
        return listOf(
            UiMetric("Días restantes", days.coerceAtLeast(0).toString(), daysCaption, status?.tone ?: UiTone.Neutral),
            UiMetric(
                "Visitas",
                current.includedVisits?.let { "${current.visitsUsed} de $it" } ?: current.visitsUsed.toString(),
                if (visitsLeft == null) "Ilimitadas" else "Quedan $visitsLeft",
                if (visitsLeft == 0) UiTone.Warning else UiTone.Neutral,
            ),
            UiMetric("Pases de invitado", "${MembresiaRules.guestPassesLeft(current)} de ${current.guestPassesTotal}", "Disponibles"),
        )
    }

    private fun visitsTable(visits: List<Visit>, withCustomer: Boolean): UiTable {
        val columns = buildList {
            add(UiTableColumn("when", "Fecha"))
            if (withCustomer) add(UiTableColumn("customer", "Cliente"))
            addAll(listOf(UiTableColumn("kind", "Tipo"), UiTableColumn("guest", "Invitado"), UiTableColumn("by", "Registró"), UiTableColumn("note", "Aviso")))
        }
        return UiTable(
            columns,
            visits.map { visit ->
                buildMap {
                    put("when", timeFormat.format(visit.visitedAt))
                    put("customer", visit.customerName)
                    put("kind", if (visit.kind == "invitado") "Invitado" else "Miembro")
                    put("guest", visit.guestName.orEmpty())
                    put("by", visit.recordedBy)
                    put("note", if (visit.override) "Registrada igual" else visit.note)
                }
            },
            emptyText = "Sin visitas.",
        )
    }

    // ---------------------------------------------------------------- listas

    private val listStatuses = listOf(
        DisplayStatus.ACTIVA, DisplayStatus.POR_VENCER, DisplayStatus.VENCIDA, DisplayStatus.PROGRAMADA, DisplayStatus.CANCELADA,
    )

    /** Membresias de un estado (null = todas) con botones para cambiar de estado. */
    fun miembros(
        access: MembresiaAccess,
        status: DisplayStatus?,
        memberships: List<Membership>,
        counts: Map<DisplayStatus, Int>,
        today: LocalDate,
        expiringDays: Int,
        notice: UiNotice? = null,
    ): UiScreen {
        val filters = buildList<UiNode> {
            add(UiButton("Todas", UiNavigate("miembros"), style = if (status == null) "primary" else "secondary"))
            listStatuses.forEach {
                add(UiButton("${it.label} (${counts[it] ?: 0})", UiNavigate("miembros", mapOf("estado" to it.key)), style = if (status == it) "primary" else "secondary"))
            }
        }
        val items = memberships.map { membership ->
            val shown = MembresiaRules.displayStatus(membership, today, expiringDays)
            UiListItem(
                title = membership.customerName,
                subtitle = "${membership.planName} · ${membership.startsOn} a ${membership.endsOn}",
                trailing = membership.endsOn.toString(),
                badges = listOf(badge(shown)),
                action = openMember(membership.customerId),
            )
        }
        val body = listOf(UiRow(filters, spacing = 8), UiList(items, emptyText = "No hay membresías en este estado."))
        return screen(access, "miembros", "Miembros", notice, body)
    }

    /** Membresias vigentes agrupadas por plan. */
    fun niveles(
        access: MembresiaAccess,
        byPlan: Map<String, List<Membership>>,
        today: LocalDate,
        expiringDays: Int,
        notice: UiNotice? = null,
    ): UiScreen {
        val body: List<UiNode> = if (byPlan.isEmpty()) {
            listOf(UiText("No hay membresías vigentes.", UiTextStyle.Caption))
        } else {
            byPlan.map { (plan, list) ->
                UiCard(
                    title = "$plan (${list.size} ${if (list.size == 1) "activa" else "activas"})",
                    children = listOf(
                        UiList(
                            list.map { membership ->
                                UiListItem(
                                    title = membership.customerName,
                                    subtitle = "Hasta ${membership.endsOn}",
                                    badges = listOf(badge(MembresiaRules.displayStatus(membership, today, expiringDays))),
                                    action = openMember(membership.customerId),
                                )
                            },
                        ),
                    ),
                )
            }
        }
        return screen(access, "niveles", "Por nivel", notice, body)
    }

    fun visitas(access: MembresiaAccess, date: LocalDate, visits: List<Visit>, notice: UiNotice? = null): UiScreen =
        screen(
            access, "recepcion", "Visitas de hoy", notice,
            listOf(UiText("$date · ${visits.size} ${if (visits.size == 1) "visita" else "visitas"}", UiTextStyle.Subtitle), visitsTable(visits, withCustomer = true)),
        )

    // ---------------------------------------------------------------- altas

    /** Valores iniciales de la membresia nueva; [customer] viene de la ficha de un miembro. */
    fun nuevaValues(today: LocalDate, customer: MemberSummary? = null): Map<String, String> = buildMap {
        put("startsOn", today.toString())
        if (customer != null) {
            put("customerId", customer.customerId.toString())
            put("name", customer.customerName)
            put("email", customer.email)
            put("phone", customer.phone)
        }
    }

    fun nueva(
        access: MembresiaAccess,
        plans: List<Plan>,
        settings: MembresiaSettings,
        hosts: List<HostCandidate>,
        values: Map<String, String>,
        notice: UiNotice? = null,
    ): UiScreen {
        val fields = listOf(
            UiField(
                "name", "Cliente", required = true, section = "Cliente",
                help = "Escribe para buscar un cliente; si no existe se crea con estos datos.",
                suggest = UiSuggest(CustomersSuggestRoute, minLength = 2),
            ),
            UiField("email", "Correo", type = "email", section = "Cliente"),
            UiField("phone", "Teléfono", type = "phone", section = "Cliente"),
            UiField("plan", "Plan", type = "dropdown", required = true, options = plans.map { planLabel(it, settings.currency) }, section = "Membresía"),
            UiField("startsOn", "Inicio", type = "date", section = "Membresía"),
            UiField("amount", "Monto (${settings.currency})", placeholder = "Vacío = precio del plan", section = "Membresía"),
        ) + detailFields(settings, hosts, section = "Detalles")
        val form = UiForm(
            id = "nueva",
            fields = fields,
            values = values,
            submit = UiButton("Crear membresía", post("crear", formId = "nueva")),
        )
        val body = if (plans.isEmpty()) {
            listOf(UiText("No hay planes activos. Crea uno en Planes.", UiTextStyle.Caption), form)
        } else {
            listOf(form)
        }
        return screen(access, "nueva", "Nueva membresía", notice, body)
    }

    private fun detailFields(settings: MembresiaSettings, hosts: List<HostCandidate>, section: String, currentSource: String = "", currentHost: String = ""): List<UiField> = listOf(
        UiField("source", "Fuente", type = "dropdown", options = (settings.sources + currentSource).filter { it.isNotBlank() }.distinct(), section = section),
        UiField("host", "Anfitrión", type = "dropdown", options = (hosts.map { it.name } + currentHost).filter { it.isNotBlank() }.distinct(), section = section),
        UiField("unit", "Unidad", section = section, help = "Oficina, escritorio o espacio asignado."),
        UiField("notes", "Notas", type = "textarea", section = section),
    )

    fun renovar(
        access: MembresiaAccess,
        membership: Membership,
        plans: List<Plan>,
        settings: MembresiaSettings,
        today: LocalDate,
        values: Map<String, String>? = null,
        notice: UiNotice? = null,
    ): UiScreen {
        val defaultStart = MembresiaRules.renewalStart(membership.endsOn, today)
        val planChosen = plans.firstOrNull { it.id == membership.planId }?.let { planLabel(it, settings.currency) }.orEmpty()
        val form = UiForm(
            id = "renovar",
            fields = listOf(
                UiField("plan", "Plan", type = "dropdown", required = true, options = plans.map { planLabel(it, settings.currency) }),
                UiField("startsOn", "Inicio", type = "date", help = "Vacío = $defaultStart."),
                UiField("amount", "Monto (${settings.currency})", placeholder = "Vacío = precio del plan"),
            ),
            values = values ?: mapOf("plan" to planChosen, "startsOn" to defaultStart.toString()),
            submit = UiButton("Renovar", post("renovar", mapOf("id" to membership.id.toString()), formId = "renovar")),
        )
        val body = listOf(
            UiText("${membership.customerName} · ${membership.planName} · vence el ${membership.endsOn}", UiTextStyle.Subtitle),
            form,
            UiButton("Volver", UiNavigate("miembro", mapOf("customer" to membership.customerId.toString())), style = "secondary"),
        )
        return screen(access, "recepcion", "Renovar membresía", notice, body)
    }

    fun editar(
        access: MembresiaAccess,
        membership: Membership,
        settings: MembresiaSettings,
        hosts: List<HostCandidate>,
        values: Map<String, String>? = null,
        notice: UiNotice? = null,
    ): UiScreen {
        val form = UiForm(
            id = "editar",
            fields = listOf(
                UiField("endsOn", "Fin", type = "date", required = true),
                UiField("amount", "Monto (${settings.currency})", required = true),
            ) + detailFields(settings, hosts, section = "Detalles", currentSource = membership.source, currentHost = membership.hostName),
            values = values ?: mapOf(
                "endsOn" to membership.endsOn.toString(),
                "amount" to membership.amount.setScale(2, RoundingMode.HALF_UP).toPlainString(),
                "source" to membership.source,
                "host" to membership.hostName,
                "unit" to membership.unit,
                "notes" to membership.notes,
            ),
            submit = UiButton("Guardar cambios", post("editar", mapOf("id" to membership.id.toString()), formId = "editar")),
        )
        val body = listOf(
            UiText("${membership.customerName} · ${membership.planName} · desde ${membership.startsOn}", UiTextStyle.Subtitle),
            form,
            UiButton("Volver", UiNavigate("miembro", mapOf("customer" to membership.customerId.toString())), style = "secondary"),
        )
        return screen(access, "recepcion", "Editar membresía", notice, body)
    }

    // ---------------------------------------------------------------- planes, ingresos, ajustes

    fun planValues(plan: Plan?): Map<String, String> = if (plan == null) {
        mapOf("durationDays" to "30", "guestPasses" to "0", "wifi" to "true", "displayOrder" to "0")
    } else {
        mapOf(
            "name" to plan.name,
            "description" to plan.description,
            "price" to plan.price.setScale(2, RoundingMode.HALF_UP).toPlainString(),
            "durationDays" to plan.durationDays.toString(),
            "includedVisits" to plan.includedVisits?.toString().orEmpty(),
            "guestPasses" to plan.guestPasses.toString(),
            "wifi" to plan.wifiAccess.toString(),
            "displayOrder" to plan.displayOrder.toString(),
        )
    }

    fun planes(
        access: MembresiaAccess,
        plans: List<Plan>,
        currency: String,
        editing: Plan?,
        values: Map<String, String>,
        notice: UiNotice? = null,
    ): UiScreen {
        val items = plans.map { plan ->
            UiListItem(
                title = plan.name,
                subtitle = listOf(
                    "${plan.durationDays} días",
                    plan.includedVisits?.let { "$it visitas" } ?: "visitas ilimitadas",
                    "${plan.guestPasses} invitados",
                    if (plan.wifiAccess) "WiFi" else "sin WiFi",
                ).joinToString(" · "),
                trailing = money(plan.price, currency),
                badges = listOf(if (plan.active) UiBadge("Activo", UiTone.Success) else UiBadge("Inactivo", UiTone.Neutral)),
                action = UiNavigate("planes", mapOf("edit" to plan.id.toString())),
            )
        }
        val form = UiForm(
            id = "plan",
            fields = listOf(
                UiField("name", "Nombre", required = true),
                UiField("description", "Descripción", type = "textarea"),
                UiField("price", "Precio ($currency)", required = true),
                UiField("durationDays", "Duración (días)", type = "number", required = true),
                UiField("includedVisits", "Visitas incluidas", type = "number", help = "Vacío = ilimitadas."),
                UiField("guestPasses", "Pases de invitado", type = "number"),
                UiField("wifi", "Incluye WiFi", type = "boolean"),
                UiField("displayOrder", "Orden", type = "number"),
            ),
            values = values,
            submit = UiButton(
                if (editing == null) "Crear plan" else "Guardar plan",
                post("plan-guardar", editing?.let { mapOf("id" to it.id.toString()) }.orEmpty(), formId = "plan"),
            ),
        )
        val editorActions = buildList<UiNode> {
            if (editing != null) {
                add(
                    UiButton(
                        if (editing.active) "Desactivar plan" else "Activar plan",
                        post("plan-activo", mapOf("id" to editing.id.toString(), "active" to (!editing.active).toString()), confirm = if (editing.active) "¿Desactivar este plan? Las membresías vigentes siguen igual." else null),
                        style = if (editing.active) "danger" else "secondary",
                    ),
                )
                add(UiButton("Nuevo plan", UiNavigate("planes"), style = "secondary"))
            }
        }
        val body = listOf(
            UiList(items, emptyText = "Aún no hay planes."),
            UiCard(title = if (editing == null) "Nuevo plan" else "Editar ${editing.name}", children = listOf<UiNode>(form) + editorActions),
        )
        return screen(access, "planes", "Planes", notice, body)
    }

    fun ingresos(
        access: MembresiaAccess,
        months: List<RevenueMonth>,
        byPlan: List<RevenueByPlan>,
        currency: String,
        notice: UiNotice? = null,
    ): UiScreen {
        val last = months.lastOrNull()
        val body = buildList<UiNode> {
            if (last != null) {
                add(UiMetric("Ingresos de ${last.month}", money(last.amount, currency), "${last.count} ${if (last.count == 1) "membresía" else "membresías"}", UiTone.Success))
            }
            add(UiBarChart(months.map { UiChartPoint(it.month.toString(), it.amount.toDouble()) }))
            add(
                UiCard(
                    title = "Por mes",
                    children = listOf(
                        UiTable(
                            listOf(UiTableColumn("month", "Mes"), UiTableColumn("count", "Membresías"), UiTableColumn("amount", "Ingresos")),
                            months.asReversed().map { mapOf("month" to it.month.toString(), "count" to it.count.toString(), "amount" to money(it.amount, currency)) },
                        ),
                    ),
                ),
            )
            add(
                UiCard(
                    title = "Por plan (últimos ${months.size} meses)",
                    children = listOf(
                        UiTable(
                            listOf(UiTableColumn("plan", "Plan"), UiTableColumn("count", "Membresías"), UiTableColumn("amount", "Ingresos")),
                            byPlan.map { mapOf("plan" to it.planName, "count" to it.count.toString(), "amount" to money(it.amount, currency)) },
                        ),
                    ),
                ),
            )
        }
        return screen(access, "ingresos", "Ingresos", notice, body)
    }

    fun ajustesValues(settings: MembresiaSettings): Map<String, String> = mapOf(
        "currency" to settings.currency,
        "expiringDays" to settings.expiringDays.toString(),
        "sources" to settings.sources.joinToString("\n"),
    )

    fun ajustes(access: MembresiaAccess, values: Map<String, String>, notice: UiNotice? = null): UiScreen {
        val form = UiForm(
            id = "ajustes",
            fields = listOf(
                UiField("currency", "Moneda", required = true, help = "Código de 3 letras, por ejemplo USD."),
                UiField("expiringDays", "Días de aviso de vencimiento", type = "number", required = true, help = "El equipo recibe un aviso esos días antes de que venza."),
                UiField("sources", "Fuentes", type = "textarea", help = "Una por línea."),
            ),
            values = values,
            submit = UiButton("Guardar ajustes", post("ajustes-guardar", formId = "ajustes")),
        )
        return screen(access, "ajustes", "Ajustes", notice, listOf(form))
    }

    // ---------------------------------------------------------------- sugerencias

    /** Sugerencias al escribir el cliente; al elegir una se llenan customerId, name, email y phone. */
    fun suggestions(members: List<MemberSummary>, today: LocalDate = LocalDate.now(), expiringDays: Int = 7): UiSuggestions = UiSuggestions(
        items = members.map { member ->
            val status = statusOf(member, today, expiringDays)?.label ?: "Sin membresía"
            UiSuggestion(
                title = member.customerName.ifBlank { member.email.ifBlank { member.phone } },
                subtitle = listOf(member.email, member.phone, status).filter { it.isNotBlank() }.joinToString(" · "),
                values = mapOf(
                    "customerId" to member.customerId.toString(),
                    "name" to member.customerName,
                    "email" to member.email,
                    "phone" to member.phone,
                ),
            )
        },
    )
}
