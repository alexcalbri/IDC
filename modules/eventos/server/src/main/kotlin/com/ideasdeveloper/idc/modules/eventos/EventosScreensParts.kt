package com.ideasdeveloper.idc.modules.eventos

import com.ideasdeveloper.idc.ui.UiButton
import com.ideasdeveloper.idc.ui.UiCard
import com.ideasdeveloper.idc.ui.UiField
import com.ideasdeveloper.idc.ui.UiFieldValues
import com.ideasdeveloper.idc.ui.UiForm
import com.ideasdeveloper.idc.ui.UiJson
import com.ideasdeveloper.idc.ui.UiNavigate
import com.ideasdeveloper.idc.ui.UiNode
import com.ideasdeveloper.idc.ui.UiNotice
import com.ideasdeveloper.idc.ui.UiRow
import com.ideasdeveloper.idc.ui.UiScreen
import com.ideasdeveloper.idc.ui.UiSuggest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.util.UUID

/**
 * Pure parsing of what the screens send (labels the user picked, dates, hours, amounts) into the types of the module.
 * Nothing here touches the database: the service applies the rules afterwards and a wrong value is an
 * [EventosValidationException] shown to the user as a notice.
 */
object EventosActions {
    const val AllOption = "Todos"
    const val NoCategory = "Sin categoría"
    const val NoSpace = "Sin espacio"
    const val NoResponsible = "Sin responsable"
    const val InvalidForm = "Formulario demasiado grande o no válido."
    private const val MaxBodyChars = 1_000_000
    private val HourFormat: DateTimeFormatter = DateTimeFormatter.ofPattern("H:mm")

    // ---- body ----

    /** Values of a form body: strings as they come and a JSON array (multiple dropdown) as its encoded text. */
    fun bodyValues(body: String): Map<String, String> {
        if (body.length > MaxBodyChars) return emptyMap()
        return runCatching {
            UiJson.parseToJsonElement(body).jsonObject.mapValues { (_, value) ->
                when (value) {
                    is JsonPrimitive -> value.contentOrNull.orEmpty()
                    is JsonArray -> UiFieldValues.options(value.map { (it as? JsonPrimitive)?.contentOrNull.orEmpty() })
                    else -> ""
                }
            }
        }.getOrDefault(emptyMap())
    }

    /** Like [bodyValues] but a body that is too big or not a JSON object is an error for the user. */
    fun formValues(body: String): Map<String, String> {
        if (body.length > MaxBodyChars) throw EventosValidationException(InvalidForm)
        if (runCatching { UiJson.parseToJsonElement(body).jsonObject }.getOrNull() == null) throw EventosValidationException(InvalidForm)
        return bodyValues(body)
    }

    fun uuid(text: String?): UUID? = text?.trim()?.takeIf { it.isNotEmpty() }?.let { runCatching { UUID.fromString(it) }.getOrNull() }

    // ---- permissions ----

    /** The permission needed to OPEN [screen] (GET). */
    fun viewPermission(screen: String): String = when (screen) {
        EventosScreens.ImportScreen -> EventosModule.Import
        "ajustes", "nuevo", "editar", "duplicar" -> EventosModule.Manage
        "asistencia" -> EventosModule.Register
        else -> EventosModule.View
    }

    /** The permission needed for the operation [op] of [screen] (POST); the service checks it again. */
    fun requiredPermission(screen: String, op: String?): String = when (screen) {
        EventosScreens.ImportScreen -> EventosModule.Import
        "ajustes", "nuevo", "editar", "duplicar" -> EventosModule.Manage
        "asistencia", "inscripcion" -> EventosModule.Register
        "evento" -> when (op) {
            "register" -> EventosModule.Register
            "status", "payment-add", "payment-delete" -> EventosModule.Manage
            else -> EventosModule.View
        }
        else -> EventosModule.View
    }

    // ---- filters ----

    fun isAll(text: String?): Boolean = text.isNullOrBlank() || text.trim().lowercase() in setOf("todos", "todas")

    /** The type asked for by label ("Públicos") or code ("publico"); null = both. */
    fun kindFilter(text: String?): EventKind? = when (text?.trim()?.lowercase()) {
        "públicos", "publicos", "publico", "público" -> EventKind.Public
        "privados", "privado" -> EventKind.Private
        else -> null
    }

    private fun picked(text: String?): String = if (isAll(text)) "" else text.orEmpty().trim()

    fun filter(params: Map<String, String>, from: Instant?, to: Instant?, statuses: List<EventStatus>, newestFirst: Boolean, limit: Int): EventFilter = EventFilter(
        from = from,
        to = to,
        kind = kindFilter(params["tipo"]),
        query = params["q"].orEmpty().trim().take(100),
        series = params["serie"].orEmpty().trim().take(EventosRules.MaxSeries),
        category = picked(params["categoria"]).take(EventosRules.MaxCategory),
        space = picked(params["espacio"]).take(EventosRules.MaxSpace),
        statuses = statuses,
        newestFirst = newestFirst,
        limit = limit,
    )

    // ---- numbers, dates and hours ----

    /** An amount typed by the user; blank is "none" and a text that is not a number is an error. */
    fun amount(text: String?, label: String): BigDecimal? {
        if (text.isNullOrBlank()) return null
        return EventosRules.parseMoney(text) ?: throw EventosValidationException("$label no es un monto válido.")
    }

    fun integer(text: String?, label: String): Int? {
        if (text.isNullOrBlank()) return null
        return text.trim().toIntOrNull() ?: throw EventosValidationException("$label debe ser un número entero.")
    }

    /** An ISO date (yyyy-MM-dd); blank is "no date". */
    fun date(text: String?): LocalDate? {
        val clean = text?.trim().orEmpty()
        if (clean.isEmpty()) return null
        return try {
            LocalDate.parse(clean)
        } catch (_: DateTimeParseException) {
            throw EventosValidationException("La fecha debe tener el formato AAAA-MM-DD.")
        }
    }

    /** An hour "HH:mm"; blank is "none". */
    fun time(text: String?): LocalTime? {
        val clean = text?.trim().orEmpty()
        if (clean.isEmpty()) return null
        return try {
            LocalTime.parse(clean, HourFormat)
        } catch (_: DateTimeParseException) {
            throw EventosValidationException("La hora debe tener el formato HH:mm, por ejemplo 18:30.")
        }
    }

    /** Date and hours of the form in the server's time zone: the start is required, the end is optional (same day). */
    fun schedule(dayText: String?, start: String?, end: String?, zone: ZoneId): Pair<Instant, Instant?> {
        val day = date(dayText) ?:throw EventosValidationException("Elige la fecha del evento.")
        val from = time(start) ?: throw EventosValidationException("Escribe la hora de inicio (HH:mm).")
        val to = time(end)
        return day.atTime(from).atZone(zone).toInstant() to to?.let { day.atTime(it).atZone(zone).toInstant() }
    }

    // ---- event form ----

    private fun choice(text: String?, none: String): String = text?.trim().orEmpty().takeIf { it.isNotEmpty() && it != none } ?: ""

    fun responsible(text: String?, users: List<EventosUser>): UUID? {
        val clean = text?.trim().orEmpty()
        if (clean.isEmpty() || clean == NoResponsible) return null
        return users.firstOrNull { it.name == clean }?.id ?: throw EventosValidationException("Responsable no válido: $clean.")
    }

    /** The event form to [EventInput]; what does not belong to [kind] is ignored. The service checks people and customer. */
    fun eventInput(kind: EventKind, values: Map<String, String>, users: List<EventosUser>, zone: ZoneId): EventInput {
        val (startsAt, endsAt) = schedule(values["date"], values["start"], values["end"], zone)
        val isPublic = kind == EventKind.Public
        val customerText = values["customer"]?.trim().orEmpty()
        val clientId = when {
            isPublic || customerText.isEmpty() -> null
            else -> uuid(values["customer_id"]) ?: throw EventosValidationException("Elige el cliente de la lista de sugerencias.")
        }
        return EventInput(
            kind = kind,
            title = values["title"].orEmpty().trim(),
            description = values["description"].orEmpty(),
            category = choice(values["category"], NoCategory),
            series = values["series"].orEmpty().trim(),
            space = choice(values["space"], NoSpace),
            startsAt = startsAt,
            endsAt = endsAt,
            capacity = if (isPublic) integer(values["capacity"], "El cupo") else null,
            hostName = values["host_name"].orEmpty().trim(),
            responsibleUserId = responsible(values["responsible"], users),
            priceMember = if (isPublic) amount(values["price_member"], "El precio de miembro") else null,
            pricePublic = if (isPublic) amount(values["price_public"], "El precio general") else null,
            clientCustomerId = clientId,
            agreedAmount = if (isPublic) null else amount(values["agreed_amount"], "El monto acordado"),
            guests = if (isPublic) null else integer(values["guests"], "Los invitados"),
            notes = values["notes"].orEmpty(),
        )
    }

    // ---- registration form ----

    /** The price type picked; "Automático" (or blank) = null, the one proposed by the rules. */
    fun priceKind(text: String?): PriceKind? {
        val clean = text?.trim().orEmpty()
        if (clean.isEmpty() || clean == EventosScreens.AutoPrice) return null
        return PriceKind.entries.firstOrNull { EventosScreens.priceLabel(it).equals(clean, ignoreCase = true) || it.code == clean.lowercase() }
            ?: throw EventosValidationException("Tipo de precio no válido: $clean.")
    }

    /** The registration form: an existing customer (hidden customer_id set by the suggestion) or the data of a new one. */
    fun registration(eventId: UUID, values: Map<String, String>): Pair<RegistrationInput, NewCustomerInput?> {
        val customerId = uuid(values["customer_id"])?.takeIf { values["customer"].orEmpty().isNotBlank() }
        val newName = values["new_name"].orEmpty().trim()
        val newCustomer = if (customerId == null && newName.isNotEmpty()) NewCustomerInput(newName, values["new_email"].orEmpty().trim(), values["new_phone"].orEmpty().trim()) else null
        if (customerId == null && newCustomer == null) {
            throw EventosValidationException(
                if (values["customer"].orEmpty().isNotBlank()) "Elige el cliente de la lista de sugerencias o escribe los datos de un cliente nuevo." else "Elige el cliente que se inscribe.",
            )
        }
        val input = RegistrationInput(
            eventId = eventId,
            customerId = customerId,
            seats = integer(values["seats"], "Las plazas") ?: 1,
            priceKind = priceKind(values["price_kind"]),
            amount = amount(values["amount"], "El monto"),
            paid = values["paid"] == "true",
            note = values["note"].orEmpty().trim(),
        )
        return input to newCustomer
    }
}

/** Builders of the forms of the module: new, edit and duplicate event, and settings. */
object EventosForms {
    private fun options(none: String, list: List<String>, current: String?): List<String> =
        (listOf(none) + list + listOfNotNull(current?.takeIf { it.isNotBlank() })).distinctBy { it.lowercase() }

    /** Fields of the event form of [kind]. [current] adds the saved category and space to the options if they left the settings. */
    fun eventFields(context: EventosScreens.Context, kind: EventKind, creating: Boolean, current: Event? = null): List<UiField> = buildList {
        val isPublic = kind == EventKind.Public
        add(UiField("title", "Título", required = true, section = "Evento"))
        add(UiField("description", "Descripción", type = "textarea", section = "Evento"))
        add(UiField("category", "Categoría", type = "dropdown", options = options(EventosActions.NoCategory, context.settings.categories, current?.category), section = "Evento"))
        add(UiField("series", "Serie", help = "Agrupa eventos que se repiten, por ejemplo Escape Sessions.", section = "Evento"))
        add(UiField("space", "Espacio", type = "dropdown", options = options(EventosActions.NoSpace, context.settings.spaces, current?.space), section = "Evento"))
        add(UiField("date", "Fecha", type = "date", required = true, section = "Cuándo"))
        add(UiField("start", "Hora de inicio", placeholder = "HH:mm", required = true, section = "Cuándo"))
        add(UiField("end", "Hora de fin", placeholder = "HH:mm", help = "Opcional; el mismo día.", section = "Cuándo"))
        add(UiField("host_name", if (isPublic) "Ponente o anfitrión" else "Anfitrión", section = "Equipo"))
        add(UiField("responsible", "Responsable", type = "dropdown", options = listOf(EventosActions.NoResponsible) + context.users.map { it.name }, section = "Equipo"))
        if (isPublic) {
            add(UiField("capacity", "Cupo", type = "number", help = "Vacío = sin límite.", section = "Cupo y precios"))
            add(UiField("price_member", "Precio de miembro", section = "Cupo y precios"))
            add(UiField("price_public", "Precio general", section = "Cupo y precios"))
        } else {
            add(
                UiField(
                    "customer", "Cliente que contrata", required = creating, placeholder = "Escribe para buscar",
                    help = "Elige un cliente de la lista de sugerencias.", suggest = UiSuggest(EventosScreens.CustomerSuggestRoute), section = "Contratante",
                ),
            )
            add(UiField("agreed_amount", "Monto acordado", section = "Contratante"))
            add(UiField("guests", "Invitados", type = "number", section = "Contratante"))
        }
        add(UiField("notes", "Notas", type = "textarea", section = "Notas"))
        if (creating) add(UiField("publish", if (isPublic) "Publicar al guardar" else "Confirmar al guardar", type = "boolean", section = "Estado"))
    }

    /** Values of the event form: the saved event or the defaults of a new one. */
    fun eventValues(context: EventosScreens.Context, kind: EventKind, event: Event?): Map<String, String> = buildMap {
        put("title", event?.title.orEmpty())
        put("description", event?.description.orEmpty())
        put("category", event?.category?.takeIf { it.isNotBlank() } ?: EventosActions.NoCategory)
        put("series", event?.series.orEmpty())
        put("space", event?.space?.takeIf { it.isNotBlank() } ?: EventosActions.NoSpace)
        putAll(timeValues(context.zone, event?.startsAt, event?.endsAt))
        put("host_name", event?.hostName.orEmpty())
        put("responsible", event?.responsibleUserId?.takeIf { id -> context.users.any { it.id == id } }?.let { context.userNameOf(it) } ?: EventosActions.NoResponsible)
        put("notes", event?.notes.orEmpty())
        if (kind == EventKind.Public) {
            put("capacity", event?.capacity?.toString().orEmpty())
            put("price_member", event?.priceMember?.let { EventosRules.money(it).toPlainString() }.orEmpty())
            put("price_public", event?.pricePublic?.let { EventosRules.money(it).toPlainString() }.orEmpty())
        } else {
            if (event?.clientCustomerId != null) {
                put("customer", event.clientName.ifBlank { "Cliente no disponible" })
                put("customer_id", event.clientCustomerId.toString())
            }
            put("agreed_amount", event?.agreedAmount?.let { EventosRules.money(it).toPlainString() }.orEmpty())
            put("guests", event?.guests?.toString().orEmpty())
        }
        if (event == null) put("publish", "false")
    }

    private fun EventosScreens.Context.userNameOf(id: UUID): String? = users.firstOrNull { it.id == id }?.name

    private fun timeValues(zone: ZoneId, startsAt: Instant?, endsAt: Instant?): Map<String, String> = buildMap {
        put("date", startsAt?.let { EventosRules.day(it, zone).toString() }.orEmpty())
        put("start", startsAt?.atZone(zone)?.toLocalTime()?.let { hour(it) }.orEmpty())
        put("end", endsAt?.atZone(zone)?.toLocalTime()?.let { hour(it) }.orEmpty())
    }

    private fun hour(time: LocalTime): String = "%02d:%02d".format(time.hour, time.minute)

    /** New event ([event] null) or edit of [event]. [submitted] is what the user typed when the last try failed. */
    fun eventForm(context: EventosScreens.Context, kind: EventKind, event: Event?, notice: UiNotice? = null, submitted: Map<String, String>? = null): UiScreen {
        val creating = event == null
        val values = eventValues(context, kind, event) + submitted.orEmpty()
        val submit = if (event == null) {
            UiButton("Crear", EventosScreens.post("nuevo", "create", mapOf("kind" to kind.code), formId = "event"))
        } else {
            UiButton("Guardar", EventosScreens.post("editar", "save", mapOf("id" to event.id.toString()), formId = "event"))
        }
        val title = when {
            event != null -> "Editar: ${event.title}"
            kind == EventKind.Public -> "Nuevo evento"
            else -> "Nuevo evento privado"
        }
        val selected = when {
            event != null -> "proximos"
            kind == EventKind.Public -> "nuevo"
            else -> "nuevo-privado"
        }
        val back = if (event == null) UiNavigate("proximos") else EventosScreens.open("evento", event.id)
        val body = listOf<UiNode>(
            UiCard(
                listOf(
                    UiForm("event", eventFields(context, kind, creating, event), values, submit = submit),
                    UiRow(listOf(UiButton("Volver", back, style = "secondary"))),
                ),
            ),
        )
        return EventosScreens.screen(context, title, selected, notice, body)
    }

    /** "Duplicar": the copy keeps everything except the state (draft), so only the new date and hours are asked. */
    fun duplicateForm(context: EventosScreens.Context, source: Event, notice: UiNotice? = null, submitted: Map<String, String>? = null): UiScreen {
        val fields = listOf(
            UiField("date", "Fecha del nuevo evento", type = "date", required = true),
            UiField("start", "Hora de inicio", placeholder = "HH:mm", required = true),
            UiField("end", "Hora de fin", placeholder = "HH:mm", help = "Opcional; el mismo día."),
        )
        val values = timeValues(context.zone, source.startsAt, source.endsAt) + ("date" to "") + submitted.orEmpty()
        val body = listOf<UiNode>(
            UiCard(
                listOf(
                    EventosScreens.hint("Se copia todo menos el estado (queda en borrador), las inscripciones, los abonos y el origen de la importación."),
                    UiForm(
                        "duplicate", fields, values,
                        submit = UiButton("Duplicar", EventosScreens.post("duplicar", "duplicate", mapOf("id" to source.id.toString()), formId = "duplicate")),
                    ),
                    UiRow(listOf(UiButton("Volver", EventosScreens.open("evento", source.id), style = "secondary"))),
                ),
                title = "Duplicar: ${source.title}",
            ),
        )
        return EventosScreens.screen(context, "Duplicar evento", "proximos", notice, body)
    }

    fun settingsForm(context: EventosScreens.Context, notice: UiNotice? = null, submitted: Map<String, String>? = null): UiScreen {
        val fields = listOf(
            UiField("currency", "Moneda", required = true, help = "Código ISO de 3 letras, por ejemplo USD. Se aplica a los eventos nuevos."),
            UiField("spaces", "Espacios", type = "textarea", help = "Uno por línea."),
            UiField("categories", "Categorías", type = "textarea", help = "Una por línea."),
        )
        val values = mapOf(
            "currency" to context.settings.currency,
            "spaces" to context.settings.spaces.joinToString("\n"),
            "categories" to context.settings.categories.joinToString("\n"),
        ) + submitted.orEmpty()
        val body = listOf<UiNode>(
            UiCard(
                listOf(UiForm("settings", fields, values, submit = UiButton("Guardar", EventosScreens.post("ajustes", "save", formId = "settings")))),
                title = "Ajustes de eventos",
            ),
        )
        return EventosScreens.screen(context, "Ajustes", "ajustes", notice, body)
    }
}
