package com.ideasdeveloper.idc.modules.crm

import com.ideasdeveloper.idc.server.modules.McpTool
import com.ideasdeveloper.idc.server.modules.ModuleFiles
import com.ideasdeveloper.idc.ui.UiNode
import com.ideasdeveloper.idc.ui.UiTone
import kotlinx.serialization.json.JsonObjectBuilder
import java.sql.Connection
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/**
 * Fuente externa de importacion (distinta de CSV y URL, que maneja el pipeline generico): lee sus filas
 * por su cuenta en segundo plano, tipa las columnas, pone sus tarjetas en la pantalla de importar y sus herramientas MCP.
 * El pipeline generico ([CrmImport]) no conoce a ninguna: solo las recorre a traves de [CrmImportSources].
 */
interface CrmImportSource {
    /** Valor de crm_import_jobs.source de los jobs de esta fuente. */
    val id: String

    /** Nombre mostrado en la lista de importaciones. */
    val label: String

    /** Etiqueta del estado "reading" (leyendo la fuente en segundo plano). */
    val readingLabel: String

    /** Extension duena de la fuente: marca los campos que crea y condiciona que corra. */
    val extensionId: String?

    /** Tipo de campo del CRM (y si admite varias opciones) para el tipo de columna que informo la fuente. */
    fun fieldType(columnType: String?): Pair<CrmFieldType, Boolean> = CrmFieldType.Text to false

    /** Sufijo con el tipo de la columna en el mapeo ("" sin tipo). */
    fun columnLabel(columnType: String): String = ""

    /** Tarjetas de la cuadricula de fuentes de la pantalla de importar (o el aviso para instalar la extension). */
    fun importCards(context: CrmScreens.Context): List<UiNode> = emptyList()

    /** Contenido extra bajo la cuadricula de fuentes (por ejemplo, lo que se puede elegir para importar). */
    fun importExtras(context: CrmScreens.Context, params: Map<String, String>): UiNode? = null

    /** Pantalla de un job de esta fuente en un estado propio (reading, failed); null: la pantalla generica. */
    fun statusScreen(job: CrmImportJob, header: List<UiNode>): List<UiNode>? = null

    /** Avisos bajo el mapeo de un job de esta fuente. */
    fun mappingNotes(job: CrmImportJob): List<UiNode> = emptyList()

    /** Datos de la fuente para list_imports (por ejemplo, si tiene credenciales guardadas; nunca las credenciales). */
    fun describe(tenant: Connection, into: JsonObjectBuilder) = Unit

    /**
     * Acciones propias de la fuente en la pantalla de importar (op de "import:<op>", con los valores del formulario).
     * Devuelve null si la operacion no es de esta fuente; si lo es, el aviso y la pantalla a la que volver.
     */
    fun action(tenant: Connection, op: String, values: Map<String, String>, userId: UUID?): CrmImportSourceResult? = null

    /** Herramientas MCP propias de la fuente. */
    val mcpTools: List<McpTool> get() = emptyList()

    /** Lee en segundo plano lo que la fuente dejo en cola (jobs en "reading"). */
    fun readQueued(tenant: Connection)

    /** Aplica filas pendientes de los jobs "running" de esta fuente; devuelve cuantas aplico. */
    fun runPending(tenant: Connection, limit: Int, today: LocalDate, files: ModuleFiles?, budget: Duration): Int
}

/** Resultado de una accion de una fuente: el aviso y la pantalla (con sus parametros) a la que se vuelve. */
data class CrmImportSourceResult(val message: String, val tone: String = UiTone.Info, val screen: String = "import", val params: Map<String, String> = emptyMap())

/**
 * Registro de las fuentes externas; el unico lugar del pipeline que las enumera. Las fuentes las traen las extensiones
 * instaladas (las que implementan [CrmImportSource]): sin la extension, la fuente no existe y sus jobs esperan.
 */
object CrmImportSources {
    /** Fuentes de los jobs que corre el runner generico (crm_import_jobs.source); cualquier otro valor es de una fuente externa. */
    private val genericSources = setOf("file")

    val all: List<CrmImportSource> get() = CrmModuleExtensions.importSources()

    fun of(source: String): CrmImportSource? = all.firstOrNull { it.id == source }

    fun label(source: String): String? = of(source)?.label

    fun fieldType(source: String, columnType: String?): Pair<CrmFieldType, Boolean> =
        of(source)?.fieldType(columnType) ?: (CrmFieldType.Text to false)

    val mcpTools: List<McpTool> get() = all.flatMap { it.mcpTools }

    fun readQueued(tenant: Connection) = all.forEach { it.readQueued(tenant) }

    /** La accion de la pantalla de importar que reconoce alguna fuente, o null si ninguna la reconoce. */
    fun action(tenant: Connection, op: String, values: Map<String, String>, userId: UUID?): CrmImportSourceResult? =
        all.firstNotNullOfOrNull { it.action(tenant, op, values, userId) }

    /**
     * Ejecucion del job de importacion: primero el runner generico (jobs sin fuente externa) y despues el de cada fuente
     * con lo que quede del cupo de filas y del tiempo. [files] es el almacen del modulo, para las fuentes que bajan archivos.
     */
    fun runPending(tenant: Connection, limit: Int, today: LocalDate, files: ModuleFiles?, budget: Duration = Duration.ofSeconds(50)): Int {
        val started = Instant.now()
        var done = CrmImport.runPending(tenant, limit, today, budget, accept = { it.source in genericSources })
        for (source in all) {
            val remaining = budget.minus(Duration.between(started, Instant.now()))
            if (done >= limit || remaining.isNegative || remaining.isZero) break
            done += source.runPending(tenant, limit - done, today, files, remaining)
        }
        return done
    }
}
