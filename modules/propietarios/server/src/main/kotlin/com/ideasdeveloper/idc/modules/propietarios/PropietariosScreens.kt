package com.ideasdeveloper.idc.modules.propietarios

import com.ideasdeveloper.idc.ui.UiBadge
import com.ideasdeveloper.idc.ui.UiButton
import com.ideasdeveloper.idc.ui.UiCard
import com.ideasdeveloper.idc.ui.UiField
import com.ideasdeveloper.idc.ui.UiFieldValues
import com.ideasdeveloper.idc.ui.UiFileRef
import com.ideasdeveloper.idc.ui.UiFiles
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
import com.ideasdeveloper.idc.ui.UiOpenUrl
import com.ideasdeveloper.idc.ui.UiRequest
import com.ideasdeveloper.idc.ui.UiRow
import com.ideasdeveloper.idc.ui.UiScreen
import com.ideasdeveloper.idc.ui.UiSuggest
import com.ideasdeveloper.idc.ui.UiText
import com.ideasdeveloper.idc.ui.UiTextStyle
import com.ideasdeveloper.idc.ui.UiTimeline
import com.ideasdeveloper.idc.ui.UiTimelineEntry
import com.ideasdeveloper.idc.ui.UiTone
import java.math.BigDecimal
import java.time.LocalDate

/**
 * Pantallas nativas del modulo. Cada constructor es puro: recibe los datos ya cargados y devuelve un [UiScreen];
 * PropietariosRoutes carga los datos y PropietariosActions lee lo que envian los formularios.
 */
object PropietariosScreens {
    const val Route: String = PropietariosModule.UiRoute
    const val CustomerSuggestRoute = "$Route/suggest/customers"
    const val ListLimit = 200

    /** Quien mira y que puede hacer. */
    data class Context(
        val canManage: Boolean,
        val canAdmin: Boolean,
        // El usuario tiene propietarios.import y hay una fuente de importacion activa: aparece "Importar".
        val importEnabled: Boolean,
        // Puede ver clientes (clientes.view): sin ello no se eligen titulares ni se abre la ficha del cliente.
        val canSeeCustomers: Boolean,
        val today: LocalDate,
    )

    data class DevelopmentData(val development: Development, val totals: Map<LotStatus, StatusTotals>, val lots: List<Lot>)

    data class LotDetail(
        val lot: Lot,
        val ownerships: List<Ownership>,
        val history: List<LotHistoryEntry>,
        val attachments: List<LotAttachment>,
    )

    // ---- formato ----

    fun area(value: BigDecimal?): String = value?.let { if (it.signum() == 0) "0" else it.stripTrailingZeros().toPlainString() }.orEmpty()

    fun statusTone(status: LotStatus): String = when (status) {
        LotStatus.Disponible -> UiTone.Success
        LotStatus.Reservado -> UiTone.Warning
        LotStatus.Vendido -> UiTone.Neutral
        LotStatus.EnConstruccion -> UiTone.Info
        LotStatus.Construido -> UiTone.Info
    }

    fun historyLabel(kind: String): String = when (kind) {
        "alta" -> "Alta de titular"
        "traspaso" -> "Cambio de titularidad"
        "baja" -> "Baja de titular"
        "importacion" -> "Importación"
        else -> "Edición"
    }

    private fun historyTone(kind: String): String = when (kind) {
        "alta" -> UiTone.Success
        "baja" -> UiTone.Warning
        "traspaso" -> UiTone.Info
        else -> UiTone.Neutral
    }

    private fun hint(text: String, tone: String? = null) = UiText(text, UiTextStyle.Caption, tone)

    private fun metric(label: String, value: String, caption: String = "") = UiCard(listOf(UiMetric(label, value, caption)))

    // ---- navegacion ----

    fun navigation(context: Context, selected: String): UiNavigation = UiNavigation(
        title = PropietariosModule.displayName,
        items = listOfNotNull(
            UiNavItem("desarrollos", "Desarrollos", UiNavigate("desarrollos")),
            UiNavItem("propietarios", "Propietarios", UiNavigate("propietarios")),
            UiNavItem("buscar", "Buscar", UiNavigate("buscar")),
            UiNavItem("importar", "Importar", UiNavigate("importar")).takeIf { context.importEnabled },
        ),
        selected = selected,
    )

    private fun screen(context: Context, title: String, selected: String, notice: UiNotice?, body: List<UiNode>) =
        UiScreen(title = title, navigation = navigation(context, selected), notice = notice, body = body)

    // ---- piezas comunes ----

    private fun lotItem(lot: Lot, withDevelopment: Boolean): UiListItem = UiListItem(
        title = "Lote ${lot.lotNumber}" + lot.propertyName.takeIf { it.isNotBlank() }?.let { " · $it" }.orEmpty(),
        subtitle = listOfNotNull(
            lot.developmentName.takeIf { withDevelopment },
            lot.registerNumber.takeIf { it.isNotBlank() }?.let { "Registro $it" },
            lot.propertyType.takeIf { it.isNotBlank() },
        ).joinToString(" · "),
        trailing = lot.areaM2?.let { "${area(it)} m²" }.orEmpty(),
        badges = listOf(UiBadge(lot.status.label, statusTone(lot.status))),
        action = UiNavigate("lote", mapOf("id" to lot.id.toString())),
    )

    private fun truncatedNote(count: Int): List<UiNode> =
        if (count > ListLimit) listOf(hint("Se muestran los primeros $ListLimit resultados: afina la búsqueda para ver el resto.", UiTone.Warning)) else emptyList()

    private fun filesField(readOnly: Boolean) = UiField(
        "files", "Adjuntos (escrituras, planos)", type = "files", readOnly = readOnly,
        files = UiFiles(upload = "$Route/files", download = "$Route/files"),
    )

    private fun fileRefs(attachments: List<LotAttachment>) = attachments.map { UiFileRef(it.id.toString(), it.name, it.path, it.size) }

    private fun mapButton(url: String?): List<UiNode> = if (url == null) emptyList() else listOf(UiButton("Ver en mapa", UiOpenUrl(url), style = "secondary"))

    // ---- Desarrollos ----

    private fun developmentFields() = listOf(
        UiField("name", "Nombre", required = true),
        UiField("location", "Ubicación", placeholder = "Ciudad, zona o dirección"),
        UiField("map_url", "Enlace del mapa", type = "url", help = "Opcional. Debe empezar por https://. Si lo dejas vacío se usan las coordenadas."),
        UiField("latitude", "Latitud", help = "Opcional, por ejemplo 9.9281."),
        UiField("longitude", "Longitud", help = "Opcional, por ejemplo -84.0907."),
        UiField("notes", "Notas", type = "textarea"),
    )

    private fun developmentValues(development: Development?) = mapOf(
        "name" to development?.name.orEmpty(),
        "location" to development?.location.orEmpty(),
        "map_url" to development?.mapUrl.orEmpty(),
        "latitude" to development?.latitude?.stripTrailingZeros()?.toPlainString().orEmpty(),
        "longitude" to development?.longitude?.stripTrailingZeros()?.toPlainString().orEmpty(),
        "notes" to development?.notes.orEmpty(),
    )

    fun developments(context: Context, list: List<DevelopmentSummary>, notice: UiNotice? = null, submitted: Map<String, String>? = null): UiScreen {
        val totalArea = list.fold(BigDecimal.ZERO) { sum, item -> sum + item.totalAreaM2 }
        val body = buildList<UiNode> {
            add(
                UiGrid(
                    listOf(
                        metric("Desarrollos", list.size.toString()),
                        metric("Lotes", list.sumOf { it.lots }.toString()),
                        metric("Área total", "${area(totalArea)} m²"),
                    ),
                ),
            )
            add(
                UiList(
                    items = list.map { item ->
                        UiListItem(
                            title = item.development.name,
                            subtitle = listOf(item.development.location.takeIf { it.isNotBlank() }, "${item.lots} lotes", "${area(item.totalAreaM2)} m²", "${item.owners} titulares")
                                .filterNotNull().joinToString(" · "),
                            action = UiNavigate("desarrollo", mapOf("id" to item.development.id.toString())),
                        )
                    },
                    emptyText = "Aún no hay desarrollos.",
                ),
            )
            if (context.canAdmin) {
                add(
                    UiCard(
                        listOf(
                            UiForm(
                                id = "development",
                                fields = developmentFields(),
                                values = developmentValues(null) + submitted.orEmpty(),
                                submit = UiButton("Crear desarrollo", UiRequest("$Route/desarrollos", params = mapOf("op" to "dev-save"), formId = "development")),
                            ),
                        ),
                        title = "Nuevo desarrollo",
                        collapsible = true,
                        expanded = list.isEmpty() || submitted != null,
                    ),
                )
            }
        }
        return screen(context, "Desarrollos", "desarrollos", notice, body)
    }

    fun development(context: Context, data: DevelopmentData, params: Map<String, String>, notice: UiNotice? = null, submitted: Map<String, String>? = null): UiScreen {
        val development = data.development
        val id = development.id.toString()
        val body = buildList<UiNode> {
            val details = listOfNotNull(development.location.takeIf { it.isNotBlank() }, development.notes.takeIf { it.isNotBlank() })
            details.forEach { add(hint(it)) }
            val buttons = buildList<UiNode> {
                if (context.canManage) add(UiButton("Nuevo lote", UiNavigate("lote", mapOf("development" to id))))
                addAll(mapButton(PropietariosRules.mapLink(development.mapUrl, development.latitude, development.longitude)))
                add(UiButton("Todos los desarrollos", UiNavigate("desarrollos"), style = "secondary"))
            }
            add(UiRow(buttons))
            add(
                UiGrid(
                    listOf(metric("Lotes", data.totals.values.sumOf { it.lots }.toString(), "${area(data.totals.values.fold(BigDecimal.ZERO) { sum, item -> sum + item.areaM2 })} m²")) +
                        LotStatus.entries.map { status ->
                            val totals = data.totals[status] ?: StatusTotals(0, BigDecimal.ZERO)
                            metric(status.label, totals.lots.toString(), "${area(totals.areaM2)} m²")
                        },
                    minItemWidth = 150,
                ),
            )
            val filterFields = listOf(
                UiField("q", "Buscar", placeholder = "Número de lote, registro o propiedad"),
                UiField("status", "Estado", type = "dropdown", options = listOf(PropietariosActions.AllOption) + LotStatus.entries.map { it.label }),
            )
            add(
                UiForm(
                    "filters", filterFields,
                    values = mapOf("q" to params["q"].orEmpty(), "status" to (params["status"]?.takeIf { it.isNotBlank() } ?: PropietariosActions.AllOption)),
                    submit = UiButton("Filtrar", UiNavigate("desarrollo", mapOf("id" to id), formId = "filters"), style = "secondary"),
                    minFieldWidth = 220,
                ),
            )
            addAll(truncatedNote(data.lots.size))
            add(UiList(data.lots.take(ListLimit).map { lotItem(it, withDevelopment = false) }, emptyText = "No hay lotes con esos filtros."))
            if (context.canAdmin) {
                add(
                    UiCard(
                        listOf(
                            UiForm(
                                id = "development",
                                fields = developmentFields(),
                                values = developmentValues(development) + submitted.orEmpty(),
                                submit = UiButton("Guardar", UiRequest("$Route/desarrollo", params = mapOf("op" to "dev-save", "id" to id), formId = "development"), style = "secondary"),
                            ),
                            UiButton(
                                "Archivar desarrollo", UiRequest("$Route/desarrollo", params = mapOf("op" to "dev-archive", "id" to id), confirm = "Se archivará el desarrollo y todos sus lotes. ¿Continuar?"),
                                style = "danger",
                            ),
                        ),
                        title = "Editar desarrollo",
                        collapsible = true,
                        expanded = submitted != null,
                    ),
                )
            }
        }
        return screen(context, development.name, "desarrollos", notice, body)
    }

    // ---- Lote ----

    private fun lotFields(readOnly: Boolean) = listOf(
        UiField("lot_number", "Número de lote", required = true, readOnly = readOnly),
        UiField("register_number", "Número de registro", readOnly = readOnly),
        UiField("property_name", "Propiedad", readOnly = readOnly),
        UiField("property_type", "Tipo de propiedad", readOnly = readOnly),
        UiField("status", "Estado", type = "dropdown", options = LotStatus.entries.map { it.label }, readOnly = readOnly),
        UiField("area_m2", "Área (m²)", readOnly = readOnly),
        UiField("map_url", "Enlace del mapa", type = "url", readOnly = readOnly, help = "Opcional. Debe empezar por https://."),
        UiField("latitude", "Latitud", readOnly = readOnly),
        UiField("longitude", "Longitud", readOnly = readOnly),
        UiField("notes", "Notas", type = "textarea", readOnly = readOnly),
    )

    private fun lotValues(lot: Lot?) = mapOf(
        "lot_number" to lot?.lotNumber.orEmpty(),
        "register_number" to lot?.registerNumber.orEmpty(),
        "property_name" to lot?.propertyName.orEmpty(),
        "property_type" to lot?.propertyType.orEmpty(),
        "status" to (lot?.status ?: LotStatus.Disponible).label,
        "area_m2" to area(lot?.areaM2),
        "map_url" to lot?.mapUrl.orEmpty(),
        "latitude" to lot?.latitude?.stripTrailingZeros()?.toPlainString().orEmpty(),
        "longitude" to lot?.longitude?.stripTrailingZeros()?.toPlainString().orEmpty(),
        "notes" to lot?.notes.orEmpty(),
    )

    fun newLot(context: Context, development: Development, notice: UiNotice? = null, submitted: Map<String, String>? = null): UiScreen {
        val form = UiForm(
            id = "lot",
            fields = lotFields(readOnly = false),
            values = lotValues(null) + submitted.orEmpty(),
            submit = UiButton("Crear lote", UiRequest("$Route/lote", params = mapOf("op" to "save", "development" to development.id.toString()), formId = "lot")),
        )
        return screen(
            context, "Nuevo lote", "desarrollos", notice,
            listOf(hint("Desarrollo: ${development.name}"), UiCard(listOf(form), title = "Datos del lote")),
        )
    }

    private fun ownerLine(ownership: Ownership): String = listOfNotNull(
        ownership.sharePercent?.let { "${it.stripTrailingZeros().toPlainString()} %" },
        ownership.startsOn?.let { "desde $it" },
        ownership.endsOn?.let { "hasta $it" },
    ).joinToString(" · ")

    fun lot(context: Context, detail: LotDetail, notice: UiNotice? = null, submitted: Map<String, String>? = null): UiScreen {
        val lot = detail.lot
        val id = lot.id.toString()
        val canEdit = context.canManage && !lot.archived
        fun request(op: String, formId: String? = null, confirm: String? = null, extra: Map<String, String> = emptyMap()) =
            UiRequest("$Route/lote", params = mapOf("op" to op, "id" to id) + extra, confirm = confirm, formId = formId)

        val current = detail.ownerships.filter { it.current }
        val past = detail.ownerships.filter { !it.current }
        val body = buildList<UiNode> {
            add(hint("${lot.developmentName}${if (lot.archived) " · Lote archivado" else ""}"))
            val buttons = buildList<UiNode> {
                add(UiButton("Ver el desarrollo", UiNavigate("desarrollo", mapOf("id" to lot.developmentId.toString())), style = "secondary"))
                addAll(mapButton(PropietariosRules.mapLink(lot.mapUrl, lot.latitude, lot.longitude)))
                if (canEdit) add(UiButton("Cambiar titularidad", UiNavigate("traspaso", mapOf("lot" to id))))
            }
            add(UiRow(buttons))

            add(
                UiCard(
                    listOf<UiNode>(
                        UiList(
                            current.map { ownership ->
                                UiListItem(
                                    title = ownership.customerName,
                                    subtitle = listOf(ownership.customerEmail.takeIf { context.canSeeCustomers && it.isNotBlank() },ownerLine(ownership).takeIf { it.isNotBlank() }).filterNotNull().joinToString(" · "),
                                    action = if (canEdit) UiNavigate("titularidad", mapOf("id" to ownership.id.toString())) else UiNavigate("propietario", mapOf("id" to ownership.customerId.toString())),
                                )
                            },
                            emptyText = "Este lote no tiene titulares vigentes.",
                        ),
                    ) + if (past.isEmpty()) emptyList<UiNode>() else listOf<UiNode>(
                        hint("Titulares anteriores"),
                        UiList(
                            past.map { ownership ->
                                UiListItem(
                                    title = ownership.customerName,
                                    subtitle = ownerLine(ownership),
                                    action = UiNavigate("propietario", mapOf("id" to ownership.customerId.toString())),
                                )
                            },
                        ),
                    ),
                    title = "Titulares vigentes",
                ),
            )

            if (canEdit && context.canSeeCustomers) {
                add(
                    UiCard(
                        listOf(
                            hint("Para quitar a un titular o pasar el lote a otra persona usa Cambiar titularidad."),
                            UiForm(
                                id = "co-owner",
                                fields = listOf(
                                    UiField("owner", "Cliente", required = true, placeholder = "Escribe para buscar", suggest = UiSuggest(CustomerSuggestRoute), help = "Elige un cliente de la lista de sugerencias."),
                                    UiField("share", "Porcentaje", help = "Opcional, de 0 a 100."),
                                ),
                                values = submitted.orEmpty().filterKeys { it in setOf("owner", "owner_id", "share") },
                                submit = UiButton("Añadir cotitular", request("add-owner", formId = "co-owner"), style = "secondary"),
                            ),
                        ),
                        title = "Añadir cotitular",
                        collapsible = true,
                        expanded = false,
                    ),
                )
            }

            add(
                UiCard(
                    listOf(
                        UiForm(
                            id = "lot",
                            fields = lotFields(readOnly = !canEdit),
                            values = lotValues(lot) + submitted.orEmpty().filterKeys { it in lotValues(null).keys },
                            submit = UiButton("Guardar", request("save", formId = "lot")).takeIf { canEdit },
                        ),
                    ),
                    title = "Datos del lote",
                ),
            )

            add(
                UiCard(
                    listOf(
                        UiForm(
                            id = "attachments",
                            fields = listOf(filesField(readOnly = !canEdit)),
                            values = mapOf("files" to UiFieldValues.files(fileRefs(detail.attachments))),
                            submit = UiButton("Guardar adjuntos", request("files", formId = "attachments"), style = "secondary").takeIf { canEdit },
                        ),
                    ),
                    title = "Adjuntos",
                ),
            )

            add(
                UiCard(
                    listOf(
                        UiTimeline(
                            detail.history.map { entry ->
                                UiTimelineEntry(
                                    title = historyLabel(entry.kind),
                                    body = entry.detail,
                                    caption = listOf(entry.author.takeIf { it.isNotBlank() }, entry.happenedOn.toString()).filterNotNull().joinToString(" · "),
                                    tone = historyTone(entry.kind),
                                )
                            },
                            emptyText = "Todavía no hay movimientos.",
                        ),
                    ),
                    title = "Historial",
                ),
            )

            if (context.canAdmin && !lot.archived) {
                add(UiButton("Archivar lote", request("archive", confirm = "El lote dejará de aparecer en listas y búsquedas. ¿Archivarlo?"), style = "danger"))
            }
        }
        return screen(context, "Lote ${lot.lotNumber}", "desarrollos", notice, body)
    }

    // ---- Cambio de titularidad ----

    fun transfer(context: Context, lot: Lot, current: List<Ownership>, notice: UiNotice? = null, submitted: Map<String, String>? = null): UiScreen {
        val id = lot.id.toString()
        val fields = buildList {
            add(UiField("date", "Fecha del cambio", type = "date", required = true))
            for (index in 1..PropietariosRules.MaxFormOwners) {
                add(
                    UiField(
                        "owner$index", "Nuevo titular $index", placeholder = "Escribe para buscar",
                        help = if (index == 1) "Elige un cliente de la lista de sugerencias. Déjalo vacío para registrar solo la baja." else "",
                        section = "Titular $index", suggest = UiSuggest(CustomerSuggestRoute),
                    ),
                )
                add(UiField("share$index", "Porcentaje $index", section = "Titular $index", help = if (index == 1) "Opcional, de 0 a 100; entre todos de 100 como máximo." else ""))
            }
            add(UiField("notes", "Notas", type = "textarea"))
        }
        val values = mapOf("date" to context.today.toString()) + submitted.orEmpty()
        val body = buildList<UiNode> {
            add(hint("${lot.developmentName} · Lote ${lot.lotNumber}"))
            add(
                UiCard(
                    listOf(
                        UiList(
                            current.map { UiListItem(title = it.customerName, subtitle = ownerLine(it)) },
                            emptyText = "El lote no tiene titulares vigentes.",
                        ),
                        hint("Las titularidades vigentes terminan en la fecha del cambio y quedan en el historial."),
                    ),
                    title = "Titulares vigentes",
                ),
            )
            if (context.canSeeCustomers) {
                add(
                    UiForm(
                        id = "transfer",
                        fields = fields,
                        values = values,
                        submit = UiButton(
                            "Registrar cambio",
                            UiRequest("$Route/traspaso", params = mapOf("op" to "transfer", "lot" to id), formId = "transfer"),
                        ),
                    ),
                )
            } else {
                add(hint("Para elegir titulares necesitas permiso para ver clientes.", UiTone.Warning))
            }
            add(UiButton("Volver al lote", UiNavigate("lote", mapOf("id" to id)), style = "secondary"))
        }
        return screen(context, "Cambiar titularidad", "desarrollos", notice, body)
    }

    fun ownership(context: Context, ownership: Ownership, lot: Lot, notice: UiNotice? = null, submitted: Map<String, String>? = null): UiScreen {
        val values = mapOf(
            "share" to area(ownership.sharePercent),
            "starts_on" to ownership.startsOn?.toString().orEmpty(),
            "ends_on" to ownership.endsOn?.toString().orEmpty(),
            "notes" to ownership.notes,
        ) + submitted.orEmpty()
        val form = UiForm(
            id = "ownership",
            fields = listOf(
                UiField("share", "Porcentaje", help = "Opcional, de 0 a 100."),
                UiField("starts_on", "Inicio", type = "date"),
                UiField("ends_on", "Fin", type = "date", help = "Con fecha de fin el titular deja de ser vigente (en esa fecha)."),
                UiField("notes", "Notas", type = "textarea"),
            ),
            values = values,
            submit = UiButton("Guardar", UiRequest("$Route/titularidad", params = mapOf("op" to "save", "id" to ownership.id.toString()), formId = "ownership")),
        )
        val body = listOf(
            hint("${lot.developmentName} · Lote ${lot.lotNumber} · ${ownership.customerName}"),
            UiCard(listOf(form), title = "Titularidad"),
            UiButton("Volver al lote", UiNavigate("lote", mapOf("id" to lot.id.toString())), style = "secondary"),
        )
        return screen(context, "Titularidad", "desarrollos", notice, body)
    }

    // ---- Propietarios ----

    fun owners(context: Context, owners: List<OwnerSummary>, query: String, notice: UiNotice? = null): UiScreen {
        val body = buildList<UiNode> {
            add(
                UiForm(
                    "filters", listOf(UiField("q", "Buscar", placeholder = "Nombre o correo")), values = mapOf("q" to query),
                    submit = UiButton("Buscar", UiNavigate("propietarios", formId = "filters"), style = "secondary"),
                    minFieldWidth = 260,
                ),
            )
            addAll(truncatedNote(owners.size))
            add(
                UiList(
                    owners.take(ListLimit).map { owner ->
                        UiListItem(
                            title = owner.name,
                            subtitle = if (context.canSeeCustomers) owner.email else "",
                            trailing = "${owner.lots} ${if (owner.lots == 1) "lote" else "lotes"} · ${area(owner.totalAreaM2)} m²",
                            action = UiNavigate("propietario", mapOf("id" to owner.customerId.toString())),
                        )
                    },
                    emptyText = "No hay propietarios con lotes vigentes.",
                ),
            )
        }
        return screen(context, "Propietarios", "propietarios", notice, body)
    }

    fun owner(context: Context, customer: CustomerRef, owned: List<OwnedLot>, notice: UiNotice? = null): UiScreen {
        fun item(entry: OwnedLot) = UiListItem(
            title = "Lote ${entry.lot.lotNumber} · ${entry.lot.developmentName}",
            subtitle = listOfNotNull(
                entry.lot.registerNumber.takeIf { it.isNotBlank() }?.let { "Registro $it" },
                entry.lot.areaM2?.let { "${area(it)} m²" },
                ownerLine(entry.ownership).takeIf { it.isNotBlank() },
            ).joinToString(" · "),
            badges = listOf(UiBadge(entry.lot.status.label, statusTone(entry.lot.status))),
            action = UiNavigate("lote", mapOf("id" to entry.lot.id.toString())),
        )
        val current = owned.filter { it.ownership.current }
        val past = owned.filter { !it.ownership.current }
        val totalArea = current.map { it.lot }.distinctBy { it.id }.fold(BigDecimal.ZERO) { sum, lot -> sum + (lot.areaM2 ?: BigDecimal.ZERO) }
        val body = buildList<UiNode> {
            if (context.canSeeCustomers && customer.email.isNotBlank()) add(hint(customer.email))
            if (context.canSeeCustomers) {
                add(UiButton("Abrir en Clientes", UiOpenModule("clientes", "customer", mapOf("id" to customer.id.toString())), style = "secondary"))
            }
            add(UiGrid(listOf(metric("Lotes vigentes", current.size.toString()), metric("Área total", "${area(totalArea)} m²"))))
            add(UiCard(listOf(UiList(current.map(::item), emptyText = "No tiene lotes vigentes.")), title = "Lotes vigentes"))
            if (past.isNotEmpty()) add(UiCard(listOf(UiList(past.map(::item))), title = "Lotes anteriores"))
            add(UiButton("Todos los propietarios", UiNavigate("propietarios"), style = "secondary"))
        }
        return screen(context, customer.name, "propietarios", notice, body)
    }

    // ---- Buscar ----

    fun search(context: Context, query: String, lots: List<Lot>, notice: UiNotice? = null): UiScreen {
        val body = buildList<UiNode> {
            add(
                UiForm(
                    "search", listOf(UiField("q", "Número de lote o de registro", required = true, placeholder = "Por ejemplo 24 o 1-234567")), values = mapOf("q" to query),
                    submit = UiButton("Buscar", UiNavigate("buscar", formId = "search")),
                    minFieldWidth = 320,
                ),
            )
            if (query.isNotBlank()) {
                addAll(truncatedNote(lots.size))
                add(UiList(lots.take(ListLimit).map { lotItem(it, withDevelopment = true) }, emptyText = "Ningún lote coincide con \"$query\"."))
            } else {
                add(hint("Busca por número de lote o de registro en todos los desarrollos."))
            }
        }
        return screen(context, "Buscar", "buscar", notice, body)
    }
}
