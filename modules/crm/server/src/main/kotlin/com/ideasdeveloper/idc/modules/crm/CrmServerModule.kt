package com.ideasdeveloper.idc.modules.crm

import com.ideasdeveloper.idc.server.modules.ModuleAccessService
import com.ideasdeveloper.idc.server.modules.ModuleDefinition
import com.ideasdeveloper.idc.server.modules.ModuleJob
import com.ideasdeveloper.idc.server.modules.ModulePermissionDefinition
import com.ideasdeveloper.idc.server.modules.ModulePermissions
import com.ideasdeveloper.idc.server.modules.ServerModule
import com.ideasdeveloper.idc.server.modules.ServerModuleProvider
import com.ideasdeveloper.idc.server.modules.SmtpSettings
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
import java.time.Duration
import java.time.LocalDateTime
import java.util.UUID

object CrmServerModule : ServerModule {
    override val migrationPaths = listOf(
        "modules/crm/migrations/V001__create_crm.sql",
        "modules/crm/migrations/V002__inbox_and_channels.sql",
    )

    override val runtimeTables = listOf(
        "crm_settings",
        "crm_stages",
        "crm_interests",
        "crm_user_interests",
        "crm_leads",
        "crm_activity",
        "crm_identities",
        "crm_messages",
        "crm_mail_settings",
        "crm_email_templates",
        "crm_outbox",
        "crm_spam_settings",
        "crm_spam_log",
        "crm_user_settings",
    )

    override val jobs = listOf(
        // Notifications, digests and scheduled emails queued in crm_outbox.
        ModuleJob("outbox", Duration.ofMinutes(1)) { context ->
            CrmNotify.sendQueued(context.tenant, CrmMail.load(context.tenant).smtp(context.access.serverMail), 30)
        },
        ModuleJob("digest", Duration.ofMinutes(15)) { context -> CrmNotify.digestIfDue(context.tenant, LocalDateTime.now()) },
        // Replies of customers by email.
        ModuleJob("imap", Duration.ofMinutes(5)) { context -> readMailbox(context.tenant) },
    )

    /** Reads new mail of the company's mailbox into the conversation of each sender's lead. */
    private fun readMailbox(tenant: Connection) {
        val settings = CrmMail.load(tenant)
        if (!settings.imapReady) return
        val (validity, last) = try {
            CrmMail.poll(settings, 50) { mail ->
                CrmInbox.receive(
                    tenant,
                    CrmInbound(
                        channel = "email",
                        name = mail.fromName,
                        email = mail.fromEmail,
                        message = CrmMail.withoutQuote(mail.text),
                        subject = mail.subject,
                        messageId = mail.messageId,
                        source = "Correo",
                        sentAt = mail.sentAt,
                    ),
                ) { leadId -> CrmNotify.newLead(tenant, leadId) }
            }
        } catch (failure: Exception) {
            CrmMail.recordPoll(tenant, null, null, failure.message ?: failure.javaClass.simpleName)
            return
        }
        CrmMail.recordPoll(tenant, validity, last, "")
    }

    override val definition = ModuleDefinition(
        id = CrmModule.id,
        displayName = CrmModule.displayName,
        description = "Leads ligados a Clientes con pipeline de etapas, seguimientos, actividad e intereses opcionales que reparten los leads entre usuarios.",
        version = "0.2.0",
        permissions = listOf(
            ModulePermissionDefinition(CrmScreens.View, "Usar el CRM", "Permite ver el pipeline, los leads y los seguimientos de sus intereses o asignados."),
            ModulePermissionDefinition(CrmScreens.Manage, "Gestionar leads", "Permite crear y editar leads, moverlos de etapa y agregar notas."),
            ModulePermissionDefinition(CrmScreens.Delete, "Eliminar leads", "Permite eliminar leads (el cliente queda en Clientes)."),
            ModulePermissionDefinition(CrmScreens.Reception, "Recepcion de leads", "Permite ver y categorizar los leads que aun no tienen interes."),
            ModulePermissionDefinition(CrmScreens.SeeAll, "Ver todos los leads", "Permite ver los leads de todos los intereses y filtrarlos por interes."),
            ModulePermissionDefinition(CrmScreens.Assign, "Asignar leads", "Permite elegir el responsable de un lead y aprobar o descartar las sugerencias."),
            ModulePermissionDefinition(CrmScreens.Settings, "Configurar CRM", "Permite cambiar etapas, intereses, correo, plantillas, anti-spam y avisos del equipo."),
        ),
        ui = ModuleUiEntry(type = "native", entry = CrmScreens.UiRoute, home = "home"),
    )

    override fun routes(route: Route, access: ModuleAccessService) {
        route.route(CrmScreens.UiRoute) {
            get("/{screen}") {
                val permissions = call.requireModuleAccess(access, CrmModule.id) ?: return@get
                val screen = call.screenId(permissions) ?: return@get
                val params = call.request.queryParameters.entries().associate { it.key to it.value.firstOrNull().orEmpty() }
                call.respondScreen(access) { tenant -> CrmScreens.build(screen, CrmScreens.Context.load(tenant, permissions, access.serverMail), params) }
            }

            post("/{screen}") {
                val permissions = call.requireModuleAccess(access, CrmModule.id) ?: return@post
                val screen = call.screenId(permissions) ?: return@post
                val params = call.request.queryParameters.entries().associate { it.key to it.value.firstOrNull().orEmpty() }
                val body = call.receiveText()
                val values = runCatching { UiJson.parseToJsonElement(body).jsonObject.mapValues { it.value.jsonPrimitive.contentOrNull.orEmpty() } }.getOrNull().orEmpty()
                val required = when (screen) {
                    "settings", "mail", "templates", "spam", "team" -> CrmScreens.Settings
                    "lead" -> if (params["op"] == "delete") CrmScreens.Delete else CrmScreens.Manage
                    else -> CrmScreens.Manage
                }
                if (!permissions.has(required)) {
                    return@post call.respond(HttpStatusCode.Forbidden, CrmErrorResponse("missing_permission", "No tienes permiso para esta accion."))
                }
                call.respondScreen(access) { tenant -> action(tenant, permissions, access.serverMail, screen, params, values) }
            }
        }
    }

    private fun action(
        tenant: Connection,
        permissions: ModulePermissions,
        serverMail: SmtpSettings?,
        screen: String,
        params: Map<String, String>,
        values: Map<String, String>,
    ): UiScreen {
        val context = CrmScreens.Context.load(tenant, permissions, serverMail)
        val actor = CrmRepository.actor(tenant, permissions)
        fun show(target: String, notice: UiNotice, targetParams: Map<String, String> = params - "op", submitted: Map<String, String>? = null) =
            CrmScreens.build(target, CrmScreens.Context.load(tenant, permissions, serverMail), targetParams, notice, submitted)
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
                    // Without the assign permission the responsible user stays as the form defaulted it (the user).
                    val owned = if (context.canAssign) input else input.copy(ownerUserId = context.scope.userId?.takeIf { id -> context.users.any { it.id == id } })
                    val id = CrmRepository.createLead(tenant, owned, stage, actor)
                    CrmNotify.newLead(tenant, id)
                    owned.ownerUserId?.takeIf { it != context.scope.userId }?.let { CrmNotify.assigned(tenant, id, it) }
                    show("lead", UiNotice("Lead creado.", UiTone.Success), mapOf("id" to id.toString()))
                }
                "lead:save" -> {
                    val lead = visibleLead("id") ?: return show("leads", UiNotice("Ese lead ya no esta disponible.", UiTone.Warning), emptyMap())
                    val parsed = try {
                        CrmForms.parseLead(values, context.stages, context.interests, context.interestsEnabled, context.users)
                    } catch (failure: CrmValidationException) {
                        return show("lead", UiNotice(failure.message.orEmpty(), UiTone.Danger), submitted = values)
                    }
                    // Only assigners change the responsible user; for the rest it stays as it was.
                    val input = if (context.canAssign) parsed else parsed.copy(ownerUserId = lead.ownerUserId)
                    if (input.interestId != lead.interestId && !context.scope.canUseInterest(input.interestId)) {
                        return show("lead", UiNotice("No puedes asignar ese interes.", UiTone.Danger), submitted = values)
                    }
                    CrmRepository.updateLead(tenant, lead, input, context.stages, context.names, actor)
                    input.ownerUserId?.takeIf { it != lead.ownerUserId && it != context.scope.userId }?.let { CrmNotify.assigned(tenant, lead.id, it) }
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
                "lead:send" -> {
                    val lead = visibleLead("id") ?: return show("leads", UiNotice("Ese lead ya no esta disponible.", UiTone.Warning), emptyMap())
                    val (channel, email) = try {
                        CrmAdminScreens.composeReply(context, lead, values)
                    } catch (failure: CrmValidationException) {
                        return show("lead", UiNotice(failure.message.orEmpty(), UiTone.Danger), submitted = values)
                    }
                    val body = email.text
                    try {
                        val externalId = channel.send(CrmChannelContext(tenant, serverMail), lead, email)
                        CrmInbox.addMessage(tenant, lead.id, "out", channel.id, email.subject, body, externalId, "sent", "", actor.userId, actor.name)
                        show("lead", UiNotice("Mensaje enviado por ${channel.label}.", UiTone.Success))
                    } catch (failure: CrmChannelException) {
                        CrmInbox.addMessage(tenant, lead.id, "out", channel.id, email.subject, body, null, "failed", failure.message.orEmpty(), actor.userId, actor.name)
                        show("lead", UiNotice(failure.message.orEmpty(), UiTone.Danger), submitted = values)
                    }
                }
                "lead:approve", "lead:discard" -> {
                    if (!context.canAssign) return show("lead", UiNotice("No tienes permiso para asignar leads.", UiTone.Danger))
                    val lead = visibleLead("id") ?: return show("assignments", UiNotice("Ese lead ya no esta disponible.", UiTone.Warning), emptyMap())
                    val approve = params["op"] == "approve"
                    val owner = CrmRepository.resolveSuggestion(tenant, lead, approve, context.names, actor)
                    owner?.takeIf { it != context.scope.userId }?.let { CrmNotify.assigned(tenant, lead.id, it) }
                    show("lead", UiNotice(if (approve) "Responsable asignado." else "Sugerencia descartada.", UiTone.Success))
                }
                "lead:reviewed" -> {
                    val lead = visibleLead("id") ?: return show("leads", UiNotice("Ese lead ya no esta disponible.", UiTone.Warning), emptyMap())
                    CrmRepository.clearReview(tenant, lead, actor)
                    show("lead", UiNotice("Lead marcado como revisado.", UiTone.Success))
                }
                "mail:save" -> {
                    val merged = try {
                        CrmMail.merge(CrmMail.load(tenant), values)
                    } catch (failure: CrmValidationException) {
                        return show("mail", UiNotice(failure.message.orEmpty(), UiTone.Danger), emptyMap(), values)
                    }
                    CrmMail.save(tenant, merged)
                    show("mail", UiNotice("Correo guardado.", UiTone.Success), emptyMap())
                }
                "templates:save" -> {
                    val name = try {
                        CrmMail.saveTemplate(tenant, uuid("id"), values)
                    } catch (failure: CrmValidationException) {
                        return show("templates", UiNotice(failure.message.orEmpty(), UiTone.Danger), params - "op", values)
                    }
                    show("templates", UiNotice("Plantilla $name guardada.", UiTone.Success), emptyMap())
                }
                "templates:delete" -> {
                    val deleted = uuid("id")?.let { CrmMail.deleteTemplate(tenant, it) } == true
                    show("templates", UiNotice(if (deleted) "Plantilla eliminada." else "Esa plantilla ya no existe.", if (deleted) UiTone.Success else UiTone.Warning), emptyMap())
                }
                "spam:save" -> {
                    val settings = try {
                        CrmSpam.parse(values)
                    } catch (failure: CrmValidationException) {
                        return show("spam", UiNotice(failure.message.orEmpty(), UiTone.Danger), emptyMap(), values)
                    }
                    CrmSpam.save(tenant, settings)
                    show("spam", UiNotice("Reglas anti-spam guardadas.", UiTone.Success), emptyMap())
                }
                "team:user" -> {
                    val user = uuid("user")?.let { id -> context.users.firstOrNull { it.id == id } }
                        ?: return show("team", UiNotice("Ese usuario ya no tiene acceso al CRM.", UiTone.Warning), emptyMap())
                    CrmNotify.saveUserSettings(
                        tenant,
                        CrmUserSettings(
                            user.id, values["email"].orEmpty().trim(), values["notifyNewLeads"] == "true",
                            values["notifyAssigned"] == "true", values["dailyDigest"] == "true",
                        ),
                    )
                    show("team", UiNotice("Avisos de ${user.name} guardados.", UiTone.Success), emptyMap())
                }
                "team:digest" -> {
                    val hour = values["digestHour"].orEmpty().trim().toIntOrNull()?.takeIf { it in 0..23 }
                        ?: return show("team", UiNotice("La hora debe ser un numero entre 0 y 23.", UiTone.Danger), emptyMap())
                    tenant.prepareStatement("UPDATE crm_settings SET digest_hour = ? WHERE id = 1").use { it.setInt(1, hour); it.executeUpdate() }
                    show("team", UiNotice("El resumen diario se enviara a las $hour:00.", UiTone.Success), emptyMap())
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
