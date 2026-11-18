package com.ideasdeveloper.idc.extensions.wordpress

import com.ideasdeveloper.idc.modules.crm.CrmExtensionIds
import com.ideasdeveloper.idc.modules.crm.CrmExtensions
import com.ideasdeveloper.idc.modules.crm.CrmHost
import com.ideasdeveloper.idc.modules.crm.CrmImport
import com.ideasdeveloper.idc.modules.crm.CrmImportException
import com.ideasdeveloper.idc.modules.crm.CrmImportMcpTools
import com.ideasdeveloper.idc.modules.crm.CrmImportNote
import com.ideasdeveloper.idc.modules.crm.CrmImportParse
import com.ideasdeveloper.idc.modules.crm.CrmImportSource
import com.ideasdeveloper.idc.modules.crm.CrmImportSourceResult
import com.ideasdeveloper.idc.modules.crm.CrmScreens
import com.ideasdeveloper.idc.modules.crm.CrmTable
import com.ideasdeveloper.idc.modules.crm.CrmWordPressException
import com.ideasdeveloper.idc.server.modules.McpTool
import com.ideasdeveloper.idc.server.modules.ModuleFiles
import com.ideasdeveloper.idc.ui.UiButton
import com.ideasdeveloper.idc.ui.UiCard
import com.ideasdeveloper.idc.ui.UiNode
import com.ideasdeveloper.idc.ui.UiRequest
import com.ideasdeveloper.idc.ui.UiText
import com.ideasdeveloper.idc.ui.UiTextStyle
import com.ideasdeveloper.idc.ui.UiTone
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import java.sql.Connection
import java.time.Duration
import java.time.LocalDate
import java.util.UUID

/**
 * Importacion de los leads de CT Leads Pro, el plugin de CRM del sitio WordPress. Se lee por el mismo plugin IDC Connector,
 * con el sitio y el secreto de la extension, asi que vive aqui y no en una extension propia. Lee en el momento (sin cola):
 * crea el job en mapeo y el resto lo hace el pipeline generico del CRM. Solo depende de la superficie publica del CRM.
 */
object WordPressImport : CrmImportSource {
    const val Source = "leadspro"
    private const val PageSize = 200
    private const val MaxPages = 200
    private const val UiRoute = CrmScreens.UiRoute

    private fun JsonElement?.obj() = this as? JsonObject
    private fun JsonElement?.arr() = (this as? JsonArray).orEmpty()
    private fun JsonElement?.str() = (this as? JsonPrimitive)?.contentOrNull?.trim().orEmpty()

    // ---- CrmImportSource ----

    override val id = Source
    override val label = "CT Leads Pro"
    override val readingLabel = "Leyendo CT Leads Pro"
    override val extensionId: String = CrmExtensionIds.WordPress

    override fun importCards(context: CrmScreens.Context): List<UiNode> {
        // Sin la extension de WordPress activa, en su lugar se muestra el aviso para instalarla.
        CrmExtensions.missingNodes(context.tenant, CrmExtensionIds.WordPress)?.let { return it }
        val ready = CrmWordPress.load(context.tenant).ready
        return listOf(
            UiCard(
                listOf(
                    UiText("Lee los leads de CT Leads Pro del sitio conectado: etapa, agente, origen, etiquetas, notas, mensajes y propiedades de interes."),
                    UiButton("Leer CT Leads Pro", UiRequest(UiRoute + "/import", params = mapOf("op" to "leadspro")), enabled = ready),
                ) + listOfNotNull(UiText("Conecta el sitio en Integraciones > WordPress.", UiTextStyle.Caption).takeIf { !ready }),
                title = "Real estate (CT Leads Pro)",
            ),
        )
    }

    override fun action(tenant: Connection, op: String, values: Map<String, String>, userId: UUID?): CrmImportSourceResult? {
        if (op != "leadspro") return null
        return try {
            val (job, rows) = readJob(tenant, LocalDate.now(), userId)
            CrmImportSourceResult("$rows filas leidas. Revisa el mapeo.", UiTone.Success, "importjob", mapOf("job" to job.toString()))
        } catch (failure: CrmImportException) {
            CrmImportSourceResult(failure.message.orEmpty(), UiTone.Danger)
        }
    }

    override val mcpTools: List<McpTool>
        get() = listOf(
            McpTool(
                name = "import_leadspro",
                title = "Importar desde CT Leads Pro",
                description = "Lee los leads del plugin CT Leads Pro del sitio WordPress conectado y crea una importacion en mapeo.",
                permission = CrmScreens.Settings,
            ) { call ->
                val (job, rows) = CrmImportMcpTools.checked { readJob(call.tenant, LocalDate.now(), CrmImportMcpTools.actor(call).userId) }
                buildJsonObject {
                    put("job", job.toString())
                    put("result", "$rows filas leidas. Revisa el mapeo con get_import.")
                }
            },
        )

    // La lectura es sincrona (la hace la accion o la herramienta): no hay jobs en "reading".
    override fun readQueued(tenant: Connection) = Unit

    override fun runPending(tenant: Connection, limit: Int, today: LocalDate, files: ModuleFiles?, budget: Duration): Int {
        // Las importaciones de CT Leads Pro esperan a que la extension de WordPress vuelva a estar activa.
        if (!CrmHost.extensionEnabled(tenant, CrmExtensionIds.WordPress)) return 0
        return CrmImport.runPending(
            tenant, limit, today, budget,
            accept = { it.source == Source },
            label = { "CT Leads Pro" },
        )
    }

    // ---- Lectura ----

    /** Lee los leads de CT Leads Pro y crea el job en mapeo; devuelve el id del job y cuantas filas leyo. */
    private fun readJob(tenant: Connection, today: LocalDate, userId: UUID?): Pair<UUID, Int> {
        val table = fromLeadsPro(tenant)
        val job = CrmImport.create(tenant, Source, "CT Leads Pro $today", table, userId)
        return job to table.rows.size
    }

    /** Leads of CT Leads Pro read through the IDC Connector plugin (stage, agent, source, tags, notes, messages, listings). */
    fun fromLeadsPro(tenant: Connection): CrmTable {
        CrmHost.extensionInactiveReason(tenant, CrmExtensionIds.WordPress)?.let { throw CrmImportException(it) }
        val settings = CrmWordPress.load(tenant)
        if (!settings.ready) throw CrmImportException("Conecta primero el sitio en Integraciones > WordPress.")
        val rows = mutableListOf<Map<String, String>>()
        var page = 1
        while (true) {
            val result = try {
                CrmWordPress.rpc(settings, "leads", buildJsonObject { put("page", page); put("per_page", PageSize) }).obj()
            } catch (failure: CrmWordPressException) {
                throw CrmImportException(failure.message.orEmpty())
            } ?: throw CrmImportException("Respuesta invalida del sitio.")
            if (result["error"].str().isNotEmpty()) throw CrmImportException(result["error"].str())
            val items = result["items"].arr().mapNotNull { it.obj() }
            items.forEach { lead ->
                val notes = (lead["notes"].arr() + lead["messages"].arr()).mapNotNull { it.obj() }.map {
                    CrmImportNote(CrmImportParse.date(it["date"].str()), it["author"].str(), it["text"].str())
                }
                rows += mapOf(
                    "Nombre" to lead["name"].str(),
                    "Correo" to lead["email"].str(),
                    "Telefono" to lead["phone"].str(),
                    "Etapa" to lead["stage"].str(),
                    "Agente" to lead["agent"].obj()?.get("name").str(),
                    "Origen" to lead["source"].str(),
                    "Etiquetas" to lead["tags"].arr().joinToString { it.str() },
                    "Creado" to lead["created"].str(),
                    CrmImport.KeyColumn to "leadspro:${lead["id"].str()}",
                    CrmImport.NotesColumn to CrmImport.notesJson(notes),
                    CrmImport.ListingsColumn to JsonArray(lead["listings"].arr()).toString(),
                )
            }
            if (items.size < PageSize || page >= MaxPages) break
            page++
        }
        return CrmTable(listOf("Nombre", "Correo", "Telefono", "Etapa", "Agente", "Origen", "Etiquetas", "Creado"), rows)
    }
}
