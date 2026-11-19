package com.ideasdeveloper.idc.modules.clientes

import com.ideasdeveloper.idc.server.modules.CustomerHistoryEntry
import com.ideasdeveloper.idc.server.modules.CustomerNote
import com.ideasdeveloper.idc.server.modules.CustomerHistoryWriter
import com.ideasdeveloper.idc.server.modules.ModuleAccessService
import com.ideasdeveloper.idc.server.modules.ModulePermissions
import com.ideasdeveloper.idc.server.modules.requireModuleAccess
import com.ideasdeveloper.idc.ui.UiAction
import com.ideasdeveloper.idc.ui.UiBadge
import com.ideasdeveloper.idc.ui.UiButton
import com.ideasdeveloper.idc.ui.UiCard
import com.ideasdeveloper.idc.ui.UiField
import com.ideasdeveloper.idc.ui.UiForm
import com.ideasdeveloper.idc.ui.UiJson
import com.ideasdeveloper.idc.ui.UiList
import com.ideasdeveloper.idc.ui.UiListItem
import com.ideasdeveloper.idc.ui.UiNavigate
import com.ideasdeveloper.idc.ui.UiNode
import com.ideasdeveloper.idc.ui.UiNotice
import com.ideasdeveloper.idc.ui.UiRequest
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.sql.Connection
import java.time.OffsetDateTime
import java.util.UUID

/**
 * The customer's profile ("ficha"), drawn by the app from these screens (server-driven, so it
 * changes without updating the app): the customer's data, whether it is shared with the other
 * companies of the business owner, a "Notas del cliente" tab (tastes and preferences, anything worth
 * knowing in every company; the CRM shows them in the lead) and one tab per company with what the
 * customer bought or hired there.
 * Modules write those events (ModuleAccessService.recordCustomerEvent, e.g. the CRM when a lead is
 * won) with a link to their record; users add entries by hand.
 */
internal object ClientesProfile {
    const val UiRoute = "/modules/clientes/ui"
    const val NotesTab = "notes"

    // Stores the events modules write (ServerModule.customerHistoryWriter).
    val writer = CustomerHistoryWriter { tenant, event ->
        val kind = event.kind.lowercase().filter { it in 'a'..'z' || it in '0'..'9' || it == '_' }
            .let { if (it.firstOrNull()?.isLetter() == true) it.take(32) else "event" }
        tenant.prepareStatement(
            """
            INSERT INTO customer_history (id, customer_id, module_id, kind, title, detail, author, link, source_ref, created_at)
            SELECT ?, ?, ?, ?, ?, ?, ?, CAST(? AS jsonb), ?, ?
            WHERE EXISTS (SELECT 1 FROM customers WHERE id = ?)
            ON CONFLICT (module_id, source_ref) WHERE source_ref IS NOT NULL DO NOTHING
            """.trimIndent()
        ).use { insert ->
            insert.setObject(1, UUID.randomUUID())
            insert.setObject(2, event.customerId)
            insert.setString(3, event.moduleId.take(63))
            insert.setString(4, kind)
            insert.setString(5, event.title.trim().ifBlank { "Evento" }.take(300))
            insert.setString(6, event.detail.take(5_000))
            insert.setString(7, event.author.take(200))
            insert.setString(8, event.link?.let { UiJson.encodeToString(UiAction.serializer(), it) })
            insert.setString(9, event.sourceRef?.take(200))
            insert.setObject(10, OffsetDateTime.ofInstant(event.at, java.time.ZoneOffset.UTC))
            insert.setObject(11, event.customerId)
            insert.executeUpdate()
        }
    }

    class Note(val id: UUID, val body: String, val author: String, val companyCode: String, val userId: UUID?, val at: OffsetDateTime)

    class HistoryRow(val at: OffsetDateTime, val moduleId: String, val kind: String, val title: String, val detail: String, val author: String, val link: UiAction?)

    // Recorded history of the customer in the company of [tenant], newest first.
    fun history(tenant: Connection, customerId: UUID, limit: Int): List<HistoryRow> = tenant.prepareStatement(
        "SELECT created_at, module_id, kind, title, detail, author, link::text AS link FROM customer_history WHERE customer_id = ? ORDER BY created_at DESC LIMIT ?"
    ).use { query ->
        query.setObject(1, customerId)
        query.setInt(2, limit)
        query.executeQuery().use { rows ->
            buildList {
                while (rows.next()) {
                    add(
                        HistoryRow(
                            at = rows.getObject("created_at", OffsetDateTime::class.java),
                            moduleId = rows.getString("module_id"),
                            kind = rows.getString("kind"),
                            title = rows.getString("title"),
                            detail = rows.getString("detail"),
                            author = rows.getString("author"),
                            link = rows.getString("link")?.let { runCatching { UiJson.decodeFromString(UiAction.serializer(), it) }.getOrNull() },
                        )
                    )
                }
            }
        }
    }

    /** Notes of a customer visible in the company of [tenant], read from its origin company, for other modules. */
    fun notesFor(access: ModuleAccessService, tenant: Connection, customerId: UUID, limit: Int): List<CustomerNote> {
        if (!ClientesSharing.supportsSharing(tenant)) return emptyList()
        val currentCode = access.companyOf(tenant)?.code ?: return emptyList()
        val customer = ClientesSharing.customer(tenant, customerId, currentCode)?.takeIf { it.visible } ?: return emptyList()
        val notes = ClientesSharing.onCompany(access, tenant, currentCode, customer.origin) { origin -> notes(origin, customerId) }
            ?: throw ClientesSharingException("No se pudo abrir la empresa del cliente.")
        return notes.take(limit).map { note ->
            CustomerNote(note.at.toInstant(), note.body, note.author, ClientesSharing.companyName(access, currentCode, note.companyCode))
        }
    }

    private fun notes(tenant: Connection, customerId: UUID): List<Note> = tenant.prepareStatement(
        "SELECT id, body, author, company_code, user_id, created_at FROM customer_notes WHERE customer_id = ? ORDER BY created_at DESC LIMIT 200"
    ).use { query ->
        query.setObject(1, customerId)
        query.executeQuery().use { rows ->
            buildList {
                while (rows.next()) {
                    add(
                        Note(
                            id = rows.getObject("id", UUID::class.java),
                            body = rows.getString("body"),
                            author = rows.getString("author"),
                            companyCode = rows.getString("company_code"),
                            userId = rows.getObject("user_id", UUID::class.java),
                            at = rows.getObject("created_at", OffsetDateTime::class.java),
                        )
                    )
                }
            }
        }
    }

    fun routes(route: Route, access: ModuleAccessService) {
        route.route(UiRoute) {
            get("/customer") {
                val permissions = call.profilePermissions(access, "clientes.view") ?: return@get
                val params = call.request.queryParameters.entries().associate { it.key to it.value.firstOrNull().orEmpty() }
                call.respondProfile(access, permissions, params, notice = null)
            }

            post("/customer") {
                val permissions = call.profilePermissions(access, "clientes.view") ?: return@post
                val params = call.request.queryParameters.entries().associate { it.key to it.value.firstOrNull().orEmpty() }
                val values = runCatching {
                    UiJson.parseToJsonElement(call.receiveText()).jsonObject.mapValues { it.value.jsonPrimitive.contentOrNull.orEmpty() }
                }.getOrNull().orEmpty()
                val notice = try {
                    withContext(Dispatchers.IO) { act(access, permissions, params, values) }
                } catch (failure: ClientesSharingException) {
                    UiNotice(failure.message.orEmpty(), UiTone.Danger)
                }
                call.respondProfile(access, permissions, params, notice)
            }
        }
    }

    // Runs the action of a POST (params["op"]) and says how it went.
    private fun act(access: ModuleAccessService, permissions: ModulePermissions, params: Map<String, String>, values: Map<String, String>): UiNotice {
        val id = params["id"]?.let { runCatching { UUID.fromString(it) }.getOrNull() } ?: throw ClientesSharingException("Cliente invalido.")
        val currentCode = permissions.companyCode
        val tenant = access.tenantConnectionForCompany(currentCode) ?: throw ClientesSharingException("No se pudo abrir la empresa.")
        return tenant.use { connection ->
            val customer = ClientesSharing.customer(connection, id, currentCode)?.takeIf { it.visible }
                ?: throw ClientesSharingException("Cliente no encontrado.")
            val author = ClientesActivity.author(connection, permissions, access)
            when (params["op"]) {
                "restore" -> {
                    if (!canRestore(permissions, customer, currentCode)) throw ClientesSharingException("No tienes permiso para restaurar este cliente o no se creo en esta empresa.")
                    when (ClientesServerModule.restoreCustomer(connection, id)) {
                        ClientesServerModule.RestoreResult.NotFound -> throw ClientesSharingException("El cliente no esta archivado.")
                        ClientesServerModule.RestoreResult.EmailTaken -> throw ClientesSharingException("Otro cliente activo ya usa el correo ${customer.email}.")
                        ClientesServerModule.RestoreResult.Restored -> Unit
                    }
                    ClientesActivity.record(connection, id, "restored", "", author)
                    ClientesServerModule.propagateLogged(access, connection, currentCode, id)
                    UiNotice("Cliente restaurado.", UiTone.Success)
                }
                "note" -> {
                    if (!permissions.has("clientes.edit")) throw ClientesSharingException("No tienes permiso para editar clientes.")
                    val body = values["body"].orEmpty().trim()
                    if (body.isEmpty()) throw ClientesSharingException("Escribe la nota.")
                    if (body.length > 5_000) throw ClientesSharingException("La nota no puede pasar de 5000 caracteres.")
                    ClientesSharing.onCompany(access, connection, currentCode, customer.origin) { origin ->
                        origin.prepareStatement(
                            "INSERT INTO customer_notes (id, customer_id, body, user_id, author, company_code) VALUES (?, ?, ?, ?, ?, ?)"
                        ).use { insert ->
                            insert.setObject(1, UUID.randomUUID())
                            insert.setObject(2, id)
                            insert.setString(3, body)
                            // User ids belong to one company's database: only kept for notes written there.
                            insert.setObject(4, author.first.takeIf { customer.origin == currentCode })
                            insert.setString(5, author.second)
                            insert.setString(6, currentCode)
                            insert.executeUpdate()
                        }
                    } ?: throw ClientesSharingException("No se pudo abrir la empresa del cliente para guardar la nota.")
                    UiNotice("Nota agregada.", UiTone.Success)
                }
                "deletenote" -> {
                    val noteId = params["note"]?.let { runCatching { UUID.fromString(it) }.getOrNull() } ?: throw ClientesSharingException("Nota invalida.")
                    val deletedText = ClientesSharing.onCompany(access, connection, currentCode, customer.origin) { origin ->
                        val note = notes(origin, id).firstOrNull { it.id == noteId } ?: return@onCompany null
                        if (!canDeleteNote(permissions, note, currentCode)) throw ClientesSharingException("Solo quien escribio la nota o el business owner pueden borrarla.")
                        origin.prepareStatement("DELETE FROM customer_notes WHERE id = ? AND customer_id = ?").use { delete ->
                            delete.setObject(1, noteId)
                            delete.setObject(2, id)
                            if (delete.executeUpdate() == 1) note.body else null
                        }
                    }
                    if (deletedText == null) throw ClientesSharingException("Nota no encontrada.")
                    ClientesActivity.record(connection, id, "note_deleted", noteDeletedBody(deletedText), author)
                    UiNotice("Nota borrada.", UiTone.Success)
                }
                "history" -> {
                    if (!permissions.has("clientes.edit")) throw ClientesSharingException("No tienes permiso para editar clientes.")
                    val title = values["title"].orEmpty().trim()
                    if (title.isEmpty()) throw ClientesSharingException("Escribe que contrato o compro el cliente.")
                    if (title.length > 300) throw ClientesSharingException("El titulo no puede pasar de 300 caracteres.")
                    writer.write(
                        connection,
                        com.ideasdeveloper.idc.server.modules.CustomerHistoryEvent(
                            customerId = id,
                            moduleId = ClientesModule.id,
                            kind = "manual",
                            title = title,
                            detail = values["detail"].orEmpty().trim().take(5_000),
                            author = author.second,
                        ),
                    )
                    UiNotice("Agregado al historial de ${ClientesSharing.companyName(access, currentCode, currentCode)}.", UiTone.Success)
                }
                "deletehistory" -> {
                    if (!permissions.has("clientes.edit")) throw ClientesSharingException("No tienes permiso para editar clientes.")
                    val entryId = params["entry"]?.let { runCatching { UUID.fromString(it) }.getOrNull() } ?: throw ClientesSharingException("Entrada invalida.")
                    val title = deleteManualEntry(connection, id, entryId) ?: throw ClientesSharingException("Solo se pueden borrar las entradas agregadas a mano en esta empresa.")
                    ClientesActivity.record(connection, id, "history_deleted", historyDeletedBody(title), author)
                    UiNotice("Entrada borrada del historial.", UiTone.Success)
                }
                else -> throw ClientesSharingException("Accion desconocida.")
            }
        }
    }

    // Only the company where the customer was created archives and restores it (copies follow it).
    private fun canRestore(permissions: ModulePermissions, customer: ClientesSharing.Customer, currentCode: String): Boolean =
        customer.archived && customer.origin == currentCode && permissions.has("clientes.delete")

    /** Text of the activity row left when a note is deleted: it keeps what the note said. */
    fun noteDeletedBody(text: String): String = "Nota borrada: ${text.trim()}"

    fun historyDeletedBody(title: String): String = "Entrada borrada del historial: ${title.trim()}"

    // Only the entries added by hand in Clientes (module 'clientes', kind 'manual') can be deleted; what other
    // modules wrote stays. Returns the title of the deleted entry, or null when there was nothing to delete.
    private fun deleteManualEntry(tenant: Connection, customerId: UUID, entryId: UUID): String? = tenant.prepareStatement(
        "DELETE FROM customer_history WHERE id = ? AND customer_id = ? AND module_id = ? AND kind = 'manual' RETURNING title"
    ).use { delete ->
        delete.setObject(1, entryId)
        delete.setObject(2, customerId)
        delete.setString(3, ClientesModule.id)
        delete.executeQuery().use { rows -> if (rows.next()) rows.getString(1) else null }
    }

    class ManualEntry(val id: UUID, val title: String, val at: OffsetDateTime)

    private fun manualEntries(tenant: Connection, customerId: UUID): List<ManualEntry> = tenant.prepareStatement(
        "SELECT id, title, created_at FROM customer_history WHERE customer_id = ? AND module_id = ? AND kind = 'manual' ORDER BY created_at DESC LIMIT 50"
    ).use { query ->
        query.setObject(1, customerId)
        query.setString(2, ClientesModule.id)
        query.executeQuery().use { rows ->
            buildList { while (rows.next()) add(ManualEntry(rows.getObject("id", UUID::class.java), rows.getString("title"), rows.getObject("created_at", OffsetDateTime::class.java))) }
        }
    }

    /**
     * The permissions to read the customer's data in [companyCode]: the user's own in the current company; in any other
     * company only the business owner (who owns them all) reads, as business owner of that company and with no user or
     * module permissions taken from the current one. Null for anyone else: they never see another company.
     */
    fun permissionsIn(companyCode: String, current: ModulePermissions): ModulePermissions? = when {
        companyCode == current.companyCode -> current
        current.businessOwner -> ModulePermissions(companyCode, "", businessOwner = true, granted = emptySet(), userId = null, serverOwner = false)
        else -> null
    }

    /** What a module reported about the customer in a company: how many events and the date of the last one. */
    class ModuleUse(val name: String, val count: Int, val last: String)

    /** The same module twice (customer_history and its own timeline) counts once: the larger count and the later date. */
    fun mergeUsage(uses: List<ModuleUse>): List<ModuleUse> =
        uses.groupBy { it.name }.map { (name, group) -> ModuleUse(name, group.maxOf { it.count }, group.maxOf { it.last }) }.sortedBy { it.name.lowercase() }

    fun usageLine(use: ModuleUse): String = "${use.name}: ${use.count} ${if (use.count == 1) "registro" else "registros"}, ultimo ${use.last.take(10)}"

    // Readable name of a module id written in customer_history.
    fun moduleLabel(moduleId: String): String = moduleId.replace('_', ' ').replaceFirstChar { it.uppercase() }

    // Modules with something in the customer's history of this company (what the user may see), with count and last date.
    private fun moduleUsage(access: ModuleAccessService, companyTenant: Connection, permissions: ModulePermissions, customerId: UUID): List<ModuleUse> {
        val recorded = companyTenant.prepareStatement(
            "SELECT module_id, count(*) AS total, max(created_at) AS last_at FROM customer_history WHERE customer_id = ? GROUP BY module_id"
        ).use { query ->
            query.setObject(1, customerId)
            query.executeQuery().use { rows ->
                buildList {
                    while (rows.next()) {
                        add(Triple(rows.getString("module_id"), rows.getInt("total"), rows.getObject("last_at", OffsetDateTime::class.java).toString()))
                    }
                }
            }
        }
        val visible = ClientesActivity.filterByModule(recorded, { it.first }) { moduleId -> access.customerEventsVisible(companyTenant, permissions, moduleId) }
            .map { (moduleId, count, last) -> ModuleUse(moduleLabel(moduleId), count, last) }
        // The timelines of other modules (CRM, hotspot...) already apply the module's own rules.
        val others = access.customerHistory(companyTenant, permissions, customerId, 200)
            .filter { it.moduleId != ClientesModule.id }
            .groupBy { it.moduleName }
            .map { (name, entries) -> ModuleUse(name, entries.size, entries.maxOf { it.at }) }
        return mergeUsage(visible + others)
    }

    private fun canDeleteNote(permissions: ModulePermissions, note: Note, currentCode: String): Boolean =
        permissions.businessOwner || (note.companyCode == currentCode && note.userId != null && note.userId == permissions.userId && !permissions.serverOwner)

    private suspend fun ApplicationCall.respondProfile(access: ModuleAccessService, permissions: ModulePermissions, params: Map<String, String>, notice: UiNotice?) {
        val screen = withContext(Dispatchers.IO) {
            access.tenantConnectionForCompany(permissions.companyCode)?.use { tenant -> screen(access, tenant, permissions, params, notice) }
        } ?: UiScreen("Cliente", notice = UiNotice("No se pudo abrir la empresa.", UiTone.Danger))
        respondText(UiJson.encodeToString(UiScreen.serializer(), screen), ContentType.Application.Json)
    }

    private fun screen(access: ModuleAccessService, tenant: Connection, permissions: ModulePermissions, params: Map<String, String>, notice: UiNotice?): UiScreen {
        val currentCode = permissions.companyCode
        val id = params["id"]?.let { runCatching { UUID.fromString(it) }.getOrNull() }
        val customer = id?.let { ClientesSharing.customer(tenant, it, currentCode) }?.takeIf { it.visible }
            ?: return UiScreen("Cliente", notice = UiNotice("Cliente no encontrado.", UiTone.Danger))
        if (!ClientesSharing.supportsSharing(tenant)) {
            return UiScreen(customer.name, notice = UiNotice("La empresa todavia no aplica la actualizacion de Clientes.", UiTone.Warning))
        }
        val tabs = ClientesSharing.tabs(access, tenant, currentCode, customer, permissions.businessOwner)
        val linked = access.linkedCompanies(currentCode, ClientesLinking.LinkPurpose)
        val tab = params["tab"].orEmpty().takeIf { requested -> requested == NotesTab || tabs.any { it.code == requested } } ?: NotesTab
        val originName = ClientesSharing.companyName(access, currentCode, customer.origin)
        fun open(nextTab: String) = UiNavigate("customer", mapOf("id" to customer.id.toString(), "tab" to nextTab))
        fun post(op: String, extra: Map<String, String> = emptyMap(), formId: String? = null, confirm: String? = null) =
            UiRequest("$UiRoute/customer", params = mapOf("id" to customer.id.toString(), "tab" to tab, "op" to op) + extra, formId = formId, confirm = confirm)

        val details = UiCard(
            title = customer.name,
            children = listOfNotNull(
                UiText("Codigo de cliente: ${customer.code}"),
                customer.email.takeIf { it.isNotBlank() }?.let { UiText("Correo: $it") },
                customer.phone.takeIf { it.isNotBlank() }?.let { UiText("Telefono: $it") },
                UiText("Creado en $originName", UiTextStyle.Caption),
                UiText(linkText(linked.map { it.name }, originName), UiTextStyle.Caption),
                customer.archived.takeIf { it }?.let { UiText("Cliente archivado.", tone = UiTone.Warning) },
            ),
            actions = listOfNotNull(
                if (canRestore(permissions, customer, currentCode)) {
                    UiButton("Restaurar cliente", post("restore", confirm = "El cliente vuelve a aparecer en la lista y en las empresas vinculadas. Continuar?"))
                } else {
                    null
                },
                if (permissions.businessOwner) UiButton("Empresas vinculadas", UiNavigate("links"), style = "secondary") else null,
                if (ClientesLinkScreens.canSeeActivity(permissions)) UiButton("Registro de Clientes", UiNavigate("activity"), style = "secondary") else null,
            ),
        )

        // What the customer did, by company of the visible tabs: the modules that have something and how much.
        val summary = UiCard(
            title = "Resumen",
            children = tabs.map { company ->
                val uses = ClientesSharing.onCompany(access, tenant, currentCode, company.code) { companyTenant ->
                    if (ClientesSharing.supportsSharing(companyTenant) && ClientesSharing.visible(companyTenant, customer.id)) moduleUsage(access, companyTenant, permissionsIn(company.code, permissions) ?: return@onCompany emptyList(), customer.id) else emptyList()
                }
                val lines = when {
                    uses == null -> listOf(UiText("No se pudo abrir ${company.name}.", tone = UiTone.Danger))
                    uses.isEmpty() -> listOf(UiText("Sin actividad registrada.", UiTextStyle.Caption))
                    else -> uses.map { UiText(usageLine(it)) }
                }
                UiCard(title = company.name, children = lines)
            },
        )

        val tabRow = UiRow(
            children = listOf(UiButton("Notas del cliente", open(NotesTab), style = if (tab == NotesTab) "primary" else "secondary")) +
                tabs.map { company -> UiButton(company.name, open(company.code), style = if (tab == company.code) "primary" else "secondary") },
            spacing = 8,
        )

        val content: List<UiNode> = if (tab == NotesTab) {
            val notes = ClientesSharing.onCompany(access, tenant, currentCode, customer.origin) { origin -> notes(origin, customer.id) }
            listOfNotNull(
                UiText("Gustos, preferencias y datos generales del cliente, para no preguntarlos en cada visita o lead. Se ven en todas las empresas donde esta el cliente y en sus leads del CRM.", UiTextStyle.Caption),
                if (notes == null) {
                    UiText("No se pudo abrir $originName para leer las notas.", tone = UiTone.Danger)
                } else {
                    UiList(
                        items = notes.map { note ->
                            UiListItem(
                                title = note.body,
                                subtitle = listOf(note.author, ClientesSharing.companyName(access, currentCode, note.companyCode)).filter { it.isNotBlank() }.joinToString(" - "),
                                trailing = note.at.toLocalDate().toString(),
                                action = if (canDeleteNote(permissions, note, currentCode)) post("deletenote", mapOf("note" to note.id.toString()), confirm = "Borrar esta nota?") else null,
                            )
                        },
                        emptyText = "Sin notas del cliente.",
                    )
                },
                if (permissions.has("clientes.edit")) {
                    UiForm(
                        id = "note",
                        fields = listOf(UiField("body", "Nueva nota", "textarea", required = true, placeholder = "Ej.: prefiere habitacion en planta baja, alergico al mani")),
                        submit = UiButton("Agregar nota", post("note", formId = "note")),
                    )
                } else {
                    null
                },
            )
        } else {
            val company = tabs.first { it.code == tab }
            val entries = ClientesSharing.onCompany(access, tenant, currentCode, company.code) { companyTenant ->
                val inCompany = permissionsIn(company.code, permissions)
                if (inCompany != null && ClientesSharing.supportsSharing(companyTenant)) access.customerHistory(companyTenant, inCompany, customer.id) else emptyList()
            }
            listOfNotNull(
                UiText("Lo que el cliente compro o contrato en ${company.name}. Las ventas del CRM se agregan solas; toca una para ver el detalle.", UiTextStyle.Caption),
                if (entries == null) {
                    UiText("No se pudo abrir ${company.name}.", tone = UiTone.Danger)
                } else {
                    UiList(items = entries.map(::historyItem), emptyText = "Sin historial en ${company.name}.")
                },
                if (company.code == currentCode && permissions.has("clientes.edit")) {
                    manualEntries(tenant, customer.id).takeIf { it.isNotEmpty() }?.let { manual ->
                        UiCard(
                            title = "Entradas agregadas a mano",
                            children = listOf(
                                UiList(
                                    items = manual.map { entry ->
                                        UiListItem(
                                            title = entry.title,
                                            trailing = entry.at.toLocalDate().toString(),
                                            badges = listOf(UiBadge("Borrar", UiTone.Danger)),
                                            action = post("deletehistory", mapOf("entry" to entry.id.toString()), confirm = "Borrar esta entrada del historial?"),
                                        )
                                    },
                                ),
                            ),
                        )
                    }
                } else {
                    null
                },
                if (company.code == currentCode && permissions.has("clientes.edit")) {
                    UiForm(
                        id = "history",
                        fields = listOf(
                            UiField("title", "Que compro o contrato", required = true, placeholder = "Ej.: Paquete de 3 noches"),
                            UiField("detail", "Detalle", "textarea"),
                        ),
                        submit = UiButton("Agregar al historial", post("history", formId = "history")),
                    )
                } else {
                    null
                },
            )
        }
        return UiScreen(title = customer.name, notice = notice, body = listOf(details, summary, tabRow) + content)
    }

    /** Says whether the company is linked: which companies share their customers with this one. [linkedNames] has the current company first. */
    fun linkText(linkedNames: List<String>, originName: String): String =
        if (linkedNames.size > 1) "Empresa vinculada: los clientes se comparten con ${linkedNames.drop(1).joinToString()}." else "Empresa no vinculada: este cliente solo se ve en $originName."

    private fun historyItem(entry: CustomerHistoryEntry): UiListItem = UiListItem(
        title = entry.title,
        subtitle = listOf(entry.detail, entry.author).filter { it.isNotBlank() }.joinToString("\n"),
        trailing = entry.at.take(16).replace('T', ' '),
        badges = listOf(UiBadge(entry.moduleName)),
        action = entry.link,
    )

    // The user's permissions in Clientes when they hold [permissionId]; otherwise answers and returns null.
    private suspend fun ApplicationCall.profilePermissions(access: ModuleAccessService, permissionId: String): ModulePermissions? {
        val permissions = requireModuleAccess(access, ClientesModule.id) ?: return null
        if (!permissions.has(permissionId)) {
            respond(HttpStatusCode.Forbidden, ClientesErrorResponse("missing_permission", "No tienes permiso para usar esta funcion."))
            return null
        }
        return permissions
    }
}
