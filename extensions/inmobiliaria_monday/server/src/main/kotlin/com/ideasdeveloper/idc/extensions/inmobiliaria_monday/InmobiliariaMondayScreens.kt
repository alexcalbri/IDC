package com.ideasdeveloper.idc.extensions.inmobiliaria_monday

import com.ideasdeveloper.idc.modules.crm.CrmActor
import com.ideasdeveloper.idc.modules.crm.CrmExtensionActionResult
import com.ideasdeveloper.idc.modules.crm.CrmHost
import com.ideasdeveloper.idc.modules.crm.CrmImportKind
import com.ideasdeveloper.idc.modules.crm.CrmScreens
import com.ideasdeveloper.idc.ui.UiButton
import com.ideasdeveloper.idc.ui.UiCard
import com.ideasdeveloper.idc.ui.UiField
import com.ideasdeveloper.idc.ui.UiFieldCondition
import com.ideasdeveloper.idc.ui.UiForm
import com.ideasdeveloper.idc.ui.UiMetric
import com.ideasdeveloper.idc.ui.UiNavigate
import com.ideasdeveloper.idc.ui.UiNode
import com.ideasdeveloper.idc.ui.UiNotice
import com.ideasdeveloper.idc.ui.UiRequest
import com.ideasdeveloper.idc.ui.UiRow
import com.ideasdeveloper.idc.ui.UiTable
import com.ideasdeveloper.idc.ui.UiTableColumn
import com.ideasdeveloper.idc.ui.UiText
import com.ideasdeveloper.idc.ui.UiTextStyle
import com.ideasdeveloper.idc.ui.UiTone
import java.sql.Connection
import java.util.UUID

/** Pantalla unica de la extension (seccion oculta del CRM, permiso crm.settings) y sus acciones. Sin tildes, como el resto del CRM. */
object InmobiliariaMondayScreens {
    const val Import = "inmobiliaria_monday_import"
    private const val Route = CrmScreens.UiRoute
    private const val NoImport = "(no importar)"
    private const val PreviewRows = 10
    private const val ShownProblems = 30
    private val optionKey = Regex("\\[([a-z0-9_]+)]$")
    private val boardId = Regex("\\[([0-9]{1,20})]$")

    private fun request(op: String, params: Map<String, String> = emptyMap(), formId: String? = null, confirm: String? = null) =
        UiRequest("$Route/$Import", params = params + ("op" to op), confirm = confirm, formId = formId)

    private fun openScreen(params: Map<String, String> = emptyMap()) = UiNavigate(Import, params)

    private fun message(text: String, tone: String = UiTone.Warning): List<UiNode> = listOf(UiText(text, tone = tone))

    // ---- Pantalla ----

    fun screen(context: CrmScreens.Context, params: Map<String, String>): List<UiNode> {
        if (!context.permissions.has(CrmScreens.Settings)) return message("No tienes permiso para importar desde monday.")
        val tenant = context.tenant
        InmobiliariaMondayService.unavailableReason(tenant)?.let { return message(it) }
        val job = params["job"]?.let { runCatching { UUID.fromString(it) }.getOrNull() }
        return try {
            if (job != null) jobScreen(tenant, job) else mainScreen(tenant, params)
        } catch (failure: InmobiliariaMondayException) {
            message(failure.message.orEmpty())
        }
    }

    private fun mainScreen(tenant: Connection, params: Map<String, String>): List<UiNode> {
        val token = InmobiliariaMondayService.token(tenant)
        val nodes = mutableListOf<UiNode>(
            UiText("Importar desde monday a Inmobiliaria", UiTextStyle.Subtitle),
            UiText(
                "Trae un tablero de monday (listados o deals) a la extension Inmobiliaria: se lee, se revisa el mapeo de columnas y recien entonces se aplica. " +
                    "Reimportar el mismo tablero actualiza lo ya importado, no lo duplica.",
                UiTextStyle.Caption,
            ),
            tokenCard(token),
        )
        if (token != null) {
            if (params["boards"] == "1") nodes += boardsCard(tenant) else nodes += UiButton("Cargar tableros", openScreen(mapOf("boards" to "1")), style = "secondary")
        }
        nodes += historyCard(tenant)
        return nodes
    }

    private fun tokenCard(token: String?): UiNode = UiCard(
        listOfNotNull(
            UiForm(
                "mondayToken",
                listOf(UiField("token", "Token de la API de monday", type = "password", required = token == null, help = "monday > tu avatar > Developers > Mi token. Se guarda sellado para la empresa y no se vuelve a mostrar.")),
                mapOf("token" to CrmHost.maskSecret(token)),
                submit = UiButton("Guardar y cargar tableros", request("token", formId = "mondayToken")),
            ),
            token?.let { UiButton("Quitar token", request("forget", confirm = "Se borra el token de monday guardado. Continuar?"), style = "secondary") },
        ),
        title = "Token de monday",
    )

    private fun boardsCard(tenant: Connection): UiNode {
        val boards = try {
            InmobiliariaMondayService.boards(tenant)
        } catch (failure: InmobiliariaMondayException) {
            return UiCard(listOf(UiText(failure.message.orEmpty(), tone = UiTone.Danger)), title = "Tableros de monday")
        }
        if (boards.isEmpty()) return UiCard(listOf(UiText("El token no tiene acceso a ningun tablero activo.", tone = UiTone.Warning)), title = "Tableros de monday")
        val kinds = InmobiliariaMondayService.requireTarget(tenant).importKinds
        val kindOptions = kinds.map { "${it.label} [${it.id}]" }
        val boardOptions = boards.map { "${it.workspace} > ${it.name}${it.items?.let { n -> " ($n)" }.orEmpty()} [${it.id}]" }
        val propertyOption = kinds.firstOrNull { it.id == MondayMapping.KindProperty }?.let { "${it.label} [${it.id}]" }
        return UiCard(
            listOf(
                UiForm(
                    "mondayStart",
                    listOfNotNull(
                        UiField("board", "Tablero", type = "dropdown", required = true, options = boardOptions),
                        UiField("kind", "Que contiene", type = "select", required = true, options = kindOptions),
                        propertyOption?.let {
                            UiField("newListings", "Es un tablero de nuevos listados (las propiedades entran como Captacion)", type = "boolean", visibleWhen = UiFieldCondition("kind", listOf(it)))
                        },
                    ),
                    mapOf("kind" to kindOptions.first()),
                    submit = UiButton("Leer tablero", request("start", formId = "mondayStart")),
                ),
            ),
            title = "Elegir tablero",
        )
    }

    private fun historyCard(tenant: Connection): UiNode {
        val jobs = InmobiliariaMondayStore.recent(tenant)
        if (jobs.isEmpty()) return UiCard(listOf(UiText("Aun no hay importaciones.", UiTextStyle.Caption)), title = "Importaciones")
        return UiCard(
            jobs.map { job ->
                UiRow(
                    listOf(
                        UiText("${job.boardName.ifEmpty { "Tablero ${job.boardId}" }} - ${kindLabel(job.kind)} - ${stateLabel(job.state)}"),
                        UiButton("Abrir", openScreen(mapOf("job" to job.id.toString())), style = "secondary"),
                    ),
                )
            },
            title = "Importaciones",
        )
    }

    private fun kindLabel(kind: String) = if (kind == MondayMapping.KindDeal) "Transacciones" else "Propiedades"

    fun stateLabel(state: String) = when (state) {
        MondayJobState.Reading -> "leyendo"
        MondayJobState.Ready -> "lista para revisar"
        MondayJobState.Applying -> "aplicando"
        MondayJobState.Done -> "terminada"
        MondayJobState.Failed -> "fallida"
        else -> state
    }

    // ---- Importacion ----

    private fun jobScreen(tenant: Connection, id: UUID): List<UiNode> {
        val job = InmobiliariaMondayService.job(tenant, id)
        val params = mapOf("job" to id.toString())
        val back = UiButton("Volver", openScreen(), style = "secondary")
        val header = listOf<UiNode>(
            UiText("${job.boardName.ifEmpty { "Tablero ${job.boardId}" }} - ${kindLabel(job.kind)}", UiTextStyle.Subtitle),
            UiText("Estado: ${stateLabel(job.state)}. Lanzada por ${job.createdByName.ifEmpty { "un usuario" }}.", UiTextStyle.Caption),
        )
        return when (job.state) {
            MondayJobState.Reading -> header + listOf(
                UiText("Se esta leyendo el tablero de monday (${job.rowsRead} elementos hasta ahora). Un tablero grande tarda unos minutos; actualiza esta pantalla para seguir."),
                UiRow(listOf(UiButton("Actualizar", openScreen(params), style = "secondary"), UiButton("Cancelar", request("cancel", params, confirm = "Se descarta la lectura. Continuar?"), style = "danger"))),
            )
            MondayJobState.Failed -> header + listOf(
                UiText(job.error.ifEmpty { "No se pudo leer el tablero." }, tone = UiTone.Danger),
                UiRow(listOf(UiButton("Descartar", request("discard", params), style = "secondary"), back)),
            )
            MondayJobState.Ready -> header + readyScreen(tenant, job, params)
            MondayJobState.Applying -> header + listOf(
                counts(job, InmobiliariaMondayStore.rowCounts(tenant, id)),
                UiText("Se esta aplicando en segundo plano, de a ${MondayMapping.BatchSize} filas por lote."),
                UiRow(listOf(UiButton("Actualizar", openScreen(params), style = "secondary"), UiButton("Cancelar", request("cancel", params, confirm = "Se detiene la aplicacion; lo ya aplicado se conserva. Continuar?"), style = "danger"))),
            )
            else -> header + doneScreen(tenant, job, params, back)
        }
    }

    private fun counts(job: MondayJob, byState: Map<String, Int>): UiNode = UiRow(
        listOf(
            UiMetric("Creadas", job.created.toString()),
            UiMetric("Actualizadas", job.updated.toString()),
            UiMetric("Omitidas", job.skipped.toString()),
            UiMetric("Con problema", (byState[MondayRowState.Failed] ?: job.failed).toString()),
            UiMetric("Pendientes", (byState[MondayRowState.Pending] ?: 0).toString()),
        ),
    )

    private fun doneScreen(tenant: Connection, job: MondayJob, params: Map<String, String>, back: UiButton): List<UiNode> {
        val nodes = mutableListOf<UiNode>(counts(job, InmobiliariaMondayStore.rowCounts(tenant, job.id)))
        nodes += if (job.failed == 0) UiText("La importacion termino sin problemas.", tone = UiTone.Success)
        else UiText("La importacion termino con ${job.failed} fila(s) con problema. Corrige el dato en monday o en Inmobiliaria y vuelve a importar: lo ya importado se actualiza, no se duplica.", tone = UiTone.Warning)
        job.problems.take(ShownProblems).forEach { nodes += UiText(it, UiTextStyle.Caption, tone = UiTone.Warning) }
        if (job.problems.size > ShownProblems) nodes += UiText("Y ${job.problems.size - ShownProblems} problema(s) mas.", UiTextStyle.Caption)
        nodes += UiRow(listOf(UiButton("Descartar el historial", request("discard", params), style = "secondary"), back))
        return nodes
    }

    private fun readyScreen(tenant: Connection, job: MondayJob, params: Map<String, String>): List<UiNode> {
        val kind = InmobiliariaMondayService.kind(tenant, job.kind)
        val missing = MondayMapping.missingRequired(kind, job.mapping)
        val nodes = mutableListOf<UiNode>(
            UiText("Se leyeron ${job.rowsRead} elementos. Revisa a que campo va cada columna y la vista previa; despues aplica la importacion."),
            mappingForm(job, kind, params),
        )
        if (job.error.isNotEmpty()) nodes += UiText(job.error, tone = UiTone.Warning)
        if (missing.isNotEmpty()) nodes += UiText("Falta mapear: ${missing.joinToString()}.", tone = UiTone.Danger)
        nodes += previewCard(tenant, job, kind)
        nodes += UiRow(
            listOf(
                UiButton("Aplicar importacion", request("apply", params, confirm = "Se importan ${job.rowsRead} elementos a Inmobiliaria. Continuar?"), enabled = missing.isEmpty()),
                UiButton("Cancelar", request("cancel", params, confirm = "Se descarta la lectura. Continuar?"), style = "danger"),
            ),
        )
        return nodes
    }

    private fun fieldOption(field: com.ideasdeveloper.idc.modules.crm.CrmImportField) = "${field.label}${if (field.required) " *" else ""} [${field.key}]"

    private fun mappingForm(job: MondayJob, kind: CrmImportKind, params: Map<String, String>): UiNode {
        val options = listOf(NoImport) + kind.fields.map(::fieldOption)
        val byKey = kind.fields.associateBy { it.key }
        val fields = job.columns.mapIndexed { index, column ->
            UiField("c$index", column.title + if (column.type.isEmpty()) "" else " (${column.type})", type = "dropdown", options = options)
        }
        val values = job.columns.mapIndexed { index, column ->
            "c$index" to (job.mapping[column.title]?.let { byKey[it] }?.let(::fieldOption) ?: NoImport)
        }.toMap()
        return UiCard(
            listOf(
                UiForm(
                    "mondayMapping", fields, values,
                    submit = UiButton("Guardar mapeo", request("saveMapping", params, formId = "mondayMapping"), style = "secondary"),
                    minFieldWidth = 320,
                ),
            ),
            title = "Mapeo de columnas (* obligatorio)",
        )
    }

    private fun previewCard(tenant: Connection, job: MondayJob, kind: CrmImportKind): UiNode {
        val mapped = kind.fields.filter { it.key in job.mapping.values }
        val rows = InmobiliariaMondayStore.rows(tenant, job.id, null, PreviewRows).map { row ->
            MondayMapping.values(row.data, job.mapping, kind, job.newListings)
        }
        return UiCard(
            listOf(
                if (mapped.isEmpty()) UiText("Mapea al menos una columna para ver la vista previa.", UiTextStyle.Caption)
                else UiTable(mapped.map { UiTableColumn(it.key, it.label) }, rows, emptyText = "Sin filas."),
            ),
            title = "Vista previa (primeras $PreviewRows filas)",
        )
    }

    // ---- Acciones ----

    fun action(context: CrmScreens.Context, actor: CrmActor, op: String, params: Map<String, String>, values: Map<String, String>): CrmExtensionActionResult {
        val tenant = context.tenant
        if (!context.permissions.has(CrmScreens.Settings)) return result(UiTone.Warning, "No tienes permiso para importar desde monday.", params)
        val job = params["job"]?.let { runCatching { UUID.fromString(it) }.getOrNull() }
        val keep = if (job != null) mapOf("job" to job.toString()) else emptyMap()
        return try {
            when (op) {
                "token" -> {
                    val typed = values["token"].orEmpty().trim().takeIf { it.isNotEmpty() && !CrmHost.isSecretPlaceholder(it) }
                    if (typed == null && InmobiliariaMondayService.token(tenant) == null) return result(UiTone.Danger, "Escribe el token de la API de monday.", emptyMap())
                    typed?.let { InmobiliariaMondayService.saveToken(tenant, it) }
                    result(UiTone.Success, "Token de monday guardado.", mapOf("boards" to "1"))
                }
                "forget" -> {
                    InmobiliariaMondayService.saveToken(tenant, null)
                    result(UiTone.Success, "Token de monday borrado.", emptyMap())
                }
                "start" -> start(tenant, actor, values)
                "saveMapping" -> {
                    val id = job ?: throw InmobiliariaMondayException("Falta la importacion.")
                    val current = InmobiliariaMondayService.job(tenant, id)
                    InmobiliariaMondayService.saveMapping(tenant, id, mappingFrom(current, values))
                    result(UiTone.Success, "Mapeo guardado.", keep)
                }
                "apply" -> {
                    InmobiliariaMondayService.apply(tenant, actor, job ?: throw InmobiliariaMondayException("Falta la importacion."))
                    result(UiTone.Success, "Se esta aplicando la importacion en segundo plano.", keep)
                }
                "cancel" -> {
                    val id = job ?: throw InmobiliariaMondayException("Falta la importacion.")
                    val text = InmobiliariaMondayService.cancel(tenant, id)
                    result(UiTone.Success, text, if (InmobiliariaMondayStore.find(tenant, id) == null) emptyMap() else keep)
                }
                "discard" -> {
                    InmobiliariaMondayService.discard(tenant, job ?: throw InmobiliariaMondayException("Falta la importacion."))
                    result(UiTone.Success, "Importacion descartada.", emptyMap())
                }
                else -> result(UiTone.Warning, "Esa accion no existe.", params - "op")
            }
        } catch (failure: InmobiliariaMondayException) {
            result(UiTone.Warning, failure.message.orEmpty(), params - "op")
        }
    }

    private fun result(tone: String, text: String, params: Map<String, String>) = CrmExtensionActionResult(Import, UiNotice(text, tone), params)

    private fun start(tenant: Connection, actor: CrmActor, values: Map<String, String>): CrmExtensionActionResult {
        val boardText = values["board"].orEmpty()
        val id = boardId.find(boardText)?.groupValues?.get(1) ?: throw InmobiliariaMondayException("Elige un tablero.")
        val kind = optionKey.find(values["kind"].orEmpty())?.groupValues?.get(1) ?: throw InmobiliariaMondayException("Elige que contiene el tablero.")
        val name = boardText.replace(boardId, "").trim().replace(Regex("\\s\\(\\d+\\)$"), "").substringAfter(" > ").take(300)
        val job = InmobiliariaMondayService.start(tenant, actor, id, name, kind, values["newListings"] == "true", emptyMap())
        return result(UiTone.Success, "Se esta leyendo el tablero de monday.", mapOf("job" to job.toString()))
    }

    /** Mapeo (columna -> campo) de los desplegables del formulario; cada desplegable lleva el indice de la columna. */
    internal fun mappingFrom(job: MondayJob, values: Map<String, String>): Map<String, String> {
        val mapping = linkedMapOf<String, String>()
        job.columns.forEachIndexed { index, column ->
            val key = optionKey.find(values["c$index"].orEmpty())?.groupValues?.get(1) ?: return@forEachIndexed
            mapping[column.title] = key
        }
        return mapping
    }
}
