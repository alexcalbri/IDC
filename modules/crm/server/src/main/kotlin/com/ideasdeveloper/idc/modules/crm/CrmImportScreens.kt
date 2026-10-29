package com.ideasdeveloper.idc.modules.crm

import com.ideasdeveloper.idc.ui.UiButton
import com.ideasdeveloper.idc.ui.UiCard
import com.ideasdeveloper.idc.ui.UiField
import com.ideasdeveloper.idc.ui.UiForm
import com.ideasdeveloper.idc.ui.UiList
import com.ideasdeveloper.idc.ui.UiListItem
import com.ideasdeveloper.idc.ui.UiMetric
import com.ideasdeveloper.idc.ui.UiGrid
import com.ideasdeveloper.idc.ui.UiNavigate
import com.ideasdeveloper.idc.ui.UiNode
import com.ideasdeveloper.idc.ui.UiRequest
import com.ideasdeveloper.idc.ui.UiRow
import com.ideasdeveloper.idc.ui.UiTable
import com.ideasdeveloper.idc.ui.UiTableColumn
import com.ideasdeveloper.idc.ui.UiText
import com.ideasdeveloper.idc.ui.UiTextStyle
import com.ideasdeveloper.idc.ui.UiTone
import java.time.ZoneId
import java.util.UUID

/** Imports (sources, mapping, preview, progress) and bulk actions on leads. */
object CrmImportScreens {
    private const val UiRoute = CrmScreens.UiRoute
    private const val Default = "Primera etapa abierta"
    private const val Keep = "Sin cambios"
    private val statusLabels = mapOf(
        "reading" to "Leyendo monday", "mapping" to "Por mapear", "running" to "Importando", "done" to "Terminada", "canceled" to "Cancelada", "failed" to "Fallo",
    )
    private val sourceLabels = mapOf("file" to "Archivo", "monday" to "monday", "leadspro" to "CT Leads Pro")

    // ---- Sources and jobs ----

    fun imports(context: CrmScreens.Context, params: Map<String, String>): List<UiNode> {
        val jobs = CrmImport.jobs(context.tenant).map { job ->
            UiListItem(
                title = job.name,
                subtitle = "${sourceLabels[job.source]} - ${statusLabels[job.status]} - ${job.processed}/${job.total} filas" +
                    if (job.processed > 0) " (${job.created} nuevos, ${job.updated} actualizados, ${job.conflicts} por revisar, ${job.skipped} omitidos)" else "",
                trailing = job.createdAt.atZone(ZoneId.systemDefault()).toLocalDate().toString(),
                action = UiNavigate("importjob", mapOf("job" to job.id.toString())),
            )
        }
        val wordpress = CrmWordPress.load(context.tenant).ready
        val mondayToken = CrmImport.mondayToken(context.tenant)
        return listOfNotNull(
            UiText("Cada importacion pasa por mapeo de columnas, vista previa y luego se aplica por lotes. Se busca a cada persona por correo o telefono; si el correo y el telefono son de clientes distintos la fila queda por revisar. Importar de nuevo actualiza los leads ya importados."),
            UiGrid(
                listOf(
                    UiCard(
                        listOfNotNull(
                            UiForm(
                                "mondayToken",
                                listOf(
                                    UiField("token", "Token de la API de monday", type = "password", required = mondayToken == null,
                                        help = "monday > tu avatar > Developers > Mi token. Se guarda para la empresa."),
                                ),
                                mapOf("token" to if (mondayToken == null) "" else CrmMail.SecretMask),
                                submit = UiButton("Guardar y cargar tableros", UiRequest(UiRoute + "/import", params = mapOf("op" to "mondayToken"), formId = "mondayToken")),
                            ),
                            mondayToken?.let {
                                UiRow(
                                    listOf(
                                        UiButton("Cargar tableros", UiNavigate("import", mapOf("monday" to "boards")), style = "secondary"),
                                        UiButton("Quitar token", UiRequest(UiRoute + "/import", params = mapOf("op" to "mondayForget"), confirm = "Se borra el token de monday guardado. Continuar?"), style = "secondary"),
                                    ),
                                )
                            },
                        ),
                        title = "monday",
                    ),
                    UiCard(
                        listOf(
                            UiText("Lee los leads de CT Leads Pro del sitio conectado: etapa, agente, origen, etiquetas, notas, mensajes y propiedades de interes."),
                            UiButton("Leer CT Leads Pro", UiRequest(UiRoute + "/import", params = mapOf("op" to "leadspro")), enabled = wordpress),
                        ) + listOfNotNull(UiText("Conecta el sitio en Integraciones > WordPress.", UiTextStyle.Caption).takeIf { !wordpress }),
                        title = "Real estate (CT Leads Pro)",
                    ),
                    UiCard(
                        listOf(
                            UiForm(
                                "importUrl",
                                listOf(UiField("url", "Direccion https del CSV o XLSX", type = "url", required = true, help = "Por ejemplo Google Sheets > Publicar en la web > CSV, o un enlace de descarga directa.")),
                                submit = UiButton("Leer archivo", UiRequest(UiRoute + "/import", params = mapOf("op" to "url"), formId = "importUrl")),
                            ),
                        ),
                        title = "Archivo por enlace",
                    ),
                    UiCard(
                        listOf(
                            UiForm(
                                "importText",
                                listOf(UiField("name", "Nombre de la importacion"), UiField("csv", "Contenido CSV", type = "textarea", required = true, help = "Copia y pega las filas con el encabezado (coma, punto y coma o tabulador).")),
                                submit = UiButton("Leer texto", UiRequest(UiRoute + "/import", params = mapOf("op" to "text"), formId = "importText")),
                            ),
                        ),
                        title = "Pegar CSV",
                    ),
                ),
                minItemWidth = 300,
            ),
            mondayToken?.takeIf { params["monday"] == "boards" }?.let { mondayBoards(it) },
            UiCard(listOf(UiList(jobs, emptyText = "Sin importaciones.")), title = "Importaciones"),
        )
    }

    /** Boards of the saved token grouped by workspace, each with a checkbox. */
    private fun mondayBoards(token: String): UiNode {
        val boards = try {
            CrmImport.mondayBoards(token)
        } catch (failure: CrmImportException) {
            return UiCard(listOf(UiText(failure.message.orEmpty(), tone = UiTone.Danger)), title = "Tableros de monday")
        }
        if (boards.isEmpty()) return UiCard(listOf(UiText("El token no tiene acceso a ningun tablero activo.", tone = UiTone.Warning)), title = "Tableros de monday")
        val fields = boards.map { board ->
            UiField("board:${board.id}", board.name + board.items?.let { " ($it elementos)" }.orEmpty(), type = "boolean", section = board.workspace)
        }
        return UiCard(
            listOf(
                UiText("Marca los tableros que quieres importar. Cada tablero queda como interes."),
                UiForm(
                    "importMonday", fields,
                    submit = UiButton("Importar tableros marcados", UiRequest(UiRoute + "/import", params = mapOf("op" to "monday", "monday" to "boards"), formId = "importMonday")),
                    minFieldWidth = 320,
                ),
            ),
            title = "Tableros de monday",
        )
    }

    fun job(context: CrmScreens.Context, params: Map<String, String>): List<UiNode> {
        val job = params["job"]?.let { runCatching { UUID.fromString(it) }.getOrNull() }?.let { CrmImport.job(context.tenant, it) }
            ?: return listOf(UiText("Esa importacion ya no existe.", tone = UiTone.Warning))
        val id = mapOf("job" to job.id.toString())
        val header = listOf(
            UiRow(listOf(UiButton("Volver", UiNavigate("import"), style = "secondary"), UiText("${job.name} (${sourceLabels[job.source]}) - ${statusLabels[job.status]}", UiTextStyle.Subtitle))),
            UiGrid(
                listOf(
                    UiCard(listOf(UiMetric("Filas", job.total.toString(), caption = "${job.processed} procesadas"))),
                    UiCard(listOf(UiMetric("Nuevos", job.created.toString()))),
                    UiCard(listOf(UiMetric("Actualizados", job.updated.toString()))),
                    UiCard(listOf(UiMetric("Por revisar", job.conflicts.toString(), captionTone = if (job.conflicts > 0) UiTone.Danger else UiTone.Neutral))),
                    UiCard(listOf(UiMetric("Omitidos / error", "${job.skipped} / ${job.errors}"))),
                ),
                minItemWidth = 160,
            ),
        )
        when (job.status) {
            "reading" -> return header.take(1) + listOf(
                UiText("Se estan leyendo ${job.boards.size} tablero(s) de monday con sus actualizaciones y subelementos. Un tablero grande tarda unos minutos; vuelve a abrir esta pantalla para seguir con el mapeo."),
                UiRow(
                    listOf(
                        UiButton("Actualizar", UiNavigate("importjob", id), style = "secondary"),
                        UiButton("Cancelar", UiRequest(UiRoute + "/importjob", params = id + ("op" to "cancel")), style = "danger"),
                    ),
                ),
            )
            "failed" -> return header.take(1) + listOf(
                UiText(job.error.ifEmpty { "No se pudieron leer los tableros." }, tone = UiTone.Danger),
                UiButton("Elegir tableros de nuevo", UiNavigate("import", mapOf("monday" to "boards")), style = "secondary"),
            )
        }
        if (job.status != "mapping") {
            val problems = (CrmImport.rows(context.tenant, job, "conflict") + CrmImport.rows(context.tenant, job, "error")).take(50).map { (rowNo, row, message) ->
                UiListItem("Fila $rowNo: ${row.values.firstOrNull().orEmpty()}", message)
            }
            return header + listOfNotNull(
                UiRow(listOf(UiButton("Cancelar", UiRequest(UiRoute + "/importjob", params = id + ("op" to "cancel"), confirm = "Se detiene la importacion; los leads ya importados quedan. Continuar?"), style = "danger"))).takeIf { job.status == "running" },
                UiText("Se aplican 300 filas por minuto. Vuelve a abrir esta pantalla para ver el avance.", UiTextStyle.Caption).takeIf { job.status == "running" },
                UiCard(listOf(UiList(problems, emptyText = "Sin filas por revisar.")), title = "Filas por revisar o con error"),
            )
        }
        val fieldOptions = CrmImportParse.fields
        val columnFields = job.columns.map { column ->
            UiField("col:$column", column, type = "dropdown", options = fieldOptions.values.toList(), section = "Columnas")
        }
        val columnValues = job.columns.associate { column -> "col:$column" to (fieldOptions[job.mapping.columns[column].orEmpty()] ?: fieldOptions.getValue("")) }
        fun valueFields(field: String, prefix: String, section: String, options: List<String>, mapped: Map<String, String>) =
            CrmImport.distinctValues(context.tenant, job, field).map { value -> UiField("$prefix:$value", value, type = "dropdown", options = options, section = section) } to
                CrmImport.distinctValues(context.tenant, job, field).associate { value ->
                    "$prefix:$value" to (mapped[value] ?: options.firstOrNull { it.equals(value, ignoreCase = true) } ?: options.first())
                }
        val (stageFields, stageValues) = valueFields("stage", "stage", "Etapas", listOf(Default) + context.stages.map { it.name }, job.mapping.stages)
        val (ownerFields, ownerValues) = valueFields("owner", "owner", "Responsables", listOf(CrmForms.Unassigned) + context.users.map { it.name }, job.mapping.owners)
        val (interestFields, interestValues) = if (context.interestsEnabled) {
            valueFields("interest", "interest", "Intereses", listOf(CrmForms.NoInterest) + context.interests.map { it.name }, job.mapping.interests)
        } else emptyList<UiField>() to emptyMap()
        val presets = CrmImport.presets(context.tenant)
        val sample = CrmImport.rows(context.tenant, job, limit = 8)
        val sampleColumns = job.columns.take(8)
        return header + listOfNotNull(
            presets.takeIf { it.isNotEmpty() }?.let {
                UiCard(
                    listOf(
                        UiForm(
                            "preset", listOf(UiField("preset", "Usar un mapeo guardado", type = "dropdown", options = it.keys.toList())),
                            submit = UiButton("Aplicar", UiRequest(UiRoute + "/importjob", params = id + ("op" to "preset"), formId = "preset"), style = "secondary"),
                        ),
                    ),
                )
            },
            UiCard(
                listOf(
                    UiText("Elige a que campo del CRM va cada columna y como se traducen sus valores. Las etapas e intereses que no elijas se buscan por nombre." +
                        if (context.interestsEnabled) "" else " (Los intereses estan desactivados: se ignoran.)"),
                    UiForm(
                        "mapping", columnFields + stageFields + ownerFields + interestFields + UiField("presetName", "Guardar este mapeo como (opcional)", section = "Mapeo guardado"),
                        columnValues + stageValues + ownerValues + interestValues,
                        submit = UiButton("Guardar mapeo y ver vista previa", UiRequest(UiRoute + "/importjob", params = id + ("op" to "map"), formId = "mapping")),
                        minFieldWidth = 240,
                    ),
                ),
                title = "Mapeo",
            ),
            UiCard(
                listOf(UiTable(sampleColumns.map { UiTableColumn(it, it) }, sample.map { (_, row, _) -> sampleColumns.associateWith { row[it].orEmpty().take(60) } })),
                title = "Primeras filas",
            ),
            UiRow(
                listOf(
                    UiButton("Importar ${job.total} filas", UiRequest(UiRoute + "/importjob", params = id + ("op" to "start"), confirm = "Se aplica el mapeo guardado a todas las filas. Continuar?")),
                    UiButton("Descartar", UiRequest(UiRoute + "/importjob", params = id + ("op" to "cancel")), style = "secondary"),
                ),
            ),
        )
    }

    /** Reads the mapping form back into a mapping. */
    fun mapping(job: CrmImportJob, values: Map<String, String>): CrmImportMapping {
        val byLabel = CrmImportParse.fields.entries.associate { it.value to it.key }
        fun section(prefix: String, ignore: String) = values.filterKeys { it.startsWith("$prefix:") }
            .mapKeys { it.key.removePrefix("$prefix:") }
            .filterValues { it.isNotBlank() && it != ignore && it != Keep }
        return CrmImportMapping(
            columns = job.columns.associateWith { column -> byLabel[values["col:$column"]].orEmpty() }.filterValues { it.isNotEmpty() },
            stages = section("stage", Default),
            owners = section("owner", CrmForms.Unassigned),
            interests = section("interest", CrmForms.NoInterest),
        )
    }

    // ---- Bulk actions ----

    const val ActionStage = "Mover a etapa"
    const val ActionOwner = "Asignar responsable"
    const val ActionDelete = "Eliminar"

    fun bulk(context: CrmScreens.Context, params: Map<String, String>): List<UiNode> {
        val filter = CrmScreens.filter(context, params)
        val page = (params["page"]?.toIntOrNull() ?: 1).coerceAtLeast(1)
        val leads = CrmRepository.leads(context.tenant, context.scope, filter, 50, (page - 1) * 50)
        val total = CrmRepository.count(context.tenant, context.scope, filter)
        val actions = listOfNotNull(ActionStage, ActionOwner.takeIf { context.canAssign }, ActionDelete.takeIf { context.permissions.has(CrmScreens.Delete) })
        val keep = params.filterKeys { it in setOf("fInterest", "fOwner", "fStage", "q") }
        val selection = leads.map { lead ->
            UiField("lead:${lead.id}", "${lead.name} - ${context.stage(lead.stageId)?.name.orEmpty()} - ${context.names.user(lead.ownerUserId)}", type = "boolean", section = "Leads (pagina $page, $total en total)")
        }
        val fields = listOf(
            UiField("all", "Seleccionar todos los visibles (${leads.size})", type = "boolean", section = "Seleccion"),
        ) + selection + listOf(
            UiField("action", "Accion", type = "dropdown", required = true, options = actions, section = "Accion"),
            UiField("stage", "Etapa", type = "dropdown", options = context.stages.map { it.name }, section = "Accion", visibleWhen = com.ideasdeveloper.idc.ui.UiFieldCondition("action", listOf(ActionStage))),
            UiField("owner", "Responsable", type = "dropdown", options = listOf(CrmForms.Unassigned) + context.users.map { it.name }, section = "Accion", visibleWhen = com.ideasdeveloper.idc.ui.UiFieldCondition("action", listOf(ActionOwner))),
        )
        return listOf(
            CrmScreens.filterCard(context, "bulk", params),
            UiCard(
                listOf(
                    UiForm(
                        "bulk", fields, mapOf("action" to actions.first()),
                        submit = UiButton("Aplicar a los seleccionados", UiRequest(UiRoute + "/bulk", params = keep + ("op" to "apply") + ("page" to page.toString()), formId = "bulk",
                            confirm = "Se aplica la accion a los leads marcados. Continuar?")),
                        minFieldWidth = 320,
                    ),
                ),
            ),
            UiRow(
                listOfNotNull(
                    UiButton("Anterior", UiNavigate("bulk", keep + ("page" to (page - 1).toString())), style = "secondary").takeIf { page > 1 },
                    UiButton("Siguiente", UiNavigate("bulk", keep + ("page" to (page + 1).toString())), style = "secondary").takeIf { page * 50 < total },
                ),
            ),
        )
    }
}
