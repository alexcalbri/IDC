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
import io.ktor.http.ContentDisposition
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.plugins.origin
import io.ktor.server.request.receive
import io.ktor.server.request.receiveText
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondBytes
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
        "modules/crm/migrations/V003__meta_channels.sql",
        "modules/crm/migrations/V004__encuentra24.sql",
        "modules/crm/migrations/V005__wordpress_and_items.sql",
        "modules/crm/migrations/V006__cloudbeds.sql",
        "modules/crm/migrations/V007__imports.sql",
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
        "crm_meta_settings",
        "crm_meta_log",
        "crm_encuentra24_settings",
        "crm_wordpress_settings",
        "crm_wp_agents",
        "crm_lead_items",
        "crm_cloudbeds_settings",
        "crm_reservations",
        "crm_import_jobs",
        "crm_import_rows",
        "crm_import_presets",
    )

    override val jobs = listOf(
        // Notifications, digests and scheduled emails queued in crm_outbox.
        ModuleJob("outbox", Duration.ofMinutes(1)) { context ->
            CrmNotify.sendQueued(context.tenant, CrmMail.load(context.tenant).smtp(context.access.serverMail), 30)
        },
        ModuleJob("digest", Duration.ofMinutes(15)) { context -> CrmNotify.digestIfDue(context.tenant, LocalDateTime.now()) },
        // Replies of customers by email.
        ModuleJob("imap", Duration.ofMinutes(5)) { context -> readMailbox(context.tenant) },
        // Contacts of the company listings in Encuentra24.
        // Cloudbeds: welcome emails before check-in and reservation statuses (archive after check-out).
        ModuleJob("cloudbeds-welcome", Duration.ofMinutes(15)) { context -> CrmCloudbeds.sendWelcomes(context.tenant, LocalDateTime.now()) },
        ModuleJob("cloudbeds-sync", Duration.ofMinutes(30)) { context -> CrmCloudbeds.syncStatuses(context.tenant, java.time.LocalDate.now()) },
        // Imports in progress: 300 rows a minute.
        ModuleJob("import", Duration.ofMinutes(1)) { context -> CrmImport.runPending(context.tenant, 300, java.time.LocalDate.now()) },
        ModuleJob("encuentra24", Duration.ofMinutes(5)) { context ->
            if (CrmEncuentra24.load(context.tenant).ready) {
                runCatching { CrmEncuentra24.sync(context.tenant) { leadId -> CrmNotify.newLead(context.tenant, leadId) } }
            }
        },
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
        version = "0.8.0",
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
        // Public webhooks: the company comes from the URL and every call is authenticated by the integration.
        // The IDC Connector plugin for WordPress, built from the module resources (it holds no secrets).
        route.get(CrmWordPressScreens.PluginPath) {
            if (!access.isInstalled(CrmModule.id)) return@get call.respond(HttpStatusCode.NotFound)
            val zip = CrmPlugin.zip(CrmServerModule::class.java.classLoader)
            call.response.header(HttpHeaders.ContentDisposition, ContentDisposition.Attachment.withParameter(ContentDisposition.Parameters.FileName, "idc-connector.zip").toString())
            call.respondBytes(zip, ContentType.Application.Zip)
        }

        route.route("/modules/crm/hooks/{company}") {
            post("/wordpress") {
                val company = call.parameters["company"].orEmpty()
                val body = call.receive<ByteArray>()
                val timestamp = call.request.headers["X-IDC-Timestamp"]
                val signature = call.request.headers["X-IDC-Signature"]
                val answer = call.webhookTenant(access, company) { tenant ->
                    val settings = CrmWordPress.load(tenant)
                    if (!settings.enabled || !CrmWordPress.validSignature(settings.secret, timestamp, signature, body)) return@webhookTenant "no"
                    try {
                        CrmWordPress.handle(tenant, body.decodeToString()) { leadId -> CrmNotify.newLead(tenant, leadId) }
                    } catch (failure: Exception) {
                        CrmWordPress.recordError(tenant, failure.message ?: failure.javaClass.simpleName)
                        return@webhookTenant "error"
                    }
                    "yes"
                } ?: return@post
                when (answer) {
                    "yes" -> call.respond(HttpStatusCode.OK)
                    "no" -> call.respond(HttpStatusCode.Forbidden)
                    // The plugin keeps the event and retries it.
                    else -> call.respond(HttpStatusCode.ServiceUnavailable)
                }
            }
            get("/meta") {
                val company = call.parameters["company"].orEmpty()
                val query = call.request.queryParameters
                val challenge = call.webhookTenant(access, company) { tenant ->
                    val settings = CrmMeta.load(tenant)
                    CrmMeta.verify(settings, query["hub.mode"], query["hub.verify_token"], query["hub.challenge"]).also {
                        CrmMeta.log(tenant, if (it != null) "verificacion_ok" else "verificacion_rechazada")
                    }
                } ?: return@get
                if (challenge.isEmpty()) return@get call.respond(HttpStatusCode.Forbidden)
                call.respondText(challenge, ContentType.Text.Plain)
            }
            post("/meta") {
                val company = call.parameters["company"].orEmpty()
                val body = call.receive<ByteArray>()
                val signature = call.request.headers["X-Hub-Signature-256"]
                val accepted = call.webhookTenant(access, company) { tenant ->
                    val settings = CrmMeta.load(tenant)
                    if (!settings.enabled || !CrmMeta.validSignature(settings.appSecret, signature, body)) {
                        CrmMeta.log(tenant, "firma_rechazada", if (settings.appSecret.isNullOrBlank()) "App Secret vacio" else "firma invalida o ausente")
                        return@webhookTenant "no"
                    }
                    // Meta disables subscriptions that keep failing: errors of one message are logged, the answer stays 200.
                    try {
                        val stored = CrmMeta.handle(tenant, settings, CrmMeta.parse(body.decodeToString(), settings.instagramAccountId)) { leadId -> CrmNotify.newLead(tenant, leadId) }
                        CrmMeta.log(tenant, "evento", "guardados=$stored")
                    } catch (failure: Exception) {
                        CrmMeta.log(tenant, "error", failure.message ?: failure.javaClass.simpleName)
                    }
                    "yes"
                } ?: return@post
                if (accepted == "yes") call.respond(HttpStatusCode.OK) else call.respond(HttpStatusCode.Forbidden)
            }
        }

        route.route(CrmScreens.UiRoute) {
            get("/{screen}") {
                val permissions = call.requireModuleAccess(access, CrmModule.id) ?: return@get
                val screen = call.screenId(permissions) ?: return@get
                val params = call.request.queryParameters.entries().associate { it.key to it.value.firstOrNull().orEmpty() }
                call.respondScreen(access) { tenant -> CrmScreens.build(screen, CrmScreens.Context.load(tenant, permissions, access.serverMail, call.publicBase()), params) }
            }

            post("/{screen}") {
                val permissions = call.requireModuleAccess(access, CrmModule.id) ?: return@post
                val screen = call.screenId(permissions) ?: return@post
                val params = call.request.queryParameters.entries().associate { it.key to it.value.firstOrNull().orEmpty() }
                val body = call.receiveText()
                val values = runCatching { UiJson.parseToJsonElement(body).jsonObject.mapValues { it.value.jsonPrimitive.contentOrNull.orEmpty() } }.getOrNull().orEmpty()
                val required = when (screen) {
                    "settings", "mail", "templates", "spam", "team", "integrations", "import", "importjob" -> CrmScreens.Settings
                    "lead" -> if (params["op"] == "delete") CrmScreens.Delete else CrmScreens.Manage
                    else -> CrmScreens.Manage
                }
                if (!permissions.has(required)) {
                    return@post call.respond(HttpStatusCode.Forbidden, CrmErrorResponse("missing_permission", "No tienes permiso para esta accion."))
                }
                call.respondScreen(access) { tenant -> action(tenant, permissions, access.serverMail, call.publicBase(), screen, params, values) }
            }
        }
    }

    private fun action(
        tenant: Connection,
        permissions: ModulePermissions,
        serverMail: SmtpSettings?,
        publicBase: String,
        screen: String,
        params: Map<String, String>,
        values: Map<String, String>,
    ): UiScreen {
        val context = CrmScreens.Context.load(tenant, permissions, serverMail, publicBase)
        val actor = CrmRepository.actor(tenant, permissions)
        fun show(target: String, notice: UiNotice, targetParams: Map<String, String> = params - "op", submitted: Map<String, String>? = null) =
            CrmScreens.build(target, CrmScreens.Context.load(tenant, permissions, serverMail, publicBase), targetParams, notice, submitted)
        fun uuid(key: String) = params[key]?.let { runCatching { UUID.fromString(it) }.getOrNull() }
        fun visibleLead(key: String) = uuid(key)?.let { CrmRepository.lead(tenant, it) }?.takeIf { context.scope.canSee(it.interestId, it.ownerUserId) }

        return try {
            when ("$screen:${params["op"]}") {
                "pipeline:move" -> {
                    val lead = visibleLead("card") ?: return show("pipeline", UiNotice("Ese lead ya no esta disponible.", UiTone.Warning), params - setOf("op", "card", "column"))
                    val stage = uuid("column")?.let { context.stage(it) } ?: return show("pipeline", UiNotice("Esa etapa ya no existe.", UiTone.Warning), params - setOf("op", "card", "column"))
                    // With rooms chosen in Cloudbeds the lead is won by confirming the reservation, not by dragging it.
                    val pendingRooms = CrmItems.list(tenant, lead.id, CrmCloudbeds.Source).any { it.status == "chosen" }
                    val reserved = CrmCloudbeds.forLead(tenant, lead.id).any { it.status !in setOf("canceled", "no_show") }
                    if (stage.kind == StageKind.Won && pendingRooms && !reserved) {
                        return show("pipeline", UiNotice("${lead.name} tiene habitaciones asignadas: confirma la reserva desde su ficha.", UiTone.Warning), params - setOf("op", "card", "column"))
                    }
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
                "integrations:metaSave" -> {
                    val merged = try {
                        CrmMeta.merge(CrmMeta.load(tenant), values)
                    } catch (failure: CrmValidationException) {
                        return show("integrations", UiNotice(failure.message.orEmpty(), UiTone.Danger), emptyMap(), values)
                    }
                    CrmMeta.save(tenant, merged)
                    // With a token and no page token yet, connect the page right away.
                    val notice = if (merged.enabled && merged.pageToken == null && merged.systemToken != null) {
                        try {
                            CrmMeta.save(tenant, CrmMeta.connectPage(merged))
                            UiNotice("Meta guardado y pagina conectada.", UiTone.Success)
                        } catch (failure: CrmMetaException) {
                            UiNotice("Meta guardado, pero la pagina no se conecto: ${failure.message}", UiTone.Warning)
                        }
                    } else UiNotice("Meta guardado.", UiTone.Success)
                    show("integrations", notice, emptyMap())
                }
                "integrations:metaPage" -> {
                    val notice = try {
                        val connected = CrmMeta.connectPage(CrmMeta.load(tenant))
                        CrmMeta.save(tenant, connected)
                        CrmMeta.log(tenant, "pagina_conectada", connected.pageName.orEmpty())
                        UiNotice("Pagina ${connected.pageName} conectada${if (connected.instagramAccountId != null) " con su cuenta de Instagram" else ""}.", UiTone.Success)
                    } catch (failure: CrmMetaException) {
                        UiNotice(failure.message.orEmpty(), UiTone.Danger)
                    }
                    show("integrations", notice, emptyMap())
                }
                "integrations:metaWhatsApp" -> {
                    val settings = CrmMeta.load(tenant)
                    try {
                        val numbers = CrmMeta.connectWhatsApp(settings)
                        CrmMeta.log(tenant, "wa_conectado", "${numbers.size} numero(s)")
                        if (numbers.size == 1 && settings.phoneNumberId == null) {
                            CrmMeta.save(tenant, settings.copy(phoneNumberId = numbers.single().id))
                            show("integrations", UiNotice("WhatsApp conectado con el numero ${numbers.single().number}.", UiTone.Success), emptyMap())
                        } else {
                            val list = numbers.joinToString(";") { "${it.id}|${it.number}|${it.name}".replace(";", ",") }
                            show("integrations", UiNotice("App suscrita a la cuenta. Elige el numero del CRM.", UiTone.Success), mapOf("numbers" to list))
                        }
                    } catch (failure: CrmMetaException) {
                        show("integrations", UiNotice(failure.message.orEmpty(), UiTone.Danger), emptyMap())
                    }
                }
                "integrations:e24Save" -> {
                    try {
                        CrmEncuentra24.saveForm(tenant, values)
                    } catch (failure: CrmValidationException) {
                        return show("integrations", UiNotice(failure.message.orEmpty(), UiTone.Danger), emptyMap(), values)
                    }
                    show("integrations", UiNotice("Encuentra24 guardado.", UiTone.Success), emptyMap())
                }
                "integrations:e24Sync" -> {
                    val from = values["fromDate"].orEmpty().trim().ifEmpty { null }?.let {
                        runCatching { java.time.LocalDate.parse(it) }.getOrNull()
                            ?: return show("integrations", UiNotice("La fecha debe tener el formato AAAA-MM-DD.", UiTone.Danger), emptyMap())
                    }
                    val notice = try {
                        val result = CrmEncuentra24.sync(tenant, from) { leadId -> CrmNotify.newLead(tenant, leadId) }
                        UiNotice("Encuentra24: $result.", UiTone.Success)
                    } catch (failure: CrmEncuentra24Exception) {
                        UiNotice(failure.message.orEmpty(), UiTone.Danger)
                    }
                    show("integrations", notice, emptyMap())
                }
                "integrations:wpSave" -> {
                    try {
                        CrmWordPress.saveForm(tenant, values)
                    } catch (failure: CrmValidationException) {
                        return show("integrations", UiNotice(failure.message.orEmpty(), UiTone.Danger), emptyMap())
                    }
                    show("integrations", UiNotice("WordPress guardado.", UiTone.Success), emptyMap())
                }
                "integrations:wpSecret" -> {
                    val secret = CrmWordPress.newSecret(tenant)
                    show("integrations", UiNotice("Secreto nuevo generado; pegalo en el plugin.", UiTone.Success), mapOf("secret" to secret))
                }
                "integrations:wpPing" -> {
                    val notice = try {
                        UiNotice("Conexion correcta: ${CrmWordPress.ping(tenant)}.", UiTone.Success)
                    } catch (failure: CrmWordPressException) {
                        CrmWordPress.recordError(tenant, failure.message.orEmpty())
                        UiNotice(failure.message.orEmpty(), UiTone.Danger)
                    }
                    show("integrations", notice, emptyMap())
                }
                "integrations:wpAgentsSync" -> {
                    val notice = try {
                        val emails = CrmNotify.userSettings(tenant).mapValues { it.value.email }
                        UiNotice("${CrmWordPress.syncAgents(tenant, context.users, emails)} agente(s) del sitio sincronizados.", UiTone.Success)
                    } catch (failure: CrmWordPressException) {
                        UiNotice(failure.message.orEmpty(), UiTone.Danger)
                    }
                    show("integrations", notice, emptyMap())
                }
                "integrations:wpAgentsSave" -> {
                    CrmWordPress.agents(tenant).forEach { agent ->
                        val chosen = values["agent_${agent.wpUserId}"] ?: return@forEach
                        CrmWordPress.mapAgent(tenant, agent.wpUserId, context.users.firstOrNull { it.name == chosen }?.id)
                    }
                    show("integrations", UiNotice("Agentes guardados.", UiTone.Success), emptyMap())
                }
                "properties:link" -> {
                    val lead = visibleLead("lead") ?: return show("leads", UiNotice("Ese lead ya no esta disponible.", UiTone.Warning), emptyMap())
                    val listingId = params["listing"]?.toLongOrNull() ?: return show("properties", UiNotice("Propiedad invalida.", UiTone.Danger), params - setOf("op", "listing"))
                    val notice = try {
                        val listing = CrmWordPress.listing(tenant, listingId)
                        CrmWordPress.linkListing(tenant, lead.id, listing, "Propiedad vinculada por ${actor.name}: ${listing.title}.")
                        UiNotice("${listing.title} vinculada a ${lead.name}.", UiTone.Success)
                    } catch (failure: CrmWordPressException) {
                        UiNotice(failure.message.orEmpty(), UiTone.Danger)
                    }
                    show("properties", notice, params - setOf("op", "listing"))
                }
                "lead:itemStatus", "lead:itemRemove", "lead:itemSold" -> {
                    val lead = visibleLead("id") ?: return show("leads", UiNotice("Ese lead ya no esta disponible.", UiTone.Warning), emptyMap())
                    val item = uuid("item")?.let { CrmItems.find(tenant, lead.id, it) } ?: return show("lead", UiNotice("Ese interes ya no existe.", UiTone.Warning), mapOf("id" to lead.id.toString()))
                    val back = mapOf("id" to lead.id.toString())
                    when (params["op"]) {
                        "itemRemove" -> {
                            CrmItems.remove(tenant, item)
                            CrmRepository.addActivity(tenant, lead.id, "item", "Quitado: ${item.title}.", actor)
                            show("lead", UiNotice("${item.title} quitado.", UiTone.Success), back)
                        }
                        "itemStatus" -> {
                            val status = params["status"].orEmpty().takeIf { it in setOf("offered", "chosen", "discarded", "interested") }
                                ?: return show("lead", UiNotice("Estado invalido.", UiTone.Danger), back)
                            CrmItems.setStatus(tenant, item, status)
                            CrmRepository.addActivity(tenant, lead.id, "item", "${item.title}: ${CrmItems.statusLabels[status]}.", actor)
                            show("lead", UiNotice("${item.title}: ${CrmItems.statusLabels[status]}.", UiTone.Success), back)
                        }
                        else -> {
                            if (item.source != CrmWordPress.Source) return show("lead", UiNotice("Solo las propiedades del sitio se cierran asi.", UiTone.Warning), back)
                            val price = values["price"].orEmpty().trim().ifEmpty { null }?.let {
                                it.replace(",", "").toBigDecimalOrNull()?.takeIf { value -> value.signum() >= 0 }
                                    ?: return show("lead", UiNotice("El precio debe ser un numero positivo.", UiTone.Danger), back)
                            }
                            val date = CrmWordPressScreens.saleDate(values["date"], context.today)
                            try {
                                CrmWordPress.markSold(tenant, item.externalId.toLong(), price, date)
                            } catch (failure: CrmWordPressException) {
                                return show("lead", UiNotice("La venta no se cerro: ${failure.message}", UiTone.Danger), back)
                            }
                            CrmItems.setStatus(tenant, item, "sold", price ?: item.price)
                            CrmRepository.addActivity(tenant, lead.id, "item", "Venta cerrada: ${item.title}${price?.let { " por ${it.toPlainString()}" }.orEmpty()} el $date. Marcada como vendida en el sitio.", actor)
                            // The lead is won: first won stage of the pipeline.
                            context.stages.firstOrNull { it.kind == StageKind.Won }?.let { won -> CrmRepository.moveLead(tenant, lead, won, context.stages, actor) }
                            show("lead", UiNotice("Venta cerrada y propiedad marcada como vendida.", UiTone.Success), back)
                        }
                    }
                }
                "rooms:assign" -> {
                    val lead = visibleLead("lead") ?: return show("leads", UiNotice("Ese lead ya no esta disponible.", UiTone.Warning), emptyMap())
                    val keep = params - setOf("op", "room", "type", "roomName", "typeName", "total", "max")
                    val stay = try {
                        CrmCloudbedsScreens.stay(lead, params, context.today)
                    } catch (failure: CrmValidationException) {
                        return show("rooms", UiNotice(failure.message.orEmpty(), UiTone.Danger), keep)
                    }
                    val room = params["room"].orEmpty().takeIf { it.isNotBlank() && it.length <= 60 } ?: return show("rooms", UiNotice("Habitacion invalida.", UiTone.Danger), keep)
                    val chosenDates = CrmItems.list(tenant, lead.id, CrmCloudbeds.Source).filter { it.status == "chosen" }.map { it.startsOn to it.endsOn }.toSet()
                    if (chosenDates.isNotEmpty() && chosenDates != setOf(stay.start to stay.end)) {
                        return show("rooms", UiNotice("Las habitaciones de una reserva comparten fechas: quita las otras o busca con sus fechas.", UiTone.Warning), keep)
                    }
                    val title = "${params["roomName"].orEmpty()} - ${params["typeName"].orEmpty()}".trim(' ', '-').ifEmpty { "Habitacion $room" }
                    CrmItems.link(
                        tenant, lead.id, CrmCloudbeds.Source, room, title,
                        subtitle = params["max"]?.toIntOrNull()?.takeIf { it > 0 }?.let { "Hasta $it personas" }.orEmpty(),
                        price = params["total"]?.toBigDecimalOrNull(), status = "chosen", startsOn = stay.start, endsOn = stay.end,
                        data = mapOf("roomTypeId" to params["type"].orEmpty(), "max" to params["max"].orEmpty(), "adults" to stay.adults.toString(), "children" to stay.children.toString()),
                    )
                    CrmRepository.addActivity(tenant, lead.id, "item", "Habitacion asignada: $title, del ${stay.start} al ${stay.end}.", actor)
                    show("rooms", UiNotice("$title asignada a ${lead.name}.", UiTone.Success), keep)
                }
                "lead:reserve" -> {
                    val lead = visibleLead("id") ?: return show("leads", UiNotice("Ese lead ya no esta disponible.", UiTone.Warning), emptyMap())
                    val back = mapOf("id" to lead.id.toString())
                    val rooms = CrmItems.list(tenant, lead.id, CrmCloudbeds.Source).filter { it.status == "chosen" }
                    val start = rooms.firstOrNull()?.startsOn
                    val end = rooms.firstOrNull()?.endsOn
                    if (rooms.isEmpty() || start == null || end == null) return show("lead", UiNotice("Asigna al menos una habitacion.", UiTone.Warning), back)
                    if (rooms.any { it.startsOn != start || it.endsOn != end }) return show("lead", UiNotice("Las habitaciones tienen fechas distintas; deja solo las de una estadia.", UiTone.Warning), back)
                    val requests = try {
                        rooms.map { room ->
                            val adults = values["adults_${room.id}"]?.trim()?.toIntOrNull()?.takeIf { it in 1..50 } ?: throw CrmValidationException("Cada habitacion necesita al menos un adulto (${room.title}).")
                            val children = values["children_${room.id}"]?.trim()?.ifEmpty { "0" }?.toIntOrNull()?.takeIf { it in 0..50 } ?: 0
                            val max = room.data["max"]?.toIntOrNull() ?: 0
                            if (max > 0 && adults + children > max) throw CrmValidationException("${room.title} admite hasta $max personas.")
                            CbRoomRequest(room.externalId, room.data["roomTypeId"].orEmpty().ifEmpty { throw CrmValidationException("Vuelve a asignar ${room.title}.") }, adults, children)
                        }
                    } catch (failure: CrmValidationException) {
                        return show("lead", UiNotice(failure.message.orEmpty(), UiTone.Danger), back, values)
                    }
                    val country = values["country"].orEmpty().trim().uppercase()
                    if (country.isNotEmpty() && !country.matches(Regex("^[A-Z]{2}$"))) return show("lead", UiNotice("El pais es un codigo de 2 letras, por ejemplo NI o US.", UiTone.Danger), back, values)
                    val (first, last) = CrmContacts.splitName(lead.name)
                    val guest = CbGuest(first, last, lead.email, CrmContacts.normalizePhone(lead.phone), country, values["zip"].orEmpty().trim().take(20))
                    val result = try {
                        val payment = CrmCloudbeds.paymentLabels.entries.firstOrNull { it.value == values["payment"] }?.key
                        CrmCloudbeds.reserve(tenant, lead.id, start, end, guest, requests, payment)
                    } catch (failure: CrmCloudbedsException) {
                        return show("lead", UiNotice("La reserva no se creo: ${failure.message}", UiTone.Danger), back, values)
                    } catch (failure: CrmValidationException) {
                        return show("lead", UiNotice(failure.message.orEmpty(), UiTone.Danger), back, values)
                    }
                    val roomNames = rooms.joinToString { it.title }
                    CrmCloudbeds.saveReservation(tenant, lead.id, result, start, end, roomNames, lead.email)
                    rooms.zip(requests).forEach { (room, request) ->
                        CrmItems.setStatus(tenant, room, "booked")
                        CrmItems.setData(tenant, room, room.data + mapOf("adults" to request.adults.toString(), "children" to request.children.toString(), "reservation" to result.reservationId))
                    }
                    CrmRepository.addActivity(tenant, lead.id, "item", "Reserva ${result.reservationId} creada en Cloudbeds: $roomNames, del $start al $end${result.total?.let { " - total ${it.toPlainString()}" }.orEmpty()}.", actor)
                    context.stages.firstOrNull { it.kind == StageKind.Won }?.let { won -> CrmRepository.moveLead(tenant, lead, won, context.stages, actor) }
                    val notice = if (result.unassigned > 0) {
                        UiNotice("Reserva ${result.reservationId} creada, pero Cloudbeds dejo ${result.unassigned} habitacion(es) sin asignar (puede que se ocupara o que la propiedad no permita elegir habitacion). Revisala en Cloudbeds.", UiTone.Warning)
                    } else UiNotice("Reserva ${result.reservationId} creada en Cloudbeds.", UiTone.Success)
                    show("lead", notice, back)
                }
                "integrations:cbSave" -> {
                    try {
                        CrmCloudbeds.saveForm(tenant, values, CrmMail.templates(tenant))
                    } catch (failure: CrmValidationException) {
                        return show("integrations", UiNotice(failure.message.orEmpty(), UiTone.Danger), emptyMap())
                    }
                    show("integrations", UiNotice("Cloudbeds guardado.", UiTone.Success), emptyMap())
                }
                "integrations:cbTest" -> {
                    val notice = try {
                        UiNotice("Conexion correcta con Cloudbeds: ${CrmCloudbeds.test(tenant)}.", UiTone.Success)
                    } catch (failure: CrmCloudbedsException) {
                        UiNotice(failure.message.orEmpty(), UiTone.Danger)
                    }
                    show("integrations", notice, emptyMap())
                }
                "import:mondayToken" -> {
                    val typed = values["token"].orEmpty().trim().takeIf { it.isNotEmpty() && it != CrmMail.SecretMask }
                    if (typed == null && CrmImport.mondayToken(tenant) == null) return show("import", UiNotice("Escribe el token de la API de monday.", UiTone.Danger), emptyMap())
                    typed?.let { CrmImport.saveMondayToken(tenant, it) }
                    show("import", UiNotice("Token de monday guardado.", UiTone.Success), mapOf("monday" to "boards"))
                }
                "import:mondayForget" -> {
                    CrmImport.saveMondayToken(tenant, null)
                    show("import", UiNotice("Token de monday borrado.", UiTone.Success), emptyMap())
                }
                "import:text", "import:url", "import:monday", "import:leadspro" -> {
                    val (source, name, table) = try {
                        when (params["op"]) {
                            "text" -> Triple("file", values["name"].orEmpty().trim().ifEmpty { "CSV pegado ${context.today}" }, CrmImportParse.csv(values["csv"].orEmpty()))
                            "url" -> values["url"].orEmpty().trim().let { url -> Triple("file", url.substringAfterLast('/').take(80).ifEmpty { "Archivo" }, CrmImport.fromUrl(url)) }
                            "monday" -> {
                                val token = CrmImport.mondayToken(tenant) ?: throw CrmImportException("Guarda primero el token de la API de monday.")
                                val boards = values.filter { it.key.startsWith("board:") && it.value == "true" }.keys.map { it.removePrefix("board:") }
                                if (boards.isEmpty()) throw CrmImportException("Marca al menos un tablero.")
                                val table = CrmImport.fromMonday(token, boards)
                                val names = table.rows.mapNotNull { it[CrmImport.BoardColumn]?.takeIf(String::isNotEmpty) }.distinct()
                                Triple("monday", "monday ${names.ifEmpty { boards }.joinToString()}".take(200), table)
                            }
                            else -> Triple("leadspro", "CT Leads Pro ${context.today}", CrmImport.fromLeadsPro(tenant))
                        }
                    } catch (failure: CrmImportException) {
                        // A failed monday read keeps the board list open so the selection can be changed.
                        return show("import", UiNotice(failure.message.orEmpty(), UiTone.Danger), if (params["op"] == "monday") mapOf("monday" to "boards") else emptyMap())
                    }
                    val job = CrmImport.create(tenant, source, name, table, actor.userId)
                    show("importjob", UiNotice("${table.rows.size} filas leidas. Revisa el mapeo.", UiTone.Success), mapOf("job" to job.toString()))
                }
                "importjob:map", "importjob:preset", "importjob:start", "importjob:cancel" -> {
                    val job = uuid("job")?.let { CrmImport.job(tenant, it) } ?: return show("import", UiNotice("Esa importacion ya no existe.", UiTone.Warning), emptyMap())
                    val back = mapOf("job" to job.id.toString())
                    when (params["op"]) {
                        "map", "preset" -> {
                            if (job.status != "mapping") return show("importjob", UiNotice("Esa importacion ya empezo.", UiTone.Warning), back)
                            val mapping = if (params["op"] == "preset") {
                                CrmImport.presets(tenant)[values["preset"]] ?: return show("importjob", UiNotice("Ese mapeo ya no existe.", UiTone.Warning), back)
                            } else CrmImportScreens.mapping(job, values)
                            CrmImport.saveMapping(tenant, job, mapping)
                            values["presetName"]?.trim()?.takeIf { it.isNotEmpty() }?.let { CrmImport.savePreset(tenant, it, mapping) }
                            val saved = CrmImport.job(tenant, job.id)!!
                            val preview = CrmImport.preview(tenant, saved, CrmImport.Context(context.stages, context.interests, context.users, context.today, saved.source))
                            val missing = if ("name" !in mapping.columns.values && "email" !in mapping.columns.values && "phone" !in mapping.columns.values) " Ninguna columna es nombre, correo o telefono." else ""
                            show(
                                "importjob",
                                UiNotice("Vista previa: ${preview.created} nuevos, ${preview.updated} actualizados, ${preview.conflicts} por revisar, ${preview.skipped} omitidos.$missing", if (missing.isEmpty()) UiTone.Success else UiTone.Warning),
                                back,
                            )
                        }
                        "start" -> {
                            if (job.status != "mapping") return show("importjob", UiNotice("Esa importacion ya empezo.", UiTone.Warning), back)
                            if (job.mapping.columns.values.none { it in setOf("name", "email", "phone") }) return show("importjob", UiNotice("Mapea al menos el nombre, el correo o el telefono.", UiTone.Danger), back)
                            CrmImport.setStatus(tenant, job, "running")
                            show("importjob", UiNotice("Importacion en marcha: se aplican 300 filas por minuto.", UiTone.Success), back)
                        }
                        else -> {
                            CrmImport.setStatus(tenant, job, "canceled")
                            show("import", UiNotice("Importacion ${job.name} cancelada.", UiTone.Success), emptyMap())
                        }
                    }
                }
                "bulk:apply" -> {
                    val keep = params - setOf("op")
                    val selected = if (values["all"] == "true") {
                        values.keys.filter { it.startsWith("lead:") }
                    } else {
                        values.filter { it.key.startsWith("lead:") && it.value == "true" }.keys
                    }.mapNotNull { runCatching { UUID.fromString(it.removePrefix("lead:")) }.getOrNull() }
                        .mapNotNull { CrmRepository.lead(tenant, it) }
                        .filter { context.scope.canSee(it.interestId, it.ownerUserId) }
                    if (selected.isEmpty()) return show("bulk", UiNotice("Marca al menos un lead.", UiTone.Warning), keep)
                    val notice = when (values["action"]) {
                        CrmImportScreens.ActionStage -> {
                            val stage = context.stages.firstOrNull { it.name == values["stage"] } ?: return show("bulk", UiNotice("Elige la etapa.", UiTone.Danger), keep)
                            selected.forEach { CrmRepository.moveLead(tenant, it, stage, context.stages, actor) }
                            "${selected.size} lead(s) movidos a ${stage.name}."
                        }
                        CrmImportScreens.ActionOwner -> {
                            if (!context.canAssign) return show("bulk", UiNotice("No tienes permiso para asignar leads.", UiTone.Danger), keep)
                            val owner = context.users.firstOrNull { it.name == values["owner"] }?.id
                            selected.forEach { lead ->
                                tenant.prepareStatement("UPDATE crm_leads SET owner_user_id = ?, updated_at = CURRENT_TIMESTAMP WHERE id = ?").use { it.setObject(1, owner); it.setObject(2, lead.id); it.executeUpdate() }
                                CrmRepository.addActivity(tenant, lead.id, "owner", "Responsable: ${context.names.user(owner)} (en lote).", actor)
                                owner?.takeIf { it != lead.ownerUserId && it != context.scope.userId }?.let { CrmNotify.assigned(tenant, lead.id, it) }
                            }
                            "${selected.size} lead(s) asignados a ${context.names.user(owner)}."
                        }
                        CrmImportScreens.ActionDelete -> {
                            if (!permissions.has(CrmScreens.Delete)) return show("bulk", UiNotice("No tienes permiso para eliminar leads.", UiTone.Danger), keep)
                            selected.forEach { CrmRepository.deleteLead(tenant, it.id) }
                            "${selected.size} lead(s) eliminados (los clientes quedan en Clientes)."
                        }
                        else -> return show("bulk", UiNotice("Elige la accion.", UiTone.Danger), keep)
                    }
                    show("bulk", UiNotice(notice, UiTone.Success), keep)
                }
                "lead:itemManual" -> {
                    val lead = visibleLead("id") ?: return show("leads", UiNotice("Ese lead ya no esta disponible.", UiTone.Warning), emptyMap())
                    val title = values["title"].orEmpty().trim()
                    if (title.isEmpty() || title.length > 300) return show("lead", UiNotice("Escribe el nombre del interes (hasta 300 caracteres).", UiTone.Danger), mapOf("id" to lead.id.toString()))
                    CrmItems.link(tenant, lead.id, "manual", UUID.randomUUID().toString(), title, values["subtitle"].orEmpty().trim().take(500))
                    CrmRepository.addActivity(tenant, lead.id, "item", "Interes agregado: $title.", actor)
                    show("lead", UiNotice("$title agregado.", UiTone.Success), mapOf("id" to lead.id.toString()))
                }
                "integrations:metaNumber" -> {
                    val id = params["id"].orEmpty().takeIf { it.matches(Regex("^[0-9]{1,64}$")) }
                        ?: return show("integrations", UiNotice("Numero invalido.", UiTone.Danger), emptyMap())
                    CrmMeta.save(tenant, CrmMeta.load(tenant).copy(phoneNumberId = id))
                    show("integrations", UiNotice("Numero de WhatsApp guardado.", UiTone.Success), emptyMap())
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

    // https://host of this server as the client reached it (behind the reverse proxy when there is one).
    private fun ApplicationCall.publicBase(): String {
        val scheme = request.headers["X-Forwarded-Proto"]?.substringBefore(',')?.trim() ?: request.origin.scheme
        val host = request.headers["X-Forwarded-Host"]?.substringBefore(',')?.trim() ?: request.headers["Host"] ?: request.origin.serverHost
        return "$scheme://$host"
    }

    /**
     * Opens the tenant of a webhook's company and runs [block]; answers 404 (and returns null) when
     * the company is unknown or does not use the CRM. [block] returns what the route answers with.
     */
    private suspend fun ApplicationCall.webhookTenant(access: ModuleAccessService, company: String, block: (Connection) -> String?): String? {
        val connection = withContext(Dispatchers.IO) { access.tenantConnectionForWebhook(company, CrmModule.id) }
            ?: run {
                respond(HttpStatusCode.NotFound)
                return null
            }
        return try {
            connection.use { tenant -> withContext(Dispatchers.IO) { block(tenant) } }.orEmpty()
        } catch (failure: SQLException) {
            respond(HttpStatusCode.InternalServerError)
            null
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
