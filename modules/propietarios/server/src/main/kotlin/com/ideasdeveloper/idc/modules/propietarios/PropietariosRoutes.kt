package com.ideasdeveloper.idc.modules.propietarios

import com.ideasdeveloper.idc.server.modules.ModuleAccessService
import com.ideasdeveloper.idc.server.modules.ModulePermissionLookup
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
import io.ktor.server.request.receiveText
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import java.sql.Connection
import java.util.UUID

/**
 * Pantallas nativas del modulo: GET y POST de cada pantalla bajo /modules/propietarios/ui. Las rutas literales (/files,
 * /files/{id}, /suggest/customers) las registra PropietariosServerModule y ganan a "/{screen}".
 * Cada GET y cada POST comprueban el permiso antes de abrir la conexion; el servicio aplica las reglas finas despues.
 */
object PropietariosRoutes {
    private val log = LoggerFactory.getLogger(PropietariosRoutes::class.java)
    private const val ImportScreen = "importar"
    private val ScreenIds = setOf("desarrollos", "desarrollo", "lote", "traspaso", "titularidad", "propietarios", "propietario", "buscar", ImportScreen)

    // Pantallas que muestran o usan clientes: solo ahi se consulta el permiso de Clientes.
    private val CustomerScreens = setOf("lote", "traspaso", "propietario", "propietarios")

    /** La pantalla que sigue, con los parametros que conserva. */
    private class Outcome(val screen: String, val params: Map<String, String>, val notice: UiNotice)

    private fun success(screen: String, params: Map<String, String>, text: String) = Outcome(screen, params, UiNotice(text, UiTone.Success))

    fun screens(route: Route, access: ModuleAccessService) {
        route.get("/{screen}") {
            val permissions = call.requireModuleAccess(access, PropietariosModule.id) ?: return@get
            val screen = call.parameters["screen"].orEmpty()
            // "importar" pide propietarios.import (antes de abrir la conexion); las demas pantallas piden propietarios.view.
            if (!permissions.has(if (screen == ImportScreen) PropietariosModule.Import else PropietariosModule.View)) return@get call.forbidden()
            if (screen !in ScreenIds) return@get call.respond(HttpStatusCode.NotFound)
            val params = call.request.queryParameters.entries().associate { it.key to it.value.firstOrNull().orEmpty() }
            if (screen == ImportScreen) {
                return@get call.respondPropietarios(access, UiScreen.serializer()) { tenant -> importScreen(tenant, access, permissions, params, null) }
            }
            // Las pantallas de escritura piden gestionar desde el GET: no se muestra un formulario que luego se rechaza.
            if ((screen == "traspaso" || screen == "titularidad") && !permissions.has(PropietariosModule.Manage)) return@get call.forbidden()
            val customers = if (screen in CustomerScreens) canSeeCustomers(access, call) else false
            call.respondPropietarios(access, UiScreen.serializer()) { tenant ->
                open(tenant, permissions, customers, screen, params, null, null)
            }
        }

        route.post("/{screen}") {
            val permissions = call.requireModuleAccess(access, PropietariosModule.id) ?: return@post
            val screen = call.parameters["screen"].orEmpty()
            if (screen !in ScreenIds) return@post call.respond(HttpStatusCode.NotFound)
            val params = call.request.queryParameters.entries().associate { it.key to it.value.firstOrNull().orEmpty() }
            if (!permissions.has(PropietariosActions.requiredPermission(screen, params["op"]))) return@post call.forbidden()
            val length = call.request.headers["Content-Length"]?.toLongOrNull()
            val values = try {
                if (length != null && length > 1_000_000L) throw PropietariosValidationException(PropietariosActions.InvalidForm)
                PropietariosActions.formValues(call.receiveText())
            } catch (failure: PropietariosValidationException) {
                return@post call.respond(HttpStatusCode.BadRequest, PropietariosErrorResponse("invalid_request", failure.message.orEmpty()))
            }
            if (screen == ImportScreen) {
                return@post call.respondPropietarios(access, UiScreen.serializer()) { tenant -> importAct(tenant, access, permissions, params, values) }
            }
            val customers = if (screen in CustomerScreens) canSeeCustomers(access, call) else false
            call.respondPropietarios(access, UiScreen.serializer()) { tenant ->
                act(tenant, access, permissions, customers, screen, params, values)
            }
        }
    }

    private suspend fun ApplicationCall.forbidden() =
        respond(HttpStatusCode.Forbidden, PropietariosErrorResponse("missing_permission", "No tienes permiso para esta accion."))

    /** Los clientes aparecen en los formularios solo para quien puede verlos (clientes.view). */
    private suspend fun canSeeCustomers(access: ModuleAccessService, call: ApplicationCall): Boolean {
        val lookup = withContext(Dispatchers.IO) {
            try {
                access.permissions(call, "clientes")
            } catch (failure: CancellationException) {
                throw failure
            } catch (failure: Exception) {
                log.warn("No se pudo leer el permiso de clientes ({}).", failure.javaClass.simpleName)
                null
            }
        }
        return lookup is ModulePermissionLookup.Granted && lookup.permissions.has("clientes.view")
    }

    // ---- carga de pantallas ----

    private fun context(tenant: Connection, permissions: ModulePermissions, customers: Boolean) = PropietariosScreens.Context(
        canManage = permissions.has(PropietariosModule.Manage),
        canAdmin = permissions.has(PropietariosModule.Admin),
        importEnabled = permissions.has(PropietariosModule.Import) && PropietariosImportSources.enabled(tenant, permissions.companyCode).isNotEmpty(),
        canSeeCustomers = customers,
        today = PropietariosRepository.today(),
    )

    // ---- importacion (las fuentes son extensiones: ver PropietariosImportSource) ----

    private fun importContext(tenant: Connection, access: ModuleAccessService, permissions: ModulePermissions) = PropietariosImportContext(
        access, tenant, permissions.companyCode, permissions, access.userIdentity(tenant, permissions)?.name ?: "Usuario", PropietariosRepository.today(),
    )

    private fun errorBody(text: String) = listOf<UiNode>(UiText(text, UiTextStyle.Body, UiTone.Danger))

    /** "Importar": la pantalla de una fuente, o los botones para elegir entre varias. Pide propietarios.import (lo comprueba la ruta). */
    private fun importScreen(tenant: Connection, access: ModuleAccessService, permissions: ModulePermissions, params: Map<String, String>, notice: UiNotice?): UiScreen {
        val context = context(tenant, permissions, customers = false)
        val sources = PropietariosImportSources.enabled(tenant, permissions.companyCode)
        fun screen(body: List<UiNode>) = UiScreen(
            title = "Importar",
            navigation = PropietariosScreens.navigation(context.copy(importEnabled = sources.isNotEmpty()), ImportScreen),
            notice = notice,
            body = body,
        )
        if (sources.isEmpty()) {
            return screen(listOf(UiText("No hay fuentes de importación activas. Instala y activa una extensión de importación de Propietarios (por ejemplo Monday) y vuelve aquí.")))
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
        } catch (failure: PropietariosValidationException) {
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
        val source = PropietariosImportSources.enabled(tenant, permissions.companyCode).firstOrNull { it.sourceId == params["source"] }
            ?: return importScreen(tenant, access, permissions, emptyMap(), UiNotice("Esa fuente de importación no está disponible.", UiTone.Danger))
        val reply = try {
            source.action(importContext(tenant, access, permissions), params["op"].orEmpty(), values)
        } catch (failure: PropietariosValidationException) {
            PropietariosImportReply(failure.message.orEmpty(), error = true)
        } catch (failure: CancellationException) {
            throw failure
        } catch (failure: Exception) {
            log.warn("Falló una operación de importación de {} ({}).", source.sourceId, failure.javaClass.simpleName)
            PropietariosImportReply("No se pudo completar la operación.", error = true)
        }
        return importScreen(tenant, access, permissions, reply.params + ("source" to source.sourceId), UiNotice(reply.notice, if (reply.error) UiTone.Danger else UiTone.Success))
    }

    private fun uuid(params: Map<String, String>, key: String): UUID? = PropietariosActions.uuid(params[key])

    private fun missing(what: String) = PropietariosValidationException(what)

    /** Construye [screen]. Una regla que falla (un lote que ya no existe, una pantalla que el usuario no puede abrir) muestra "Desarrollos" con el mensaje. */
    private fun open(
        tenant: Connection,
        permissions: ModulePermissions,
        customers: Boolean,
        screen: String,
        params: Map<String, String>,
        notice: UiNotice?,
        submitted: Map<String, String>?,
        loaded: PropietariosScreens.Context? = null,
    ): UiScreen {
        val context = loaded ?: context(tenant, permissions, customers)
        return try {
            build(tenant, context, screen, params, notice, submitted)
        } catch (failure: PropietariosValidationException) {
            build(tenant, context, "desarrollos", emptyMap(), UiNotice(failure.message.orEmpty(), UiTone.Danger), null)
        }
    }

    private fun build(
        tenant: Connection,
        context: PropietariosScreens.Context,
        screen: String,
        params: Map<String, String>,
        notice: UiNotice?,
        submitted: Map<String, String>?,
    ): UiScreen {
        val today = context.today
        return when (screen) {
            "desarrollo" -> {
                val development = uuid(params, "id")?.let { PropietariosRepository.getDevelopment(tenant, it) }?.takeIf { !it.archived } ?: throw missing("Desarrollo no encontrado.")
                val filter = LotFilter(
                    developmentId = development.id,
                    status = PropietariosActions.statusFilter(params["status"]),
                    query = params["q"]?.trim()?.take(200)?.takeIf { it.isNotEmpty() },
                    limit = PropietariosScreens.ListLimit + 1,
                )
                PropietariosScreens.development(
                    context,
                    PropietariosScreens.DevelopmentData(development, PropietariosRepository.statusTotals(tenant, development.id), PropietariosRepository.listLots(tenant, filter)),
                    params, notice, submitted,
                )
            }
            "lote" -> {
                val lotId = uuid(params, "id")
                if (lotId == null) {
                    // Formulario de un lote nuevo en un desarrollo.
                    if (!context.canManage) throw missing("No tienes permiso para crear lotes.")
                    val development = uuid(params, "development")?.let { PropietariosRepository.getDevelopment(tenant, it) }?.takeIf { !it.archived } ?: throw missing("Desarrollo no encontrado.")
                    PropietariosScreens.newLot(context, development, notice, submitted)
                } else {
                    val lot = PropietariosRepository.getLot(tenant, lotId) ?: throw missing("Lote no encontrado.")
                    PropietariosScreens.lot(
                        context,
                        PropietariosScreens.LotDetail(
                            lot,
                            PropietariosRepository.listOwnerships(tenant, lot.id, today),
                            PropietariosRepository.listHistory(tenant, lot.id, 30),
                            PropietariosRepository.listAttachments(tenant, lot.id),
                        ),
                        notice, submitted,
                    )
                }
            }
            "traspaso" -> {
                if (!context.canManage) throw missing("No tienes permiso para registrar cambios de titularidad.")
                val lot = uuid(params, "lot")?.let { PropietariosRepository.getLot(tenant, it) }?.takeIf { !it.archived } ?: throw missing("Lote no encontrado.")
                PropietariosScreens.transfer(context, lot, PropietariosRepository.listOwnerships(tenant, lot.id, today).filter { it.current }, notice, submitted)
            }
            "titularidad" -> {
                if (!context.canManage) throw missing("No tienes permiso para editar titularidades.")
                val ownership = uuid(params, "id")?.let { PropietariosRepository.getOwnership(tenant, it, today) } ?: throw missing("Titularidad no encontrada.")
                val lot = PropietariosRepository.getLot(tenant, ownership.lotId)?.takeIf { !it.archived } ?: throw missing("Lote no encontrado.")
                PropietariosScreens.ownership(context, ownership, lot, notice, submitted)
            }
            "propietarios" -> {
                val query = params["q"]?.trim()?.take(200).orEmpty()
                PropietariosScreens.owners(context, PropietariosRepository.listOwners(tenant, query, today, PropietariosScreens.ListLimit + 1), query, notice)
            }
            "propietario" -> {
                val customer = uuid(params, "id")?.let { PropietariosRepository.visibleCustomer(tenant, it) } ?: throw missing("Propietario no encontrado.")
                PropietariosScreens.owner(context, customer, PropietariosRepository.ownedLots(tenant, customer.id, today), notice)
            }
            "buscar" -> {
                val query = params["q"]?.trim()?.take(200).orEmpty()
                val lots = if (query.isEmpty()) emptyList() else PropietariosRepository.listLots(tenant, LotFilter(query = query, limit = PropietariosScreens.ListLimit + 1))
                PropietariosScreens.search(context, query, lots, notice)
            }
            else -> PropietariosScreens.developments(context, PropietariosRepository.listDevelopments(tenant, today), notice, submitted)
        }
    }

    // ---- acciones ----

    /** Parametros de consulta que cada pantalla conserva tras una accion (el resto pertenece a la operacion que acaba de correr). */
    private fun keep(screen: String, params: Map<String, String>): Map<String, String> {
        val keys = when (screen) {
            "desarrollo" -> setOf("id", "q", "status")
            "lote" -> setOf("id", "development")
            "traspaso" -> setOf("lot")
            "titularidad" -> setOf("id")
            else -> emptySet()
        }
        return params.filterKeys { it in keys }
    }

    private fun act(
        tenant: Connection,
        access: ModuleAccessService,
        permissions: ModulePermissions,
        customers: Boolean,
        screen: String,
        params: Map<String, String>,
        values: Map<String, String>,
    ): UiScreen {
        val context = context(tenant, permissions, customers)
        val outcome = try {
            perform(tenant, access, permissions, screen, params, values)
        } catch (failure: PropietariosValidationException) {
            // La misma pantalla otra vez con el mensaje y lo que el usuario escribio.
            return open(tenant, permissions, customers, screen, keep(screen, params), UiNotice(failure.message.orEmpty(), UiTone.Danger), values, context)
        }
        return open(tenant, permissions, customers, outcome.screen, outcome.params, outcome.notice, null, context)
    }

    private fun perform(
        tenant: Connection,
        access: ModuleAccessService,
        permissions: ModulePermissions,
        screen: String,
        params: Map<String, String>,
        values: Map<String, String>,
    ): Outcome {
        val op = params["op"].orEmpty()
        val actorId = permissions.userId
        val author by lazy { access.userIdentity(tenant, permissions)?.name ?: "Usuario" }
        fun requireId(key: String): UUID = uuid(params, key) ?: throw missing("Falta el elemento sobre el que actuar.")
        fun lotOutcome(id: UUID, text: String) = success("lote", mapOf("id" to id.toString()), text)

        return when (screen) {
            "desarrollos" -> when (op) {
                "dev-save" -> {
                    PropietariosService.require(permissions, PropietariosModule.Admin)
                    val id = PropietariosService.createDevelopment(tenant, PropietariosActions.developmentInput(values), actorId)
                    success("desarrollo", mapOf("id" to id.toString()), "Desarrollo creado.")
                }
                else -> throw missing("Acción no disponible.")
            }

            "desarrollo" -> {
                val id = requireId("id")
                when (op) {
                    "dev-save" -> {
                        PropietariosService.require(permissions, PropietariosModule.Admin)
                        PropietariosService.updateDevelopment(tenant, id, PropietariosActions.developmentInput(values))
                        success("desarrollo", mapOf("id" to id.toString()), "Desarrollo guardado.")
                    }
                    "dev-archive" -> {
                        PropietariosService.require(permissions, PropietariosModule.Admin)
                        PropietariosService.archiveDevelopment(tenant, id)
                        success("desarrollos", emptyMap(), "Desarrollo archivado.")
                    }
                    else -> throw missing("Acción no disponible.")
                }
            }

            "lote" -> when (op) {
                "save" -> {
                    PropietariosService.require(permissions, PropietariosModule.Manage)
                    val lotId = uuid(params, "id")
                    if (lotId == null) {
                        val development = requireId("development")
                        val created = PropietariosService.createLot(tenant, PropietariosActions.lotInput(development, values), author, actorId)
                        lotOutcome(created, "Lote creado.")
                    } else {
                        val lot = PropietariosRepository.getLot(tenant, lotId) ?: throw missing("Lote no encontrado.")
                        PropietariosService.updateLot(tenant, lotId, PropietariosActions.lotInput(lot.developmentId, values), author)
                        lotOutcome(lotId, "Cambios guardados.")
                    }
                }
                "files" -> {
                    PropietariosService.require(permissions, PropietariosModule.Manage)
                    val lotId = requireId("id")
                    PropietariosRepository.getLot(tenant, lotId)?.takeIf { !it.archived } ?: throw missing("Lote no encontrado.")
                    val changes = PropietariosActions.fileChanges(values, PropietariosRepository.listAttachments(tenant, lotId))
                    PropietariosRepository.transaction(tenant) {
                        if (changes.added.isNotEmpty()) PropietariosService.attachFiles(tenant, lotId, changes.added, actorId, author)
                        if (changes.removed.isNotEmpty()) PropietariosService.removeFiles(tenant, lotId, changes.removed, author)
                    }
                    lotOutcome(lotId, "Adjuntos actualizados.")
                }
                "add-owner" -> {
                    PropietariosService.require(permissions, PropietariosModule.Manage)
                    val lotId = requireId("id")
                    val result = PropietariosService.addOwner(tenant, lotId, PropietariosActions.coOwner(values), null, "", author, actorId)
                    PropietariosCustomerEvents.after(tenant, access, result, author)
                    lotOutcome(lotId, "Cotitular añadido.")
                }
                "archive" -> {
                    PropietariosService.require(permissions, PropietariosModule.Admin)
                    val lotId = requireId("id")
                    val developmentId = PropietariosRepository.getLot(tenant, lotId)?.developmentId ?: throw missing("Lote no encontrado.")
                    PropietariosService.archiveLot(tenant, lotId, author)
                    success("desarrollo", mapOf("id" to developmentId.toString()), "Lote archivado.")
                }
                else -> throw missing("Acción no disponible.")
            }

            "traspaso" -> when (op) {
                "transfer" -> {
                    PropietariosService.require(permissions, PropietariosModule.Manage)
                    val lotId = requireId("lot")
                    val date = PropietariosActions.date(values["date"]) ?: throw missing("Escribe la fecha del cambio.")
                    val newOwners = PropietariosActions.newOwners(values)
                    val result = PropietariosService.transfer(tenant, lotId, date, newOwners, values["notes"].orEmpty(), author, actorId)
                    PropietariosCustomerEvents.after(tenant, access, result, author)
                    lotOutcome(lotId, if (newOwners.isEmpty()) "Baja registrada." else "Cambio de titularidad registrado.")
                }
                else -> throw missing("Acción no disponible.")
            }

            "titularidad" -> when (op) {
                "save" -> {
                    PropietariosService.require(permissions, PropietariosModule.Manage)
                    val id = requireId("id")
                    val share = PropietariosActions.decimal(values["share"], "El porcentaje")
                    val result = PropietariosService.editOwnership(
                        tenant, id, share, PropietariosActions.date(values["starts_on"]), PropietariosActions.date(values["ends_on"]), values["notes"].orEmpty(), author,
                    )
                    PropietariosCustomerEvents.after(tenant, access, result, author)
                    lotOutcome(result.lotId, "Titularidad guardada.")
                }
                else -> throw missing("Acción no disponible.")
            }

            else -> throw missing("Acción no disponible.")
        }
    }
}
