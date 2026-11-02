package com.ideasdeveloper.idc.modules.crm

import com.ideasdeveloper.idc.server.modules.McpSchema
import com.ideasdeveloper.idc.server.modules.McpTool
import com.ideasdeveloper.idc.server.modules.McpToolCall
import com.ideasdeveloper.idc.server.modules.McpToolException
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import java.sql.Connection
import java.util.UUID

/**
 * Agent tools of the CRM settings (permission crm.settings), with the same parsing and validations
 * as the settings screens. Edits change only what the agent sends: the current values are the base,
 * like the screens' forms. Passwords (SMTP, IMAP) and the integrations (Meta, WordPress, Cloudbeds,
 * Encuentra24) and imports are not offered, so no credential passes through the agent.
 */
object CrmSettingsMcpTools {
    private fun list(description: String) = buildJsonObject {
        put("type", "array")
        put("description", description)
        put("items", buildJsonObject { put("type", "string") })
    }

    private val stageArguments = mapOf(
        "name" to McpSchema.string("Nombre de la etapa, hasta 60 caracteres."),
        "kind" to McpSchema.string("Tipo: open (abierta), won (ganada) o lost (perdida).", StageKind.entries.map { it.id }),
        "tone" to McpSchema.string("Color.", StageTone.entries.map { it.label }),
        "position" to McpSchema.integer("Orden en el embudo; menor aparece antes.", 0, 999),
    )

    private val mailArguments = mapOf(
        "smtpMode" to McpSchema.string("servidor: SMTP del servidor; empresa: cuenta propia.", listOf("servidor", "empresa")),
        "smtpHost" to McpSchema.string("Servidor SMTP de la cuenta propia."),
        "smtpPort" to McpSchema.integer("Puerto SMTP.", 1, 65535),
        "smtpUsername" to McpSchema.string("Usuario SMTP."),
        "smtpFrom" to McpSchema.string("Remitente de los correos.", format = "email"),
        "smtpSecurity" to McpSchema.string("Seguridad SMTP.", listOf("starttls", "ssl", "none")),
        "imapEnabled" to McpSchema.boolean("Leer las respuestas del buzon por IMAP (necesita la contrasena IMAP guardada en la app)."),
        "imapHost" to McpSchema.string("Servidor IMAP."),
        "imapPort" to McpSchema.integer("Puerto IMAP.", 1, 65535),
        "imapUsername" to McpSchema.string("Usuario IMAP."),
        "imapFolder" to McpSchema.string("Carpeta a leer, por defecto INBOX."),
        "imapSsl" to McpSchema.boolean("Conectar a IMAP con SSL."),
    )

    private val spamArguments = mapOf(
        "words" to list("Palabras o frases que bloquean un mensaje, hasta 40 caracteres cada una. Reemplaza la lista."),
        "maxLinks" to McpSchema.integer("Maximo de enlaces por mensaje.", 0, 50),
        "blockCyrillic" to McpSchema.boolean("Bloquear mensajes con letras cirilicas."),
        "blockedDomains" to list("Dominios de correo bloqueados, como ejemplo.com. Reemplaza la lista."),
        "channels" to buildJsonObject {
            put("type", "array")
            put("description", "Canales donde se aplican las reglas. Reemplaza la lista.")
            put("items", McpSchema.string("Canal.", CrmSpam.channelLabels.keys.toList()))
        },
        "skipKnownLeads" to McpSchema.boolean("No revisar a quien ya es un lead."),
    )

    private const val Confirm = " Cambia el CRM de toda la empresa: confirma con el usuario antes."

    val tools = listOf(
        McpTool(
            name = "get_settings",
            title = "Configuracion del CRM",
            description = "Devuelve la configuracion del CRM: intereses y quien ve cada uno, etapas, plantillas de etapas, correo (sin contrasenas), " +
                "variables de las plantillas de mensajes, reglas anti-spam, avisos del equipo y hora del resumen diario.",
            permission = CrmScreens.Settings,
            readOnly = true,
        ) { call -> settings(call) },
        McpTool(
            name = "set_interests_enabled",
            title = "Activar o desactivar intereses",
            description = "Con intereses, cada usuario ve solo los leads de sus intereses; sin intereses, todos los usuarios del CRM ven todos los leads.$Confirm",
            permission = CrmScreens.Settings,
            inputSchema = McpSchema.obj(mapOf("enabled" to McpSchema.boolean("true activa los intereses.")), required = listOf("enabled")),
        ) { call -> setInterestsEnabled(call) },
        McpTool(
            name = "save_interest",
            title = "Crear o renombrar interes",
            description = "Crea un interes (sin id) o renombra uno existente (con id de get_settings).",
            permission = CrmScreens.Settings,
            inputSchema = McpSchema.obj(
                mapOf("id" to McpSchema.uuid("Id del interes a renombrar."), "name" to McpSchema.string("Nombre, hasta 60 caracteres.")),
                required = listOf("name"),
            ),
        ) { call -> saveInterest(call) },
        McpTool(
            name = "delete_interest",
            title = "Eliminar interes",
            description = "Elimina un interes; sus leads pasan a Recepcion (sin interes).$Confirm",
            permission = CrmScreens.Settings,
            inputSchema = McpSchema.obj(mapOf("id" to McpSchema.uuid("Id del interes.")), required = listOf("id")),
        ) { call -> deleteInterest(call) },
        McpTool(
            name = "set_user_interests",
            title = "Intereses de un usuario",
            description = "Define que intereses ve un usuario del CRM (reemplaza los actuales).",
            permission = CrmScreens.Settings,
            inputSchema = McpSchema.obj(
                mapOf(
                    "user" to McpSchema.string("Nombre o id del usuario (get_settings)."),
                    "interests" to list("Nombres o ids de los intereses; lista vacia le quita todos."),
                ),
                required = listOf("user", "interests"),
            ),
        ) { call -> setUserInterests(call) },
        McpTool(
            name = "save_stage",
            title = "Crear o editar etapa",
            description = "Crea una etapa del embudo (sin id; name obligatorio) o edita una existente (con id; solo cambia lo enviado).",
            permission = CrmScreens.Settings,
            inputSchema = McpSchema.obj(stageArguments + ("id" to McpSchema.uuid("Id de la etapa a editar."))),
        ) { call -> saveStage(call) },
        McpTool(
            name = "delete_stage",
            title = "Eliminar etapa",
            description = "Elimina una etapa sin leads (mueve antes sus leads a otra). El embudo necesita al menos una etapa.$Confirm",
            permission = CrmScreens.Settings,
            inputSchema = McpSchema.obj(mapOf("id" to McpSchema.uuid("Id de la etapa.")), required = listOf("id")),
        ) { call -> deleteStage(call) },
        McpTool(
            name = "apply_pipeline_template",
            title = "Aplicar plantilla de etapas",
            description = "Reemplaza todas las etapas por las de una plantilla (ids en get_settings). Solo mientras la empresa no tiene leads.$Confirm",
            permission = CrmScreens.Settings,
            destructive = true,
            inputSchema = McpSchema.obj(mapOf("template" to McpSchema.string("Id de la plantilla.", CrmTemplates.all.map { it.id })), required = listOf("template")),
        ) { call -> applyTemplate(call) },
        McpTool(
            name = "update_mail_settings",
            title = "Cambiar correo del CRM",
            description = "Cambia como envia (SMTP) y lee (IMAP) correos el CRM. Solo cambia lo enviado. Las contrasenas se escriben en la app.$Confirm",
            permission = CrmScreens.Settings,
            inputSchema = McpSchema.obj(mailArguments),
        ) { call -> updateMail(call) },
        McpTool(
            name = "save_message_template",
            title = "Crear o editar plantilla de mensaje",
            description = "Crea una plantilla de mensaje (sin id) o edita una (con id; solo cambia lo enviado). Admite las variables {nombre} de get_settings.",
            permission = CrmScreens.Settings,
            inputSchema = McpSchema.obj(
                mapOf(
                    "id" to McpSchema.uuid("Id de la plantilla a editar (get_settings)."),
                    "name" to McpSchema.string("Nombre, hasta 80 caracteres."),
                    "subject" to McpSchema.string("Asunto, hasta 300 caracteres."),
                    "body" to McpSchema.string("Contenido (texto o HTML), hasta 50000 caracteres."),
                ),
            ),
        ) { call -> saveMessageTemplate(call) },
        McpTool(
            name = "delete_message_template",
            title = "Eliminar plantilla de mensaje",
            description = "Elimina una plantilla de mensaje.$Confirm",
            permission = CrmScreens.Settings,
            destructive = true,
            inputSchema = McpSchema.obj(mapOf("id" to McpSchema.uuid("Id de la plantilla.")), required = listOf("id")),
        ) { call -> deleteMessageTemplate(call) },
        McpTool(
            name = "update_spam_rules",
            title = "Cambiar reglas anti-spam",
            description = "Cambia las reglas que bloquean leads y mensajes entrantes. Solo cambia lo enviado; las listas enviadas reemplazan las actuales.$Confirm",
            permission = CrmScreens.Settings,
            inputSchema = McpSchema.obj(spamArguments),
        ) { call -> updateSpam(call) },
        McpTool(
            name = "update_user_notifications",
            title = "Avisos de un usuario",
            description = "Cambia los avisos por correo de un usuario del CRM. Solo cambia lo enviado.",
            permission = CrmScreens.Settings,
            inputSchema = McpSchema.obj(
                mapOf(
                    "user" to McpSchema.string("Nombre o id del usuario (get_settings)."),
                    "email" to McpSchema.string("Correo para los avisos; vacio usa ninguno.", format = "email"),
                    "notifyNewLeads" to McpSchema.boolean("Aviso de cada lead nuevo (solo llega a quien ve todos los leads)."),
                    "notifyAssigned" to McpSchema.boolean("Aviso cuando le asignan un lead."),
                    "dailyDigest" to McpSchema.boolean("Resumen diario de sus leads."),
                ),
                required = listOf("user"),
            ),
        ) { call -> updateNotifications(call) },
        McpTool(
            name = "set_digest_hour",
            title = "Hora del resumen diario",
            description = "Hora (0 a 23, hora del servidor) a la que se envia el resumen diario.",
            permission = CrmScreens.Settings,
            inputSchema = McpSchema.obj(mapOf("hour" to McpSchema.integer("Hora del dia.", 0, 23)), required = listOf("hour")),
        ) { call -> setDigestHour(call) },
    )

    private fun context(call: McpToolCall) = CrmScreens.Context.load(call.tenant, call.permissions)

    // Validation errors of the CRM become errors the agent can act on.
    private inline fun <T> validated(block: () -> T): T = try {
        block()
    } catch (failure: CrmValidationException) {
        throw McpToolException(failure.message.orEmpty())
    }

    private fun result(message: String, vararg extra: Pair<String, String>) = buildJsonObject {
        put("result", message)
        extra.forEach { (key, value) -> put(key, value) }
    }

    private fun strings(call: McpToolCall, key: String): List<String> =
        (call.arguments[key] as? JsonArray)?.map { (it as? JsonPrimitive)?.contentOrNull?.trim() ?: throw McpToolException("'$key' debe ser una lista de textos.") }
            ?: throw McpToolException("'$key' debe ser una lista.")

    private fun user(call: McpToolCall, context: CrmScreens.Context): CrmUser {
        val wanted = call.requiredString("user", 200)
        return context.users.firstOrNull { it.id.toString() == wanted || it.name.equals(wanted, ignoreCase = true) }
            ?: throw McpToolException("'$wanted' no es un usuario con acceso al CRM. Usuarios: ${context.users.joinToString { it.name }}.")
    }

    private fun digestHour(tenant: Connection): Int =
        tenant.prepareStatement("SELECT digest_hour FROM crm_settings WHERE id = 1").use { query ->
            query.executeQuery().use { rows -> if (rows.next()) rows.getInt(1) else 7 }
        }

    private fun settings(call: McpToolCall): JsonElement {
        val context = context(call)
        val tenant = call.tenant
        val userInterests = CrmRepository.userInterests(tenant)
        val notifications = CrmNotify.userSettings(tenant)
        val mail = CrmMail.load(tenant)
        val spam = CrmSpam.load(tenant)
        fun text(value: String?) = value?.let(::JsonPrimitive) ?: JsonNull
        return buildJsonObject {
            put("interestsEnabled", context.interestsEnabled)
            putJsonArray("interests") {
                context.interests.forEach { add(buildJsonObject { put("id", it.id.toString()); put("name", it.name) }) }
            }
            putJsonArray("stages") {
                context.stages.forEach { stage ->
                    add(buildJsonObject {
                        put("id", stage.id.toString())
                        put("name", stage.name)
                        put("kind", stage.kind.id)
                        put("tone", stage.tone.label)
                        put("position", stage.position)
                        put("leads", CrmRepository.stageLeadCount(tenant, stage.id))
                    })
                }
            }
            putJsonArray("pipelineTemplates") {
                CrmTemplates.all.forEach { template ->
                    add(buildJsonObject {
                        put("id", template.id)
                        put("label", template.label)
                        put("stages", JsonArray(template.stages.map { JsonPrimitive(it.name) }))
                    })
                }
            }
            putJsonArray("users") {
                context.users.forEach { user ->
                    val saved = notifications[user.id]
                    add(buildJsonObject {
                        put("id", user.id.toString())
                        put("name", user.name)
                        put("interests", JsonArray(context.interests.filter { it.id in userInterests[user.id].orEmpty() }.map { JsonPrimitive(it.name) }))
                        put("notificationEmail", saved?.email.orEmpty())
                        put("notifyNewLeads", saved?.notifyNewLeads ?: false)
                        put("notifyAssigned", saved?.notifyAssigned ?: true)
                        put("dailyDigest", saved?.dailyDigest ?: false)
                    })
                }
            }
            put("digestHour", digestHour(tenant))
            put(
                "mail",
                buildJsonObject {
                    put("smtpMode", mail.smtpMode)
                    put("serverMailAvailable", call.access.serverMail != null)
                    put("smtpHost", text(mail.smtpHost))
                    put("smtpPort", mail.smtpPort)
                    put("smtpUsername", text(mail.smtpUsername))
                    put("smtpPasswordConfigured", !mail.smtpPassword.isNullOrBlank())
                    put("smtpFrom", text(mail.smtpFrom))
                    put("smtpSecurity", mail.smtpSecurity)
                    put("imapEnabled", mail.imapEnabled)
                    put("imapHost", text(mail.imapHost))
                    put("imapPort", mail.imapPort)
                    put("imapUsername", text(mail.imapUsername))
                    put("imapPasswordConfigured", !mail.imapPassword.isNullOrBlank())
                    put("imapFolder", mail.imapFolder)
                    put("imapSsl", mail.imapSsl)
                    if (mail.imapLastError.isNotEmpty()) put("imapLastError", mail.imapLastError)
                },
            )
            putJsonArray("messageTemplates") {
                CrmMail.templates(tenant).forEach { template ->
                    add(buildJsonObject {
                        put("id", template.id.toString())
                        put("name", template.name)
                        put("subject", template.subject)
                    })
                }
            }
            put("templateVariables", JsonObject(CrmMail.placeholders.map { (key, label) -> "{$key}" to JsonPrimitive(label) }.toMap()))
            put(
                "spam",
                buildJsonObject {
                    put("words", JsonArray(spam.words.map(::JsonPrimitive)))
                    put("maxLinks", spam.maxLinks)
                    put("blockCyrillic", spam.blockCyrillic)
                    put("blockedDomains", JsonArray(spam.blockedDomains.map(::JsonPrimitive)))
                    put("channels", JsonArray(spam.channels.sorted().map(::JsonPrimitive)))
                    put("skipKnownLeads", spam.skipKnownLeads)
                },
            )
        }
    }

    private fun setInterestsEnabled(call: McpToolCall): JsonElement {
        if (!call.has("enabled")) throw McpToolException("El argumento 'enabled' es obligatorio.")
        val enabled = call.boolean("enabled")
        CrmRepository.setInterestsEnabled(call.tenant, enabled)
        return result(if (enabled) "Intereses activados." else "Intereses desactivados: todos los usuarios del CRM ven todos los leads.")
    }

    private fun saveInterest(call: McpToolCall): JsonElement {
        val context = context(call)
        val id = call.uuid("id")
        if (id != null && context.interests.none { it.id == id }) throw McpToolException("Ese interes no existe; revisa get_settings.")
        val name = validated { CrmForms.parseInterestName(mapOf("name" to call.string("name", 100))) }
        if (context.interests.any { it.name.equals(name, ignoreCase = true) && it.id != id }) throw McpToolException("Ya existe el interes $name.")
        CrmRepository.saveInterest(call.tenant, id, name)
        return result("Interes $name guardado.")
    }

    private fun deleteInterest(call: McpToolCall): JsonElement {
        if (!CrmRepository.deleteInterest(call.tenant, call.requiredUuid("id"))) throw McpToolException("Ese interes no existe.")
        return result("Interes eliminado; sus leads estan en Recepcion.")
    }

    private fun setUserInterests(call: McpToolCall): JsonElement {
        val context = context(call)
        val user = user(call, context)
        val chosen = strings(call, "interests").map { wanted ->
            context.interests.firstOrNull { it.id.toString() == wanted || it.name.equals(wanted, ignoreCase = true) }
                ?: throw McpToolException("No existe el interes '$wanted'. Intereses: ${context.interests.joinToString { it.name }}.")
        }
        CrmRepository.setUserInterests(call.tenant, user.id, chosen.map { it.id }.toSet())
        return result("Intereses de ${user.name}: ${chosen.joinToString { it.name }.ifEmpty { "ninguno" }}.")
    }

    private fun saveStage(call: McpToolCall): JsonElement {
        val context = context(call)
        val id = call.uuid("id")
        val existing = id?.let { stageId -> context.stages.firstOrNull { it.id == stageId } ?: throw McpToolException("Esa etapa no existe; revisa get_settings.") }
        // Form values: the existing stage (or a new open one at the end) with what the agent sent on top.
        val values = mutableMapOf(
            "name" to existing?.name.orEmpty(),
            "kind" to (existing?.kind ?: StageKind.Open).label,
            "tone" to (existing?.tone ?: StageTone.Neutral).label,
            "position" to (existing?.position ?: ((context.stages.maxOfOrNull { it.position } ?: -1) + 1).coerceAtMost(999)).toString(),
        )
        if (call.has("name")) values["name"] = call.string("name", 100)
        if (call.has("kind")) {
            val kind = call.string("kind", 20)
            values["kind"] = StageKind.entries.firstOrNull { it.id == kind || it.label.equals(kind, ignoreCase = true) }?.label
                ?: throw McpToolException("'kind' debe ser open, won o lost.")
        }
        if (call.has("tone")) {
            val tone = call.string("tone", 20)
            values["tone"] = StageTone.entries.firstOrNull { it.label.equals(tone, ignoreCase = true) }?.label
                ?: throw McpToolException("'tone' debe ser ${StageTone.entries.joinToString { it.label }}.")
        }
        if (call.has("position")) values["position"] = call.int("position", 0, 0..999).toString()
        val input = validated { CrmForms.parseStage(values) }
        if (context.stages.any { it.name.equals(input.name, ignoreCase = true) && it.id != id }) throw McpToolException("Ya existe la etapa ${input.name}.")
        CrmRepository.saveStage(call.tenant, id, input)
        return result("Etapa ${input.name} guardada.")
    }

    private fun deleteStage(call: McpToolCall): JsonElement {
        val context = context(call)
        val id = call.requiredUuid("id")
        if (context.stages.size <= 1) throw McpToolException("El pipeline necesita al menos una etapa.")
        if (!validated { CrmRepository.deleteStage(call.tenant, id) }) throw McpToolException("Esa etapa no existe.")
        return result("Etapa eliminada.")
    }

    private fun applyTemplate(call: McpToolCall): JsonElement {
        val template = CrmTemplates.find(call.requiredString("template", 40))
            ?: throw McpToolException("Plantilla desconocida. Plantillas: ${CrmTemplates.all.joinToString { it.id }}.")
        validated { CrmRepository.applyTemplate(call.tenant, template) }
        return result("Etapas de ${template.label} aplicadas: ${template.stages.joinToString { it.name }}.")
    }

    private fun updateMail(call: McpToolCall): JsonElement {
        if (call.arguments.keys.none { it in mailArguments }) throw McpToolException("Envia al menos un campo a cambiar.")
        val current = CrmMail.load(call.tenant)
        // CrmMail.merge reads the whole mail form; blank passwords keep the saved ones.
        val values = mutableMapOf(
            "smtpMode" to if (current.smtpMode == "empresa") "Cuenta propia" else "SMTP del servidor",
            "smtpHost" to current.smtpHost.orEmpty(),
            "smtpPort" to current.smtpPort.toString(),
            "smtpUsername" to current.smtpUsername.orEmpty(),
            "smtpFrom" to current.smtpFrom.orEmpty(),
            "smtpSecurity" to current.smtpSecurity,
            "imapEnabled" to current.imapEnabled.toString(),
            "imapHost" to current.imapHost.orEmpty(),
            "imapPort" to current.imapPort.toString(),
            "imapUsername" to current.imapUsername.orEmpty(),
            "imapFolder" to current.imapFolder,
            "imapSsl" to current.imapSsl.toString(),
        )
        if (call.has("smtpMode")) {
            values["smtpMode"] = when (call.string("smtpMode", 20)) {
                "empresa" -> "Cuenta propia"
                "servidor" -> "SMTP del servidor"
                else -> throw McpToolException("'smtpMode' debe ser servidor o empresa.")
            }
        }
        listOf("smtpHost", "smtpUsername", "smtpFrom", "smtpSecurity", "imapHost", "imapUsername", "imapFolder").forEach { key ->
            if (call.has(key)) values[key] = call.string(key, 300)
        }
        if (call.has("smtpPort")) values["smtpPort"] = call.int("smtpPort", current.smtpPort, 1..65535).toString()
        if (call.has("imapPort")) values["imapPort"] = call.int("imapPort", current.imapPort, 1..65535).toString()
        if (call.has("imapEnabled")) values["imapEnabled"] = call.boolean("imapEnabled").toString()
        if (call.has("imapSsl")) values["imapSsl"] = call.boolean("imapSsl").toString()
        val merged = validated { CrmMail.merge(current, values) }
        CrmMail.save(call.tenant, merged)
        return result("Correo guardado.")
    }

    private fun saveMessageTemplate(call: McpToolCall): JsonElement {
        val id = call.uuid("id")
        val existing = id?.let { templateId ->
            CrmMail.templates(call.tenant).firstOrNull { it.id == templateId } ?: throw McpToolException("Esa plantilla no existe; revisa get_settings.")
        }
        val values = mapOf(
            "name" to if (call.has("name")) call.string("name", 200) else existing?.name.orEmpty(),
            "subject" to if (call.has("subject")) call.string("subject", 400) else existing?.subject.orEmpty(),
            "body" to if (call.has("body")) call.string("body", 50_001) else existing?.body.orEmpty(),
        )
        val name = validated { CrmMail.saveTemplate(call.tenant, id, values) }
        return result("Plantilla $name guardada.")
    }

    private fun deleteMessageTemplate(call: McpToolCall): JsonElement {
        if (!CrmMail.deleteTemplate(call.tenant, call.requiredUuid("id"))) throw McpToolException("Esa plantilla no existe.")
        return result("Plantilla eliminada.")
    }

    private fun updateSpam(call: McpToolCall): JsonElement {
        if (call.arguments.keys.none { it in spamArguments }) throw McpToolException("Envia al menos un campo a cambiar.")
        val current = CrmSpam.load(call.tenant)
        val words = if (call.has("words")) strings(call, "words") else current.words
        val domains = if (call.has("blockedDomains")) strings(call, "blockedDomains") else current.blockedDomains
        val channels = if (call.has("channels")) {
            strings(call, "channels").onEach { if (it !in CrmSpam.channelLabels) throw McpToolException("Canal desconocido '$it'. Canales: ${CrmSpam.channelLabels.keys.joinToString()}.") }
        } else {
            current.channels.toList()
        }
        // CrmSpam.parse reads the anti-spam form: lists one per line and a checkbox per channel.
        val values = mapOf(
            "words" to words.joinToString("\n"),
            "blockedDomains" to domains.joinToString("\n"),
            "maxLinks" to (if (call.has("maxLinks")) call.int("maxLinks", current.maxLinks, 0..50) else current.maxLinks).toString(),
            "blockCyrillic" to (if (call.has("blockCyrillic")) call.boolean("blockCyrillic") else current.blockCyrillic).toString(),
            "skipKnownLeads" to (if (call.has("skipKnownLeads")) call.boolean("skipKnownLeads") else current.skipKnownLeads).toString(),
        ) + channels.associate { "channel_$it" to "true" }
        val settings = validated { CrmSpam.parse(values) }
        CrmSpam.save(call.tenant, settings)
        return result("Reglas anti-spam guardadas.")
    }

    private fun updateNotifications(call: McpToolCall): JsonElement {
        val context = context(call)
        val user = user(call, context)
        val saved = CrmNotify.userSettings(call.tenant)[user.id]
        val settings = CrmUserSettings(
            userId = user.id,
            email = if (call.has("email")) call.string("email", 300) else saved?.email.orEmpty(),
            notifyNewLeads = if (call.has("notifyNewLeads")) call.boolean("notifyNewLeads") else saved?.notifyNewLeads ?: false,
            notifyAssigned = if (call.has("notifyAssigned")) call.boolean("notifyAssigned") else saved?.notifyAssigned ?: true,
            dailyDigest = if (call.has("dailyDigest")) call.boolean("dailyDigest") else saved?.dailyDigest ?: false,
        )
        validated { CrmNotify.saveUserSettings(call.tenant, settings) }
        return result("Avisos de ${user.name} guardados.")
    }

    private fun setDigestHour(call: McpToolCall): JsonElement {
        if (!call.has("hour")) throw McpToolException("El argumento 'hour' es obligatorio.")
        val hour = call.int("hour", 7, 0..23)
        call.tenant.prepareStatement("UPDATE crm_settings SET digest_hour = ? WHERE id = 1").use { it.setInt(1, hour); it.executeUpdate() }
        return result("El resumen diario se enviara a las $hour:00.")
    }
}
