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
        "mapping" to "Por mapear", "running" to "Importando", "done" to "Terminada", "canceled" to "Cancelada", "failed" to "Fallo",
    )
    private val sourceLabels = mapOf("file" to "Archivo")

    private fun sourceLabel(job: CrmImportJob) = CrmImportSources.label(job.source) ?: sourceLabels[job.source]

    // El estado "reading" lo pone una fuente externa de lectura en segundo plano: ella lo nombra.
    private fun statusLabel(job: CrmImportJob) =
        if (job.status == "reading") CrmImportSources.of(job.source)?.readingLabel ?: "Leyendo" else statusLabels[job.status]

    // ---- Sources and jobs ----

    fun imports(context: CrmScreens.Context, params: Map<String, String>): List<UiNode> {
        val jobs = CrmImport.jobs(context.tenant).map { job ->
            UiListItem(
                title = job.name,
                subtitle = "${sourceLabel(job)} - ${statusLabel(job)} - ${job.processed}/${job.total} filas" +
                    if (job.processed > 0) " (${job.created} nuevos, ${job.updated} actualizados, ${job.conflicts} por revisar, ${job.skipped} omitidos)" else "",
                trailing = job.createdAt.atZone(ZoneId.systemDefault()).toLocalDate().toString(),
                action = UiNavigate("importjob", mapOf("job" to job.id.toString())),
            )
        }
        // Las fuentes externas ponen aqui sus tarjetas (o el aviso para instalar su extension).
        val sourceCards: List<UiNode> = CrmImportSources.all.flatMap { it.importCards(context) }
        return listOfNotNull(
            UiText("Cada importacion pasa por mapeo de columnas, vista previa y luego se aplica por lotes. Se busca a cada persona por correo o telefono; si el correo y el telefono son de clientes distintos la fila queda por revisar. Importar de nuevo actualiza los leads ya importados."),
            UiGrid(
                listOf(
                    *sourceCards.toTypedArray(),
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
            *CrmImportSources.all.mapNotNull { it.importExtras(context, params) }.toTypedArray(),
            UiCard(listOf(UiList(jobs, emptyText = "Sin importaciones.")), title = "Importaciones"),
        )
    }

    fun job(context: CrmScreens.Context, params: Map<String, String>): List<UiNode> {
        val job = params["job"]?.let { runCatching { UUID.fromString(it) }.getOrNull() }?.let { CrmImport.job(context.tenant, it) }
            ?: return listOf(UiText("Esa importacion ya no existe.", tone = UiTone.Warning))
        val id = mapOf("job" to job.id.toString())
        val header = listOf(
            UiRow(listOf(UiButton("Volver", UiNavigate("import"), style = "secondary"), UiText("${job.name} (${sourceLabel(job)}) - ${statusLabel(job)}", UiTextStyle.Subtitle))),
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
        // Los estados de lectura en segundo plano (reading, failed) los pinta la fuente externa del job.
        CrmImportSources.of(job.source)?.statusScreen(job, header.take(1))?.let { return it }
        if (job.status == "failed") return header.take(1) + listOf(UiText(job.error.ifEmpty { "No se pudo leer el origen." }, tone = UiTone.Danger))
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
        val fieldOptions = CrmImportTargets.options(context.fields, CrmImportTargets.customerFields(context.tenant))
        val columnFields = job.columns.map { column ->
            val type = job.columnTypes[column]?.takeIf { it.isNotEmpty() }?.let { CrmImportSources.of(job.source)?.columnLabel(it) }.orEmpty()
            UiField("col:$column", column + type, type = "dropdown", options = fieldOptions.values.toList(), section = "Columnas")
        }
        // Columns proposed as new fields: the user confirms them by saving the mapping.
        val toCreate = job.columns.filter { job.mapping.columns[it] in setOf(CrmImportTargets.NewCrm, CrmImportTargets.NewCustomer) }
        val columnValues = job.columns.associate { column -> "col:$column" to (fieldOptions[job.mapping.columns[column].orEmpty()] ?: fieldOptions.getValue("")) }
        fun valueFields(field: String, prefix: String, section: String, options: List<String>, mapped: Map<String, String>) =
            CrmImport.distinctValues(context.tenant, job, field).map { value -> UiField("$prefix:$value", value, type = "dropdown", options = options, section = section) } to
                CrmImport.distinctValues(context.tenant, job, field).associate { value ->
                    "$prefix:$value" to (mapped[value] ?: options.firstOrNull { it.equals(value, ignoreCase = true) } ?: options.first())
                }
        val (stageFields, stageValues) = valueFields("stage", "stage", "Etapas", listOf(Default) + context.stages.map { it.name }, job.mapping.stages)
        val (ownerFields, ownerValues) = valueFields("owner", "owner", "Personas asignadas (cada persona de la columna)", listOf(CrmForms.Unassigned) + context.users.map { it.name }, job.mapping.owners)
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
                    UiText(
                        "Datos de la persona (documento de identidad, fecha de nacimiento, direccion...) van como campo del cliente: los ven Clientes y los demas modulos. " +
                            "Lo propio del negocio va como campo del CRM.",
                        UiTextStyle.Caption,
                    ),
                    UiForm(
                        "mapping", columnFields + stageFields + ownerFields + interestFields + UiField("presetName", "Guardar este mapeo como (opcional)", section = "Mapeo guardado"),
                        columnValues + stageValues + ownerValues + interestValues,
                        submit = UiButton("Guardar mapeo y ver vista previa", UiRequest(UiRoute + "/importjob", params = id + ("op" to "map"), formId = "mapping")),
                        minFieldWidth = 240,
                    ),
                ),
                title = "Mapeo",
            ),
            toCreate.takeIf { it.isNotEmpty() }?.let { columns ->
                UiCard(
                    listOf(
                        UiText("Estas columnas no tienen un campo en el CRM. Al guardar el mapeo se crean los campos marcados con \"Crear campo...\"; elige \"No importar\" para las que no quieras."),
                        UiList(
                            columns.map { column ->
                                val target = if (job.mapping.columns[column] == CrmImportTargets.NewCustomer) "campo del cliente" else "campo del CRM"
                                val (type, multiple) = CrmImportSources.fieldType(job.source, job.columnTypes[column])
                                UiListItem(column, "Se crearia como $target de tipo ${type.label}${if (multiple) " (varias opciones)" else ""}")
                            },
                        ),
                    ),
                    title = "Campos que faltan",
                )
            },
            *(CrmImportSources.of(job.source)?.mappingNotes(job).orEmpty()).toTypedArray(),
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
    fun mapping(context: CrmScreens.Context, job: CrmImportJob, values: Map<String, String>): CrmImportMapping {
        val byLabel = CrmImportTargets.options(context.fields, CrmImportTargets.customerFields(context.tenant)).entries.associate { it.value to it.key }
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
    const val ActionAddOwner = "Anadir asignado"
    const val ActionRemoveOwner = "Quitar asignado"
    const val ActionClearOwners = "Sin asignar"
    const val ActionArchive = "Archivar"
    const val ActionRestore = "Restaurar"
    const val ActionDelete = "Eliminar"

    /**
     * Bulk actions of the list view of "Leads": one checkbox per lead of the current page and the action to
     * apply (the renderer has no selection mode, so this is a form). Null without crm.leads.manage.
     * [keep] are the filters of the view, sent back so the list stays as it was.
     */
    fun bulkCard(context: CrmScreens.Context, leads: List<CrmLead>, page: Int, total: Int, keep: Map<String, String>): UiNode? {
        if (!context.permissions.has(CrmScreens.Manage) || leads.isEmpty()) return null
        val actions = listOfNotNull(
            ActionStage, ActionAddOwner.takeIf { context.canAssign }, ActionRemoveOwner.takeIf { context.canAssign }, ActionClearOwners.takeIf { context.canAssign },
            ActionArchive, ActionRestore,
            ActionDelete.takeIf { context.permissions.has(CrmScreens.Delete) },
        )
        val selection = leads.map { lead ->
            UiField("lead:${lead.id}", "${lead.name} - ${context.stage(lead.stageId)?.name.orEmpty()} - ${CrmAssignees.label(context.names, lead.ownerUserIds)}", type = "boolean", section = "Leads (pagina $page, $total en total)")
        }
        val fields = listOf(
            UiField("all", "Seleccionar todos los visibles (${leads.size})", type = "boolean", section = "Seleccion"),
        ) + selection + listOf(
            UiField("action", "Accion", type = "dropdown", required = true, options = actions, section = "Accion"),
            UiField("stage", "Etapa", type = "dropdown", options = context.stages.map { it.name }, section = "Accion", visibleWhen = com.ideasdeveloper.idc.ui.UiFieldCondition("action", listOf(ActionStage))),
            UiField("owner", "Usuario", type = "dropdown", options = (context.users + CrmBulkActions.legacyOf(context, leads)).map { it.name }, section = "Accion", visibleWhen = com.ideasdeveloper.idc.ui.UiFieldCondition("action", listOf(ActionAddOwner, ActionRemoveOwner))),
        )
        return UiCard(
            listOf(
                UiText("Marca los leads de esta pagina y elige la accion. Los filtros de arriba se mantienen.", UiTextStyle.Caption),
                UiForm(
                    "bulk", fields, mapOf("action" to actions.first()),
                    submit = UiButton(
                        "Aplicar a los seleccionados",
                        UiRequest(UiRoute + "/leads", params = keep + ("op" to "bulk") + ("page" to page.toString()), formId = "bulk", confirm = "Se aplica la accion a los leads marcados. Continuar?"),
                    ),
                    minFieldWidth = 320,
                ),
            ),
            title = "Acciones en lote",
            collapsible = true,
            expanded = false,
        )
    }
}
