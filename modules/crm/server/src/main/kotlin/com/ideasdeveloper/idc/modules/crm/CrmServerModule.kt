package com.ideasdeveloper.idc.modules.crm

import com.ideasdeveloper.idc.server.extensions.ServerExtension
import com.ideasdeveloper.idc.server.modules.ModuleAccessService
import com.ideasdeveloper.idc.server.modules.ModuleDefinition
import com.ideasdeveloper.idc.server.modules.ModuleMcp
import com.ideasdeveloper.idc.server.modules.ModuleNotifier
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
        "modules/crm/migrations/V015__exit_automation_and_show_archived.sql",
        "modules/crm/migrations/V016__leads_view_preference.sql",
        "modules/crm/migrations/V017__lead_owners.sql",
        "modules/crm/migrations/V018__lead_mentions.sql",
        "modules/crm/migrations/V019__field_sections_and_stay_dates.sql",
        "modules/crm/migrations/V020__system_sections.sql",
        "modules/crm/migrations/V021__automations_templates_translation.sql",
        "modules/crm/migrations/V022__extensions_and_secrets.sql",
    )

    override val runtimeTables = listOf(
        "crm_settings",
        "crm_stages",
        "crm_interests",
        "crm_user_interests",
        "crm_leads",
        "crm_lead_owners",
        "crm_lead_mentions",
        "crm_activity",
        "crm_identities",
        "crm_messages",
        "crm_mail_settings",
        "crm_email_templates",
        "crm_outbox",
        "crm_spam_settings",
        "crm_spam_log",
        "crm_user_settings",
        "crm_lead_items",
        "crm_reservations",
        "crm_import_jobs",
        "crm_import_rows",
        "crm_import_presets",
        "crm_field_sections",
        "crm_field_definitions",
        "crm_attachments",
        "crm_template_translations",
        "crm_automations",
        "crm_automation_runs",
    )

    /**
     * A duplicated company gets the same pipeline, interests, custom fields, templates, spam rules
     * and import presets. Leads, conversations and per-user settings stay behind, and so do the
     * integrations (mail, Meta, WordPress, Encuentra24, Cloudbeds y las demas extensiones): they are connected again.
     */
    override val duplicatedSettings = listOf(
        // The translation key is a credential of the integration: the copy connects it again.
        SettingsTable("crm_settings", reset = mapOf("digest_sent_on" to null, "translation_api_key" to "", "extensions_migrated_at" to null)),
        SettingsTable("crm_stages"),
        SettingsTable("crm_interests"),
        SettingsTable("crm_field_sections"),
        // Fields created by an extension of the original company are the copy's own: its extensions start uninstalled.
        SettingsTable("crm_field_definitions", reset = mapOf("extension_id" to null)),
        SettingsTable("crm_email_templates"),
        // Rules come off and forget who they notified (users are not copied); stages, templates and fields keep their ids, so the rest still points to them.
        SettingsTable("crm_automations", reset = mapOf("enabled" to false, "notify_user_ids" to "[]")),
        SettingsTable("crm_spam_settings"),
        SettingsTable("crm_import_presets"),
    )

    // Integrations of the CRM that each company installs on its own (EXTENSIONS.md). All of them are packages now (extensions/...): none ships bundled.
    override val bundledExtensions: List<ServerExtension>
        get() = emptyList()

    override fun linkExtensions(extensions: List<ServerExtension>) {
        CrmModuleExtensions.link(extensions)
    }

    override val mcp: ModuleMcp
        get() = CrmMcpTools.mcp

    override val customerTimeline: CustomerTimeline
        get() = CrmCustomerTimeline.timeline

    private val secretsWarned = java.util.concurrent.atomic.AtomicBoolean(false)

    override val jobs = listOf(
        // Notifications, digests and scheduled emails queued in crm_outbox.
        ModuleJob("outbox", Duration.ofMinutes(1)) { context ->
            val smtp = CrmMail.smtp(context.tenant, context.access.serverMail, context.access.secrets)
            if (smtp == null) CrmNotify.warnNoSmtp(context.tenant, context.companyCode)
            CrmNotify.sendQueued(context.tenant, smtp, 30)
        },
        // Una sola vez por empresa: el SMTP propio que tuviera el CRM pasa al SMTP de la empresa (company_mail_settings); gana sobre el de Hotspot.
        ModuleJob("mail-migration", Duration.ofMinutes(5)) { context ->
            val log = org.slf4j.LoggerFactory.getLogger("CrmMailMigration")
            val result = CrmMailMigration.run(context.tenant, context.access.secrets)
            when (result.outcome) {
                CrmMailMigration.Outcome.Migrated -> log.info("CRM {}: SMTP propio del CRM sembrado en el SMTP de la empresa (fuente: CRM, {}).", context.companyCode, result.detail)
                CrmMailMigration.Outcome.KeptCompany -> log.info("CRM {}: la empresa ya tenia SMTP; no se pisa y se descarta el del CRM ({}).", context.companyCode, result.detail)
                CrmMailMigration.Outcome.Skipped -> log.info("CRM {}: SMTP propio del CRM sin uso, se limpia ({}).", context.companyCode, result.detail)
                CrmMailMigration.Outcome.Waiting -> log.warn("CRM {}: el SMTP propio del CRM sigue sin migrar ({}).", context.companyCode, result.detail)
                CrmMailMigration.Outcome.Failed -> log.warn("CRM {}: {}.", context.companyCode, result.detail)
                CrmMailMigration.Outcome.Absent -> Unit
            }
        },
        ModuleJob("digest", Duration.ofMinutes(15)) { context -> CrmNotify.digestIfDue(context.tenant, LocalDateTime.now()) },
        // Follow-ups due: in-app notice (and email) to the assigned users, once per lead and date.
        ModuleJob("followups", Duration.ofMinutes(15)) { context -> CrmNotify.followUpsDue(context.tenant, context.notifier, java.time.Instant.now()) },
        // Replies of customers by email.
        ModuleJob("imap", Duration.ofMinutes(5)) { context -> readMailbox(context.tenant, context.access.secrets) },
        // Contacts of the company listings in Encuentra24.
        // Cloudbeds: welcome emails before check-in and reservation statuses (archive after check-out).
        ModuleJob("cloudbeds-welcome", Duration.ofMinutes(15)) { context -> CrmModuleExtensions.cloudbeds()?.sendWelcomes(CrmHost, context.tenant, LocalDateTime.now()) ?: 0 },
        ModuleJob("cloudbeds-sync", Duration.ofMinutes(30)) { context -> CrmModuleExtensions.cloudbeds()?.syncStatuses(CrmHost, context.tenant, java.time.LocalDate.now()) ?: 0 },
        // Date automations: run (once per lead and value of the date) or warn about missing data.
        ModuleJob("automations", Duration.ofMinutes(5)) { context -> CrmAutomationEngine.runDue(context.tenant, context.notifier) },
        // Event automations (lead created, stage or field changed, new message) are queued when the event happens and run here, up to 50 a minute;
        // runs stuck in "running" are marked failed.
        ModuleJob("automation-events", Duration.ofMinutes(1)) { context -> CrmAutomationEngine.processPending(context.tenant, context.notifier) },
        // Old rows of crm_automation_runs: 90 days, except the date and warning keys (a year).
        ModuleJob("automations-purge", Duration.ofHours(24)) { context -> CrmAutomationStore.purge(context.tenant) },
        // Archive after check-out (without Cloudbeds): leads of the chosen stages whose end date arrived.
        ModuleJob("exit-archive", Duration.ofMinutes(15)) { context -> CrmExit.archiveDue(context.tenant, java.time.Instant.now()) },
        // Imports: what the external sources queued (CrmImportSources) is read, then imports in progress apply 300 rows
        // a minute: the generic runner first and then the one of each source.
        ModuleJob("import", Duration.ofMinutes(1)) { context ->
            CrmImportSources.readQueued(context.tenant)
            CrmImportSources.runPending(context.tenant, 300, java.time.LocalDate.now(), context.access.files(context.companyCode, CrmModule.id))
        },
        ModuleJob("encuentra24", Duration.ofMinutes(5)) { context ->
            // La importacion vive en la extension "encuentra24"; sin ella el trabajo no hace nada.
            val provider = CrmModuleExtensions.encuentra24()
            if (provider != null && provider.active(CrmHost, context.tenant)) {
                runCatching { provider.sync(CrmHost, context.tenant, null) }
            }
        },
        // Once per company (and again until the master key exists): integrations already configured become enabled extensions and
        // their plain-text secrets are sealed.
        ModuleJob("extensions-migration", Duration.ofMinutes(10)) { context ->
            val (registered, sealed) = CrmExtensions.migrateTenant(context.tenant, context.access.secrets)
            if (registered > 0 || sealed > 0) {
                org.slf4j.LoggerFactory.getLogger("CrmExtensions").info("CRM {}: {} integracion(es) registradas como extensiones, {} secreto(s) cifrados.", context.companyCode, registered, sealed)
            } else if (!context.access.secrets.available && secretsWarned.compareAndSet(false, true)) {
                org.slf4j.LoggerFactory.getLogger("CrmExtensions").warn("CRM: sin IDC_MASTER_KEY los secretos de las integraciones siguen sin cifrar.")
            }
        },
        // Stored files of removed attachments, deleted leads and forms never saved.
        ModuleJob("attachments-cleanup", Duration.ofMinutes(15)) { context ->
            CrmAttachments.cleanup(context.tenant, context.access.files(context.companyCode, CrmModule.id))
        },
    )

    /** Reads new mail of the company's mailbox into the conversation of each sender's lead. */
    private fun readMailbox(tenant: Connection, secrets: com.ideasdeveloper.idc.server.security.SecretBox) {
        val settings = CrmMail.load(tenant)
        if (!settings.imapReady) return
        // El remitente del SMTP de la empresa: sus propios correos no se importan como respuestas de clientes.
        val ownFrom = CrmMail.companySmtp(tenant, secrets)?.from
        val (validity, last) = try {
            CrmMail.poll(settings, 50, ownFrom) { mail ->
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
        version = "0.27.0",
        minCoreVersion = "0.8.0",
        minAppVersion = "0.7.0",
        supportsExtensions = true,
        permissions = listOf(
            ModulePermissionDefinition(CrmScreens.View, "Usar el CRM", "Permite ver el pipeline, los leads y los seguimientos de sus intereses o asignados."),
            ModulePermissionDefinition(CrmScreens.Manage, "Gestionar leads", "Permite crear y editar leads, moverlos de etapa y agregar notas. Quien es mencionado en una nota puede leer ese lead y responder con notas sin este permiso."),
            ModulePermissionDefinition(CrmScreens.Delete, "Eliminar leads", "Permite eliminar leads (el cliente queda en Clientes)."),
            ModulePermissionDefinition(CrmScreens.Reception, "Recepcion de leads", "Permite ver y categorizar los leads que aun no tienen interes."),
            ModulePermissionDefinition(CrmScreens.SeeAll, "Ver todos los leads", "Permite ver los leads de todos los intereses y filtrarlos por interes."),
            ModulePermissionDefinition(CrmScreens.Assign, "Asignar leads", "Permite elegir a quienes se asigna un lead (puede ser mas de uno) y aprobar o descartar las sugerencias."),
            ModulePermissionDefinition(CrmScreens.Settings, "Configurar CRM", "Permite cambiar etapas, intereses, correo, plantillas, anti-spam y avisos del equipo."),
        ),
        ui = ModuleUiEntry(type = "native", entry = CrmScreens.UiRoute, home = "home"),
        extensionPermissions = CrmExtensionPermissions.all,
    )

    override fun routes(route: Route, access: ModuleAccessService) {
        CrmCustomerEvents.access = access
        CrmLeadEvents.access = access
        // Secrets of the integrations are sealed with the key of each company database (CrmSecrets).
        CrmSecrets.box = access.secrets
        // The integrations are extensions (los paquetes en extensions/...): their public routes live under /modules/crm/extensions/<id>.
        // The old /modules/crm/meta/..., /modules/crm/hooks/... and /modules/crm/wordpress/... routes no longer answer.

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
                            // Reading a file is reading the lead: a mention is enough (never for uploading or linking).
                            CrmMentions.access(tenant, CrmRepository.scope(tenant, permissions), lead).canRead
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

            // Users the note of a lead can mention, while "@" is typed (UiSuggest.trigger). Same rule as adding a note:
            // without it (or for a lead the user cannot reach) the list is empty.
            get("/suggest/mentions") {
                val permissions = call.requireModuleAccess(access, CrmModule.id) ?: return@get
                val query = call.request.queryParameters
                call.respondTenant(access, UiSuggestions.serializer()) { tenant ->
                    val context = CrmScreens.Context.load(tenant, permissions)
                    val lead = query["lead"]?.let { runCatching { UUID.fromString(it) }.getOrNull() }?.let { CrmRepository.lead(tenant, it) }
                    // Only who can give access by mentioning (lead inside their scope + manage): a mention-only reader gets nothing.
                    val allowed = lead != null && permissions.has(CrmScreens.View) && permissions.has(CrmScreens.Manage) &&
                        CrmMentions.canGrant(context.readAccess(lead))
                    if (!allowed) UiSuggestions()
                    else CrmMentions.suggestions(CrmMentions.suggest(context.mentionable, query["q"].orEmpty().take(100), context.scope.userId))
                }
            }

            get("/{screen}") {
                val permissions = call.requireModuleAccess(access, CrmModule.id) ?: return@get
                // The id as asked (old routes like "pipeline" or "bulk" open their own view or tab); build() resolves it.
                val screen = call.screenId(permissions, canonical = false) ?: return@get
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
                    "settings", "automations", "mail", "templates", "spam", "team", "integrations", "import", "importjob" -> CrmScreens.Settings
                    // Approving or discarding a suggestion needs canAssign (assign or see-all), checked in action().
                    "lead" -> when (params["op"]) {
                        "delete" -> CrmScreens.Delete
                        "approve", "discard" -> null
                        // Answering with a note is also open to who was mentioned in the lead (checked in action()).
                        "note" -> null
                        else -> CrmScreens.Manage
                    }
                    else -> CrmScreens.Manage
                }
                if (required != null && !permissions.has(required)) {
                    return@post call.respond(HttpStatusCode.Forbidden, CrmErrorResponse("missing_permission", "No tienes permiso para esta accion."))
                }
                call.respondScreen(access) { tenant -> action(tenant, permissions, access.serverMail, call.publicBase(), screen, params, values, access.userIdentity(tenant, permissions), access.notificationsFor(CrmModule.id)) }
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
        notifier: ModuleNotifier,
    ): UiScreen {
        val context = CrmScreens.Context.load(tenant, permissions, serverMail, publicBase)
        val actor = CrmRepository.actor(tenant, permissions, identity)
        fun show(target: String, notice: UiNotice, targetParams: Map<String, String> = params - "op", submitted: Map<String, String>? = null) =
            CrmScreens.build(target, CrmScreens.Context.load(tenant, permissions, serverMail, publicBase), targetParams, notice, submitted)
        fun uuid(key: String) = params[key]?.let { runCatching { UUID.fromString(it) }.getOrNull() }
        // Inside the user's scope: what EVERY action that changes a lead asks. A lead seen only through a mention is not here.
        fun visibleLead(key: String) = uuid(key)?.let { CrmRepository.lead(tenant, it) }?.takeIf { context.scope.canSee(it.interestId, it.ownerUserIds) }
        // Scope or an active mention: only to read the lead and add notes.
        fun readableLead(key: String) = uuid(key)?.let { CrmRepository.lead(tenant, it) }?.takeIf { context.readAccess(it).canRead }

        // Every integration action needs its extension active in the company (saving settings included).
        val operation = "$screen:${params["op"]}"
        val extensionOfOperation = when {
            operation.startsWith("integrations:meta") -> CrmExtensionIds.Meta
            operation.startsWith("integrations:e24") -> CrmExtensionIds.Encuentra24
            operation.startsWith("integrations:wp") -> CrmExtensionIds.WordPress
            operation.startsWith("integrations:cb") -> CrmExtensionIds.Cloudbeds
            else -> null
        }
        extensionOfOperation?.let { id -> CrmExtensions.inactive(tenant, id)?.let { return show("integrations", UiNotice(it, UiTone.Warning), emptyMap()) } }

        return try {
            // Las acciones propias de las fuentes externas de importacion (las trae su extension) las atiende la fuente.
            if (screen == "import") {
                CrmImportSources.action(tenant, params["op"].orEmpty(), values, actor.userId)?.let { result ->
                    return show(result.screen, UiNotice(result.message, result.tone), result.params)
                }
            }
            when ("$screen:${params["op"]}") {
                "pipeline:move" -> {
                    val lead = visibleLead("card") ?: return show(
                        "pipeline",
                        UiNotice(
                            if (readableLead("card") != null) "Ves ese lead porque te mencionaron: puedes leerlo y responder con notas, no moverlo." else "Ese lead ya no esta disponible.",
                            UiTone.Warning,
                        ),
                        params - setOf("op", "card", "column"),
                    )
                    // The "Archivados" column is not a stage: archived leads stay put.
                    if (lead.archivedAt != null || params["column"] == "archived") {
                        return show("pipeline", UiNotice("${lead.name} esta archivado: no se mueve entre etapas.", UiTone.Warning), params - setOf("op", "card", "column"))
                    }
                    val stage = uuid("column")?.let { context.stage(it) } ?: return show("pipeline", UiNotice("Esa etapa ya no existe.", UiTone.Warning), params - setOf("op", "card", "column"))
                    // With rooms chosen in Cloudbeds the lead is won by confirming the reservation, not by dragging it.
                    if (CrmRepository.wonNeedsReservation(tenant, lead.id, stage)) {
                        return show("pipeline", UiNotice("${lead.name} tiene habitaciones asignadas: confirma la reserva desde su ficha.", UiTone.Warning), params - setOf("op", "card", "column"))
                    }
                    CrmRepository.moveLead(tenant, lead, stage, context.stages, actor)
                    show("pipeline", UiNotice("${lead.name} paso a ${stage.name}.", UiTone.Success), params - setOf("op", "card", "column"))
                }
                "pipeline:action" -> {
                    val back = params - setOf("op", "action", "cards", "value")
                    fun notice(text: String, tone: String) = show("pipeline", UiNotice(text, tone), back)
                    try {
                        val actionId = params["action"].orEmpty()
                        // Only the actions the board offers this user (same rule as its declaration).
                        val offered = CrmBoardActions.available(
                            canManage = permissions.has(CrmScreens.Manage), canAssign = context.canAssign,
                            canDelete = permissions.has(CrmScreens.Delete), isUser = context.users.any { it.id == context.scope.userId },
                            archivedColumn = true,
                        )
                        if (actionId !in offered) throw CrmActionNotice("Elige la accion.")
                        val cards = CrmBoardActions.parseCards(params["cards"])
                        val value = CrmBoardActions.checkValue(
                            actionId, params["value"],
                            if (actionId == CrmBoardActions.Move) context.stages.map { it.name } else context.users.map { it.name },
                        )
                        // Leads outside the user's scope (or gone) are never touched: CrmBulkActions leaves out the mention-only ones.
                        val found = cards.ids.mapNotNull { CrmRepository.lead(tenant, it) }
                            .filter { context.readAccess(it).canRead }
                        var rejected = cards.invalid + (cards.ids.size - found.size)
                        val requested = when (actionId) {
                            // Archived leads stay put (they are not in a stage).
                            CrmBoardActions.Move -> found.filter { it.archivedAt == null }.also { rejected += found.size - it.size }
                            CrmBoardActions.Restore -> found.filter { it.archivedAt != null }.also { rejected += found.size - it.size }
                            else -> found
                        }
                        val message = if (actionId == CrmBoardActions.AssignMe) {
                            val me = context.users.first { it.id == context.scope.userId }
                            CrmBulkActions.assign(context, requested, CrmAssignees.Mode.Add, listOf(me.id), actor, notifier)
                        } else {
                            CrmBulkActions.apply(context, requested, CrmBoardActions.bulkAction(actionId)!!, value, actor, notifier)
                        }
                        val extra = if (rejected > 0) " $rejected rechazados (no disponibles para esta accion)." else ""
                        notice("$message$extra", UiTone.Success)
                    } catch (failure: CrmActionNotice) {
                        notice(failure.message.orEmpty(), if (failure.warning) UiTone.Warning else UiTone.Danger)
                    }
                }
                "new:create" -> {
                    val input = try {
                        CrmForms.parseLead(values, context.stages, context.interests, context.interestsEnabled, context.users, context.fields, stay = context.stay)
                    } catch (failure: CrmValidationException) {
                        return show("new", UiNotice(failure.message.orEmpty(), UiTone.Danger), emptyMap(), values)
                    }
                    if (!context.scope.canUseInterest(input.interestId)) {
                        return show("new", UiNotice("No puedes crear leads de ese interes.", UiTone.Danger), emptyMap(), values)
                    }
                    val stage = context.stage(input.stageId)!!
                    // Without the assign permission the assigned user stays as the form defaulted it (the user).
                    val owned = if (context.canAssign) input else input.copy(ownerUserIds = listOfNotNull(context.scope.userId?.takeIf { id -> context.users.any { it.id == id } }))
                    val chosen = values["customerId"]?.let { runCatching { UUID.fromString(it) }.getOrNull() }
                    val id = CrmRepository.createLead(tenant, owned, stage, actor, chosen, context.fields)
                    CrmNotify.newLead(tenant, id)
                    CrmNotify.assigned(tenant, notifier, id, owned.ownerUserIds.orEmpty(), context.scope.userId)
                    show("lead", UiNotice("Lead creado.", UiTone.Success), mapOf("id" to id.toString()))
                }
                "lead:save" -> {
                    val lead = visibleLead("id") ?: return show("leads", UiNotice("Ese lead ya no esta disponible.", UiTone.Warning), emptyMap())
                    val parsed = try {
                        // Assigned users without access can be unchecked in the form (their labels end in "(sin acceso)"), never added.
                        CrmForms.parseLead(
                            values, context.stages, context.interests, context.interestsEnabled,
                            context.users + CrmRepository.legacyUsers(tenant, context.users, lead.ownerUserIds), context.fields, lead.customFields, context.stay,
                        )
                    } catch (failure: CrmValidationException) {
                        return show("lead", UiNotice(failure.message.orEmpty(), UiTone.Danger), submitted = values)
                    }
                    // Only assigners change the assigned users; for the rest they are not touched at all (null).
                    val input = parsed.copy(ownerUserIds = CrmAssignees.wanted(context.canAssign, parsed.ownerUserIds))
                    // The list the form was drawn with: only the difference with the new one is applied.
                    val before = params[CrmScreens.OwnersBeforeKey]?.let { raw -> raw.split(',').mapNotNull { runCatching { UUID.fromString(it.trim()) }.getOrNull() } }
                    if (input.interestId != lead.interestId && !context.scope.canUseInterest(input.interestId)) {
                        return show("lead", UiNotice("No puedes asignar ese interes.", UiTone.Danger), submitted = values)
                    }
                    val change = try {
                        CrmRepository.updateLead(tenant, lead, input, context.stages, context.names, actor, context.fields, before)
                    } catch (failure: CrmValidationException) {
                        return show("lead", UiNotice(failure.message.orEmpty(), UiTone.Danger), submitted = values)
                    }
                    // Only the users really added are told (not a stale picture of the list).
                    CrmNotify.assigned(tenant, notifier, lead.id, change.added, context.scope.userId)
                    val owners = CrmAssignees.of(tenant, lead.id)
                    if (context.scope.canSee(input.interestId, owners)) {
                        show("lead", UiNotice("Cambios guardados.", UiTone.Success))
                    } else {
                        show("leads", UiNotice("Cambios guardados. El lead paso a un interes que no ves.", UiTone.Success), emptyMap())
                    }
                }
                "lead:restore" -> {
                    val lead = visibleLead("id") ?: return show("leads", UiNotice("Ese lead ya no esta disponible.", UiTone.Warning), emptyMap())
                    val check = restoreCheck(lead.archivedAt != null, permissions.has(CrmScreens.Manage))
                    // Back to the view the button was in; "lead" (the detail) when it came from there.
                    val back = params["from"]?.takeIf { it == "pipeline" || it == "leads" || it == "list" }
                    fun after(notice: UiNotice) = if (back == "list") show("leads", notice, params - setOf("op", "id", "from") + (CrmScreens.ViewKey to CrmScreens.ViewList))
                        else if (back != null) show(back, notice, params - setOf("op", "id", "from")) else show("lead", notice, mapOf("id" to lead.id.toString()))
                    when (check) {
                        CrmRestoreCheck.Restore ->
                            // False: someone restored it between the check and the update.
                            if (CrmRepository.restoreLead(tenant, lead.id, actor)) {
                                after(UiNotice("${lead.name} se restauro en ${context.stage(lead.stageId)?.name ?: "su etapa"}.", UiTone.Success))
                            } else {
                                after(UiNotice("${lead.name} ya estaba abierto.", UiTone.Warning))
                            }
                        CrmRestoreCheck.AlreadyOpen -> after(UiNotice("${lead.name} ya estaba abierto.", UiTone.Warning))
                        CrmRestoreCheck.NoPermission -> after(UiNotice("No tienes permiso para restaurar leads.", UiTone.Danger))
                    }
                }
                "lead:note" -> {
                    // The one write a mention allows. Scope: needs crm.leads.manage like before; mention: only the active mention.
                    val lead = readableLead("id") ?: return show("leads", UiNotice("Ese lead ya no esta disponible.", UiTone.Warning), emptyMap())
                    if (!permissions.has(CrmScreens.View) || !CrmMentions.canAddNote(context.readAccess(lead), permissions.has(CrmScreens.Manage))) {
                        return show("lead", UiNotice("No tienes permiso para agregar notas a este lead.", UiTone.Danger), mapOf("id" to lead.id.toString()))
                    }
                    val body = CrmForms.note(values)
                    val chosen = CrmForms.mentionNames(values)
                    val unmatched = CrmAssignees.match(chosen, context.mentionable).unmatched
                    if (unmatched.isNotEmpty()) {
                        return show("lead", UiNotice("Ya no se puede mencionar a: ${unmatched.joinToString()}.", UiTone.Danger), mapOf("id" to lead.id.toString()))
                    }
                    val result = CrmMentions.addNote(tenant, lead.id, body, chosen, context.mentionable, actor, CrmMentions.canGrant(context.readAccess(lead)))
                    CrmNotify.mentioned(tenant, notifier, lead.id, body, actor, result.mentioned)
                    val told = if (result.mentioned.isEmpty()) "" else " Se aviso a ${result.mentioned.joinToString { it.name }}."
                    val plain = if (result.granted) "" else " Tus @ quedan como texto: solo quien gestiona el lead puede dar acceso al mencionar."
                    val unknown = if (result.unknown.isEmpty()) "" else " No se reconocio: ${result.unknown.joinToString { "@$it" }}."
                    show("lead", UiNotice("Nota agregada.$told$unknown$plain", if (result.unknown.isEmpty()) UiTone.Success else UiTone.Warning), mapOf("id" to lead.id.toString()))
                }
                "lead:unmention" -> {
                    // Whoever manages the lead (permission + lead inside their scope) takes a mention's access away.
                    val lead = visibleLead("id") ?: return show("leads", UiNotice("Ese lead ya no esta disponible.", UiTone.Warning), emptyMap())
                    val user = uuid("user") ?: return show("lead", UiNotice("Usuario invalido.", UiTone.Danger), mapOf("id" to lead.id.toString()))
                    val revoked = CrmMentions.revoke(tenant, lead.id, user, context.names, actor)
                    show("lead", UiNotice(if (revoked) "Acceso quitado." else "Ese usuario ya no tenia acceso por mencion.", if (revoked) UiTone.Success else UiTone.Warning), mapOf("id" to lead.id.toString()))
                }
                "lead:send" -> {
                    val lead = visibleLead("id") ?: return show("leads", UiNotice("Ese lead ya no esta disponible.", UiTone.Warning), emptyMap())
                    val translationNotes = mutableListOf<String>()
                    val (channel, email) = try {
                        CrmAdminScreens.composeReply(context, lead, values, translationNotes)
                    } catch (failure: CrmValidationException) {
                        return show("lead", UiNotice(failure.message.orEmpty(), UiTone.Danger), submitted = values)
                    }
                    // A template that could not be translated goes out in its original language; the lead's activity says so.
                    translationNotes.forEach { CrmRepository.addActivity(tenant, lead.id, "item", it, actor) }
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
                    val lead = visibleLead("id") ?: return show("reception", UiNotice("Ese lead ya no esta disponible.", UiTone.Warning), emptyMap())
                    val approve = params["op"] == "approve"
                    val resolved = CrmRepository.resolveSuggestion(tenant, lead, approve, context.names, actor)
                    CrmNotify.assigned(tenant, notifier, lead.id, listOfNotNull(resolved.added), context.scope.userId)
                    val notice = when {
                        resolved.noAccess -> UiNotice("El usuario sugerido ya no tiene acceso: se descarto la sugerencia y no se asigno a nadie.", UiTone.Warning)
                        approve -> UiNotice("Asignado: se anadio a los asignados del lead.", UiTone.Success)
                        else -> UiNotice("Sugerencia descartada.", UiTone.Success)
                    }
                    // From "Sin calificar" the user goes back to the list; from the lead, stays on it.
                    if (params["from"] == "reception" && CrmScreens.canOpen("reception", permissions)) show("reception", notice, emptyMap()) else show("lead", notice)
                }
                "lead:button" -> {
                    // A button field of the lead detail: its template or manual automations run as the user who pressed it.
                    val lead = visibleLead("id") ?: return show("leads", UiNotice("Ese lead ya no esta disponible.", UiTone.Warning), emptyMap())
                    val back = mapOf("id" to lead.id.toString())
                    val field = context.fields.firstOrNull { it.key == params["field"] && it.type == CrmFieldType.Button }
                        ?: return show("lead", UiNotice("Ese boton ya no existe.", UiTone.Warning), back)
                    if (!context.readAccess(lead).canWrite) return show("lead", UiNotice("No tienes permiso para ejecutar este boton en este lead.", UiTone.Danger), back)
                    val result = CrmButtons.press(tenant, lead, field, actor, notifier)
                    show("lead", UiNotice(result.text, result.tone), back)
                }
                "automations:save" -> {
                    val saved = try {
                        CrmAutomationScreens.save(context, uuid("automation"), values)
                    } catch (failure: CrmValidationException) {
                        return show("automations", UiNotice(failure.message.orEmpty(), UiTone.Danger), params - "op", values)
                    }
                    show("automations", UiNotice(saved.message, if (saved.warning) UiTone.Warning else UiTone.Success), emptyMap())
                }
                "automations:toggle" -> {
                    val automation = uuid("automation")?.let { CrmAutomationStore.find(tenant, it) }
                        ?: return show("automations", UiNotice("Esa automatizacion ya no existe.", UiTone.Warning), emptyMap())
                    val saved = try {
                        CrmAutomationScreens.setEnabled(context, automation, params["enabled"] == "true")
                    } catch (failure: CrmValidationException) {
                        return show("automations", UiNotice(failure.message.orEmpty(), UiTone.Danger), emptyMap())
                    }
                    show("automations", UiNotice(saved.message, if (saved.warning) UiTone.Warning else UiTone.Success), emptyMap())
                }
                "automations:delete" -> {
                    val deleted = uuid("automation")?.let { CrmAutomationStore.delete(tenant, it) } == true
                    show("automations", UiNotice(if (deleted) "Automatizacion eliminada." else "Esa automatizacion ya no existe.", if (deleted) UiTone.Success else UiTone.Warning), emptyMap())
                }
                "automations:welcomeOff" -> {
                    CrmHost.setWelcomeEnabled(tenant, false)
                    show("automations", UiNotice("Bienvenida de Cloudbeds apagada: solo saldra el correo de tus automatizaciones.", UiTone.Success), emptyMap())
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
                "templates:preview" -> {
                    // Preview of a template for a lead the user can see (translated when the template asks for it).
                    val template = uuid("id")?.let { id -> CrmMail.templates(tenant).firstOrNull { it.id == id } }
                        ?: return show("templates", UiNotice("Esa plantilla ya no existe.", UiTone.Warning), emptyMap())
                    val back = mapOf("template" to template.id.toString())
                    val lead = CrmAdminScreens.previewLead(context, values["previewLead"].orEmpty())
                        ?: return show("templates", UiNotice("Elige un lead para la vista previa.", UiTone.Warning), back, values)
                    val mail = CrmTemplateMailer.renderFor(tenant, template, lead)
                    show("templates", UiNotice(CrmAdminScreens.previewText(template, lead, mail), if (mail.note == null) UiTone.Success else UiTone.Warning), back, values)
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
                            // Not sent (an old form): keeps what was saved instead of turning it off.
                            values["notifyMentioned"]?.let { it == "true" } ?: CrmNotify.userSettings(tenant)[user.id]?.notifyMentioned ?: true,
                            notifyNewMessage = values["notifyNewMessage"]?.let { it == "true" } ?: CrmNotify.userSettings(tenant)[user.id]?.notifyNewMessage ?: true,
                            notifyFollowUp = values["notifyFollowUp"]?.let { it == "true" } ?: CrmNotify.userSettings(tenant)[user.id]?.notifyFollowUp ?: true,
                        ),
                    )
                    show("team", UiNotice("Avisos de ${user.name} guardados.", UiTone.Success), emptyMap())
                }
                "settings:digest", "team:digest" -> {
                    val hour = values["digestHour"].orEmpty().trim().toIntOrNull()?.takeIf { it in 0..23 }
                        ?: return show("settings", UiNotice("La hora debe ser un numero entre 0 y 23.", UiTone.Danger), emptyMap())
                    tenant.prepareStatement("UPDATE crm_settings SET digest_hour = ? WHERE id = 1").use { it.setInt(1, hour); it.executeUpdate() }
                    show("settings", UiNotice("El resumen diario se enviara a las $hour:00.", UiTone.Success), emptyMap())
                }
                "settings:exitSave" -> {
                    val config = try {
                        CrmExitScreens.parse(context, values)
                    } catch (failure: CrmValidationException) {
                        return show("automations", UiNotice(failure.message.orEmpty(), UiTone.Danger), params - "op", values)
                    }
                    // Turning it on archives every lead already past its check-out: ask first.
                    val pending = CrmExit.pendingOnActivation(tenant, CrmExit.load(tenant), config, java.time.Instant.now())
                    if (pending > 0 && values["exitConfirm"] != "true") {
                        return show(
                            "automations",
                            UiNotice("Al activar se archivaran $pending leads con salida vencida. Marca la confirmacion y guarda de nuevo.", UiTone.Warning),
                            params - "op", values + ("exitConfirm" to "false"),
                        )
                    }
                    CrmExit.save(tenant, config)
                    show("automations", UiNotice(if (config.enabled) "Automatizacion de salida guardada." else "Automatizacion de salida desactivada.", UiTone.Success), emptyMap())
                }
                "integrations:metaSave" -> {
                    val merged = try {
                        CrmHost.metaMerge(CrmHost.metaSettings(tenant), values)
                    } catch (failure: CrmValidationException) {
                        return show("integrations", UiNotice(failure.message.orEmpty(), UiTone.Danger), emptyMap(), values)
                    }
                    CrmHost.saveMeta(tenant, merged)
                    // Saving connects everything at once: page token and Instagram, and the WhatsApp account.
                    if (!merged.enabled || merged.systemToken == null) return show("integrations", UiNotice("Meta guardado.", UiTone.Success), emptyMap())
                    val setup = CrmHost.metaSetup(tenant, merged)
                    show(
                        "integrations",
                        UiNotice("Meta guardado. ${setup.notice}".trim(), if (setup.problems.isEmpty()) UiTone.Success else UiTone.Warning),
                        metaNumbers(setup),
                    )
                }
                "integrations:metaServerApp" -> {
                    // Only the server owner, from the server workspace, configures the server app.
                    if (!permissions.serverOwner || CrmHost.metaServerWorkspace() != permissions.companyCode) {
                        return show("integrations", UiNotice("Solo el dueno del servidor configura la app de Meta del servidor, desde el espacio del servidor.", UiTone.Danger), emptyMap())
                    }
                    val app = try {
                        CrmHost.metaMergeApp(CrmHost.metaLoadApp(tenant), values)
                    } catch (failure: CrmValidationException) {
                        return show("integrations", UiNotice(failure.message.orEmpty(), UiTone.Danger), emptyMap(), values)
                    }
                    CrmHost.metaSaveApp(tenant, app)
                    show("integrations", UiNotice(if (app.enabled) "App de Meta del servidor guardada: las empresas ya pueden conectarse." else "App de Meta del servidor guardada (desactivada).", UiTone.Success), emptyMap())
                }
                "integrations:metaMode" -> {
                    val mode = params["mode"].takeIf { it == OwnMode || it == ServerMode }
                        ?: return show("integrations", UiNotice("Modo invalido.", UiTone.Danger), emptyMap())
                    val current = CrmHost.metaSettings(tenant)
                    if (mode == ServerMode && CrmHost.metaServerApp()?.ready != true) {
                        return show("integrations", UiNotice("El servidor no tiene una app de Meta activa.", UiTone.Warning), emptyMap())
                    }
                    if (mode != current.connectionMode) {
                        // Tokens of one mode are useless in the other: the company connects again.
                        if (current.serverMode) CrmHost.metaDisconnect(tenant, permissions.companyCode)
                        CrmHost.saveMeta(
                            tenant,
                            CrmHost.metaSettings(tenant).copy(
                                connectionMode = mode, enabled = false, appSecret = null, verifyToken = null, systemToken = null,
                                pageId = null, pageName = null, pageToken = null, instagramAccountId = null, instagramUsername = null,
                                wabaId = null, phoneNumberId = null, messengerToken = null, instagramToken = null, sharedTokens = true,
                                oauthToken = null, metaUserId = null,
                            ),
                        )
                        CrmHost.metaLog(tenant, "modo", if (mode == ServerMode) "app del servidor" else "app propia")
                    }
                    show("integrations", UiNotice(if (mode == ServerMode) "Ahora conecta la empresa con Meta." else "Configura la app propia de la empresa.", UiTone.Success), emptyMap())
                }
                "integrations:metaLink" -> {
                    if (CrmHost.metaServerApp()?.ready != true) return show("integrations", UiNotice("El servidor no tiene una app de Meta activa.", UiTone.Warning), emptyMap())
                    val ticket = CrmHost.metaNewTicket(tenant, actor.name)
                    show(
                        "integrations",
                        UiNotice("Abre la ventana de Meta, inicia sesion y elige la pagina y el numero. El enlace vence en 30 minutos.", UiTone.Info),
                        mapOf("link" to CrmHost.metaConnectUrl(publicBase, permissions.companyCode, ticket)),
                    )
                }
                "integrations:metaDisconnect" -> {
                    CrmHost.metaDisconnect(tenant, permissions.companyCode)
                    show("integrations", UiNotice("Meta desconectado: ya no se reciben ni se envian mensajes por WhatsApp, Messenger ni Instagram.", UiTone.Success), emptyMap())
                }
                "integrations:metaServerOptions" -> {
                    CrmHost.saveMeta(tenant, CrmHost.metaSettings(tenant).copy(recordEchoes = values["recordEchoes"] == "true"))
                    show("integrations", UiNotice("Opciones de Meta guardadas.", UiTone.Success), emptyMap())
                }
                "integrations:e24Save" -> {
                    try {
                        CrmHost.saveEncuentra24Form(tenant, values)
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
                        val result = CrmHost.encuentra24Sync(tenant, from)
                        UiNotice("Encuentra24: $result.", UiTone.Success)
                    } catch (failure: CrmEncuentra24Exception) {
                        UiNotice(failure.message.orEmpty(), UiTone.Danger)
                    }
                    show("integrations", notice, emptyMap())
                }
                "integrations:wpSave" -> {
                    try {
                        CrmHost.saveWordPressForm(tenant, values)
                    } catch (failure: CrmValidationException) {
                        return show("integrations", UiNotice(failure.message.orEmpty(), UiTone.Danger), emptyMap())
                    }
                    show("integrations", UiNotice("WordPress guardado.", UiTone.Success), emptyMap())
                }
                "integrations:wpSecret" -> {
                    val secret = try {
                        CrmHost.wordpressNewSecret(tenant)
                    } catch (failure: CrmValidationException) {
                        return show("integrations", UiNotice(failure.message.orEmpty(), UiTone.Danger), emptyMap())
                    }
                    show("integrations", UiNotice("Secreto nuevo generado; pegalo en el plugin.", UiTone.Success), mapOf("secret" to secret))
                }
                "integrations:wpPing" -> {
                    val notice = try {
                        UiNotice("Conexion correcta: ${CrmHost.wordpressPing(tenant)}.", UiTone.Success)
                    } catch (failure: CrmWordPressException) {
                        CrmHost.recordWordPressError(tenant, failure.message.orEmpty())
                        UiNotice(failure.message.orEmpty(), UiTone.Danger)
                    }
                    show("integrations", notice, emptyMap())
                }
                "integrations:wpAgentsSync" -> {
                    val notice = try {
                        val emails = CrmNotify.userSettings(tenant).mapValues { it.value.email }
                        UiNotice("${CrmHost.wordpressSyncAgents(tenant, context.users, emails)} agente(s) del sitio sincronizados.", UiTone.Success)
                    } catch (failure: CrmWordPressException) {
                        UiNotice(failure.message.orEmpty(), UiTone.Danger)
                    }
                    show("integrations", notice, emptyMap())
                }
                "integrations:wpAgentsSave" -> {
                    CrmHost.wordpressAgents(tenant).forEach { agent ->
                        val chosen = values["agent_${agent.wpUserId}"] ?: return@forEach
                        CrmHost.wordpressMapAgent(tenant, agent.wpUserId, context.users.firstOrNull { it.name == chosen }?.id)
                    }
                    show("integrations", UiNotice("Agentes guardados.", UiTone.Success), emptyMap())
                }
                "properties:link" -> {
                    val lead = visibleLead("lead") ?: return show("leads", UiNotice("Ese lead ya no esta disponible.", UiTone.Warning), emptyMap())
                    val listingId = params["listing"]?.toLongOrNull() ?: return show("properties", UiNotice("Propiedad invalida.", UiTone.Danger), params - setOf("op", "listing"))
                    val notice = try {
                        val listing = CrmHost.wordpressListing(tenant, listingId)
                        CrmHost.wordpressLinkListing(tenant, lead.id, listing, "Propiedad vinculada por ${actor.name}: ${listing.title}.")
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
                "integrations:staySave" -> {
                    val lost = try {
                        CrmStay.save(tenant, CrmStay.parseSelection(values, context.fields))
                    } catch (failure: CrmValidationException) {
                        return show("integrations", UiNotice(failure.message.orEmpty(), UiTone.Danger), emptyMap())
                    }
                    show("integrations", UiNotice("Fechas de estadia guardadas.${CrmStay.lostNote(lost)}", if (lost > 0) UiTone.Warning else UiTone.Success), emptyMap())
                }
                "integrations:translationSave" -> {
                    try {
                        CrmTranslate.save(tenant, CrmTranslate.merge(CrmTranslate.load(tenant), values, context.fields))
                    } catch (failure: CrmValidationException) {
                        return show("integrations", UiNotice(failure.message.orEmpty(), UiTone.Danger), emptyMap(), values)
                    }
                    show("integrations", UiNotice("Traduccion guardada.", UiTone.Success), emptyMap())
                }
                "integrations:cbSave" -> {
                    val provider = CrmModuleExtensions.cloudbeds()
                        ?: return show("integrations", UiNotice(CrmCloudbedsMissing, UiTone.Danger), emptyMap())
                    try {
                        provider.saveSettings(CrmHost, tenant, values, CrmMail.templates(tenant))
                    } catch (failure: CrmValidationException) {
                        return show("integrations", UiNotice(failure.message.orEmpty(), UiTone.Danger), emptyMap())
                    }
                    show("integrations", UiNotice("Cloudbeds guardado.", UiTone.Success), emptyMap())
                }
                "integrations:cbTest" -> {
                    val provider = CrmModuleExtensions.cloudbeds()
                        ?: return show("integrations", UiNotice(CrmCloudbedsMissing, UiTone.Danger), emptyMap())
                    val notice = try {
                        UiNotice("Conexion correcta con Cloudbeds: ${provider.test(CrmHost, tenant)}.", UiTone.Success)
                    } catch (failure: CrmCloudbedsException) {
                        UiNotice(failure.message.orEmpty(), UiTone.Danger)
                    }
                    show("integrations", notice, emptyMap())
                }
                "import:text", "import:url" -> {
                    val (source, name, table) = try {
                        when (params["op"]) {
                            "text" -> Triple("file", values["name"].orEmpty().trim().ifEmpty { "CSV pegado ${context.today}" }, CrmImportParse.csv(values["csv"].orEmpty()))
                            "url" -> values["url"].orEmpty().trim().let { url -> Triple("file", url.substringAfterLast('/').take(80).ifEmpty { "Archivo" }, CrmImport.fromUrl(url)) }
                            else -> return show("import", UiNotice("Operacion de importacion desconocida.", UiTone.Danger), emptyMap())
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
                "leads:bulk", "leads:apply" -> {
                    // Back to the list view, with the same filters and page.
                    val keep = params - setOf("op") + (CrmScreens.ViewKey to CrmScreens.ViewList)
                    // Only the leads of the page the checkboxes were drawn for (same filters and page), never others.
                    // The ones seen only through a mention are never part of it: they are read-only.
                    val pageLeads = CrmScreens.listPage(context, params).writable(context)
                    val chosen = CrmBulkActions.selectedIds(values, pageLeads.map { it.id }).toSet()
                    val selected = pageLeads.filter { it.id in chosen }
                    val action = values["action"].orEmpty()
                    val value = if (action == CrmImportScreens.ActionAddOwner || action == CrmImportScreens.ActionRemoveOwner) values["owner"].orEmpty() else values["stage"].orEmpty()
                    try {
                        show("leads", UiNotice(CrmBulkActions.apply(context, selected, action, value, actor, notifier), UiTone.Success), keep)
                    } catch (notice: CrmActionNotice) {
                        show("leads", UiNotice(notice.message.orEmpty(), if (notice.warning) UiTone.Warning else UiTone.Danger), keep)
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
                    CrmHost.saveMeta(tenant, CrmHost.metaSettings(tenant).copy(phoneNumberId = id))
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
                        CrmFields.save(
                            tenant, editing?.id,
                            CrmFields.parseDefinition(
                                values, editing, context.sections, CrmMail.templates(tenant),
                                CrmAutomationStore.all(tenant).filter { it.trigger.type == CrmTriggerType.Manual },
                            ),
                            context.fields,
                        )
                    } catch (failure: CrmValidationException) {
                        return show("settings", UiNotice(failure.message.orEmpty(), UiTone.Danger), params - "op", values)
                    }
                    show("settings", UiNotice("Campo ${field.label} guardado.", UiTone.Success), emptyMap())
                }
                "settings:sectionSave" -> {
                    val id = uuid("id")
                    val section = try {
                        CrmFieldSections.save(tenant, id, CrmFieldSections.parse(values, context.sections, id), context.sections)
                    } catch (failure: CrmValidationException) {
                        return show("settings", UiNotice(failure.message.orEmpty(), UiTone.Danger), params - "op", values)
                    }
                    show("settings", UiNotice("Seccion ${section.name} guardada.", UiTone.Success), emptyMap())
                }
                "settings:sectionDelete" -> {
                    val section = uuid("id")?.let { id -> context.sections.firstOrNull { it.id == id } }
                        ?: return show("settings", UiNotice("Esa seccion ya no existe.", UiTone.Warning), emptyMap())
                    try {
                        CrmFieldSections.delete(tenant, section)
                    } catch (failure: CrmValidationException) {
                        return show("settings", UiNotice(failure.message.orEmpty(), UiTone.Danger), emptyMap())
                    }
                    show("settings", UiNotice("Seccion ${section.name} eliminada; sus campos quedaron en ${CrmFields.Section}.", UiTone.Success), emptyMap())
                }
                "settings:sectionMove" -> {
                    val moved = uuid("id")?.let { CrmFieldSections.move(tenant, context.sections, it, params["dir"] == "up") } == true
                    show("settings", UiNotice(if (moved) "Orden de las secciones guardado." else "Esa seccion no se puede mover ahi (Datos del cliente va siempre primero).", if (moved) UiTone.Success else UiTone.Warning), emptyMap())
                }
                "settings:fieldDelete" -> {
                    val field = uuid("id")?.let { id -> context.fields.firstOrNull { it.id == id } }
                        ?: return show("settings", UiNotice("Ese campo ya no existe.", UiTone.Warning), emptyMap())
                    val lost = CrmFields.delete(tenant, field)
                    show("settings", UiNotice("Campo ${field.label} eliminado.${CrmStay.lostNote(lost)}", if (lost > 0) UiTone.Warning else UiTone.Success), emptyMap())
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
    private fun metaNumbers(setup: MetaSetup): Map<String, String> =
        if (setup.numbers.isEmpty()) emptyMap()
        else mapOf("numbers" to setup.numbers.joinToString(";") { listOf(it.id, it.number, it.name, it.platform).joinToString("|") { part -> part.replace(";", ",").replace("|", "/") } })

    // https://host of this server as the client reached it (behind the reverse proxy when there is one).
    private fun ApplicationCall.publicBase(): String {
        val scheme = request.headers["X-Forwarded-Proto"]?.substringBefore(',')?.trim() ?: request.origin.scheme
        val host = request.headers["X-Forwarded-Host"]?.substringBefore(',')?.trim() ?: request.headers["Host"] ?: request.origin.serverHost
        return "$scheme://$host"
    }

    private suspend fun ApplicationCall.screenId(permissions: ModulePermissions, canonical: Boolean = true): String? {
        val requested = parameters["screen"].orEmpty()
        val screen = if (requested == "home") CrmScreens.home(permissions) else requested.takeIf { CrmScreens.isScreen(it) }?.let { if (canonical) CrmScreens.canonical(it) else it }
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
