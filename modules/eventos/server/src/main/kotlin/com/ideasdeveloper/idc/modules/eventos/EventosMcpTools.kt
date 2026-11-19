package com.ideasdeveloper.idc.modules.eventos

import com.ideasdeveloper.idc.server.modules.McpSchema
import com.ideasdeveloper.idc.server.modules.McpTool
import com.ideasdeveloper.idc.server.modules.McpToolCall
import com.ideasdeveloper.idc.server.modules.McpToolException
import com.ideasdeveloper.idc.server.modules.ModuleMcp
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.time.ZoneId

/**
 * Agent tools of Eventos. Every write goes through [EventosService] (the same rules and permission checks as the screens) and
 * every read through [EventosRepository], so an agent sees and changes only what its user can. Customers are read with the
 * visibility filter of Clientes and never with e-mail or phone. The handlers run on the bridge's tool thread, which is where
 * the JDBC calls belong. No tool handles credentials.
 */
object EventosMcpTools {
    const val Instructions =
        "Eventos organiza los eventos de la empresa. Hay dos tipos: público (kind=publico: tiene cupo, precios, inscripciones y lista de espera) " +
            "y privado (kind=privado: tiene un contratante, un monto acordado y abonos; no tiene inscripciones). " +
            "Estados (códigos): borrador, publicado, lleno, realizado, cancelado; en un evento privado publicado significa Confirmado y borrador Tentativo. " +
            "Las plazas ocupadas son la suma de las plazas de las inscripciones en estado inscrito; las disponibles son el cupo menos las ocupadas " +
            "(sin cupo no hay límite). register_customer inscribe a un cliente de Clientes (customer_id; búscalo con las herramientas de Clientes): " +
            "si las plazas caben queda inscrito y si no queda en espera, y la respuesta lo dice. La lista de espera no avanza sola: " +
            "quien gestiona el evento decide a quién pasar a inscrito desde la app. Al inscribir se propone el precio de miembro si el cliente " +
            "tiene una membresía vigente y el evento tiene precio de miembro; price_kind (miembro, general, cortesia) y amount lo cambian. " +
            "Solo ves y cambias lo que los permisos del usuario permiten. No hay herramientas para crear o editar eventos: eso se hace en la app. " +
            "Antes de inscribir a varias personas muestra al usuario el resumen y espera su confirmación."

    private val statusCodes = RegistrationStatus.entries.map { it.code }
    private val priceKinds = PriceKind.entries.map { it.code }
    private const val MaxEvents = 100

    val tools: List<McpTool> = listOf(
        McpTool(
            name = "list_events",
            title = "Buscar eventos",
            description = "Lista eventos con su estado, fecha, espacio y cupo (ocupadas, disponibles, en espera). Sin from ni to devuelve los próximos desde hoy. " +
                "Filtra por tipo, serie o texto en título, serie, espacio y anfitrión. Máximo $MaxEvents.",
            permission = EventosModule.View,
            readOnly = true,
            inputSchema = McpSchema.obj(
                mapOf(
                    "from" to McpSchema.date("Eventos que empiezan desde este día."),
                    "to" to McpSchema.date("Eventos que empiezan hasta este día (incluido)."),
                    "kind" to McpSchema.string("Solo este tipo.", EventKind.entries.map { it.code }),
                    "q" to McpSchema.string("Texto a buscar."),
                    "series" to McpSchema.string("Solo eventos de esta serie (por ejemplo Escape Sessions)."),
                ),
            ),
        ) { call -> guarded { listEvents(call) } },
        McpTool(
            name = "get_event",
            title = "Ver evento",
            description = "Devuelve un evento con su descripción, cupo, ocupadas, en espera y asistieron; si es privado, el contratante, el monto acordado, lo pagado en abonos y el saldo.",
            permission = EventosModule.View,
            readOnly = true,
            inputSchema = McpSchema.obj(mapOf("event_id" to McpSchema.uuid("Id del evento.")), required = listOf("event_id")),
        ) { call -> guarded { getEvent(call) } },
        McpTool(
            name = "get_capacity",
            title = "Cupo de un evento",
            description = "Devuelve el cupo, las plazas ocupadas y disponibles, las personas en espera y si el evento está lleno.",
            permission = EventosModule.View,
            readOnly = true,
            inputSchema = McpSchema.obj(mapOf("event_id" to McpSchema.uuid("Id del evento.")), required = listOf("event_id")),
        ) { call -> guarded { getCapacity(call) } },
        McpTool(
            name = "list_registrations",
            title = "Inscripciones de un evento",
            description = "Lista las inscripciones de un evento público: nombre del cliente, plazas, estado, si pagó y si asistió. No incluye correo ni teléfono. " +
                "Por defecto inscritos y en espera; con status=cancelado, las canceladas.",
            permission = EventosModule.View,
            readOnly = true,
            inputSchema = McpSchema.obj(
                mapOf(
                    "event_id" to McpSchema.uuid("Id del evento."),
                    "status" to McpSchema.string("Solo este estado.", statusCodes),
                ),
                required = listOf("event_id"),
            ),
        ) { call -> guarded { listRegistrations(call) } },
        McpTool(
            name = "register_customer",
            title = "Inscribir a un cliente",
            description = "Inscribe a un cliente existente en un evento público publicado. Si las plazas caben queda inscrito; si no, en lista de espera (la respuesta lo indica). " +
                "Sin price_kind se propone el precio de miembro cuando corresponde; sin amount el monto es precio por plazas. Un cliente no puede inscribirse dos veces en el mismo evento. " +
                "Avisa al responsable del evento.",
            permission = EventosModule.Register,
            inputSchema = McpSchema.obj(
                mapOf(
                    "event_id" to McpSchema.uuid("Id del evento."),
                    "customer_id" to McpSchema.uuid("Id del cliente (de Clientes)."),
                    "seats" to McpSchema.integer("Plazas; por defecto 1.", 1, EventosRules.MaxSeats),
                    "price_kind" to McpSchema.string("Tipo de precio.", priceKinds),
                    "amount" to McpSchema.number("Monto total de la inscripción (por defecto precio por plazas; una cortesía es 0)."),
                    "paid" to McpSchema.boolean("Ya pagó (por defecto no)."),
                    "note" to McpSchema.string("Nota de la inscripción (hasta ${EventosRules.MaxRegistrationNote} caracteres)."),
                ),
                required = listOf("event_id", "customer_id"),
            ),
        ) { call -> guarded { registerCustomer(call) } },
        McpTool(
            name = "cancel_registration",
            title = "Cancelar inscripción",
            description = "Cancela una inscripción (inscrito o en espera). Si el evento estaba lleno vuelve a publicado y, si hay personas en espera, avisa al responsable; nadie pasa a inscrito solo.",
            permission = EventosModule.Register,
            inputSchema = McpSchema.obj(mapOf("registration_id" to McpSchema.uuid("Id de la inscripción.")), required = listOf("registration_id")),
        ) { call -> guarded { cancelRegistration(call) } },
        McpTool(
            name = "record_attendance",
            title = "Registrar asistencia",
            description = "Marca (attended=true, por defecto) o desmarca la asistencia de una persona inscrita. Solo desde el día del evento y si el evento no está cancelado.",
            permission = EventosModule.Register,
            inputSchema = McpSchema.obj(
                mapOf(
                    "registration_id" to McpSchema.uuid("Id de la inscripción."),
                    "attended" to McpSchema.boolean("true para marcar la asistencia, false para quitarla; por defecto true."),
                ),
                required = listOf("registration_id"),
            ),
        ) { call -> guarded { recordAttendance(call) } },
        McpTool(
            name = "set_registration_paid",
            title = "Marcar pago de una inscripción",
            description = "Marca (paid=true) o desmarca (paid=false) el pago de una inscripción.",
            permission = EventosModule.Register,
            inputSchema = McpSchema.obj(
                mapOf(
                    "registration_id" to McpSchema.uuid("Id de la inscripción."),
                    "paid" to McpSchema.boolean("true si pagó, false si no."),
                ),
                required = listOf("registration_id", "paid"),
            ),
        ) { call -> guarded { setRegistrationPaid(call) } },
    )

    // A getter: the tools of the import extensions are read on every request of the bridge (extensions are linked after this object loads).
    val mcp: ModuleMcp get() = ModuleMcp(Instructions, tools + EventosImportSources.all.flatMap { it.mcpTools })

    /** Runs a handler turning the module's validation errors into the agent's tool errors. */
    private fun guarded(block: () -> JsonElement): JsonElement = try {
        block()
    } catch (failure: EventosValidationException) {
        throw McpToolException(failure.message.orEmpty())
    }

    // ---- JSON (public and free of the database so they can be tested) ----

    /** The event as the agent sees it. [detailed] adds the description and the people numbers. */
    fun eventJson(item: EventWithStats, detailed: Boolean = false): JsonObject = buildJsonObject {
        val event = item.event
        val stats = item.stats
        put("id", event.id.toString())
        put("kind", event.kind.code)
        put("title", event.title)
        put("status", event.status.code)
        put("status_label", EventosRules.statusLabel(event.kind, event.status))
        put("starts_at", event.startsAt.toString())
        event.endsAt?.let { put("ends_at", it.toString()) }
        put("space", event.space)
        put("category", event.category)
        put("series", event.series)
        if (event.kind == EventKind.Public) {
            put("capacity", event.capacity)
            put("occupied", stats.occupied)
            put("available", EventosRules.available(event.capacity, stats.occupied))
            put("waiting", stats.waiting)
        }
        if (detailed) {
            put("description", event.description)
            put("host", event.hostName)
            put("currency", event.currency)
            put("notes", event.notes)
            if (event.kind == EventKind.Public) {
                put("price_member", event.priceMember)
                put("price_public", event.pricePublic)
                put("registered_people", stats.registrations)
                put("attended", stats.attended)
            } else {
                put("client_customer_id", event.clientCustomerId?.toString())
                put("client", event.clientName)
                put("agreed_amount", event.agreedAmount)
                put("guests", event.guests)
                put("paid_in_payments", stats.paymentsTotal)
                put("balance", EventosRules.balance(event.agreedAmount, listOf(stats.paymentsTotal)))
            }
            if (event.status == EventStatus.Cancelled) put("cancel_reason", event.cancelReason)
        }
    }

    /** A registration without e-mail or phone of the customer. */
    fun registrationJson(registration: Registration): JsonObject = buildJsonObject {
        put("registration_id", registration.id.toString())
        put("event_id", registration.eventId.toString())
        put("customer_id", registration.customerId.toString())
        put("customer", registration.customerName)
        put("status", registration.status.code)
        put("seats", registration.seats)
        put("price_kind", registration.priceKind.code)
        put("amount", registration.amount)
        put("currency", registration.eventCurrency)
        put("paid", registration.paid)
        put("attended", registration.attendedAt != null)
    }

    // ---- reads ----

    private fun listEvents(call: McpToolCall): JsonElement {
        val zone = ZoneId.systemDefault()
        val from = call.date("from")
        val to = call.date("to")
        val kind = call.string("kind", 20).takeIf { it.isNotEmpty() }?.let {
            EventKind.fromCode(it) ?: throw McpToolException("'kind' debe ser publico o privado (recibido '$it').")
        }
        val filter = EventFilter(
            from = (from ?: if (to == null) EventosRepository.today() else null)?.atStartOfDay(zone)?.toInstant(),
            to = to?.plusDays(1)?.atStartOfDay(zone)?.toInstant(),
            kind = kind,
            query = call.string("q", 100),
            series = call.string("series", EventosRules.MaxSeries),
            limit = MaxEvents,
        )
        val events = EventosRepository.listEvents(call.tenant, filter)
        return buildJsonObject {
            put("count", events.size)
            if (events.size >= MaxEvents) put("note", "Puede haber más eventos: acota las fechas o los filtros.")
            put("events", JsonArray(events.map { eventJson(it) }))
        }
    }

    private fun event(call: McpToolCall): EventWithStats =
        EventosRepository.getEvent(call.tenant, call.requiredUuid("event_id")) ?: throw McpToolException("Evento no encontrado.")

    private fun getEvent(call: McpToolCall): JsonElement = eventJson(event(call), detailed = true)

    private fun getCapacity(call: McpToolCall): JsonElement {
        val item = event(call)
        val event = item.event
        if (event.kind != EventKind.Public) throw McpToolException("Los eventos privados no tienen cupo.")
        return buildJsonObject {
            put("event_id", event.id.toString())
            put("title", event.title)
            put("status", event.status.code)
            put("capacity", event.capacity)
            put("unlimited", event.capacity == null)
            put("occupied", item.stats.occupied)
            put("available", EventosRules.available(event.capacity, item.stats.occupied))
            put("waiting", item.stats.waiting)
            put("full", EventosRules.isFull(event.capacity, item.stats.occupied))
        }
    }

    private fun listRegistrations(call: McpToolCall): JsonElement {
        val item = event(call)
        val status = call.string("status", 20).takeIf { it.isNotEmpty() }?.let {
            RegistrationStatus.fromCode(it) ?: throw McpToolException("'status' debe ser ${statusCodes.joinToString(", ")} (recibido '$it').")
        }
        val registrations = EventosRepository.listRegistrations(call.tenant, item.event.id, status)
        return buildJsonObject {
            put("event", item.event.title)
            put("count", registrations.size)
            put("registrations", buildJsonArray { registrations.forEach { add(registrationJson(it)) } })
        }
    }

    // ---- writes ----

    private fun registerCustomer(call: McpToolCall): JsonElement {
        val priceKind = call.string("price_kind", 20).takeIf { it.isNotEmpty() }?.let {
            PriceKind.fromCode(it) ?: throw McpToolException("'price_kind' debe ser ${priceKinds.joinToString(", ")} (recibido '$it').")
        }
        val input = RegistrationInput(
            eventId = call.requiredUuid("event_id"),
            customerId = call.requiredUuid("customer_id"),
            seats = call.int("seats", 1, 1..EventosRules.MaxSeats),
            priceKind = priceKind,
            amount = call.decimal("amount"),
            paid = call.boolean("paid", false),
            note = call.string("note", EventosRules.MaxRegistrationNote + 1),
        )
        val result = EventosService.register(call.tenant, call.access, call.permissions, input)
        return buildJsonObject {
            put("result", if (result.waitlisted) "El cliente quedó en la lista de espera: no hay plazas suficientes." else "El cliente quedó inscrito.")
            put("waitlisted", result.waitlisted)
            put("event_became_full", result.eventBecameFull)
            result.membership?.let { put("membership", EventosMembership.label(it)) }
            put("registration", registrationJson(result.registration))
        }
    }

    private fun cancelRegistration(call: McpToolCall): JsonElement {
        val registration = EventosService.cancelRegistration(call.tenant, call.access, call.permissions, call.requiredUuid("registration_id"))
        return buildJsonObject {
            put("result", "Inscripción cancelada.")
            put("registration", registrationJson(registration))
        }
    }

    private fun recordAttendance(call: McpToolCall): JsonElement {
        val attended = call.boolean("attended", true)
        val registration = EventosService.setAttendance(call.tenant, call.access, call.permissions, call.requiredUuid("registration_id"), attended)
        return buildJsonObject {
            put("result", if (attended) "Asistencia registrada." else "Asistencia quitada.")
            put("registration", registrationJson(registration))
        }
    }

    private fun setRegistrationPaid(call: McpToolCall): JsonElement {
        if (!call.has("paid")) throw McpToolException("El argumento 'paid' es obligatorio.")
        val paid = call.boolean("paid", false)
        val registration = EventosService.setPaid(call.tenant, call.permissions, call.requiredUuid("registration_id"), paid)
        return buildJsonObject {
            put("result", if (paid) "Pago marcado." else "Pago quitado.")
            put("registration", registrationJson(registration))
        }
    }
}
