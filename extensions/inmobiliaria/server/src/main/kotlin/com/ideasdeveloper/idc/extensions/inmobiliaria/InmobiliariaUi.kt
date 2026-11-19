package com.ideasdeveloper.idc.extensions.inmobiliaria

import com.ideasdeveloper.idc.modules.crm.CrmActor
import com.ideasdeveloper.idc.modules.crm.CrmExtensionActionResult
import com.ideasdeveloper.idc.modules.crm.CrmLead
import com.ideasdeveloper.idc.modules.crm.CrmLeadFilter
import com.ideasdeveloper.idc.modules.crm.CrmRepository
import com.ideasdeveloper.idc.modules.crm.CrmScreens
import com.ideasdeveloper.idc.modules.crm.CrmUser
import com.ideasdeveloper.idc.ui.UiButton
import com.ideasdeveloper.idc.ui.UiNavigate
import com.ideasdeveloper.idc.ui.UiNode
import com.ideasdeveloper.idc.ui.UiNotice
import com.ideasdeveloper.idc.ui.UiRequest
import com.ideasdeveloper.idc.ui.UiRow
import com.ideasdeveloper.idc.ui.UiText
import com.ideasdeveloper.idc.ui.UiTextStyle
import com.ideasdeveloper.idc.ui.UiTone
import java.math.BigDecimal
import java.math.RoundingMode
import java.sql.Connection
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.UUID

/** Ids de las pantallas de la extension (deben coincidir con las secciones de [InmobiliariaServerExtension]). */
object InmobiliariaScreenIds {
    const val Inventory = "inmobiliaria_inventory"
    const val Property = "inmobiliaria_property"
    const val Intake = "inmobiliaria_intake"
    const val Deals = "inmobiliaria_deals"
    const val Deal = "inmobiliaria_deal"
    const val Stages = "inmobiliaria_stages"
}

/** Formato de textos para las pantallas (puro, sin base de datos). Sin tildes, como el resto del CRM. */
object InmobiliariaFormat {
    private val months = listOf("Ene", "Feb", "Mar", "Abr", "May", "Jun", "Jul", "Ago", "Sep", "Oct", "Nov", "Dic")
    private val timeFormat: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
    const val Empty = "-"

    /** "Ene 2027". */
    fun month(month: YearMonth): String = "${months[month.monthValue - 1]} ${month.year}"

    /** Monto con miles separados por coma; sin decimales si son cero ("125,000", "1,250.50"). */
    fun amount(value: BigDecimal): String {
        val scaled = value.setScale(2, RoundingMode.HALF_UP)
        return if (scaled.remainder(BigDecimal.ONE).signum() == 0) String.format(Locale.US, "%,d", scaled.toBigInteger()) else String.format(Locale.US, "%,.2f", scaled)
    }

    /** "USD 125,000"; "-" si no hay monto. */
    fun money(value: BigDecimal?, currency: String): String = if (value == null) Empty else "$currency ${amount(value)}"

    /** "5 %" (sin ceros sobrantes). */
    fun percent(value: BigDecimal): String = "${InmobiliariaMath.plain(value)} %"

    fun date(value: LocalDate?): String = value?.toString() ?: Empty

    fun timestamp(value: Instant): String = timeFormat.format(value.atZone(ZoneId.systemDefault()))

    /** Valor de un campo numerico de formulario: sin ceros sobrantes; vacio si no hay valor. */
    fun field(value: BigDecimal?): String = value?.let { InmobiliariaMath.plain(it) }.orEmpty()
}

/** Lectura de los filtros y la pagina que mandan las pantallas de lista (puro, sin base de datos). */
object InmobiliariaFilters {
    const val All = "Todos"
    const val MineListings = "Mis listados"
    const val MineDeals = "Mis cierres"
    const val PageSize = 50

    private fun choice(raw: String?): String = raw.orEmpty().trim().takeIf { it != All }.orEmpty()

    private fun moneyOrNull(raw: String?): BigDecimal? = runCatching { InmobiliariaInput.parseMoney(raw) }.getOrNull()

    /** Filtros del inventario; un valor que no se entiende (precio con letras, tipo desconocido) se ignora. */
    fun property(params: Map<String, String>, agents: List<CrmUser>, userId: UUID?): PropertyFilter {
        val agent = choice(params["fAgent"])
        return PropertyFilter(
            text = params["q"].orEmpty().trim().take(100),
            zone = choice(params["fZone"]).take(120),
            kind = PropertyKind.parse(choice(params["fKind"])),
            status = PropertyStatus.parse(choice(params["fStatus"])),
            agentId = agents.firstOrNull { it.name == agent }?.id,
            minPrice = moneyOrNull(params["minPrice"]),
            maxPrice = moneyOrNull(params["maxPrice"]),
            mineOf = userId.takeIf { agent == MineListings },
        )
    }

    /** Captaciones: propiedades en Captacion, con el texto y "Mis proximos listados". */
    fun intake(params: Map<String, String>, userId: UUID?): PropertyFilter = PropertyFilter(
        text = params["q"].orEmpty().trim().take(100),
        status = PropertyStatus.Intake,
        mineOf = userId.takeIf { params["fMine"] == "true" },
    )

    fun deal(params: Map<String, String>, stages: List<DealStage>, agents: List<CrmUser>, userId: UUID?): DealFilter {
        val agent = choice(params["fAgent"])
        val stage = choice(params["fStage"])
        return DealFilter(
            stageId = stages.firstOrNull { it.name == stage }?.id,
            agentId = if (agent == MineDeals) userId else agents.firstOrNull { it.name == agent }?.id,
            openOnly = params["fOpen"] == "true",
        )
    }

    /** Pagina pedida (desde 1), acotada a las que existen. */
    fun page(raw: String?, total: Int): Int = (raw?.toIntOrNull() ?: 1).coerceIn(1, pages(total))

    fun pages(total: Int): Int = maxOf(1, (total + PageSize - 1) / PageSize)

    /** De los parametros de la pantalla, solo los que son filtros (para conservarlos al cambiar de pagina). */
    fun keep(params: Map<String, String>): Map<String, String> =
        params.filterKeys { it in FilterKeys }.filterValues { it.isNotBlank() }

    private val FilterKeys = setOf("q", "fZone", "fKind", "fStatus", "fAgent", "minPrice", "maxPrice", "fMine", "fStage", "fOpen")
}

/** Lecturas sueltas de la base de la empresa que las pantallas necesitan en lote (nunca una consulta por fila). */
internal object InmobiliariaLookups {
    private fun bind(tenant: Connection, statement: java.sql.PreparedStatement, params: List<Any>) {
        params.forEachIndexed { index, value ->
            when (value) {
                is Collection<*> -> statement.setArray(index + 1, tenant.createArrayOf("uuid", value.toTypedArray()))
                else -> statement.setObject(index + 1, value)
            }
        }
    }

    /** Nombres de los clientes que el usuario ve (los de su alcance); los demas no aparecen en el resultado. */
    fun customerNames(context: CrmScreens.Context, ids: Collection<UUID>): Map<UUID, String> {
        if (ids.isEmpty()) return emptyMap()
        val (condition, scopeParams) = CrmRepository.customerScopeSql(context.scope) ?: ("TRUE" to emptyList())
        val sql = "SELECT c.id, c.display_name FROM customers c WHERE c.id = ANY(?) AND $condition"
        return context.tenant.prepareStatement(sql).use { query ->
            bind(context.tenant, query, listOf<Any>(ids.toSet()) + scopeParams)
            query.executeQuery().use { rows -> buildMap { while (rows.next()) put(rows.getObject(1, UUID::class.java), rows.getString(2).orEmpty()) } }
        }
    }

    /** Nombres de los leads que el usuario ve segun su alcance. */
    fun leadNames(context: CrmScreens.Context, ids: Collection<UUID>): Map<UUID, String> {
        if (ids.isEmpty()) return emptyMap()
        val (condition, scopeParams) = context.scope.sqlCondition()
        val sql = "SELECT l.id, c.display_name FROM crm_leads l JOIN customers c ON c.id = l.customer_id WHERE l.id = ANY(?) AND $condition"
        return context.tenant.prepareStatement(sql).use { query ->
            bind(context.tenant, query, listOf<Any>(ids.toSet()) + scopeParams)
            query.executeQuery().use { rows -> buildMap { while (rows.next()) put(rows.getObject(1, UUID::class.java), rows.getString(2).orEmpty()) } }
        }
    }

    /**
     * Lead escrito en un formulario: un id, o un nombre/correo/telefono que coincida con un solo lead de los que el usuario ve.
     * [current] es el lead que el formulario ya traia (con el texto con que se mostro): si el texto no cambio, se conserva.
     * Vacio = nulo. El servicio vuelve a comprobar la visibilidad del lead.
     */
    fun resolveLead(context: CrmScreens.Context, raw: String, current: Pair<UUID, String>? = null): UUID? {
        val text = raw.trim()
        if (text.isEmpty()) return null
        if (current != null && text == current.second) return current.first
        runCatching { UUID.fromString(text) }.getOrNull()?.let { return it }
        val found = CrmRepository.leads(context.tenant, context.scope, CrmLeadFilter(search = text.take(100)), 6, 0)
        return pickLead(text, found).id
    }

    /** Elige entre los leads encontrados: uno solo, o el que se llama exactamente igual; si no, un error que dice como afinar. */
    fun pickLead(text: String, found: List<CrmLead>): CrmLead {
        if (found.isEmpty()) throw InmobiliariaException("No hay un lead visible para ti que coincida con '$text'.")
        if (found.size == 1) return found.first()
        found.filter { it.name.equals(text, ignoreCase = true) || it.email.equals(text, ignoreCase = true) }.singleOrNull()?.let { return it }
        val options = found.take(5).joinToString("; ") { lead -> listOf(lead.name, lead.email).filter { it.isNotBlank() }.joinToString(" ") }
        throw InmobiliariaException("Varios leads coinciden con '$text' ($options). Escribe el correo completo o el id del lead.")
    }
}

/** Piezas y resultados que comparten las pantallas. */
internal object InmobiliariaUi {
    const val Route = CrmScreens.UiRoute

    fun session(context: CrmScreens.Context, actor: CrmActor? = null): InmobiliariaSession =
        InmobiliariaSession.of(context.tenant, context.permissions, context.scope, actor ?: CrmActor(context.scope.userId, ""))

    fun request(screen: String, op: String, params: Map<String, String> = emptyMap(), formId: String? = null, confirm: String? = null) =
        UiRequest("$Route/$screen", params = params + ("op" to op), confirm = confirm, formId = formId)

    fun back(screen: String, label: String, params: Map<String, String> = emptyMap()) = UiButton(label, UiNavigate(screen, params), style = "secondary")

    fun message(text: String, tone: String = UiTone.Warning): List<UiNode> = listOf(UiText(text, tone = tone))

    fun heading(text: String): UiNode = UiText(text, UiTextStyle.Subtitle)

    fun caption(text: String): UiNode = UiText(text, UiTextStyle.Caption)

    fun row(vararg buttons: UiButton?): UiNode? = buttons.filterNotNull().takeIf { it.isNotEmpty() }?.let { UiRow(it) }

    /** Anterior / Pagina x de y / Siguiente; nada si cabe en una pagina. */
    fun pager(screen: String, keep: Map<String, String>, page: Int, total: Int): UiNode? {
        val pages = InmobiliariaFilters.pages(total)
        if (pages <= 1) return null
        return UiRow(
            listOfNotNull(
                UiButton("Anterior", UiNavigate(screen, keep + ("page" to (page - 1).toString())), style = "secondary").takeIf { page > 1 },
                UiText("Pagina $page de $pages", UiTextStyle.Caption),
                UiButton("Siguiente", UiNavigate(screen, keep + ("page" to (page + 1).toString())), style = "secondary").takeIf { page < pages },
            ),
        )
    }

    fun uuid(raw: String?): UUID? = raw?.let { runCatching { UUID.fromString(it.trim()) }.getOrNull() }

    // ---- Resultados de las acciones ----

    fun success(screen: String, text: String, params: Map<String, String> = emptyMap(), warnings: List<String> = emptyList()): CrmExtensionActionResult =
        if (warnings.isEmpty()) CrmExtensionActionResult(screen, UiNotice(text, UiTone.Success), params)
        else CrmExtensionActionResult(screen, UiNotice((listOf(text) + warnings).joinToString(" "), UiTone.Warning), params)

    /** Aviso de que algo no se pudo hacer; el formulario conserva lo escrito ([submitted]). */
    fun failure(screen: String, text: String, params: Map<String, String>, submitted: Map<String, String>?): CrmExtensionActionResult =
        CrmExtensionActionResult(screen, UiNotice(text, UiTone.Warning), params, submitted)

    /** Nombre del agente (usuario del CRM) o "-" si ya no esta. */
    fun agentName(users: List<CrmUser>, id: UUID?): String = id?.let { wanted -> users.firstOrNull { it.id == wanted }?.name } ?: InmobiliariaFormat.Empty
}
