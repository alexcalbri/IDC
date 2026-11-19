package com.ideasdeveloper.idc.modules.eventos

import com.ideasdeveloper.idc.server.modules.ModuleAccessService
import com.ideasdeveloper.idc.server.modules.ModulePermissions
import com.ideasdeveloper.idc.server.modules.requireModuleAccess
import com.ideasdeveloper.idc.ui.UiButton
import com.ideasdeveloper.idc.ui.UiNavigate
import com.ideasdeveloper.idc.ui.UiNode
import com.ideasdeveloper.idc.ui.UiNotice
import com.ideasdeveloper.idc.ui.UiRow
import com.ideasdeveloper.idc.ui.UiScreen
import com.ideasdeveloper.idc.ui.UiText
import com.ideasdeveloper.idc.ui.UiTextStyle
import com.ideasdeveloper.idc.ui.UiTone
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.receiveStream
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import java.sql.Connection
import java.time.DayOfWeek
import java.time.Instant
import java.util.UUID

/**
 * Native screens of the module: GET /{screen} and POST /{screen}?op=... under /modules/eventos/ui. The literal route
 * /suggest/customers is registered by EventosServerModule and wins over "/{screen}". Each route checks requireModuleAccess
 * and then the exact permission of the screen or operation (EventosActions) before the company database opens; the service
 * applies the fine rules again.
 */
object EventosRoutes {
    private val log = LoggerFactory.getLogger(EventosRoutes::class.java)
    private val ScreenIds = setOf("proximos", "pasados", "evento", "inscripcion", "asistencia", "nuevo", "editar", "duplicar", "ajustes", EventosScreens.ImportScreen)
    private const val ImportScreen = EventosScreens.ImportScreen
    private const val MaxBodyBytes = 1_000_000

    /** The screen to show next, with the query parameters it keeps. */
    private class Outcome(val screen: String, val params: Map<String, String>, val notice: UiNotice)

    private fun success(screen: String, params: Map<String, String>, text: String, tone: String = UiTone.Success) = Outcome(screen, params, UiNotice(text, tone))

    private fun idOf(id: UUID) = mapOf("id" to id.toString())

    fun install(route: Route, access: ModuleAccessService) {
        route.get("/{screen}") {
            val permissions = call.requireModuleAccess(access, EventosModule.id) ?: return@get
            val screen = call.parameters["screen"].orEmpty()
            if (screen !in ScreenIds) return@get call.respond(HttpStatusCode.NotFound)
            if (!permissions.has(EventosActions.viewPermission(screen))) return@get call.forbidden()
            val params = call.request.queryParameters.entries().associate { it.key to it.value.firstOrNull().orEmpty() }
            call.respondEventos(access, UiScreen.serializer()) { tenant ->
                if (screen == ImportScreen) importScreen(tenant, access, permissions, params, null) else open(tenant, permissions, screen, params, null, null)
            }
        }

        route.post("/{screen}") {
            val permissions = call.requireModuleAccess(access, EventosModule.id) ?: return@post
            val screen = call.parameters["screen"].orEmpty()
            if (screen !in ScreenIds) return@post call.respond(HttpStatusCode.NotFound)
            val params = call.request.queryParameters.entries().associate { it.key to it.value.firstOrNull().orEmpty() }
            // The minimum permission is checked before the connection opens; the service applies the fine rules after.
            if (!permissions.has(EventosActions.requiredPermission(screen, params["op"]))) return@post call.forbidden()
            // Hard 1 MB cap: the body is read up to the cap + 1 byte, so a chunked body without Content-Length cannot get past it.
            val bytes = withContext(Dispatchers.IO) { call.receiveStream().use { it.readNBytes(MaxBodyBytes + 1) } }
            if (bytes.size > MaxBodyBytes) {
                return@post call.respond(HttpStatusCode.PayloadTooLarge, EventosErrorResponse("payload_too_large", EventosActions.InvalidForm))
            }
            val values = try {
                EventosActions.formValues(String(bytes, Charsets.UTF_8))
            } catch (failure: EventosValidationException) {
                return@post call.respond(HttpStatusCode.BadRequest, EventosErrorResponse("invalid_request", failure.message.orEmpty()))
            }
            call.respondEventos(access, UiScreen.serializer()) { tenant ->
                if (screen == ImportScreen) importAct(tenant, access, permissions, params, values) else act(tenant, access, permissions, screen, params, values)
            }
        }
    }

    private suspend fun ApplicationCall.forbidden() =
        respond(HttpStatusCode.Forbidden, EventosErrorResponse("missing_permission", "No tienes permiso para esta accion."))

    // ---- loading screens ----

    private fun context(tenant: Connection, permissions: ModulePermissions): EventosScreens.Context {
        val caps = EventosScreens.Caps.of(permissions)
        return EventosScreens.Context(
            caps = caps,
            users = EventosRepository.users(tenant),
            settings = EventosRepository.settings(tenant),
            today = EventosRepository.today(),
            now = Instant.now(),
            importEnabled = caps.import && EventosImportSources.enabled(tenant, permissions.companyCode).isNotEmpty(),
        )
    }

    private fun missing(what: String) = EventosValidationException(what)

    private fun uuid(params: Map<String, String>, key: String): UUID? = EventosActions.uuid(params[key])

    private fun requireId(params: Map<String, String>, key: String = "id"): UUID = uuid(params, key) ?: throw missing("Falta el elemento sobre el que actuar.")

    private fun loadEvent(tenant: Connection, id: UUID): EventWithStats = EventosRepository.getEvent(tenant, id) ?: throw missing("Evento no encontrado.")

    private fun detail(tenant: Connection, id: UUID): EventosScreens.EventDetail {
        val item = loadEvent(tenant, id)
        val isPublic = item.event.kind == EventKind.Public
        return EventosScreens.EventDetail(
            event = item,
            registrations = if (isPublic) EventosRepository.listRegistrations(tenant, id, limit = EventosScreens.RosterLimit) else emptyList(),
            payments = if (isPublic) emptyList() else EventosRepository.listPayments(tenant, id),
        )
    }

    /** Builds [screen]. A rule that fails (an event that no longer exists) shows "Próximos" with the message. */
    private fun open(
        tenant: Connection,
        permissions: ModulePermissions,
        screen: String,
        params: Map<String, String>,
        notice: UiNotice?,
        submitted: Map<String, String>?,
        loaded: EventosScreens.Context? = null,
    ): UiScreen {
        val context = loaded ?: context(tenant, permissions)
        return try {
            build(tenant, context, screen, params, notice, submitted)
        } catch (failure: EventosValidationException) {
            build(tenant, context, "proximos", emptyMap(), UiNotice(failure.message.orEmpty(), UiTone.Danger), null)
        }
    }

    private fun build(
        tenant: Connection,
        context: EventosScreens.Context,
        screen: String,
        params: Map<String, String>,
        notice: UiNotice?,
        submitted: Map<String, String>?,
    ): UiScreen = when (screen) {
        "pasados" -> {
            val filter = EventFilter(statuses = listOf(EventStatus.Done, EventStatus.Cancelled), newestFirst = true, limit = EventosScreens.PastLimit + 1)
            EventosScreens.pasados(context, EventosRepository.listEvents(tenant, filter), notice)
        }
        "evento" -> EventosScreens.evento(context, detail(tenant, requireId(params)), notice, submitted)
        "inscripcion" -> {
            val registration = EventosRepository.getRegistration(tenant, requireId(params)) ?: throw missing("Inscripción no encontrada.")
            val event = loadEvent(tenant, registration.eventId).event
            val email = if (context.caps.register) EventosRepository.visibleCustomer(tenant, registration.customerId)?.email.orEmpty() else ""
            val membership = if (registration.status == RegistrationStatus.Cancelled) null else EventosMembership.activeMembership(tenant, registration.customerId)
            EventosScreens.inscripcion(context, EventosScreens.RegistrationDetail(registration, event, email, membership), notice)
        }
        "asistencia" -> {
            val event = loadEvent(tenant, requireId(params)).event
            if (event.kind != EventKind.Public) throw missing("Los eventos privados no tienen asistencia.")
            val people = EventosRepository.listRegistrations(tenant, event.id, RegistrationStatus.Registered, limit = EventosScreens.RosterLimit)
            EventosScreens.asistencia(context, event, people, notice)
        }
        "nuevo" -> {
            if (!context.caps.manage) throw missing("No tienes permiso para crear eventos.")
            EventosForms.eventForm(context, EventKind.fromCode(params["kind"]) ?: EventKind.Public, null, notice, submitted)
        }
        "editar" -> {
            if (!context.caps.manage) throw missing("No tienes permiso para editar eventos.")
            val event = loadEvent(tenant, requireId(params)).event
            EventosForms.eventForm(context, event.kind, event, notice, submitted)
        }
        "duplicar" -> {
            if (!context.caps.manage) throw missing("No tienes permiso para duplicar eventos.")
            EventosForms.duplicateForm(context, loadEvent(tenant, requireId(params)).event, notice, submitted)
        }
        "ajustes" -> {
            if (!context.caps.manage) throw missing("No tienes permiso para cambiar los ajustes.")
            EventosForms.settingsForm(context, notice, submitted)
        }
        else -> upcoming(tenant, context, params, notice)
    }

    private fun upcoming(tenant: Connection, context: EventosScreens.Context, params: Map<String, String>, notice: UiNotice?): UiScreen {
        val zone = context.zone
        val from = context.today.atStartOfDay(zone).toInstant()
        val to = context.today.with(DayOfWeek.MONDAY).plusWeeks(EventosScreens.AgendaWeeks.toLong()).atStartOfDay(zone).toInstant()
        val filter = EventosActions.filter(
            params, from, to,
            statuses = listOf(EventStatus.Draft, EventStatus.Published, EventStatus.Full),
            newestFirst = false,
            limit = EventosScreens.ListLimit + 1,
        )
        return EventosScreens.proximos(context, EventosRepository.listEvents(tenant, filter), EventosRepository.overview(tenant, context.now, zone), params, notice)
    }

    // ---- import screen (the sources are extensions: see EventosImportSource) ----

    private fun importContext(tenant: Connection, access: ModuleAccessService, permissions: ModulePermissions) = EventosImportContext(
        access, tenant, permissions.companyCode, permissions, access.userIdentity(tenant, permissions)?.name ?: "Usuario", EventosRepository.today(),
    )

    private fun errorBody(text: String) = listOf<UiNode>(UiText(text, UiTextStyle.Body, UiTone.Danger))

    /** "Importar": one source's screen, or the buttons to choose among several. Needs eventos.import (checked by the route). */
    private fun importScreen(tenant: Connection, access: ModuleAccessService, permissions: ModulePermissions, params: Map<String, String>, notice: UiNotice?): UiScreen {
        val caps = EventosScreens.Caps.of(permissions)
        val sources = EventosImportSources.enabled(tenant, permissions.companyCode)
        fun screen(body: List<UiNode>) = UiScreen(
            title = "Importar",
            navigation = EventosScreens.navigation(caps, ImportScreen, import = sources.isNotEmpty()),
            notice = notice,
            body = body,
        )
        if (sources.isEmpty()) {
            return screen(listOf(UiText("No hay fuentes de importación activas. Instala y activa una extensión de importación de Eventos (por ejemplo Monday) y vuelve aquí.")))
        }
        val chosen = sources.firstOrNull { it.sourceId == params["source"] } ?: sources.singleOrNull()
        val picker = if (sources.size > 1) {
            listOf<UiNode>(UiRow(sources.map { UiButton(it.title, UiNavigate(ImportScreen, mapOf("source" to it.sourceId)), style = if (it === chosen) "primary" else "secondary") }))
        } else {
            emptyList()
        }
        if (chosen == null) return screen(picker + UiText("Elige desde dónde quieres importar.", UiTextStyle.Caption))
        val body = try {
            chosen.screen(importContext(tenant, access, permissions), params)
        } catch (failure: EventosValidationException) {
            errorBody(failure.message.orEmpty())
        } catch (failure: CancellationException) {
            throw failure
        } catch (failure: Exception) {
            log.warn("No se pudo mostrar la fuente de importación {} ({}).", chosen.sourceId, failure.javaClass.simpleName)
            errorBody("No se pudo mostrar esta fuente de importación.")
        }
        return screen(picker + body)
    }

    private fun importAct(tenant: Connection, access: ModuleAccessService, permissions: ModulePermissions, params: Map<String, String>, values: Map<String, String>): UiScreen {
        val source = EventosImportSources.enabled(tenant, permissions.companyCode).firstOrNull { it.sourceId == params["source"] }
            ?: return importScreen(tenant, access, permissions, emptyMap(), UiNotice("Esa fuente de importación no está disponible.", UiTone.Danger))
        val reply = try {
            source.action(importContext(tenant, access, permissions), params["op"].orEmpty(), values)
        } catch (failure: EventosValidationException) {
            EventosImportReply(failure.message.orEmpty(), error = true)
        } catch (failure: CancellationException) {
            throw failure
        } catch (failure: Exception) {
            log.warn("Falló una operación de importación de {} ({}).", source.sourceId, failure.javaClass.simpleName)
            EventosImportReply("No se pudo completar la operación.", error = true)
        }
        return importScreen(tenant, access, permissions, reply.params + ("source" to source.sourceId), UiNotice(reply.notice, if (reply.error) UiTone.Danger else UiTone.Success))
    }

    // ---- actions ----

    /** Query parameters each screen keeps when it is shown again after an error. */
    private fun keep(screen: String, params: Map<String, String>): Map<String, String> {
        val keys = when (screen) {
            "evento", "inscripcion", "asistencia", "editar", "duplicar" -> setOf("id")
            "nuevo" -> setOf("kind")
            else -> emptySet()
        }
        return params.filterKeys { it in keys }
    }

    private fun act(
        tenant: Connection,
        access: ModuleAccessService,
        permissions: ModulePermissions,
        screen: String,
        params: Map<String, String>,
        values: Map<String, String>,
    ): UiScreen {
        val context = context(tenant, permissions)
        val outcome = try {
            perform(tenant, access, permissions, context, screen, params, values)
        } catch (failure: EventosValidationException) {
            // The same screen again with the message and what the user typed.
            return open(tenant, permissions, screen, keep(screen, params), UiNotice(failure.message.orEmpty(), UiTone.Danger), values, context)
        }
        return open(tenant, permissions, outcome.screen, outcome.params, outcome.notice, null, context)
    }

    private fun perform(
        tenant: Connection,
        access: ModuleAccessService,
        permissions: ModulePermissions,
        context: EventosScreens.Context,
        screen: String,
        params: Map<String, String>,
        values: Map<String, String>,
    ): Outcome {
        val op = params["op"].orEmpty()
        return when (screen) {
            "evento" -> {
                val id = requireId(params)
                when (op) {
                    "register" -> {
                        val (input, newCustomer) = EventosActions.registration(id, values)
                        val result = EventosService.register(tenant, access, permissions, input, newCustomer)
                        val (text, tone) = EventosScreens.registeredNotice(result)
                        success("evento", idOf(id), text, tone)
                    }
                    "status" -> {
                        val target = EventStatus.fromCode(params["status"]) ?: throw missing("Estado no válido.")
                        val event = EventosService.changeStatus(tenant, access, permissions, id, target, values["reason"].orEmpty().trim())
                        success("evento", idOf(id), "Evento ${EventosRules.statusLabel(event.kind, event.status).lowercase()}.")
                    }
                    "payment-add" -> {
                        val paidOn = EventosActions.date(values["pay_date"]) ?: context.today
                        val amount = EventosActions.amount(values["pay_amount"], "El abono") ?: throw missing("Escribe el monto del abono.")
                        EventosService.addPayment(tenant, access, permissions, id, paidOn, amount, values["pay_method"].orEmpty().trim(), values["pay_note"].orEmpty().trim())
                        success("evento", idOf(id), "Abono registrado.")
                    }
                    "payment-delete" -> {
                        EventosService.deletePayment(tenant, permissions, id, requireId(params, "payment"))
                        success("evento", idOf(id), "Abono borrado.")
                    }
                    else -> throw missing("Acción no disponible.")
                }
            }

            "inscripcion" -> {
                val id = requireId(params)
                val same = idOf(id)
                when (op) {
                    "paid", "unpaid" -> {
                        EventosService.setPaid(tenant, permissions, id, paid = op == "paid")
                        success("inscripcion", same, if (op == "paid") "Marcada como pagada." else "Marcada como no pagada.")
                    }
                    "attend", "unattend" -> {
                        EventosService.setAttendance(tenant, access, permissions, id, attended = op == "attend")
                        success("inscripcion", same, if (op == "attend") "Asistencia registrada." else "Asistencia quitada.")
                    }
                    "promote" -> {
                        EventosService.promote(tenant, access, permissions, id)
                        success("inscripcion", same, "Pasó a inscrito.")
                    }
                    "cancel" -> {
                        val cancelled = EventosService.cancelRegistration(tenant, access, permissions, id)
                        success("evento", idOf(cancelled.eventId), "Inscripción cancelada.")
                    }
                    else -> throw missing("Acción no disponible.")
                }
            }

            "asistencia" -> {
                val id = requireId(params)
                val registration = requireId(params, "registration")
                // The registration has to belong to the event of the screen (ids come from the request).
                if (EventosRepository.getRegistration(tenant, registration)?.eventId != id) throw missing("Esa inscripción no pertenece a este evento.")
                when (op) {
                    "attend", "unattend" -> {
                        val updated = EventosService.setAttendance(tenant, access, permissions, registration, attended = op == "attend")
                        success("asistencia", idOf(id), if (op == "attend") "${updated.customerName}: llegó." else "${updated.customerName}: asistencia quitada.")
                    }
                    else -> throw missing("Acción no disponible.")
                }
            }

            "nuevo" -> when (op) {
                "create" -> {
                    val kind = EventKind.fromCode(params["kind"]) ?: EventKind.Public
                    val input = EventosActions.eventInput(kind, values, context.users, context.zone)
                    val created = EventosService.createEvent(
                        tenant, access, permissions, input,
                        publish = values["publish"] == "true", requireClient = kind == EventKind.Private,
                    )
                    success("evento", idOf(created.id), "Evento creado.")
                }
                else -> throw missing("Acción no disponible.")
            }

            "editar" -> when (op) {
                "save" -> {
                    val id = requireId(params)
                    val kind = loadEvent(tenant, id).event.kind
                    EventosService.updateEvent(tenant, access, permissions, id, EventosActions.eventInput(kind, values, context.users, context.zone))
                    success("evento", idOf(id), "Cambios guardados.")
                }
                else -> throw missing("Acción no disponible.")
            }

            "duplicar" -> when (op) {
                "duplicate" -> {
                    val id = requireId(params)
                    val (startsAt, endsAt) = EventosActions.schedule(values["date"], values["start"], values["end"], context.zone)
                    val copy = EventosService.duplicateEvent(tenant, access, permissions, id, startsAt, endsAt)
                    success("evento", idOf(copy.id), "Evento duplicado como borrador.")
                }
                else -> throw missing("Acción no disponible.")
            }

            "ajustes" -> when (op) {
                "save" -> {
                    EventosService.saveSettings(
                        tenant, permissions, values["currency"].orEmpty(),
                        EventosRules.lines(values["spaces"].orEmpty()), EventosRules.lines(values["categories"].orEmpty()),
                    )
                    success("ajustes", emptyMap(), "Ajustes guardados.")
                }
                else -> throw missing("Acción no disponible.")
            }

            else -> throw missing("Acción no disponible.")
        }
    }
}
