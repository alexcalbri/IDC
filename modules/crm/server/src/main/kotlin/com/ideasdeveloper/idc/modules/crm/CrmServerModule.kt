package com.ideasdeveloper.idc.modules.crm

import com.ideasdeveloper.idc.server.modules.ModuleAccessService
import com.ideasdeveloper.idc.server.modules.ModuleDefinition
import com.ideasdeveloper.idc.server.modules.ModulePermissionDefinition
import com.ideasdeveloper.idc.server.modules.ModulePermissions
import com.ideasdeveloper.idc.server.modules.ServerModule
import com.ideasdeveloper.idc.server.modules.ServerModuleProvider
import com.ideasdeveloper.idc.server.modules.requireModuleAccess
import com.ideasdeveloper.idc.ui.ModuleUiEntry
import com.ideasdeveloper.idc.ui.UiJson
import com.ideasdeveloper.idc.ui.UiNotice
import com.ideasdeveloper.idc.ui.UiScreen
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.sql.Connection
import java.sql.SQLException
import java.util.UUID

object CrmServerModule : ServerModule {
    override val migrationPaths = listOf(
        "modules/crm/migrations/V001__create_crm.sql",
    )

    override val runtimeTables = listOf(
        "crm_settings",
        "crm_stages",
        "crm_interests",
        "crm_user_interests",
        "crm_leads",
        "crm_activity",
    )

    override val definition = ModuleDefinition(
        id = CrmModule.id,
        displayName = CrmModule.displayName,
        description = "Leads ligados a Clientes con pipeline de etapas, seguimientos, actividad e intereses opcionales que reparten los leads entre usuarios.",
        version = "0.1.0",
        permissions = listOf(
            ModulePermissionDefinition(CrmScreens.View, "Usar el CRM", "Permite ver el pipeline, los leads y los seguimientos de sus intereses o asignados."),
            ModulePermissionDefinition(CrmScreens.Manage, "Gestionar leads", "Permite crear y editar leads, moverlos de etapa y agregar notas."),
            ModulePermissionDefinition(CrmScreens.Delete, "Eliminar leads", "Permite eliminar leads (el cliente queda en Clientes)."),
            ModulePermissionDefinition(CrmScreens.Reception, "Recepcion de leads", "Permite ver y categorizar los leads que aun no tienen interes."),
            ModulePermissionDefinition(CrmScreens.SeeAll, "Ver todos los leads", "Permite ver los leads de todos los intereses y filtrarlos por interes."),
            ModulePermissionDefinition(CrmScreens.Settings, "Configurar CRM", "Permite cambiar etapas, intereses y los intereses de cada usuario."),
        ),
        ui = ModuleUiEntry(type = "native", entry = CrmScreens.UiRoute, home = "home"),
    )

    override fun routes(route: Route, access: ModuleAccessService) {
        route.route(CrmScreens.UiRoute) {
            get("/{screen}") {
                val permissions = call.requireModuleAccess(access, CrmModule.id) ?: return@get
                val screen = call.screenId(permissions) ?: return@get
                val params = call.request.queryParameters.entries().associate { it.key to it.value.firstOrNull().orEmpty() }
                call.respondScreen(access) { tenant -> CrmScreens.build(screen, CrmScreens.Context.load(tenant, permissions), params) }
            }

            post("/{screen}") {
                val permissions = call.requireModuleAccess(access, CrmModule.id) ?: return@post
                val screen = call.screenId(permissions) ?: return@post
                val params = call.request.queryParameters.entries().associate { it.key to it.value.firstOrNull().orEmpty() }
                val body = call.receiveText()
                val values = runCatching { UiJson.parseToJsonElement(body).jsonObject.mapValues { it.value.jsonPrimitive.contentOrNull.orEmpty() } }.getOrNull().orEmpty()
                val required = when (screen) {
                    "settings" -> CrmScreens.Settings
                    "lead" -> if (params["op"] == "delete") CrmScreens.Delete else CrmScreens.Manage
                    else -> CrmScreens.Manage
                }
                if (!permissions.has(required)) {
                    return@post call.respond(HttpStatusCode.Forbidden, CrmErrorResponse("missing_permission", "No tienes permiso para esta accion."))
                }
                call.respondScreen(access) { tenant -> action(tenant, permissions, screen, params, values) }
            }
        }
    }

    private fun action(tenant: Connection, permissions: ModulePermissions, screen: String, params: Map<String, String>, values: Map<String, String>): UiScreen {
        val context = CrmScreens.Context.load(tenant, permissions)
        val actor = CrmRepository.actor(tenant, permissions)
        fun show(target: String, notice: UiNotice, targetParams: Map<String, String> = params - "op", submitted: Map<String, String>? = null) =
            CrmScreens.build(target, CrmScreens.Context.load(tenant, permissions), targetParams, notice, submitted)
        fun uuid(key: String) = params[key]?.let { runCatching { UUID.fromString(it) }.getOrNull() }
        fun visibleLead(key: String) = uuid(key)?.let { CrmRepository.lead(tenant, it) }?.takeIf { context.scope.canSee(it.interestId, it.ownerUserId) }

        return try {
            when ("$screen:${params["op"]}") {
                "pipeline:move" -> {
                    val lead = visibleLead("card") ?: return show("pipeline", UiNotice("Ese lead ya no esta disponible.", UiTone.Warning), params - setOf("op", "card", "column"))
                    val stage = uuid("column")?.let { context.stage(it) } ?: return show("pipeline", UiNotice("Esa etapa ya no existe.", UiTone.Warning), params - setOf("op", "card", "column"))
                    CrmRepository.moveLead(tenant, lead, stage, context.stages, actor)
                    show("pipeline", UiNotice("${lead.name} paso a ${stage.name}.", UiTone.Success), params - setOf("op", "card", "column"))
                }
                "new:create" -> {
                    val input = try {
                        CrmForms.parseLead(values, context.stages, context.interests, context.interestsEnabled, context.users)
                    } catch (failure: CrmValidationException) {
                        return show("new", UiNotice(failure.message.orEmpty(), UiTone.Danger), emptyMap(), values)
                    }
                    if (!context.scope.canUseInterest(input.interestId)) {
                        return show("new", UiNotice("No puedes crear leads de ese interes.", UiTone.Danger), emptyMap(), values)
                    }
                    val stage = context.stage(input.stageId)!!
                    val id = CrmRepository.createLead(tenant, input, stage, actor)
                    show("lead", UiNotice("Lead creado.", UiTone.Success), mapOf("id" to id.toString()))
                }
                "lead:save" -> {
                    val lead = visibleLead("id") ?: return show("leads", UiNotice("Ese lead ya no esta disponible.", UiTone.Warning), emptyMap())
                    val input = try {
                        CrmForms.parseLead(values, context.stages, context.interests, context.interestsEnabled, context.users)
                    } catch (failure: CrmValidationException) {
                        return show("lead", UiNotice(failure.message.orEmpty(), UiTone.Danger), submitted = values)
                    }
                    if (input.interestId != lead.interestId && !context.scope.canUseInterest(input.interestId)) {
                        return show("lead", UiNotice("No puedes asignar ese interes.", UiTone.Danger), submitted = values)
                    }
                    CrmRepository.updateLead(tenant, lead, input, context.stages, context.names, actor)
                    if (context.scope.canSee(input.interestId, input.ownerUserId)) {
                        show("lead", UiNotice("Cambios guardados.", UiTone.Success))
                    } else {
                        show("leads", UiNotice("Cambios guardados. El lead paso a un interes que no ves.", UiTone.Success), emptyMap())
                    }
                }
                "lead:note" -> {
                    val lead = visibleLead("id") ?: return show("leads", UiNotice("Ese lead ya no esta disponible.", UiTone.Warning), emptyMap())
                    CrmRepository.addNote(tenant, lead.id, CrmForms.note(values), actor)
                    show("lead", UiNotice("Nota agregada.", UiTone.Success))
                }
                "lead:delete" -> {
                    val lead = visibleLead("id") ?: return show("leads", UiNotice("Ese lead ya no esta disponible.", UiTone.Warning), emptyMap())
                    CrmRepository.deleteLead(tenant, lead.id)
                    show("leads", UiNotice("Lead ${lead.name} eliminado.", UiTone.Success), emptyMap())
                }
                "settings:interests" -> {
                    val enabled = values["enabled"] == "true"
                    CrmRepository.setInterestsEnabled(tenant, enabled)
                    show("settings", UiNotice(if (enabled) "Intereses activados." else "Intereses desactivados: todos los usuarios del CRM ven todos los leads.", UiTone.Success), emptyMap())
                }
                "settings:interestSave" -> {
                    val name = try {
                        CrmForms.parseInterestName(values)
                    } catch (failure: CrmValidationException) {
                        return show("settings", UiNotice(failure.message.orEmpty(), UiTone.Danger), params - "op", values)
                    }
                    if (context.interests.any { it.name.equals(name, ignoreCase = true) && it.id != uuid("id") }) {
                        return show("settings", UiNotice("Ya existe el interes $name.", UiTone.Danger), params - "op", values)
                    }
                    CrmRepository.saveInterest(tenant, uuid("id"), name)
                    show("settings", UiNotice("Interes $name guardado.", UiTone.Success), emptyMap())
                }
                "settings:interestDelete" -> {
                    val deleted = uuid("id")?.let { CrmRepository.deleteInterest(tenant, it) } == true
                    show("settings", UiNotice(if (deleted) "Interes eliminado; sus leads estan en Recepcion." else "Ese interes ya no existe.", if (deleted) UiTone.Success else UiTone.Warning), emptyMap())
                }
                "settings:userInterests" -> {
                    val user = uuid("user")?.let { id -> context.users.firstOrNull { it.id == id } }
                        ?: return show("settings", UiNotice("Ese usuario ya no tiene acceso al CRM.", UiTone.Warning), emptyMap())
                    val chosen = context.interests.filter { values[it.id.toString()] == "true" }.map { it.id }.toSet()
                    CrmRepository.setUserInterests(tenant, user.id, chosen)
                    show("settings", UiNotice("Intereses de ${user.name} guardados.", UiTone.Success), emptyMap())
                }
                "settings:stageSave" -> {
                    val input = try {
                        CrmForms.parseStage(values)
                    } catch (failure: CrmValidationException) {
                        return show("settings", UiNotice(failure.message.orEmpty(), UiTone.Danger), params - "op", values)
                    }
                    if (context.stages.any { it.name.equals(input.name, ignoreCase = true) && it.id != uuid("id") }) {
                        return show("settings", UiNotice("Ya existe la etapa ${input.name}.", UiTone.Danger), params - "op", values)
                    }
                    CrmRepository.saveStage(tenant, uuid("id"), input)
                    show("settings", UiNotice("Etapa ${input.name} guardada.", UiTone.Success), emptyMap())
                }
                "settings:stageDelete" -> {
                    val id = uuid("id") ?: return show("settings", UiNotice("Etapa invalida.", UiTone.Danger), emptyMap())
                    if (context.stages.size <= 1) return show("settings", UiNotice("El pipeline necesita al menos una etapa.", UiTone.Warning), emptyMap())
                    val deleted = CrmRepository.deleteStage(tenant, id)
                    show("settings", UiNotice(if (deleted) "Etapa eliminada." else "Esa etapa ya no existe.", if (deleted) UiTone.Success else UiTone.Warning), emptyMap())
                }
                "settings:template" -> {
                    val template = CrmTemplates.find(params["template"].orEmpty()) ?: return show("settings", UiNotice("Plantilla desconocida.", UiTone.Danger), emptyMap())
                    CrmRepository.applyTemplate(tenant, template)
                    show("settings", UiNotice("Etapas de ${template.label} aplicadas.", UiTone.Success), emptyMap())
                }
                else -> show(screen, UiNotice("Accion desconocida.", UiTone.Danger))
            }
        } catch (failure: CrmValidationException) {
            show(screen, UiNotice(failure.message.orEmpty(), UiTone.Danger))
        }
    }

    private suspend fun ApplicationCall.screenId(permissions: ModulePermissions): String? {
        val requested = parameters["screen"].orEmpty()
        val screen = if (requested == "home") CrmScreens.home(permissions) else requested.takeIf { CrmScreens.isScreen(it) }
        if (screen == null) respond(HttpStatusCode.NotFound, CrmErrorResponse("UNKNOWN_SCREEN", "Seccion no encontrada o sin permisos."))
        return screen
    }

    // Opens the tenant database of the authorized company, runs [block] and responds with its screen.
    private suspend fun ApplicationCall.respondScreen(access: ModuleAccessService, block: (Connection) -> UiScreen) {
        val companyCode = access.companyCode(this)
            ?: return respond(HttpStatusCode.BadRequest, CrmErrorResponse("MISSING_COMPANY", "La empresa es requerida."))
        val connection = withContext(Dispatchers.IO) { access.tenantConnectionForCompany(companyCode) }
            ?: return respond(HttpStatusCode.Forbidden, CrmErrorResponse("TENANT_UNAVAILABLE", "No se pudo abrir la empresa."))
        try {
            val screen = connection.use { tenant -> withContext(Dispatchers.IO) { block(tenant) } }
            respondText(UiJson.encodeToString(UiScreen.serializer(), screen), ContentType.Application.Json)
        } catch (failure: SQLException) {
            respond(HttpStatusCode.InternalServerError, CrmErrorResponse("DATABASE_ERROR", "No se pudo completar la operacion."))
        }
    }
}

@Serializable
data class CrmErrorResponse(val code: String, val message: String)

class CrmServerModuleProvider : ServerModuleProvider {
    override fun create(): ServerModule = CrmServerModule
}
