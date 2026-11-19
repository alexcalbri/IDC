package com.ideasdeveloper.idc.modules.membresia

import com.ideasdeveloper.idc.server.modules.ModuleAccessErrorResponse
import com.ideasdeveloper.idc.server.modules.ModuleAccessService
import com.ideasdeveloper.idc.server.modules.ModulePermissions
import com.ideasdeveloper.idc.server.modules.requireModuleAccess
import com.ideasdeveloper.idc.ui.UiJson
import com.ideasdeveloper.idc.ui.UiNotice
import com.ideasdeveloper.idc.ui.UiScreen
import com.ideasdeveloper.idc.ui.UiSuggestions
import com.ideasdeveloper.idc.ui.UiTone
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.receiveStream
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import io.ktor.http.HttpHeaders
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.slf4j.LoggerFactory
import java.io.IOException
import java.math.BigDecimal
import java.sql.Connection
import java.sql.SQLException
import java.time.DateTimeException
import java.time.LocalDate
import java.util.UUID

/**
 * Rutas de las pantallas nativas. Cada ruta comprueba el permiso especifico despues de requireModuleAccess y todo el
 * JDBC corre en Dispatchers.IO (respondTenant).
 */
object MembresiaRoutes {
    private val log = LoggerFactory.getLogger(MembresiaRoutes::class.java)

    private const val MaxQueryLength = 80
    private const val MaxBodyLength = 100_000

    // Permiso que exige cada accion POST.
    private val actionPermission = mapOf(
        "checkin" to MembresiaModule.Visits,
        "guest" to MembresiaModule.Visits,
        "crear" to MembresiaModule.Manage,
        "renovar" to MembresiaModule.Manage,
        "editar" to MembresiaModule.Manage,
        "cancelar" to MembresiaModule.Manage,
        "plan-guardar" to MembresiaModule.Plans,
        "plan-activo" to MembresiaModule.Plans,
        "ajustes-guardar" to MembresiaModule.Plans,
    )

    fun install(route: Route, access: ModuleAccessService) {
        route.route(MembresiaModule.UiRoute) {
            // Sugerencias de la busqueda de recepcion (solo clientes con membresia).
            get("/suggest/members") {
                val permissions = call.requireModuleAccess(access, MembresiaModule.Id) ?: return@get
                if (!permissions.has(MembresiaModule.View)) return@get call.forbidden()
                val text = call.request.queryParameters["q"].orEmpty().trim().take(MaxQueryLength)
                call.respondTenant(access, UiSuggestions.serializer()) { tenant ->
                    if (text.length < 2) return@respondTenant UiSuggestions()
                    val today = LocalDate.now()
                    val expiringDays = MembresiaRepository.settings(tenant).expiringDays
                    MembresiaScreens.suggestions(MembresiaRepository.searchMembers(tenant, text, today, expiringDays, limit = 10, onlyMembers = true), today, expiringDays)
                }
            }

            // Sugerencias del cliente al crear una membresia (cualquier cliente visible).
            get("/suggest/customers") {
                val permissions = call.requireModuleAccess(access, MembresiaModule.Id) ?: return@get
                if (!permissions.has(MembresiaModule.Manage)) return@get call.forbidden()
                val text = call.request.queryParameters["q"].orEmpty().trim().take(MaxQueryLength)
                call.respondTenant(access, UiSuggestions.serializer()) { tenant ->
                    if (text.length < 2) return@respondTenant UiSuggestions()
                    val today = LocalDate.now()
                    val expiringDays = MembresiaRepository.settings(tenant).expiringDays
                    MembresiaScreens.suggestions(MembresiaRepository.searchMembers(tenant, text, today, expiringDays, limit = 10, onlyMembers = false), today, expiringDays)
                }
            }

            get("/{screen}") {
                val permissions = call.requireModuleAccess(access, MembresiaModule.Id) ?: return@get
                val rights = MembresiaAccess.from(permissions)
                val requested = call.parameters["screen"].orEmpty()
                val screen = if (requested == "home") MembresiaScreens.home(rights) ?: return@get call.forbidden() else requested
                if (!MembresiaScreens.isScreen(screen)) return@get call.respond(HttpStatusCode.NotFound, ModuleAccessErrorResponse("UNKNOWN_SCREEN", "Sección no encontrada."))
                if (!MembresiaScreens.allowed(rights, screen)) return@get call.forbidden()
                val params = call.request.queryParameters.entries().associate { it.key to it.value.firstOrNull().orEmpty() }
                call.respondTenant(access, UiScreen.serializer()) { tenant ->
                    render(tenant, rights, screen, params, today = LocalDate.now())
                }
            }

            post("/{screen}") {
                val permissions = call.requireModuleAccess(access, MembresiaModule.Id) ?: return@post
                val action = call.parameters["screen"].orEmpty()
                val required = actionPermission[action]
                    ?: return@post call.respond(HttpStatusCode.NotFound, ModuleAccessErrorResponse("UNKNOWN_ACTION", "Acción no encontrada."))
                if (!permissions.has(required)) return@post call.forbidden()
                val values = call.formValues() ?: return@post call.respond(HttpStatusCode.PayloadTooLarge, ModuleAccessErrorResponse("BODY_TOO_LARGE", "Los datos enviados son demasiado grandes."))
                call.respondTenant(access, UiScreen.serializer()) { tenant ->
                    perform(access, tenant, permissions, action, values, LocalDate.now())
                }
            }
        }
    }

    // ---------------------------------------------------------------- pantallas

    private fun notice(text: String, tone: String = UiTone.Info) = UiNotice(text, tone)

    private fun failure(text: String) = UiNotice(text, UiTone.Danger)

    private fun uuid(text: String?): UUID? = text?.trim()?.takeIf { it.length == 36 }?.let { runCatching { UUID.fromString(it) }.getOrNull() }

    private fun date(text: String?): LocalDate? = text?.trim()?.takeIf { it.isNotEmpty() }?.let { runCatching { LocalDate.parse(it) }.getOrNull() }

    /** Arma la pantalla pedida con los datos de la empresa; un id invalido o inexistente vuelve a la pantalla de inicio con un aviso. */
    private fun render(
        tenant: Connection,
        rights: MembresiaAccess,
        screen: String,
        params: Map<String, String>,
        today: LocalDate,
        notice: UiNotice? = null,
        values: Map<String, String>? = null,
        overrideReason: String? = null,
    ): UiScreen {
        val settings = MembresiaRepository.settings(tenant)
        val expiringDays = settings.expiringDays
        fun fallback(message: String): UiScreen {
            val home = MembresiaScreens.home(rights) ?: "recepcion"
            return render(tenant, rights, if (home == screen) "ajustes" else home, emptyMap(), today, failure(message))
        }
        return when (screen) {
            "recepcion" -> {
                val query = (params["q"] ?: params["name"]).orEmpty().trim().take(MaxQueryLength)
                val results = if (query.length >= 2) MembresiaRepository.searchMembers(tenant, query, today, expiringDays, limit = 20) else emptyList()
                MembresiaScreens.recepcion(
                    rights, query, results, MembresiaRepository.countsByStatus(tenant, today, expiringDays),
                    MembresiaRepository.visitsCountOn(tenant, today), today, expiringDays, notice,
                )
            }
            "miembro" -> {
                val customerId = uuid(params["customer"]) ?: return fallback("El miembro no es válido.")
                val detail = MembresiaRepository.member(tenant, customerId, today, expiringDays) ?: return fallback("El miembro no existe o no está disponible.")
                MembresiaScreens.miembro(rights, detail, today, expiringDays, notice, overrideReason)
            }
            "miembros" -> {
                val status = DisplayStatus.fromKey(params["estado"])
                MembresiaScreens.miembros(
                    rights, status, MembresiaRepository.memberships(tenant, status, today, expiringDays),
                    MembresiaRepository.countsByStatus(tenant, today, expiringDays), today, expiringDays, notice,
                )
            }
            "niveles" -> MembresiaScreens.niveles(rights, MembresiaRepository.activeByPlan(tenant, today), today, expiringDays, notice)
            "visitas" -> MembresiaScreens.visitas(rights, today, MembresiaRepository.visitsOn(tenant, today), notice)
            "nueva" -> {
                val customer = uuid(params["customer"])?.let { MembresiaRepository.visibleCustomer(tenant, it) }
                MembresiaScreens.nueva(
                    rights, MembresiaRepository.plans(tenant), settings, MembresiaRepository.hostCandidates(tenant),
                    values ?: MembresiaScreens.nuevaValues(today, customer), notice,
                )
            }
            "renovar" -> {
                val membership = uuid(params["id"])?.let { MembresiaRepository.membership(tenant, it) } ?: return fallback("La membresía no existe.")
                MembresiaScreens.renovar(rights, membership, MembresiaRepository.plans(tenant), settings, today, values, notice)
            }
            "editar" -> {
                val membership = uuid(params["id"])?.let { MembresiaRepository.membership(tenant, it) } ?: return fallback("La membresía no existe.")
                MembresiaScreens.editar(rights, membership, settings, MembresiaRepository.hostCandidates(tenant), values, notice)
            }
            "planes" -> {
                val plans = MembresiaRepository.plans(tenant, includeInactive = true)
                val editing = uuid(params["edit"] ?: params["id"])?.let { id -> plans.firstOrNull { it.id == id } }
                MembresiaScreens.planes(rights, plans, settings.currency, editing, values ?: MembresiaScreens.planValues(editing), notice)
            }
            "ingresos" -> {
                val rows = MembresiaRepository.revenueRows(tenant, today)
                MembresiaScreens.ingresos(rights, MembresiaRules.revenueByMonth(rows, today, 12, settings.currency), MembresiaRules.revenueByPlan(rows, today, 12, settings.currency), settings.currency, notice)
            }
            "ajustes" -> MembresiaScreens.ajustes(rights, values ?: MembresiaScreens.ajustesValues(settings), notice)
            else -> fallback("Sección no encontrada.")
        }
    }

    // ---------------------------------------------------------------- acciones

    private fun perform(
        access: ModuleAccessService,
        tenant: Connection,
        permissions: ModulePermissions,
        action: String,
        values: Map<String, String>,
        today: LocalDate,
    ): UiScreen {
        val rights = MembresiaAccess.from(permissions)
        fun show(screen: String, params: Map<String, String>, notice: UiNotice, form: Map<String, String>? = null, override: String? = null) =
            render(tenant, rights, screen, params, today, notice, form, override)
        fun member(customerId: UUID, notice: UiNotice, override: String? = null) =
            show("miembro", mapOf("customer" to customerId.toString()), notice, override = override)
        val author = MembresiaService.authorName(access, tenant, permissions)
        return try {
            when (action) {
                "checkin" -> checkIn(tenant, values, author, today, ::member, ::show)
                "guest" -> guest(tenant, values, author, today, ::member, ::show)
                "crear" -> create(access, tenant, values, author, today, ::member, ::show)
                "renovar" -> {
                    val id = uuid(values["id"]) ?: return show("recepcion", emptyMap(), failure("La membresía no es válida."))
                    val plans = MembresiaRepository.plans(tenant)
                    val settings = MembresiaRepository.settings(tenant)
                    val errors = mutableListOf<String>()
                    val plan = values["plan"].orEmpty().takeIf { it.isNotBlank() }?.let { label -> plans.firstOrNull { MembresiaScreens.planLabel(it, settings.currency) == label } }
                    if (values["plan"].orEmpty().isNotBlank() && plan == null) errors += "Elige un plan de la lista."
                    val startsOn = parseOptionalDate(values["startsOn"], "El inicio no es una fecha válida (AAAA-MM-DD).", errors)
                    val amount = parseOptionalAmount(values["amount"], errors)
                    if (errors.isNotEmpty()) return show("renovar", mapOf("id" to id.toString()), failure(errors.joinToString(" ")), values)
                    try {
                        val renewed = MembresiaService.renew(access, tenant, id, RenewalInput(plan?.id, startsOn, amount), author, today)
                            ?: return show("recepcion", emptyMap(), failure("Membresía no encontrada."))
                        member(renewed.customerId, notice("Membresía renovada: ${renewed.startsOn} a ${renewed.endsOn}.", UiTone.Success))
                    } catch (invalid: MembresiaValidationException) {
                        show("renovar", mapOf("id" to id.toString()), failure(invalid.errors.joinToString(" ")), values)
                    }
                }
                "editar" -> {
                    val id = uuid(values["id"]) ?: return show("recepcion", emptyMap(), failure("La membresía no es válida."))
                    val hosts = MembresiaRepository.hostCandidates(tenant)
                    val errors = mutableListOf<String>()
                    val endsOn = date(values["endsOn"]) ?: run { errors += "El fin no es una fecha válida (AAAA-MM-DD)."; null }
                    val amount = MembresiaRules.parseAmount(values["amount"]) ?: run { errors += "El monto no es válido."; null }
                    if (errors.isNotEmpty() || endsOn == null || amount == null) {
                        return show("editar", mapOf("id" to id.toString()), failure(errors.joinToString(" ")), values)
                    }
                    val currentMembership = MembresiaRepository.membership(tenant, id)
                    val host = hostOf(values["host"], hosts, currentMembership)
                    try {
                        val edited = MembresiaService.edit(
                            access, tenant, id,
                            MembershipEdit(endsOn, amount, values["source"].orEmpty().trim(), host?.userId, host?.name.orEmpty(), values["unit"].orEmpty(), values["notes"].orEmpty()),
                            today,
                        ) ?: return show("recepcion", emptyMap(), failure("Membresía no encontrada."))
                        member(edited.customerId, notice("Cambios guardados.", UiTone.Success))
                    } catch (invalid: MembresiaValidationException) {
                        show("editar", mapOf("id" to id.toString()), failure(invalid.errors.joinToString(" ")), values)
                    }
                }
                "cancelar" -> {
                    val id = uuid(values["id"]) ?: return show("recepcion", emptyMap(), failure("La membresía no es válida."))
                    try {
                        val cancelled = MembresiaService.cancel(access, tenant, id, values["reason"].orEmpty(), author)
                            ?: return show("recepcion", emptyMap(), failure("Membresía no encontrada o ya cancelada."))
                        member(cancelled.customerId, notice("Membresía cancelada.", UiTone.Success))
                    } catch (invalid: MembresiaValidationException) {
                        val membership = MembresiaRepository.membership(tenant, id)
                        if (membership == null) show("recepcion", emptyMap(), failure(invalid.errors.joinToString(" "))) else member(membership.customerId, failure(invalid.errors.joinToString(" ")))
                    }
                }
                "plan-guardar" -> savePlan(tenant, values, ::show)
                "plan-activo" -> {
                    val id = uuid(values["id"]) ?: return show("planes", emptyMap(), failure("El plan no es válido."))
                    val active = values["active"] == "true"
                    val changed = MembresiaRepository.setPlanActive(tenant, id, active)
                    if (!changed) show("planes", emptyMap(), failure("El plan no existe."))
                    else show("planes", emptyMap(), notice(if (active) "Plan activado." else "Plan desactivado.", UiTone.Success))
                }
                "ajustes-guardar" -> {
                    val days = values["expiringDays"].orEmpty().trim().toIntOrNull()
                    if (days == null) return show("ajustes", emptyMap(), failure("Los días de aviso deben ser un número."), values)
                    val settings = MembresiaSettings(values["currency"].orEmpty().trim().uppercase(), days, MembresiaRules.parseSources(values["sources"].orEmpty()))
                    try {
                        MembresiaRepository.saveSettings(tenant, settings)
                        show("ajustes", emptyMap(), notice("Ajustes guardados.", UiTone.Success))
                    } catch (invalid: MembresiaValidationException) {
                        show("ajustes", emptyMap(), failure(invalid.errors.joinToString(" ")), values)
                    }
                }
                else -> show("recepcion", emptyMap(), failure("Acción no encontrada."))
            }
        } catch (invalid: MembresiaValidationException) {
            // Cualquier validacion no prevista: se vuelve a la pantalla de inicio con el motivo.
            show(MembresiaScreens.home(rights) ?: "recepcion", emptyMap(), failure(invalid.errors.joinToString(" ")))
        }
    }

    private fun checkIn(
        tenant: Connection,
        values: Map<String, String>,
        author: String,
        today: LocalDate,
        member: (UUID, UiNotice, String?) -> UiScreen,
        show: (String, Map<String, String>, UiNotice, Map<String, String>?, String?) -> UiScreen,
    ): UiScreen {
        val customerId = uuid(values["customer"]) ?: return show("recepcion", emptyMap(), failure("El cliente no es válido."), null, null)
        return when (val outcome = MembresiaService.checkIn(tenant, customerId, values["override"] == "true", author, today)) {
            CheckInOutcome.NoMember -> show("recepcion", emptyMap(), failure("El cliente no existe o no está disponible."), null, null)
            CheckInOutcome.NoMembership -> member(customerId, notice("Este cliente aún no tiene membresía.", UiTone.Warning), null)
            is CheckInOutcome.Done -> when (val result = outcome.result) {
                is VisitResult.Recorded -> {
                    val exhausted = MembresiaRules.visitsLeft(outcome.membership) == 0
                    val text = when {
                        result.overridden -> "Visita registrada igual (con aviso)."
                        exhausted -> "Visita registrada. Era la última visita incluida en su plan."
                        else -> "Visita registrada"
                    }
                    member(customerId, notice(text, if (exhausted && !result.overridden) UiTone.Warning else UiTone.Success), null)
                }
                VisitResult.AlreadyToday -> member(customerId, notice("Ya tenía visita hoy", UiTone.Info), null)
                is VisitResult.NeedsOverride -> member(customerId, notice(result.reason, UiTone.Warning), result.reason)
                is VisitResult.Refused -> member(customerId, failure(result.reason), null)
            }
        }
    }

    private fun guest(
        tenant: Connection,
        values: Map<String, String>,
        author: String,
        today: LocalDate,
        member: (UUID, UiNotice, String?) -> UiScreen,
        show: (String, Map<String, String>, UiNotice, Map<String, String>?, String?) -> UiScreen,
    ): UiScreen {
        val customerId = uuid(values["customer"]) ?: return show("recepcion", emptyMap(), failure("El cliente no es válido."), null, null)
        return try {
            when (val outcome = MembresiaService.guestPass(tenant, customerId, values["guestName"].orEmpty(), author, today)) {
                GuestPassOutcome.NoMember -> show("recepcion", emptyMap(), failure("El cliente no existe o no está disponible."), null, null)
                GuestPassOutcome.NoMembership -> member(customerId, notice("Este cliente aún no tiene membresía.", UiTone.Warning), null)
                is GuestPassOutcome.Done -> when (val result = outcome.result) {
                    GuestPassResult.Recorded -> member(customerId, notice("Invitado registrado. Pases restantes: ${MembresiaRules.guestPassesLeft(outcome.membership)}.", UiTone.Success), null)
                    is GuestPassResult.Refused -> member(customerId, failure(result.reason), null)
                }
            }
        } catch (invalid: MembresiaValidationException) {
            member(customerId, failure(invalid.errors.joinToString(" ")), null)
        }
    }

    private fun create(
        access: ModuleAccessService,
        tenant: Connection,
        values: Map<String, String>,
        author: String,
        today: LocalDate,
        member: (UUID, UiNotice, String?) -> UiScreen,
        show: (String, Map<String, String>, UiNotice, Map<String, String>?, String?) -> UiScreen,
    ): UiScreen {
        val settings = MembresiaRepository.settings(tenant)
        val plans = MembresiaRepository.plans(tenant)
        val hosts = MembresiaRepository.hostCandidates(tenant)
        val errors = mutableListOf<String>()
        val plan = values["plan"].orEmpty().let { label -> plans.firstOrNull { MembresiaScreens.planLabel(it, settings.currency) == label } }
        if (plan == null) errors += "Elige un plan de la lista."
        val startsOn = parseOptionalDate(values["startsOn"], "El inicio no es una fecha válida (AAAA-MM-DD).", errors)
        val amount = parseOptionalAmount(values["amount"], errors)
        val existing = uuid(values["customerId"])
        val name = values["name"].orEmpty().trim()
        if (existing == null && name.isEmpty()) errors += "Escribe o elige el cliente."
        if (errors.isNotEmpty() || plan == null) return show("nueva", emptyMap(), failure(errors.joinToString(" ")), values, null)
        val host = hostOf(values["host"], hosts)
        val input = MembershipInput(
            customerId = existing ?: UUID(0L, 0L),
            planId = plan.id,
            startsOn = startsOn,
            amount = amount,
            source = values["source"].orEmpty().trim(),
            hostUserId = host?.userId,
            hostName = host?.name.orEmpty(),
            unit = values["unit"].orEmpty(),
            notes = values["notes"].orEmpty(),
        )
        val newCustomer = if (existing == null) NewCustomerInput(name, values["email"].orEmpty(), values["phone"].orEmpty()) else null
        return try {
            val created = MembresiaService.create(access, tenant, input, newCustomer, author, today)
            member(created.customerId, notice("Membresía creada: ${created.planName}, ${created.startsOn} a ${created.endsOn}.", UiTone.Success), null)
        } catch (invalid: MembresiaValidationException) {
            show("nueva", emptyMap(), failure(invalid.errors.joinToString(" ")), values, null)
        }
    }

    private fun savePlan(
        tenant: Connection,
        values: Map<String, String>,
        show: (String, Map<String, String>, UiNotice, Map<String, String>?, String?) -> UiScreen,
    ): UiScreen {
        val rawId = values["id"]
        val id = uuid(rawId)
        if (!rawId.isNullOrBlank() && id == null) return show("planes", emptyMap(), failure("El plan no es válido."), null, null)
        val errors = mutableListOf<String>()
        val price = MembresiaRules.parseAmount(values["price"]) ?: run { errors += "El precio no es válido."; null }
        val duration = values["durationDays"].orEmpty().trim().toIntOrNull() ?: run { errors += "La duración debe ser un número de días."; null }
        val includedText = values["includedVisits"].orEmpty().trim()
        val included = includedText.toIntOrNull()
        if (includedText.isNotEmpty() && included == null) errors += "Las visitas incluidas deben ser un número o quedar vacías."
        val passes = values["guestPasses"].orEmpty().trim().ifEmpty { "0" }.toIntOrNull() ?: run { errors += "Los pases de invitado deben ser un número."; null }
        val order = values["displayOrder"].orEmpty().trim().ifEmpty { "0" }.toIntOrNull() ?: run { errors += "El orden debe ser un número."; null }
        val formParams = id?.let { mapOf("edit" to it.toString()) }.orEmpty()
        if (errors.isNotEmpty() || price == null || duration == null || passes == null || order == null) {
            return show("planes", formParams, failure(errors.joinToString(" ")), values, null)
        }
        val input = PlanInput(
            name = values["name"].orEmpty(),
            description = values["description"].orEmpty(),
            price = price,
            durationDays = duration,
            includedVisits = included,
            guestPasses = passes,
            wifiAccess = values["wifi"] == "true",
            displayOrder = order,
        )
        return try {
            val saved = MembresiaRepository.savePlan(tenant, id, input)
            show("planes", emptyMap(), notice("Plan ${saved.name} guardado.", UiTone.Success), null, null)
        } catch (invalid: MembresiaValidationException) {
            show("planes", formParams, failure(invalid.errors.joinToString(" ")), values, null)
        }
    }

    private fun parseOptionalDate(text: String?, message: String, errors: MutableList<String>): LocalDate? {
        if (text.isNullOrBlank()) return null
        return date(text) ?: run { errors += message; null }
    }

    private fun parseOptionalAmount(text: String?, errors: MutableList<String>): BigDecimal? {
        if (text.isNullOrBlank()) return null
        return MembresiaRules.parseAmount(text) ?: run { errors += "El monto no es válido (hasta 2 decimales)."; null }
    }

    // Anfitrion elegido; si ya no es candidato pero es el actual de la membresia, se conserva (user id y nombre).
    private fun hostOf(name: String?, hosts: List<HostCandidate>, current: Membership? = null): HostCandidate? {
        val label = name?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        hosts.firstOrNull { it.name == label }?.let { return it }
        val kept = current?.takeIf { it.hostName == label && it.hostUserId != null } ?: return null
        return HostCandidate(kept.hostUserId!!, kept.hostName)
    }

    // ---------------------------------------------------------------- http

    private suspend fun ApplicationCall.forbidden() =
        respond(HttpStatusCode.Forbidden, ModuleAccessErrorResponse("missing_permission", "No tienes permiso para esta acción."))

    /**
     * Valores del formulario (cuerpo JSON) y parametros de la ruta; los parametros del boton mandan sobre el cuerpo.
     * Null si el cuerpo supera [MaxBodyLength] bytes (se comprueba Content-Length y, si falta, se lee con tope).
     */
    private suspend fun ApplicationCall.formValues(): Map<String, String>? {
        val declared = request.headers[HttpHeaders.ContentLength]?.toLongOrNull()
        if (declared != null && declared > MaxBodyLength) return null
        val bytes = try {
            withContext(Dispatchers.IO) { receiveStream().use { it.readNBytes(MaxBodyLength + 1) } }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: IOException) {
            ByteArray(0)
        }
        if (bytes.size > MaxBodyLength) return null
        val fromBody = try {
            UiJson.parseToJsonElement(bytes.toString(Charsets.UTF_8)).jsonObject
                .mapNotNull { (key, value) -> runCatching { value.jsonPrimitive.contentOrNull }.getOrNull()?.let { key to it } }.toMap()
        } catch (_: SerializationException) {
            emptyMap()
        } catch (_: IllegalArgumentException) {
            emptyMap()
        }
        val fromQuery = request.queryParameters.entries().associate { it.key to it.value.firstOrNull().orEmpty() }
        return fromBody + fromQuery
    }

    // Abre la base de la empresa autorizada, corre [block] en IO y responde su resultado como JSON.
    private suspend fun <T> ApplicationCall.respondTenant(access: ModuleAccessService, serializer: KSerializer<T>, block: (Connection) -> T) {
        val companyCode = access.companyCode(this)
            ?: return respond(HttpStatusCode.BadRequest, ModuleAccessErrorResponse("MISSING_COMPANY", "La empresa es requerida."))
        val connection = withContext(Dispatchers.IO) { access.tenantConnectionForCompany(companyCode) }
            ?: return respond(HttpStatusCode.Forbidden, ModuleAccessErrorResponse("TENANT_UNAVAILABLE", "No se pudo abrir la empresa."))
        try {
            val result = connection.use { tenant -> withContext(Dispatchers.IO) { block(tenant) } }
            respondText(UiJson.encodeToString(serializer, result), ContentType.Application.Json)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: SQLException) {
            log.error("Membresías: falló una pantalla de la empresa {}", companyCode, failure)
            respond(HttpStatusCode.InternalServerError, ModuleAccessErrorResponse("DATABASE_ERROR", "No se pudo completar la operación."))
        } catch (failure: DateTimeException) {
            log.warn("Membresías: fecha inválida en la empresa {}", companyCode, failure)
            respond(HttpStatusCode.BadRequest, ModuleAccessErrorResponse("INVALID_REQUEST", "Los datos enviados no son válidos."))
        } catch (failure: IllegalArgumentException) {
            log.warn("Membresías: datos inválidos en la empresa {}", companyCode, failure)
            respond(HttpStatusCode.BadRequest, ModuleAccessErrorResponse("INVALID_REQUEST", "Los datos enviados no son válidos."))
        } catch (failure: IllegalStateException) {
            log.error("Membresías: estado inesperado en la empresa {}", companyCode, failure)
            respond(HttpStatusCode.InternalServerError, ModuleAccessErrorResponse("UNEXPECTED_STATE", "No se pudo completar la operación."))
        }
    }
}
