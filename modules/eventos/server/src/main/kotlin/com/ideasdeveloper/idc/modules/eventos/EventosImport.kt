package com.ideasdeveloper.idc.modules.eventos

import com.ideasdeveloper.idc.server.extensions.ServerExtension
import com.ideasdeveloper.idc.server.modules.McpTool
import com.ideasdeveloper.idc.server.modules.ModuleAccessService
import com.ideasdeveloper.idc.server.modules.ModulePermissions
import com.ideasdeveloper.idc.ui.UiNode
import org.slf4j.LoggerFactory
import java.math.BigDecimal
import java.sql.Connection
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.util.UUID

/**
 * A place events can be imported from (for example the Monday board "La Jolla Eventos"). Extensions of the module implement
 * it: Eventos lists the active ones in the "Importar" screen, runs their pending work in the `import-runner` job and offers
 * their agent tools.
 */
interface EventosImportSource {
    /** The extension id, for example "eventos_monday". */
    val sourceId: String

    /** What the user sees, for example "Monday". */
    val title: String

    fun enabled(tenant: Connection, companyCode: String): Boolean

    /** The body of the import screen for this source ([params] are the query parameters of the screen). */
    fun screen(context: EventosImportContext, params: Map<String, String>): List<UiNode>

    /** An operation of the screen (POST); [values] are what the form sent. */
    fun action(context: EventosImportContext, op: String, values: Map<String, String>): EventosImportReply

    /** Tools for agents (permission eventos.import); Eventos adds them to its own. */
    val mcpTools: List<McpTool> get() = emptyList()

    /** Moves reading and applying forward and returns the rows processed. Short, idempotent, errors logged. */
    fun runPending(context: EventosImportJobContext, budget: Duration): Int
}

class EventosImportContext(
    val access: ModuleAccessService,
    val tenant: Connection,
    val companyCode: String,
    val permissions: ModulePermissions,
    val author: String,
    val today: LocalDate,
)

class EventosImportJobContext(
    val access: ModuleAccessService,
    val tenant: Connection,
    val companyCode: String,
    val today: LocalDate,
)

/** The answer to an action: the notice shown, whether it is an error, and the parameters of the screen shown next. */
data class EventosImportReply(val notice: String, val error: Boolean = false, val params: Map<String, String> = emptyMap())

object EventosImportSources {
    private val log = LoggerFactory.getLogger(EventosImportSources::class.java)

    @Volatile
    private var linked: List<EventosImportSource> = emptyList()

    /** Called once at startup with the extensions of the module; keeps the ones that are import sources. */
    fun link(extensions: List<ServerExtension>) {
        linked = extensions.filterIsInstance<EventosImportSource>()
    }

    val all: List<EventosImportSource> get() = linked

    /** The sources the company has enabled. A source that fails to answer counts as disabled. */
    fun enabled(tenant: Connection, companyCode: String): List<EventosImportSource> = all.filter { source ->
        runCatching { source.enabled(tenant, companyCode) }
            .onFailure { log.warn("No se pudo comprobar la fuente de importación {} ({}).", source.sourceId, it.javaClass.simpleName) }
            .getOrDefault(false)
    }

    fun find(id: String): EventosImportSource? = all.firstOrNull { it.sourceId == id }

    /** Runs the enabled sources one after another, each with an equal share of what is left of [budget]. */
    fun runPending(context: EventosImportJobContext, budget: Duration): Int {
        val sources = enabled(context.tenant, context.companyCode)
        val started = Instant.now()
        var processed = 0
        sources.forEachIndexed { index, source ->
            val remaining = budget.minus(Duration.between(started, Instant.now()))
            if (remaining.isNegative || remaining.isZero) return processed
            val share = remaining.dividedBy((sources.size - index).toLong())
            processed += try {
                source.runPending(context, share)
            } catch (stop: InterruptedException) {
                throw stop
            } catch (stop: java.util.concurrent.CancellationException) {
                throw stop
            } catch (failure: Exception) {
                log.warn("Falló la importación de {} ({}).", source.sourceId, failure.javaClass.simpleName)
                0
            }
        }
        return processed
    }
}

data class ImportedPayment(val key: String, val paidOn: LocalDate, val amount: BigDecimal, val note: String = "")

data class ImportedEvent(
    /** The id of the item in the source, for example "monday:<itemId>": it is the event's import_key. */
    val key: String,
    val title: String,
    val description: String = "",
    val notes: String = "",
    val category: String = "",
    val space: String = "",
    /** Local time of the server; an item without time starts at 00:00. */
    val startsAt: LocalDateTime?,
    val endsAt: LocalDateTime? = null,
    val status: EventStatus,
    val clientName: String,
    val clientEmail: String = "",
    val clientPhone: String = "",
    val responsibleEmail: String? = null,
    val responsibleName: String? = null,
    val agreedAmount: BigDecimal? = null,
    val guests: Int? = null,
    val payments: List<ImportedPayment> = emptyList(),
)

data class ImportSummary(
    val events: Int,
    val newCustomers: Int,
    val matchedPeople: List<String>,
    val unmatchedPeople: List<String>,
    val bySpace: Map<String, Int>,
    val problems: List<String>,
)

sealed class ImportOutcome {
    data class Created(val eventId: UUID) : ImportOutcome()
    data class Updated(val eventId: UUID) : ImportOutcome()
    data class Skipped(val reason: String) : ImportOutcome()
}
