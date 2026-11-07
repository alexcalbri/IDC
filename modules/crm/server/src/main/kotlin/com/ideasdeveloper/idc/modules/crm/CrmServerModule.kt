package com.ideasdeveloper.idc.modules.crm

import com.ideasdeveloper.idc.server.modules.ModuleAccessService
import com.ideasdeveloper.idc.server.modules.ModuleDefinition
import com.ideasdeveloper.idc.server.modules.ModuleMcp
import com.ideasdeveloper.idc.server.modules.SettingsTable
import com.ideasdeveloper.idc.server.modules.ModuleJob
import com.ideasdeveloper.idc.server.modules.ModulePermissionDefinition
import com.ideasdeveloper.idc.server.modules.CustomerTimeline
import com.ideasdeveloper.idc.server.modules.ModulePermissions
import com.ideasdeveloper.idc.server.modules.UserIdentity
import com.ideasdeveloper.idc.server.modules.ServerModule
import com.ideasdeveloper.idc.server.modules.ServerModuleProvider
import com.ideasdeveloper.idc.server.modules.SmtpSettings
import com.ideasdeveloper.idc.server.modules.requireModuleAccess
import com.ideasdeveloper.idc.ui.ModuleUiEntry
import com.ideasdeveloper.idc.ui.UiJson
import com.ideasdeveloper.idc.ui.UiNotice
import com.ideasdeveloper.idc.ui.UiScreen
import com.ideasdeveloper.idc.ui.UiSuggestions
import io.ktor.utils.io.jvm.javaio.toInputStream
import io.ktor.server.response.respondOutputStream
import io.ktor.server.request.receiveChannel
import io.ktor.server.request.contentType
import com.ideasdeveloper.idc.ui.UiFileRef
import java.net.URLDecoder
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
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import io.ktor.server.request.receiveParameters
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
        "modules/crm/migrations/V008__monday_token.sql",
        "modules/crm/migrations/V009__monday_background_read.sql",
        "modules/crm/migrations/V010__custom_fields_and_attachments.sql",
        "modules/crm/migrations/V011__attachments_in_file_storage.sql",
        "modules/crm/migrations/V012__import_fields_and_files.sql",
        "modules/crm/migrations/V013__meta_channel_tokens.sql",
        "modules/crm/migrations/V014__meta_server_app.sql",
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
        "crm_meta_server_app",
        "crm_meta_routes",
        "crm_meta_tickets",
        "crm_encuentra24_settings",
        "crm_wordpress_settings",
        "crm_wp_agents",
        "crm_lead_items",
        "crm_cloudbeds_settings",
        "crm_reservations",
        "crm_import_jobs",
        "crm_import_rows",
        "crm_import_presets",
        "crm_field_definitions",
        "crm_attachments",
    )

    /**
     * A duplicated company gets the same pipeline, interests, custom fields, templates, spam rules
     * and import presets. Leads, conversations and per-user settings stay behind, and so do the
     * integrations (mail, Meta, WordPress, Encuentra24, Cloudbeds, monday): they are connected again.
     */
    override val duplicatedSettings = listOf(
        SettingsTable("crm_settings", reset = mapOf("monday_token" to null, "digest_sent_on" to null)),
        SettingsTable("crm_stages"),
        SettingsTable("crm_interests"),
        SettingsTable("crm_field_definitions"),
        SettingsTable("crm_email_templates"),
        SettingsTable("crm_spam_settings"),
        SettingsTable("crm_import_presets"),
    )

    override val mcp: ModuleMcp
        get() = CrmMcpTools.mcp

    override val customerTimeline: CustomerTimeline
        get() = CrmCustomerTimeline.timeline

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
        // Imports: queued monday boards are read, then imports in progress apply 300 rows a minute.
        ModuleJob("import", Duration.ofMinutes(1)) { context ->
            CrmImport.readQueued(context.tenant)
            CrmImport.runPending(context.tenant, 300, java.time.LocalDate.now(), context.access.files(context.companyCode, CrmModule.id))
        },
        ModuleJob("encuentra24", Duration.ofMinutes(5)) { context ->
            if (CrmEncuentra24.load(context.tenant).ready) {
                runCatching { CrmEncuentra24.sync(context.tenant) { leadId -> CrmNotify.newLead(context.tenant, leadId) } }
            }
        },
        // Stored files of removed attachments, deleted leads and forms never saved.
        ModuleJob("attachments-cleanup", Duration.ofMinutes(15)) { context ->
            CrmAttachments.cleanup(context.tenant, context.access.files(context.companyCode, CrmModule.id))
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
        version = "0.14.0",
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
        CrmMetaServer.access = access
        metaServerRoutes(route, access)
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
            // Files of the lead forms: uploads wait for the form to be saved (see CrmAttachments).
            post("/files") {
                val permissions = call.requireModuleAccess(access, CrmModule.id) ?: return@post
                if (!permissions.has(CrmScreens.Manage)) {
                    return@post call.respond(HttpStatusCode.Forbidden, CrmErrorResponse("missing_permission", "No tienes permiso para esta accion."))
                }
                val length = call.request.headers[HttpHeaders.ContentLength]?.toLongOrNull()
                if (length != null && length > CrmAttachments.MaxFileBytes) {
                    return@post call.respond(HttpStatusCode.PayloadTooLarge, CrmErrorResponse("too_large", "El archivo supera el maximo de ${CrmAttachments.MaxFileBytes / (1024 * 1024)} MB."))
                }
                fun header(name: String) = call.request.headers[name]?.let { runCatching { URLDecoder.decode(it, Charsets.UTF_8) }.getOrNull() }.orEmpty()
                val name = header("X-File-Name")
                val path = header("X-File-Path")
                val contentType = call.request.contentType().withoutParameters().toString()
                val companyCode = access.companyCode(call)
                    ?: return@post call.respond(HttpStatusCode.BadRequest, CrmErrorResponse("MISSING_COMPANY", "La empresa es requerida."))
                // The body goes straight to the file storage; it is never held in memory whole.
                val body = call.receiveChannel().toInputStream()
                val result = withContext(Dispatchers.IO) {
                    access.tenantConnectionForCompany(companyCode)?.use { tenant ->
                        runCatching {
                            val actor = CrmRepository.actor(tenant, permissions, access.userIdentity(tenant, permissions))
                            CrmAttachments.upload(tenant, access.files(companyCode, CrmModule.id), name, path, contentType, body, actor.name)
                        }
                    }
                } ?: return@post call.respond(HttpStatusCode.Forbidden, CrmErrorResponse("TENANT_UNAVAILABLE", "No se pudo abrir la empresa."))
                result.fold(
                    onSuccess = { ref -> call.respondText(UiJson.encodeToString(UiFileRef.serializer(), ref), ContentType.Application.Json) },
                    onFailure = { failure ->
                        if (failure is CrmValidationException) call.respond(HttpStatusCode.BadRequest, CrmErrorResponse("invalid_file", failure.message.orEmpty()))
                        else call.respond(HttpStatusCode.InternalServerError, CrmErrorResponse("DATABASE_ERROR", "No se pudo guardar el archivo."))
                    },
                )
            }

            // A file of a lead the user can see, or a pending upload (only its id, just returned to the uploader, reaches it).
            get("/files/{id}") {
                val permissions = call.requireModuleAccess(access, CrmModule.id) ?: return@get
                val id = call.parameters["id"]?.let { runCatching { UUID.fromString(it) }.getOrNull() }
                    ?: return@get call.respond(HttpStatusCode.NotFound)
                val companyCode = access.companyCode(call)
                    ?: return@get call.respond(HttpStatusCode.BadRequest, CrmErrorResponse("MISSING_COMPANY", "La empresa es requerida."))
                val file = withContext(Dispatchers.IO) {
                    access.tenantConnectionForCompany(companyCode)?.use { tenant ->
                        CrmAttachments.file(tenant, access.files(companyCode, CrmModule.id), id)?.takeIf { stored ->
                            val leadId = stored.leadId ?: return@takeIf permissions.has(CrmScreens.Manage)
                            val lead = CrmRepository.lead(tenant, leadId) ?: return@takeIf false
                            CrmRepository.scope(tenant, permissions).canSee(lead.interestId, lead.ownerUserId)
                        }
                    }
                } ?: return@get call.respond(HttpStatusCode.NotFound, CrmErrorResponse("not_found", "Ese archivo ya no esta disponible."))
                call.response.header(
                    HttpHeaders.ContentDisposition,
                    ContentDisposition.Attachment.withParameter(ContentDisposition.Parameters.FileName, file.ref.name).toString(),
                )
                val type = runCatching { ContentType.parse(file.contentType) }.getOrDefault(ContentType.Application.OctetStream)
                val content = withContext(Dispatchers.IO) { file.open() }
                    ?: return@get call.respond(HttpStatusCode.NotFound, CrmErrorResponse("missing_content", "El archivo ya no esta en el almacenamiento del servidor."))
                call.respondOutputStream(type) { content.use { it.copyTo(this) } }
            }

            // Customers matching the contact typed in the new lead form (UiField.suggest).
            get("/suggest/customers") {
                val permissions = call.requireModuleAccess(access, CrmModule.id) ?: return@get
                if (!permissions.has(CrmScreens.Manage)) {
                    return@get call.respond(HttpStatusCode.Forbidden, CrmErrorResponse("missing_permission", "No tienes permiso para esta accion."))
                }
                val query = call.request.queryParameters
                call.respondTenant(access, UiSuggestions.serializer()) { tenant ->
                    CrmScreens.customerSuggestions(
                        CrmRepository.customerSuggestions(
                            tenant,
                            field = query["field"].orEmpty(),
                            query = query["q"].orEmpty().take(200),
                            email = query["email"].orEmpty().take(254),
                            phone = query["phone"].orEmpty().take(40),
                        ),
                    )
                }
            }

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
                call.respondScreen(access) { tenant -> action(tenant, permissions, access.serverMail, call.publicBase(), screen, params, values, access.userIdentity(tenant, permissions)) }
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
        identity: UserIdentity?,
    ): UiScreen {
        val context = CrmScreens.Context.load(tenant, permissions, serverMail, publicBase)
        val actor = CrmRepository.actor(tenant, permissions, identity)
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
                        CrmForms.parseLead(values, context.stages, context.interests, context.interestsEnabled, context.users, context.fields)
                    } catch (failure: CrmValidationException) {
                        return show("new", UiNotice(failure.message.orEmpty(), UiTone.Danger), emptyMap(), values)
                    }
                    if (!context.scope.canUseInterest(input.interestId)) {
                        return show("new", UiNotice("No puedes crear leads de ese interes.", UiTone.Danger), emptyMap(), values)
                    }
                    val stage = context.stage(input.stageId)!!
                    // Without the assign permission the responsible user stays as the form defaulted it (the user).
                    val owned = if (context.canAssign) input else input.copy(ownerUserId = context.scope.userId?.takeIf { id -> context.users.any { it.id == id } })
                    val chosen = values["customerId"]?.let { runCatching { UUID.fromString(it) }.getOrNull() }
                    val id = CrmRepository.createLead(tenant, owned, stage, actor, chosen, context.fields)
                    CrmNotify.newLead(tenant, id)
                    owned.ownerUserId?.takeIf { it != context.scope.userId }?.let { CrmNotify.assigned(tenant, id, it) }
                    show("lead", UiNotice("Lead creado.", UiTone.Success), mapOf("id" to id.toString()))
                }
                "lead:save" -> {
                    val lead = visibleLead("id") ?: return show("leads", UiNotice("Ese lead ya no esta disponible.", UiTone.Warning), emptyMap())
                    val parsed = try {
                        CrmForms.parseLead(values, context.stages, context.interests, context.interestsEnabled, context.users, context.fields, lead.customFields)
                    } catch (failure: CrmValidationException) {
                        return show("lead", UiNotice(failure.message.orEmpty(), UiTone.Danger), submitted = values)
                    }
                    // Only assigners change the responsible user; for the rest it stays as it was.
                    val input = if (context.canAssign) parsed else parsed.copy(ownerUserId = lead.ownerUserId)
                    if (input.interestId != lead.interestId && !context.scope.canUseInterest(input.interestId)) {
                        return show("lead", UiNotice("No puedes asignar ese interes.", UiTone.Danger), submitted = values)
                    }
                    CrmRepository.updateLead(tenant, lead, input, context.stages, context.names, actor, context.fields)
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
                    // Saving connects everything at once: page token and Instagram, and the WhatsApp account.
                    if (!merged.enabled || merged.systemToken == null) return show("integrations", UiNotice("Meta guardado.", UiTone.Success), emptyMap())
                    val setup = CrmMeta.setup(tenant, merged)
                    show(
                        "integrations",
                        UiNotice("Meta guardado. ${setup.notice}".trim(), if (setup.problems.isEmpty()) UiTone.Success else UiTone.Warning),
                        metaNumbers(setup),
                    )
                }
                "integrations:metaServerApp" -> {
                    // Only the server owner, from the server workspace, configures the server app.
                    if (!permissions.serverOwner || CrmMetaServer.serverWorkspace() != permissions.companyCode) {
                        return show("integrations", UiNotice("Solo el dueno del servidor configura la app de Meta del servidor, desde el espacio del servidor.", UiTone.Danger), emptyMap())
                    }
                    val app = try {
                        CrmMetaServer.mergeApp(CrmMetaServer.loadApp(tenant), values)
                    } catch (failure: CrmValidationException) {
                        return show("integrations", UiNotice(failure.message.orEmpty(), UiTone.Danger), emptyMap(), values)
                    }
                    CrmMetaServer.saveApp(tenant, app)
                    show("integrations", UiNotice(if (app.enabled) "App de Meta del servidor guardada: las empresas ya pueden conectarse." else "App de Meta del servidor guardada (desactivada).", UiTone.Success), emptyMap())
                }
                "integrations:metaMode" -> {
                    val mode = params["mode"].takeIf { it == OwnMode || it == ServerMode }
                        ?: return show("integrations", UiNotice("Modo invalido.", UiTone.Danger), emptyMap())
                    val current = CrmMeta.load(tenant)
                    if (mode == ServerMode && CrmMetaServer.app()?.ready != true) {
                        return show("integrations", UiNotice("El servidor no tiene una app de Meta activa.", UiTone.Warning), emptyMap())
                    }
                    if (mode != current.connectionMode) {
                        // Tokens of one mode are useless in the other: the company connects again.
                        if (current.serverMode) CrmMetaServer.disconnect(tenant, permissions.companyCode)
                        CrmMeta.save(
                            tenant,
                            CrmMeta.load(tenant).copy(
                                connectionMode = mode, enabled = false, appSecret = null, verifyToken = null, systemToken = null,
                                pageId = null, pageName = null, pageToken = null, instagramAccountId = null, instagramUsername = null,
                                wabaId = null, phoneNumberId = null, messengerToken = null, instagramToken = null, sharedTokens = true,
                                oauthToken = null, metaUserId = null,
                            ),
                        )
                        CrmMeta.log(tenant, "modo", if (mode == ServerMode) "app del servidor" else "app propia")
                    }
                    show("integrations", UiNotice(if (mode == ServerMode) "Ahora conecta la empresa con Meta." else "Configura la app propia de la empresa.", UiTone.Success), emptyMap())
                }
                "integrations:metaLink" -> {
                    if (CrmMetaServer.app()?.ready != true) return show("integrations", UiNotice("El servidor no tiene una app de Meta activa.", UiTone.Warning), emptyMap())
                    val ticket = CrmMetaServer.newTicket(tenant, actor.name)
                    show(
                        "integrations",
                        UiNotice("Abre la ventana de Meta, inicia sesion y elige la pagina y el numero. El enlace vence en 30 minutos.", UiTone.Info),
                        mapOf("link" to CrmMetaServer.connectUrl(publicBase, permissions.companyCode, ticket)),
                    )
                }
                "integrations:metaDisconnect" -> {
                    CrmMetaServer.disconnect(tenant, permissions.companyCode)
                    show("integrations", UiNotice("Meta desconectado: ya no se reciben ni se envian mensajes por WhatsApp, Messenger ni Instagram.", UiTone.Success), emptyMap())
                }
                "integrations:metaServerOptions" -> {
                    CrmMeta.save(tenant, CrmMeta.load(tenant).copy(recordEchoes = values["recordEchoes"] == "true"))
                    show("integrations", UiNotice("Opciones de Meta guardadas.", UiTone.Success), emptyMap())
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
                        else -> try {
                            show("lead", UiNotice(CrmLeadItemActions.markSold(context, lead, item, values, actor), UiTone.Success), back)
                        } catch (notice: CrmActionNotice) {
                            show("lead", UiNotice(notice.message.orEmpty(), if (notice.warning) UiTone.Warning else UiTone.Danger), back)
                        }
                    }
                }
                "rooms:assign" -> {
                    val lead = visibleLead("lead") ?: return show("leads", UiNotice("Ese lead ya no esta disponible.", UiTone.Warning), emptyMap())
                    val keep = params - setOf("op", "room", "type", "roomName", "typeName", "total", "max")
                    try {
                        show("rooms", UiNotice(CrmLeadItemActions.assignRoom(context, lead, params, actor), UiTone.Success), keep)
                    } catch (notice: CrmActionNotice) {
                        show("rooms", UiNotice(notice.message.orEmpty(), if (notice.warning) UiTone.Warning else UiTone.Danger), keep)
                    }
                }
                "lead:reserve" -> {
                    val lead = visibleLead("id") ?: return show("leads", UiNotice("Ese lead ya no esta disponible.", UiTone.Warning), emptyMap())
                    val back = mapOf("id" to lead.id.toString())
                    try {
                        val reservation = CrmLeadItemActions.reserve(context, lead, values, actor)
                        show("lead", UiNotice(reservation.message, if (reservation.warning) UiTone.Warning else UiTone.Success), back)
                    } catch (notice: CrmActionNotice) {
                        // Rules (no rooms, mixed dates) reopen the lead; bad input keeps what the user typed.
                        if (notice.warning) {
                            show("lead", UiNotice(notice.message.orEmpty(), UiTone.Warning), back)
                        } else {
                            show("lead", UiNotice(notice.message.orEmpty(), UiTone.Danger), back, values)
                        }
                    }
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
                "import:monday" -> {
                    // Reading a board takes minutes, longer than the app waits: the import job reads it.
                    if (CrmImport.mondayToken(tenant) == null) return show("import", UiNotice("Guarda primero el token de la API de monday.", UiTone.Danger), emptyMap())
                    val boards = values.filter { it.key.startsWith("board:") && it.value == "true" }.keys.map { it.removePrefix("board:") }
                    val job = try {
                        CrmImport.queueMonday(tenant, boards, actor.userId)
                    } catch (failure: CrmImportException) {
                        return show("import", UiNotice(failure.message.orEmpty(), UiTone.Danger), mapOf("monday" to "boards"))
                    }
                    show("importjob", UiNotice("Se estan leyendo los tableros de monday.", UiTone.Success), mapOf("job" to job.toString()))
                }
                "import:text", "import:url", "import:leadspro" -> {
                    val (source, name, table) = try {
                        when (params["op"]) {
                            "text" -> Triple("file", values["name"].orEmpty().trim().ifEmpty { "CSV pegado ${context.today}" }, CrmImportParse.csv(values["csv"].orEmpty()))
                            "url" -> values["url"].orEmpty().trim().let { url -> Triple("file", url.substringAfterLast('/').take(80).ifEmpty { "Archivo" }, CrmImport.fromUrl(url)) }
                            else -> Triple("leadspro", "CT Leads Pro ${context.today}", CrmImport.fromLeadsPro(tenant))
                        }
                    } catch (failure: CrmImportException) {
                        return show("import", UiNotice(failure.message.orEmpty(), UiTone.Danger), emptyMap())
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
                            } else CrmImportScreens.mapping(context, job, values)
                            // Columns marked "Crear campo..." become fields of the CRM or of the customer first.
                            val created = CrmImport.saveMappingCreatingFields(tenant, job, mapping)
                            val saved = CrmImport.job(tenant, job.id)!!
                            values["presetName"]?.trim()?.takeIf { it.isNotEmpty() }?.let { CrmImport.savePreset(tenant, it, saved.mapping) }
                            val preview = CrmImport.preview(tenant, saved, CrmImport.Context(context.stages, context.interests, context.users, context.today, saved.source))
                            val missing = if ("name" !in mapping.columns.values && "email" !in mapping.columns.values && "phone" !in mapping.columns.values) " Ninguna columna es nombre, correo o telefono." else ""
                            show(
                                "importjob",
                                UiNotice(
                                    (if (created.isEmpty()) "" else "Campos creados: ${created.joinToString()}. ") +
                                        "Vista previa: ${preview.created} nuevos, ${preview.updated} actualizados, ${preview.conflicts} por revisar, ${preview.skipped} omitidos.$missing",
                                    if (missing.isEmpty()) UiTone.Success else UiTone.Warning,
                                ),
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
                    val action = values["action"].orEmpty()
                    val value = if (action == CrmImportScreens.ActionOwner) values["owner"].orEmpty() else values["stage"].orEmpty()
                    try {
                        show("bulk", UiNotice(CrmBulkActions.apply(context, selected, action, value, actor), UiTone.Success), keep)
                    } catch (notice: CrmActionNotice) {
                        show("bulk", UiNotice(notice.message.orEmpty(), if (notice.warning) UiTone.Warning else UiTone.Danger), keep)
                    }
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
                "settings:fieldSave" -> {
                    val editing = uuid("id")?.let { id -> context.fields.firstOrNull { it.id == id } }
                    if (uuid("id") != null && editing == null) return show("settings", UiNotice("Ese campo ya no existe.", UiTone.Warning), emptyMap())
                    val field = try {
                        CrmFields.save(tenant, editing?.id, CrmFields.parseDefinition(values, editing), context.fields)
                    } catch (failure: CrmValidationException) {
                        return show("settings", UiNotice(failure.message.orEmpty(), UiTone.Danger), params - "op", values)
                    }
                    show("settings", UiNotice("Campo ${field.label} guardado.", UiTone.Success), emptyMap())
                }
                "settings:fieldDelete" -> {
                    val field = uuid("id")?.let { id -> context.fields.firstOrNull { it.id == id } }
                        ?: return show("settings", UiNotice("Ese campo ya no existe.", UiTone.Warning), emptyMap())
                    CrmFields.delete(tenant, field)
                    show("settings", UiNotice("Campo ${field.label} eliminado.", UiTone.Success), emptyMap())
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

    // Numbers of the WhatsApp account for the screen (id|number|name|platform;...), so the CRM number can be chosen.
    private fun metaNumbers(setup: CrmMeta.Setup): Map<String, String> =
        if (setup.numbers.isEmpty()) emptyMap()
        else mapOf("numbers" to setup.numbers.joinToString(";") { listOf(it.id, it.number, it.name, it.platform).joinToString("|") { part -> part.replace(";", ",").replace("|", "/") } })

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
    /**
     * Public routes of the server Meta app: its webhook (one URL for every company), the deauthorize
     * and data deletion callbacks, and the connection page opened from Integraciones.
     */
    private fun metaServerRoutes(route: Route, access: ModuleAccessService) {
        route.route("/modules/crm/meta") {
            get("/webhook") {
                if (!access.isInstalled(CrmModule.id)) return@get call.respond(HttpStatusCode.NotFound)
                val query = call.request.queryParameters
                val challenge = withContext(Dispatchers.IO) {
                    runCatching {
                        CrmMetaServer.withServerWorkspace { server ->
                            CrmMetaServer.verify(CrmMetaServer.loadApp(server), query["hub.mode"], query["hub.verify_token"], query["hub.challenge"]).also {
                                CrmMeta.log(server, if (it != null) "verificacion_ok" else "verificacion_rechazada", "app del servidor")
                            }
                        }
                    }.getOrNull()
                } ?: return@get call.respond(HttpStatusCode.Forbidden)
                call.respondText(challenge, ContentType.Text.Plain)
            }
            post("/webhook") {
                if (!access.isInstalled(CrmModule.id)) return@post call.respond(HttpStatusCode.NotFound)
                val body = call.receive<ByteArray>()
                val signature = call.request.headers["X-Hub-Signature-256"]
                val status = withContext(Dispatchers.IO) { runCatching { CrmMetaServer.dispatch(body, signature) }.getOrDefault(500) }
                call.respond(HttpStatusCode.fromValue(status))
            }
            // Meta calls these with a signed_request when a person removes the app or asks to delete their data.
            post("/deauthorize") {
                if (!access.isInstalled(CrmModule.id)) return@post call.respond(HttpStatusCode.NotFound)
                val signed = call.receiveParameters()["signed_request"]
                val forgotten = withContext(Dispatchers.IO) { runCatching { CrmMetaServer.forgetUser(signed) }.getOrNull() }
                call.respond(if (forgotten == null) HttpStatusCode.BadRequest else HttpStatusCode.OK)
            }
            post("/data-deletion") {
                if (!access.isInstalled(CrmModule.id)) return@post call.respond(HttpStatusCode.NotFound)
                val signed = call.receiveParameters()["signed_request"]
                withContext(Dispatchers.IO) { runCatching { CrmMetaServer.forgetUser(signed) }.getOrNull() }
                    ?: return@post call.respond(HttpStatusCode.BadRequest)
                val code = CrmMetaServer.confirmationCode()
                val answer = buildJsonObject {
                    put("url", "${call.publicBase()}${CrmMetaServer.DeletionStatusPath}?id=$code")
                    put("confirmation_code", code)
                }
                call.respondText(answer.toString(), ContentType.Application.Json)
            }
            get("/deletion") {
                call.respondText(
                    "Los tokens y la conexion con Meta de esta persona se eliminaron del CRM. Codigo: " +
                        call.request.queryParameters["id"].orEmpty().filter { it.isLetterOrDigit() }.take(40),
                    ContentType.Text.Plain,
                )
            }
            route("/connect/{company}") {
                get {
                    val company = call.parameters["company"].orEmpty()
                    val ticket = call.request.queryParameters["t"].orEmpty()
                    val page = call.connectTenant(access, company, ticket) { _, app -> CrmMetaServer.connectPage(app, company, ticket) } ?: return@get
                    call.response.header("Cache-Control", "no-store")
                    call.respondText(page, ContentType.Text.Html)
                }
                post("/{step}") {
                    val company = call.parameters["company"].orEmpty()
                    val values = runCatching { UiJson.parseToJsonElement(call.receiveText()).jsonObject.mapValues { it.value.jsonPrimitive.contentOrNull.orEmpty() } }
                        .getOrNull().orEmpty()
                    val outcome = call.connectTenant(access, company, values["t"]) { tenant, app ->
                        try {
                            when (call.parameters["step"]) {
                                "pages" -> if (app.pagesConfigId == null) CrmMetaServer.Outcome(false, "Messenger e Instagram no estan habilitados en la app del servidor.")
                                    else CrmMetaServer.connectLogin(tenant, company, app, values["code"].orEmpty())
                                "page" -> CrmMetaServer.choosePage(tenant, company, values["pageId"].orEmpty())
                                "whatsapp" -> if (app.whatsAppConfigId == null) CrmMetaServer.Outcome(false, "WhatsApp no esta habilitado en la app del servidor.")
                                    else CrmMetaServer.connectWhatsApp(
                                        tenant, company, app, values["code"].orEmpty(), values["wabaId"].orEmpty(),
                                        values["phoneNumberId"].orEmpty(), values["pin"],
                                    )
                                else -> CrmMetaServer.Outcome(false, "Paso desconocido.")
                            }
                        } catch (failure: CrmMetaException) {
                            CrmMeta.log(tenant, "conexion_error", failure.message.orEmpty())
                            CrmMetaServer.Outcome(false, failure.message.orEmpty())
                        }
                    } ?: return@post
                    val answer = buildJsonObject {
                        put("ok", outcome.ok)
                        put("message", outcome.message)
                        put("pages", JsonArray(outcome.pages.map { page ->
                            buildJsonObject {
                                put("id", page.id)
                                put("name", page.name)
                                put("instagram", page.instagram)
                            }
                        }))
                    }
                    call.respondText(answer.toString(), ContentType.Application.Json)
                }
            }
        }
    }

    // Tenant of a company for the connection page, after checking its link token and the server app.
    private suspend fun <T> ApplicationCall.connectTenant(
        access: ModuleAccessService,
        company: String,
        ticket: String?,
        block: (Connection, CrmMetaServerApp) -> T,
    ): T? {
        if (!access.isInstalled(CrmModule.id)) {
            respond(HttpStatusCode.NotFound)
            return null
        }
        val app = withContext(Dispatchers.IO) { CrmMetaServer.app() }?.takeIf { it.ready }
        if (app == null) {
            respondText("La app de Meta del servidor no esta activa.", ContentType.Text.Plain, HttpStatusCode.NotFound)
            return null
        }
        val connection = withContext(Dispatchers.IO) { access.tenantConnectionForWebhook(company, CrmModule.id) }
        if (connection == null) {
            respond(HttpStatusCode.NotFound)
            return null
        }
        return try {
            connection.use { tenant ->
                withContext(Dispatchers.IO) {
                    if (!CrmMetaServer.validTicket(tenant, ticket)) null else block(tenant, app)
                }
            } ?: run {
                respondText("El enlace vencio o no es valido: abre otra vez la conexion desde Integraciones.", ContentType.Text.Plain, HttpStatusCode.Forbidden)
                null
            }
        } catch (failure: SQLException) {
            respond(HttpStatusCode.InternalServerError)
            null
        }
    }

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
    private suspend fun ApplicationCall.respondScreen(access: ModuleAccessService, block: (Connection) -> UiScreen) =
        respondTenant(access, UiScreen.serializer(), block)

    // Opens the tenant database of the authorized company, runs [block] and responds with its result as JSON.
    private suspend fun <T> ApplicationCall.respondTenant(access: ModuleAccessService, serializer: KSerializer<T>, block: (Connection) -> T) {
        val companyCode = access.companyCode(this)
            ?: return respond(HttpStatusCode.BadRequest, CrmErrorResponse("MISSING_COMPANY", "La empresa es requerida."))
        val connection = withContext(Dispatchers.IO) { access.tenantConnectionForCompany(companyCode) }
            ?: return respond(HttpStatusCode.Forbidden, CrmErrorResponse("TENANT_UNAVAILABLE", "No se pudo abrir la empresa."))
        try {
            val result = connection.use { tenant -> withContext(Dispatchers.IO) { block(tenant) } }
            respondText(UiJson.encodeToString(serializer, result), ContentType.Application.Json)
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
