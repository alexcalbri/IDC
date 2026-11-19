package com.ideasdeveloper.idc.modules.mantenimiento

import com.ideasdeveloper.idc.server.modules.ModuleAccessService
import com.ideasdeveloper.idc.server.modules.ModulePermissions
import com.ideasdeveloper.idc.server.modules.requireModuleAccess
import com.ideasdeveloper.idc.ui.UiButton
import com.ideasdeveloper.idc.ui.UiJson
import com.ideasdeveloper.idc.ui.UiNavigate
import com.ideasdeveloper.idc.ui.UiNode
import com.ideasdeveloper.idc.ui.UiNotice
import com.ideasdeveloper.idc.ui.UiRow
import com.ideasdeveloper.idc.ui.UiScreen
import com.ideasdeveloper.idc.ui.UiText
import com.ideasdeveloper.idc.ui.UiTextStyle
import com.ideasdeveloper.idc.ui.UiTone
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.receiveText
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import org.slf4j.LoggerFactory
import java.sql.Connection
import java.sql.SQLException
import java.util.UUID

@Serializable
data class MantenimientoErrorResponse(val code: String, val message: String)

/**
 * Abre la base de la empresa autorizada, ejecuta [block] en el dispatcher de IO y responde con su resultado en JSON. Una regla del módulo
 * que falla responde 400 con su mensaje; un error de base de datos responde 500.
 */
suspend fun <T> ApplicationCall.respondMantenimiento(access: ModuleAccessService, serializer: KSerializer<T>, block: (Connection) -> T) {
    val companyCode = access.companyCode(this)
        ?: return respond(HttpStatusCode.BadRequest, MantenimientoErrorResponse("MISSING_COMPANY", "La empresa es requerida."))
    try {
        // La conexión se abre y se cierra dentro del mismo withContext(IO).
        val result = withContext(Dispatchers.IO) { access.tenantConnectionForCompany(companyCode)?.use { tenant -> block(tenant) } }
            ?: return respond(HttpStatusCode.Forbidden, MantenimientoErrorResponse("TENANT_UNAVAILABLE", "No se pudo abrir la empresa."))
        respondText(UiJson.encodeToString(serializer, result), ContentType.Application.Json)
    } catch (failure: MantenimientoValidationException) {
        respond(HttpStatusCode.BadRequest, MantenimientoErrorResponse("invalid_request", failure.message.orEmpty()))
    } catch (failure: SQLException) {
        respond(HttpStatusCode.InternalServerError, MantenimientoErrorResponse("DATABASE_ERROR", "No se pudo completar la operación."))
    } catch (failure: CancellationException) {
        throw failure
    } catch (failure: Exception) {
        LoggerFactory.getLogger("MantenimientoRoutes").warn("Falló una operación de mantenimiento ({}).", failure.javaClass.simpleName)
        respond(HttpStatusCode.InternalServerError, MantenimientoErrorResponse("INTERNAL_ERROR", "No se pudo completar la operación."))
    }
}

/**
 * Rutas del módulo bajo [MantenimientoModule.UiRoute]: las pantallas nativas (GET y POST `/{screen}?op=`) y los archivos
 * (`POST /files`, `GET /files/{id}`). Las rutas literales de archivos ganan a `/{screen}`.
 */
object MantenimientoRoutes {
    private val log = LoggerFactory.getLogger(MantenimientoRoutes::class.java)
    const val ImportScreen = "importar"
    private val ScreenIds = setOf("upcoming", "mine", "orders", "order", "new-order", "assets", "asset", "plans", "plan", "admin", ImportScreen)

    /** La pantalla que sigue, con los parámetros que conserva. */
    private class Outcome(val screen: String, val params: Map<String, String>, val notice: UiNotice)

    private fun success(screen: String, params: Map<String, String>, text: String, tone: String = UiTone.Success) = Outcome(screen, params, UiNotice(text, tone))

    fun install(route: Route, access: ModuleAccessService) {
        route.route(MantenimientoModule.UiRoute) {
            MantenimientoFiles.install(this, access)
            screens(this, access)
        }
    }

    private fun screens(route: Route, access: ModuleAccessService) {
        route.get("/{screen}") {
            val permissions = call.requireModuleAccess(access, MantenimientoModule.id) ?: return@get
            val screen = call.parameters["screen"].orEmpty()
            if (screen !in ScreenIds) return@get call.respond(HttpStatusCode.NotFound)
            // El permiso mínimo se comprueba antes de abrir la conexión; las reglas finas por orden las aplica el servicio.
            if (!permissions.has(MantenimientoActions.requiredPermission(screen, null))) return@get call.forbidden()
            val params = call.request.queryParameters.entries().associate { it.key to it.value.firstOrNull().orEmpty() }
            call.respondMantenimiento(access, UiScreen.serializer()) { tenant ->
                if (screen == ImportScreen) importScreen(tenant, access, permissions, params, null)
                else open(tenant, access, permissions, screen, params, null, null)
            }
        }

        route.post("/{screen}") {
            val permissions = call.requireModuleAccess(access, MantenimientoModule.id) ?: return@post
            val screen = call.parameters["screen"].orEmpty()
            if (screen !in ScreenIds) return@post call.respond(HttpStatusCode.NotFound)
            val params = call.request.queryParameters.entries().associate { it.key to it.value.firstOrNull().orEmpty() }
            if (!permissions.has(MantenimientoActions.requiredPermission(screen, params["op"]))) return@post call.forbidden()
            val length = call.request.headers["Content-Length"]?.toLongOrNull()
            val values = try {
                if (length != null && length > 1_000_000L) throw MantenimientoValidationException(MantenimientoActions.InvalidForm)
                MantenimientoActions.formValues(call.receiveText())
            } catch (failure: MantenimientoValidationException) {
                return@post call.respond(HttpStatusCode.BadRequest, MantenimientoErrorResponse("invalid_request", failure.message.orEmpty()))
            }
            call.respondMantenimiento(access, UiScreen.serializer()) { tenant ->
                if (screen == ImportScreen) importAct(tenant, access, permissions, params, values)
                else act(tenant, access, permissions, screen, params, values)
            }
        }
    }

    private suspend fun ApplicationCall.forbidden() =
        respond(HttpStatusCode.Forbidden, MantenimientoErrorResponse("missing_permission", "No tienes permiso para esta acción."))

    // ---- cargar pantallas ----

    private fun context(tenant: Connection, permissions: ModulePermissions): MantenimientoScreens.Context = MantenimientoScreens.Context(
        scope = MantenimientoService.scope(permissions),
        users = MantenimientoRepository.assignableUsers(tenant),
        properties = MantenimientoRepository.listProperties(tenant),
        today = MantenimientoRepository.today(),
        importEnabled = permissions.has(MantenimientoModule.Import) && MantenimientoImportSources.enabled(tenant, permissions.companyCode).isNotEmpty(),
    )

    private fun uuid(params: Map<String, String>, key: String): UUID? = MantenimientoActions.uuid(params[key])

    private fun missing(what: String) = MantenimientoValidationException(what)

    /** Construye [screen]. Una regla que falla (una orden que ya no existe, una pantalla que no puede abrir) muestra "Próximos" con el mensaje. */
    private fun open(
        tenant: Connection,
        access: ModuleAccessService,
        permissions: ModulePermissions,
        screen: String,
        params: Map<String, String>,
        notice: UiNotice?,
        submitted: Map<String, String>?,
        loaded: MantenimientoScreens.Context? = null,
    ): UiScreen {
        val context = loaded ?: context(tenant, permissions)
        return try {
            build(tenant, access, permissions, context, screen, params, notice, submitted)
        } catch (failure: MantenimientoValidationException) {
            build(tenant, access, permissions, context, "upcoming", emptyMap(), UiNotice(failure.message.orEmpty(), UiTone.Danger), null)
        }
    }

    private fun build(
        tenant: Connection,
        access: ModuleAccessService,
        permissions: ModulePermissions,
        context: MantenimientoScreens.Context,
        screen: String,
        params: Map<String, String>,
        notice: UiNotice?,
        submitted: Map<String, String>?,
    ): UiScreen {
        val scope = context.scope
        return when (screen) {
            "mine" -> {
                // Sin usuario de la empresa (dueño del servidor) no hay "mías": mineOf nulo traería todo.
                val orders = scope.userId?.let {
                    MantenimientoRepository.listOrders(tenant, scope, OrderFilter(mineOf = it, limit = MantenimientoScreens.ListLimit + 1))
                }.orEmpty()
                MantenimientoScreens.mine(context, orders, notice)
            }

            "orders" -> {
                if (!scope.seesAll) throw missing("Solo quienes ejecutan o gestionan mantenimiento ven todas las órdenes. Revisa Mis órdenes.")
                val board = MantenimientoScreens.viewOf(params) == MantenimientoScreens.ViewBoard
                val filter = MantenimientoActions.orderFilter(params, context.users, context.properties, board, MantenimientoScreens.ListLimit + 1)
                val data = if (board) {
                    MantenimientoScreens.OrdersData(
                        columns = OrderStatus.entries.associateWith { status ->
                            MantenimientoRepository.listOrders(tenant, scope, filter.copy(status = status, limit = MantenimientoScreens.KanbanLimit + 1))
                        },
                    )
                } else {
                    MantenimientoScreens.OrdersData(orders = MantenimientoRepository.listOrders(tenant, scope, filter))
                }
                MantenimientoScreens.orders(context, data, params, notice)
            }

            "order" -> {
                val order = MantenimientoService.visibleOrder(tenant, scope, uuid(params, "id") ?: throw missing("Orden no encontrada."))
                val needsPurchase = order.purchaseRequestId != null || (scope.canWork(order) && order.status.isActive)
                MantenimientoScreens.order(
                    context,
                    MantenimientoScreens.OrderDetail(
                        order = order,
                        checklist = MantenimientoRepository.listChecklist(tenant, scope, order.id),
                        notes = MantenimientoRepository.listNotes(tenant, scope, order.id),
                        attachments = MantenimientoRepository.listAttachments(tenant, scope, order.id),
                        assets = MantenimientoService.assetOptions(tenant, full = scope.canEdit(order), current = order.assetId),
                        plan = order.planId?.let { MantenimientoRepository.getPlan(tenant, it) },
                        purchaseAvailable = needsPurchase && MantenimientoService.purchaseAvailable(tenant, access, permissions),
                    ),
                    notice, submitted,
                )
            }

            "new-order" -> MantenimientoScreens.newOrder(
                context, MantenimientoScreens.creatableKind(params["kind"]), MantenimientoService.assetOptions(tenant, full = true, current = null), notice, submitted,
            )

            "assets" -> {
                val property = MantenimientoActions.propertyFilter(params["property"], context.properties)
                val query = params["q"]?.trim().orEmpty()
                val assets = MantenimientoRepository.listAssets(tenant, property?.id, query, includeArchived = false, limit = MantenimientoScreens.ListLimit + 1)
                MantenimientoScreens.assets(context, assets, property, query, notice)
            }

            "asset" -> {
                val id = uuid(params, "id")
                val asset = id?.let { MantenimientoRepository.getAsset(tenant, it) ?: throw missing("Activo no encontrado.") }
                if (asset == null && !scope.canManage) throw missing("No tienes permiso para crear activos.")
                val history = asset?.let {
                    MantenimientoRepository.listOrders(tenant, scope, OrderFilter(assetId = it.id, sort = OrderSort.Newest, limit = MantenimientoScreens.HistoryLimit + 1))
                }.orEmpty()
                val plans = asset?.let { MantenimientoRepository.listPlans(tenant, assetId = it.id, limit = 50) }.orEmpty()
                MantenimientoScreens.asset(context, asset, history, plans, notice, submitted)
            }

            "plans" -> MantenimientoScreens.plans(context, MantenimientoRepository.listPlans(tenant, limit = MantenimientoScreens.ListLimit), notice)

            "plan" -> {
                val id = uuid(params, "id")
                val plan = id?.let { MantenimientoRepository.getPlan(tenant, it) ?: throw missing("Plan no encontrado.") }
                if (plan == null && !scope.canManage) throw missing("No tienes permiso para crear planes.")
                val checklist = plan?.let { MantenimientoRepository.planChecklist(tenant, it.id) }.orEmpty()
                val generated = plan?.let {
                    MantenimientoRepository.listOrders(tenant, scope, OrderFilter(planId = it.id, sort = OrderSort.Newest, limit = MantenimientoScreens.HistoryLimit + 1))
                }.orEmpty()
                val assets = MantenimientoService.assetOptions(tenant, full = scope.canManage, current = plan?.assetId)
                MantenimientoScreens.plan(context, plan, checklist, generated, assets, notice, submitted)
            }

            "admin" -> {
                if (!scope.canManage) throw missing("Solo quien gestiona mantenimiento puede abrir los ajustes.")
                MantenimientoAdminScreens.admin(
                    context,
                    MantenimientoRepository.listProperties(tenant, includeArchived = true),
                    MantenimientoRepository.settings(tenant),
                    MantenimientoService.tareasChoice(tenant, access, permissions),
                    notice,
                )
            }

            else -> {
                val property = MantenimientoActions.propertyFilter(params["property"], context.properties)
                val today = context.today
                MantenimientoScreens.upcoming(
                    context,
                    MantenimientoRepository.orderMetrics(tenant, scope, today, property?.id),
                    MantenimientoRepository.listOrders(tenant, scope, OrderFilter(activeOnly = true, propertyId = property?.id, limit = MantenimientoScreens.ListLimit + 1)),
                    MantenimientoRepository.listPlans(tenant, active = true, propertyId = property?.id, nextDueOnOrBefore = today.plusDays(30), limit = 50),
                    property, notice,
                )
            }
        }
    }

    // ---- acciones ----

    /** Parámetros de cada pantalla que se conservan después de una acción (el resto pertenece a la operación que acaba de correr). */
    private fun keep(screen: String, params: Map<String, String>): Map<String, String> = when (screen) {
        "orders" -> MantenimientoActions.filterParams(params) + params.filterKeys { it == "view" }
        "order", "asset", "plan" -> params.filterKeys { it == "id" }
        "new-order" -> params.filterKeys { it == "kind" }
        else -> emptyMap()
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
            perform(tenant, access, permissions, screen, params, values)
        } catch (failure: MantenimientoValidationException) {
            // La misma pantalla otra vez, con el mensaje y lo que el usuario escribió.
            return open(tenant, access, permissions, screen, keep(screen, params), UiNotice(failure.message.orEmpty(), UiTone.Danger), values, context)
        }
        return open(tenant, access, permissions, outcome.screen, outcome.params, outcome.notice, null, context)
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
        fun requireId(key: String): UUID = uuid(params, key) ?: throw missing("Falta el elemento sobre el que actuar.")
        fun orderOutcome(id: UUID, text: String) = success("order", mapOf("id" to id.toString()), text)

        return when (screen) {
            "orders" -> when (op) {
                "move" -> {
                    val target = OrderStatus.fromId(params["column"]) ?: throw missing("Estado no válido.")
                    val after = MantenimientoService.changeStatus(tenant, access, permissions, requireId("card"), target, emptyMap())
                    success("orders", keep(screen, params), "${after.displayNumber} pasó a ${after.status.label}.")
                }
                else -> throw missing("Acción no disponible.")
            }

            "order" -> {
                val id = requireId("id")
                when (op) {
                    "save" -> {
                        MantenimientoService.saveOrder(tenant, access, permissions, id, values)
                        orderOutcome(id, "Cambios guardados.")
                    }
                    "files" -> {
                        MantenimientoService.saveFiles(tenant, permissions, id, values)
                        orderOutcome(id, "Adjuntos actualizados.")
                    }
                    "status" -> {
                        val after = MantenimientoService.changeStatus(tenant, access, permissions, id, MantenimientoActions.status(params["status"]), values)
                        orderOutcome(id, "Estado: ${after.status.label}.")
                    }
                    "take" -> {
                        MantenimientoService.take(tenant, access, permissions, id)
                        orderOutcome(id, "Ahora eres responsable de la orden.")
                    }
                    "note" -> {
                        MantenimientoService.addNote(tenant, access, permissions, id, values["body"].orEmpty())
                        orderOutcome(id, "Nota añadida.")
                    }
                    "check-add" -> {
                        MantenimientoService.addCheck(tenant, permissions, id, values["text"].orEmpty())
                        orderOutcome(id, "Elemento añadido.")
                    }
                    "check-toggle" -> {
                        MantenimientoService.toggleCheck(tenant, permissions, id, requireId("item"), params["done"].toBoolean())
                        orderOutcome(id, "Lista actualizada.")
                    }
                    "check-remove" -> {
                        MantenimientoService.removeCheck(tenant, permissions, id, requireId("item"))
                        orderOutcome(id, "Elemento quitado.")
                    }
                    "purchase" -> orderOutcome(id, MantenimientoService.requestPurchase(tenant, access, permissions, id))
                    else -> throw missing("Acción no disponible.")
                }
            }

            "new-order" -> when (op) {
                "create" -> {
                    val kind = MantenimientoScreens.creatableKind(params["kind"])
                    val order = MantenimientoService.createOrder(tenant, access, permissions, kind, values)
                    orderOutcome(order.id, if (kind == OrderKind.Project) "Solicitud de proyecto enviada." else "Avería reportada.")
                }
                else -> throw missing("Acción no disponible.")
            }

            "asset" -> when (op) {
                "save" -> {
                    val saved = MantenimientoService.saveAsset(tenant, permissions, uuid(params, "id"), values)
                    success("asset", mapOf("id" to saved.toString()), "Activo guardado.")
                }
                "archive", "restore" -> {
                    val id = requireId("id")
                    MantenimientoService.archiveAsset(tenant, permissions, id, archived = op == "archive")
                    success("asset", mapOf("id" to id.toString()), if (op == "archive") "Activo archivado." else "Activo restaurado.")
                }
                else -> throw missing("Acción no disponible.")
            }

            "plan" -> {
                fun planOutcome(id: UUID, text: String) = success("plan", mapOf("id" to id.toString()), text)
                when (op) {
                    "save" -> planOutcome(MantenimientoService.savePlan(tenant, permissions, uuid(params, "id"), values), "Plan guardado.")
                    "active" -> {
                        val id = requireId("id")
                        val active = params["active"].toBoolean()
                        MantenimientoService.setPlanActive(tenant, permissions, id, active)
                        planOutcome(id, if (active) "Plan activado." else "Plan desactivado.")
                    }
                    "plan-check-add" -> {
                        val id = requireId("id")
                        MantenimientoService.changePlanChecklist(tenant, permissions, id, null) { items, _ -> items + values["text"].orEmpty() }
                        planOutcome(id, "Elemento añadido.")
                    }
                    "plan-check-remove" -> {
                        val id = requireId("id")
                        MantenimientoService.changePlanChecklist(tenant, permissions, id, requireId("item")) { items, index -> items.filterIndexed { n, _ -> n != index } }
                        planOutcome(id, "Elemento quitado.")
                    }
                    "plan-check-up", "plan-check-down" -> {
                        val id = requireId("id")
                        MantenimientoService.changePlanChecklist(tenant, permissions, id, requireId("item")) { items, index -> MantenimientoActions.moved(items, index, up = op == "plan-check-up") }
                        planOutcome(id, "Elemento movido.")
                    }
                    else -> throw missing("Acción no disponible.")
                }
            }

            "admin" -> when (op) {
                "prop-add" -> {
                    MantenimientoService.addProperty(tenant, permissions, values["name"].orEmpty())
                    success("admin", emptyMap(), "Propiedad añadida.")
                }
                "prop-rename" -> {
                    MantenimientoService.renameProperty(tenant, permissions, requireId("prop"), values["name"].orEmpty())
                    success("admin", emptyMap(), "Propiedad renombrada.")
                }
                "prop-up", "prop-down" -> {
                    MantenimientoService.moveProperty(tenant, permissions, requireId("prop"), up = op == "prop-up")
                    success("admin", emptyMap(), "Propiedad movida.")
                }
                "prop-archive", "prop-restore" -> {
                    MantenimientoService.archiveProperty(tenant, permissions, requireId("prop"), archived = op == "prop-archive")
                    success("admin", emptyMap(), if (op == "prop-archive") "Propiedad archivada." else "Propiedad restaurada.")
                }
                "settings-save" -> {
                    MantenimientoService.saveDueSoon(tenant, permissions, values["due_soon"])
                    success("admin", emptyMap(), "Ajustes guardados.")
                }
                "tareas-save" -> {
                    MantenimientoService.saveTareasBoard(tenant, access, permissions, values["board"])
                    success("admin", emptyMap(), "Integración con Tareas guardada.")
                }
                "tareas-off" -> {
                    MantenimientoService.turnOffTareas(tenant, permissions)
                    success("admin", emptyMap(), "Integración con Tareas apagada.")
                }
                else -> throw missing("Acción no disponible.")
            }

            else -> throw missing("Acción no disponible.")
        }
    }

    // ---- importación (las fuentes son extensiones: ver MantenimientoImportSource) ----

    private fun importContext(tenant: Connection, access: ModuleAccessService, permissions: ModulePermissions) = MantenimientoImportContext(
        access, tenant, permissions.companyCode, permissions, access.userIdentity(tenant, permissions)?.name ?: "Usuario", MantenimientoRepository.today(),
    )

    private fun errorBody(text: String) = listOf<UiNode>(UiText(text, UiTextStyle.Body, UiTone.Danger))

    /** "Importar": la pantalla de una fuente, o los botones para elegir entre varias. Pide mantenimiento.import (lo comprueba la ruta). */
    private fun importScreen(tenant: Connection, access: ModuleAccessService, permissions: ModulePermissions, params: Map<String, String>, notice: UiNotice?): UiScreen {
        val scope = MantenimientoService.scope(permissions)
        val sources = MantenimientoImportSources.enabled(tenant, permissions.companyCode)
        fun screen(body: List<UiNode>) = UiScreen(
            title = "Importar",
            navigation = MantenimientoScreens.navigation(scope, ImportScreen, import = sources.isNotEmpty()),
            notice = notice,
            body = body,
        )
        if (sources.isEmpty()) {
            return screen(listOf(UiText("No hay fuentes de importación activas. Instala y activa una extensión de importación de Mantenimiento (por ejemplo Monday) y vuelve aquí.")))
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
        } catch (failure: MantenimientoValidationException) {
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
        val source = MantenimientoImportSources.enabled(tenant, permissions.companyCode).firstOrNull { it.sourceId == params["source"] }
            ?: return importScreen(tenant, access, permissions, emptyMap(), UiNotice("Esa fuente de importación no está disponible.", UiTone.Danger))
        val reply = try {
            source.action(importContext(tenant, access, permissions), params["op"].orEmpty(), values)
        } catch (failure: MantenimientoValidationException) {
            MantenimientoImportReply(failure.message.orEmpty(), error = true)
        } catch (failure: CancellationException) {
            throw failure
        } catch (failure: Exception) {
            log.warn("Falló una operación de importación de {} ({}).", source.sourceId, failure.javaClass.simpleName)
            MantenimientoImportReply("No se pudo completar la operación.", error = true)
        }
        return importScreen(tenant, access, permissions, reply.params + ("source" to source.sourceId), UiNotice(reply.notice, if (reply.error) UiTone.Danger else UiTone.Success))
    }
}
