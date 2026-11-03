package com.ideasdeveloper.idc.modules.crm

import com.ideasdeveloper.idc.server.modules.McpSchema
import com.ideasdeveloper.idc.server.modules.McpTool
import com.ideasdeveloper.idc.server.modules.McpToolCall
import com.ideasdeveloper.idc.server.modules.McpToolException
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import java.util.UUID

/**
 * Agent tools of the CRM imports (crm.settings, like the Importar screens) and bulk actions
 * (crm.leads.manage, like "Acciones en lote"). The monday token is saved only in the app; the tools
 * use the stored one, server side, and never receive or return it.
 */
object CrmImportMcpTools {
    private const val Confirm = " Confirma con el usuario antes."

    private fun map(description: String) = buildJsonObject {
        put("type", "object")
        put("description", description)
        put("additionalProperties", buildJsonObject { put("type", "string") })
    }

    private val jobArgument = "job" to McpSchema.uuid("Id de la importacion.")

    val tools = listOf(
        McpTool(
            name = "list_imports",
            title = "Importaciones",
            description = "Lista las importaciones recientes con su estado y totales, los campos a los que se puede mapear una columna y los mapeos guardados.",
            permission = CrmScreens.Settings,
            readOnly = true,
        ) { call -> imports(call) },
        McpTool(
            name = "import_csv",
            title = "Importar CSV",
            description = "Lee leads desde un texto CSV (con encabezados) y crea una importacion en mapeo. Despues revisa el mapeo con get_import y empiezala con start_import.",
            permission = CrmScreens.Settings,
            inputSchema = McpSchema.obj(
                mapOf("csv" to McpSchema.string("Contenido CSV con la fila de encabezados."), "name" to McpSchema.string("Nombre de la importacion (opcional).")),
                required = listOf("csv"),
            ),
        ) { call -> read(call) { "file" to (call.string("name", 80).ifEmpty { "CSV pegado ${it.today}" } to CrmImportParse.csv(call.requiredString("csv", 5_000_000))) } },
        McpTool(
            name = "import_from_url",
            title = "Importar desde una direccion",
            description = "Descarga un CSV o XLSX publico (https://, por ejemplo un Google Sheets publicado) y crea una importacion en mapeo.",
            permission = CrmScreens.Settings,
            inputSchema = McpSchema.obj(mapOf("url" to McpSchema.string("Direccion https:// del archivo.")), required = listOf("url")),
        ) { call ->
            read(call) {
                val url = call.requiredString("url", 2_000)
                "file" to (url.substringAfterLast('/').take(80).ifEmpty { "Archivo" } to CrmImport.fromUrl(url))
            }
        },
        McpTool(
            name = "import_leadspro",
            title = "Importar desde CT Leads Pro",
            description = "Lee los leads del plugin CT Leads Pro del sitio WordPress conectado y crea una importacion en mapeo.",
            permission = CrmScreens.Settings,
        ) { call -> read(call) { "leadspro" to ("CT Leads Pro ${it.today}" to CrmImport.fromLeadsPro(call.tenant)) } },
        McpTool(
            name = "list_monday_boards",
            title = "Tableros de monday",
            description = "Lista los tableros de monday.com de la cuenta, con el token guardado en la app.",
            permission = CrmScreens.Settings,
            readOnly = true,
        ) { call -> mondayBoards(call) },
        McpTool(
            name = "import_monday",
            title = "Importar tableros de monday",
            description = "Lee en segundo plano los tableros elegidos de monday.com (puede tardar minutos) y crea una importacion; consulta su avance con get_import.",
            permission = CrmScreens.Settings,
            inputSchema = McpSchema.obj(
                mapOf("boards" to buildJsonObject {
                    put("type", "array")
                    put("description", "Ids de los tableros (list_monday_boards).")
                    put("items", buildJsonObject { put("type", "string") })
                }),
                required = listOf("boards"),
            ),
        ) { call -> importMonday(call) },
        McpTool(
            name = "get_import",
            title = "Ver importacion",
            description = "Devuelve una importacion: estado, columnas con su mapeo actual, filas de ejemplo, valores distintos de etapa, responsable e interes " +
                "para mapearlos, la vista previa de lo que hara y, si termino, las filas con conflicto o error.",
            permission = CrmScreens.Settings,
            readOnly = true,
            inputSchema = McpSchema.obj(mapOf(jobArgument), required = listOf("job")),
        ) { call -> importJob(call) },
        McpTool(
            name = "map_import",
            title = "Mapear importacion",
            description = "Define a que campo va cada columna (claves de fields en list_imports; columnas no enviadas no se importan) y, opcionalmente, como se " +
                "traducen los valores de etapa, responsable e interes a los del CRM. Puede guardarse como mapeo reutilizable. Devuelve la vista previa.",
            permission = CrmScreens.Settings,
            inputSchema = McpSchema.obj(
                mapOf(
                    jobArgument,
                    "columns" to map("Columna del archivo -> clave del campo."),
                    "stages" to map("Valor de la columna de etapa -> nombre de la etapa del CRM."),
                    "owners" to map("Valor de la columna de responsable -> nombre del usuario del CRM."),
                    "interests" to map("Valor de la columna de interes -> nombre del interes del CRM."),
                    "presetName" to McpSchema.string("Guardar este mapeo con este nombre (opcional)."),
                ),
                required = listOf("job", "columns"),
            ),
        ) { call -> mapImport(call) },
        McpTool(
            name = "apply_import_preset",
            title = "Usar mapeo guardado",
            description = "Aplica a la importacion un mapeo guardado (nombres en list_imports) y devuelve la vista previa.",
            permission = CrmScreens.Settings,
            inputSchema = McpSchema.obj(mapOf(jobArgument, "preset" to McpSchema.string("Nombre del mapeo guardado.")), required = listOf("job", "preset")),
        ) { call -> applyPreset(call) },
        McpTool(
            name = "start_import",
            title = "Empezar importacion",
            description = "Empieza a aplicar la importacion (300 filas por minuto): crea y actualiza leads y clientes segun la vista previa.$Confirm",
            permission = CrmScreens.Settings,
            inputSchema = McpSchema.obj(mapOf(jobArgument), required = listOf("job")),
        ) { call -> startImport(call) },
        McpTool(
            name = "cancel_import",
            title = "Cancelar importacion",
            description = "Cancela una importacion; lo ya aplicado se queda.",
            permission = CrmScreens.Settings,
            inputSchema = McpSchema.obj(mapOf(jobArgument), required = listOf("job")),
        ) { call -> cancelImport(call) },
        McpTool(
            name = "bulk_update_leads",
            title = "Acciones en lote",
            description = "Mueve de etapa, asigna responsable o elimina varios leads visibles a la vez (hasta 500). Eliminar necesita el permiso de eliminar leads.$Confirm",
            permission = CrmScreens.Manage,
            destructive = true,
            inputSchema = McpSchema.obj(
                mapOf(
                    "leads" to buildJsonObject {
                        put("type", "array")
                        put("description", "Ids de los leads.")
                        put("items", McpSchema.uuid("Id del lead."))
                    },
                    "action" to McpSchema.string("move: mover a etapa; assign: asignar responsable; delete: eliminar.", listOf("move", "assign", "delete")),
                    "stage" to McpSchema.string("Etapa destino (move)."),
                    "owner" to McpSchema.string("Responsable (assign); vacio deja sin asignar."),
                ),
                required = listOf("leads", "action"),
            ),
        ) { call -> bulk(call) },
    )

    private fun context(call: McpToolCall) = CrmScreens.Context.load(call.tenant, call.permissions, call.access.serverMail)

    private fun actor(call: McpToolCall): CrmActor {
        val actor = CrmRepository.actor(call.tenant, call.permissions, call.access.userIdentity(call.tenant, call.permissions))
        return actor.copy(name = "${actor.name} (agente ${call.agentName})")
    }

    private inline fun <T> checked(block: () -> T): T = try {
        block()
    } catch (failure: CrmImportException) {
        throw McpToolException(failure.message.orEmpty())
    } catch (failure: CrmValidationException) {
        throw McpToolException(failure.message.orEmpty())
    } catch (failure: CrmActionNotice) {
        throw McpToolException(failure.message.orEmpty())
    }

    private fun strings(call: McpToolCall, key: String): List<String> =
        (call.arguments[key] as? JsonArray)?.map { (it as? JsonPrimitive)?.contentOrNull?.trim() ?: throw McpToolException("'$key' debe ser una lista de textos.") }
            ?: throw McpToolException("'$key' debe ser una lista.")

    private fun stringMap(call: McpToolCall, key: String): Map<String, String> {
        val value = call.arguments[key] ?: return emptyMap()
        val obj = value as? JsonObject ?: throw McpToolException("'$key' debe ser un objeto de textos.")
        return obj.mapValues { (it.value as? JsonPrimitive)?.contentOrNull?.trim() ?: throw McpToolException("'$key' debe ser un objeto de textos.") }
    }

    private fun job(call: McpToolCall): CrmImportJob =
        CrmImport.job(call.tenant, call.requiredUuid("job")) ?: throw McpToolException("Esa importacion ya no existe; revisa list_imports.")

    private fun jobSummary(job: CrmImportJob) = buildJsonObject {
        put("id", job.id.toString())
        put("name", job.name)
        put("source", job.source)
        put("status", job.status)
        put("total", job.total)
        put("processed", job.processed)
        put("created", job.created)
        put("updated", job.updated)
        put("skipped", job.skipped)
        put("conflicts", job.conflicts)
        put("errors", job.errors)
        put("createdAt", job.createdAt.toString())
        if (job.error.isNotEmpty()) put("error", job.error)
    }

    private fun imports(call: McpToolCall): JsonElement = buildJsonObject {
        putJsonArray("imports") { CrmImport.jobs(call.tenant).forEach { add(jobSummary(it)) } }
        put("fields", JsonObject(CrmImportParse.fields.filterKeys { it.isNotEmpty() }.mapValues { JsonPrimitive(it.value) }))
        put("presets", JsonArray(CrmImport.presets(call.tenant).keys.sorted().map(::JsonPrimitive)))
        put("mondayTokenConfigured", CrmImport.mondayToken(call.tenant) != null)
    }

    // Reads a source into a new job in "mapping": [source] gives (source, (name, table)).
    private fun read(call: McpToolCall, source: (CrmScreens.Context) -> Pair<String, Pair<String, CrmTable>>): JsonElement {
        val context = context(call)
        val (kind, named) = checked { source(context) }
        val (name, table) = named
        val id = CrmImport.create(call.tenant, kind, name, table, actor(call).userId)
        return buildJsonObject {
            put("job", id.toString())
            put("result", "${table.rows.size} filas leidas. Revisa el mapeo con get_import.")
        }
    }

    private fun mondayBoards(call: McpToolCall): JsonElement {
        val token = CrmImport.mondayToken(call.tenant) ?: throw McpToolException("Guarda primero el token de la API de monday en la app (Importar).")
        val boards = checked { CrmImport.mondayBoards(token) }
        return buildJsonObject {
            putJsonArray("boards") {
                boards.forEach { board ->
                    add(buildJsonObject {
                        put("id", board.id)
                        put("name", board.name)
                        put("workspace", board.workspace)
                        board.items?.let { put("items", it) }
                    })
                }
            }
        }
    }

    private fun importMonday(call: McpToolCall): JsonElement {
        if (CrmImport.mondayToken(call.tenant) == null) throw McpToolException("Guarda primero el token de la API de monday en la app (Importar).")
        val id = checked { CrmImport.queueMonday(call.tenant, strings(call, "boards"), actor(call).userId) }
        return buildJsonObject {
            put("job", id.toString())
            put("result", "Se estan leyendo los tableros de monday; consulta get_import en unos minutos.")
        }
    }

    private fun preview(call: McpToolCall, job: CrmImportJob, context: CrmScreens.Context) =
        CrmImport.preview(call.tenant, job, CrmImport.Context(context.stages, context.interests, context.users, context.today, job.source))

    private fun importJob(call: McpToolCall): JsonElement {
        val context = context(call)
        val job = job(call)
        return JsonObject(jobSummary(job) + buildJsonObject {
            put("columns", JsonObject(job.columns.associateWith { JsonPrimitive(job.mapping.columns[it].orEmpty()) }))
            put("stages", JsonObject(job.mapping.stages.mapValues { JsonPrimitive(it.value) }))
            put("owners", JsonObject(job.mapping.owners.mapValues { JsonPrimitive(it.value) }))
            put("interests", JsonObject(job.mapping.interests.mapValues { JsonPrimitive(it.value) }))
            put("sampleRows", JsonArray(CrmImport.rows(call.tenant, job, limit = 5).map { (_, row, _) -> JsonObject(row.mapValues { JsonPrimitive(it.value.take(300)) }) }))
            if (job.status == "mapping") {
                put("distinctValues", buildJsonObject {
                    listOf("stage", "owner", "interest").forEach { field -> put(field, JsonArray(CrmImport.distinctValues(call.tenant, job, field).map(::JsonPrimitive))) }
                })
                if (job.columns.isNotEmpty()) {
                    val preview = preview(call, job, context)
                    put("preview", buildJsonObject {
                        put("created", preview.created)
                        put("updated", preview.updated)
                        put("conflicts", preview.conflicts)
                        put("skipped", preview.skipped)
                    })
                }
            } else {
                listOf("conflict", "error").forEach { status ->
                    put("${status}Rows", JsonArray(CrmImport.rows(call.tenant, job, status, 20).map { (number, _, message) -> JsonPrimitive("Fila $number: $message") }))
                }
            }
        })
    }

    private fun previewResult(call: McpToolCall, job: CrmImportJob, context: CrmScreens.Context, mapping: CrmImportMapping): JsonElement {
        val saved = CrmImport.job(call.tenant, job.id)!!
        val preview = preview(call, saved, context)
        val missing = if (mapping.columns.values.none { it in setOf("name", "email", "phone") }) " Ninguna columna es nombre, correo o telefono." else ""
        return buildJsonObject {
            put("result", "Vista previa: ${preview.created} nuevos, ${preview.updated} actualizados, ${preview.conflicts} por revisar, ${preview.skipped} omitidos.$missing")
        }
    }

    private fun mapImport(call: McpToolCall): JsonElement {
        val context = context(call)
        val job = job(call)
        if (job.status != "mapping") throw McpToolException("Esa importacion ya empezo.")
        val columns = stringMap(call, "columns")
        columns.keys.firstOrNull { it !in job.columns }?.let { throw McpToolException("La columna '$it' no esta en el archivo. Columnas: ${job.columns.joinToString()}.") }
        columns.values.firstOrNull { it.isNotEmpty() && it !in CrmImportParse.fields }?.let { throw McpToolException("El campo '$it' no existe; revisa fields en list_imports.") }
        val mapping = CrmImportMapping(
            columns = columns.filterValues { it.isNotEmpty() },
            stages = stringMap(call, "stages").filterValues { it.isNotEmpty() },
            owners = stringMap(call, "owners").filterValues { it.isNotEmpty() },
            interests = stringMap(call, "interests").filterValues { it.isNotEmpty() },
        )
        CrmImport.saveMapping(call.tenant, job, mapping)
        call.string("presetName", 80).takeIf { it.isNotEmpty() }?.let { CrmImport.savePreset(call.tenant, it, mapping) }
        return previewResult(call, job, context, mapping)
    }

    private fun applyPreset(call: McpToolCall): JsonElement {
        val context = context(call)
        val job = job(call)
        if (job.status != "mapping") throw McpToolException("Esa importacion ya empezo.")
        val mapping = CrmImport.presets(call.tenant)[call.requiredString("preset", 80)] ?: throw McpToolException("Ese mapeo ya no existe; revisa list_imports.")
        CrmImport.saveMapping(call.tenant, job, mapping)
        return previewResult(call, job, context, mapping)
    }

    private fun startImport(call: McpToolCall): JsonElement {
        val job = job(call)
        if (job.status != "mapping") throw McpToolException("Esa importacion ya empezo.")
        if (job.mapping.columns.values.none { it in setOf("name", "email", "phone") }) throw McpToolException("Mapea al menos el nombre, el correo o el telefono.")
        CrmImport.setStatus(call.tenant, job, "running")
        return buildJsonObject { put("result", "Importacion en marcha: se aplican 300 filas por minuto. Consulta el avance con get_import.") }
    }

    private fun cancelImport(call: McpToolCall): JsonElement {
        val job = job(call)
        CrmImport.setStatus(call.tenant, job, "canceled")
        return buildJsonObject { put("result", "Importacion ${job.name} cancelada.") }
    }

    private fun bulk(call: McpToolCall): JsonElement {
        val context = context(call)
        val ids = strings(call, "leads")
        if (ids.size > 500) throw McpToolException("Envia hasta 500 leads por llamada.")
        val leads = ids.map { runCatching { UUID.fromString(it) }.getOrNull() ?: throw McpToolException("'$it' no es un id de lead.") }
            .mapNotNull { CrmRepository.lead(call.tenant, it) }
            .filter { context.scope.canSee(it.interestId, it.ownerUserId) }
        val (action, value) = when (call.requiredString("action", 20)) {
            "move" -> CrmImportScreens.ActionStage to call.requiredString("stage", 100).let { wanted -> context.stages.firstOrNull { it.name.equals(wanted, ignoreCase = true) }?.name ?: wanted }
            // An unknown name would leave the leads unassigned: only blank means that.
            "assign" -> CrmImportScreens.ActionOwner to call.string("owner", 200).let { wanted ->
                if (wanted.isEmpty()) "" else context.users.firstOrNull { it.name.equals(wanted, ignoreCase = true) }?.name
                    ?: throw McpToolException("'$wanted' no es un usuario con acceso al CRM. Usuarios: ${context.users.joinToString { it.name }}.")
            }
            "delete" -> CrmImportScreens.ActionDelete to ""
            else -> throw McpToolException("'action' debe ser move, assign o delete.")
        }
        val message = checked { CrmBulkActions.apply(context, leads, action, value, actor(call)) }
        return buildJsonObject {
            put("result", message)
            if (leads.size < ids.size) put("note", "${ids.size - leads.size} id(s) no existen o no son visibles para el usuario y se omitieron.")
        }
    }
}
