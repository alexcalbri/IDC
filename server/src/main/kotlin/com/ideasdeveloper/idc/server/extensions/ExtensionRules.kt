package com.ideasdeveloper.idc.server.extensions

import com.ideasdeveloper.idc.ModuleVersion
import com.ideasdeveloper.idc.server.modules.ModuleAccessErrorResponse
import io.ktor.http.HttpStatusCode

/*
 * Rules of the extension lifecycle that need no database: state transitions, the gate's answer,
 * uninstall answers, which settings may be copied and whether a group target may receive an apply.
 * They are kept apart so they are tested without connections.
 */

/** A refused extension operation; [kind] decides the HTTP status. */
class ExtensionException(val code: String, override val message: String, val kind: Kind = Kind.Conflict) : RuntimeException(message) {
    enum class Kind(val status: HttpStatusCode) {
        BadRequest(HttpStatusCode.BadRequest),
        Forbidden(HttpStatusCode.Forbidden),
        NotFound(HttpStatusCode.NotFound),
        Conflict(HttpStatusCode.Conflict),
        Unavailable(HttpStatusCode.ServiceUnavailable),
    }
}

object ExtensionLifecycle {
    private val transitions: Map<String, Set<String>> = mapOf(
        ExtensionStatus.Installed to setOf(ExtensionStatus.Enabled, ExtensionStatus.Disabled, ExtensionStatus.Incompatible, ExtensionStatus.Removed),
        ExtensionStatus.Enabled to setOf(ExtensionStatus.Disabled, ExtensionStatus.Incompatible, ExtensionStatus.Removed),
        ExtensionStatus.Disabled to setOf(ExtensionStatus.Enabled, ExtensionStatus.Incompatible, ExtensionStatus.Removed),
        // Once a compatible version is loaded again the owner can enable it or leave it disabled.
        ExtensionStatus.Incompatible to setOf(ExtensionStatus.Enabled, ExtensionStatus.Disabled, ExtensionStatus.Removed),
        // Installing again after an uninstall starts from "installed".
        ExtensionStatus.Removed to setOf(ExtensionStatus.Installed),
    )

    fun canTransition(from: String?, to: String): Boolean =
        if (from == null) to == ExtensionStatus.Installed else transitions[from]?.contains(to) == true

    fun require(from: String?, to: String) {
        if (!canTransition(from, to)) {
            throw ExtensionException("invalid_transition", "La extension no puede pasar de ${from ?: "no instalada"} a $to.")
        }
    }

    /** Whether the loaded module satisfies [minModuleVersion]; an unknown loaded version counts as satisfied. */
    fun isCompatible(minModuleVersion: String?, loadedModuleVersion: String?): Boolean {
        val minimum = minModuleVersion?.trim()?.takeIf { it.isNotEmpty() } ?: return true
        val loaded = loadedModuleVersion?.trim()?.takeIf { it.isNotEmpty() } ?: return true
        return ModuleVersion.compare(loaded, minimum) >= 0
    }
}

/** What the gate answers for the status stored in tenant_extensions (null = no row). */
sealed class ExtensionGateDecision {
    data object Open : ExtensionGateDecision()
    data class Closed(val status: HttpStatusCode, val body: ModuleAccessErrorResponse) : ExtensionGateDecision()

    companion object {
        fun forStatus(status: String?): ExtensionGateDecision = when (status) {
            ExtensionStatus.Enabled -> Open
            ExtensionStatus.Incompatible -> Closed(
                HttpStatusCode.Conflict,
                ModuleAccessErrorResponse("extension_incompatible", "La extension no es compatible con la version del modulo."),
            )
            ExtensionStatus.Installed, ExtensionStatus.Disabled -> Closed(
                HttpStatusCode.Forbidden,
                ModuleAccessErrorResponse("extension_disabled", "La extension esta desactivada en esta empresa."),
            )
            else -> Closed(
                HttpStatusCode.NotFound,
                ModuleAccessErrorResponse("extension_not_installed", "La extension no esta instalada en esta empresa."),
            )
        }
    }
}

object UninstallChoices {
    /**
     * The "keep"/"delete" choice of every declared option. [answers] wins over the declared default;
     * an option whose default is "ask" needs an answer, and unknown options or values are refused.
     */
    fun resolve(options: List<ExtensionUninstallOption>, answers: Map<String, String>): Map<String, String> {
        val known = options.map { it.id }.toSet()
        answers.keys.firstOrNull { it !in known }?.let {
            throw ExtensionException("unknown_uninstall_option", "La opcion '$it' no existe en esta extension.", ExtensionException.Kind.BadRequest)
        }
        val valid = setOf(UninstallDefault.Keep, UninstallDefault.Delete)
        val missing = mutableListOf<String>()
        val resolved = LinkedHashMap<String, String>()
        for (option in options) {
            val answer = answers[option.id]?.trim()?.lowercase()
            when {
                answer != null && answer in valid -> resolved[option.id] = answer
                answer != null -> throw ExtensionException(
                    "invalid_uninstall_choice", "La respuesta de '${option.id}' debe ser keep o delete.", ExtensionException.Kind.BadRequest,
                )
                option.default in valid -> resolved[option.id] = option.default
                else -> missing += option.id
            }
        }
        if (missing.isNotEmpty()) {
            throw ExtensionException(
                "uninstall_choice_required", "Responde estas opciones antes de desinstalar: ${missing.joinToString(", ")}.",
                ExtensionException.Kind.BadRequest,
            )
        }
        return resolved
    }

    fun views(options: List<ExtensionUninstallOption>): List<UninstallOptionView> = options.map { UninstallOptionView(it.id, it.label, it.default) }
}

object SettingsCopy {
    /**
     * The settings that may be copied to another company: only those the manifest declares, never
     * those of scope "connection" (a page, an account or a number belongs to one company).
     */
    fun copyableKeys(definitions: List<ExtensionSettingDefinition>): List<String> =
        definitions.filter { it.scope != ExtensionSettingScope.Connection }.map { it.key }

    fun copyable(definitions: List<ExtensionSettingDefinition>, source: Map<String, String>): Map<String, String> {
        val keys = copyableKeys(definitions).toSet()
        return source.filterKeys { it in keys }
    }
}

/** Facts about one group target, read by ExtensionService; the validator only decides. */
data class GroupTargetFacts(
    val companyCode: String,
    val exists: Boolean,
    val active: Boolean,
    val sameGroup: Boolean,
    val suspended: Boolean,
    /** Business owner email of the target now; null when it cannot be read. */
    val ownerEmail: String?,
    /** Email recorded for the group when it was linked (the source company's group_owner_email). */
    val expectedOwnerEmail: String?,
    val moduleEnabled: Boolean,
    val moduleInstalled: Boolean,
    val compatible: Boolean,
)

object GroupTargetValidator {
    /** Why [facts] cannot receive an operation, or null when it can. */
    fun problem(facts: GroupTargetFacts, sourceCode: String): String? = when {
        facts.companyCode == sourceCode -> "La empresa de origen no puede ser destino."
        !facts.exists -> "La empresa no existe."
        !facts.active -> "La empresa no esta activa."
        !facts.sameGroup -> "La empresa no pertenece al grupo."
        facts.suspended -> "La empresa esta suspendida en el grupo hasta que el dueno del servidor la revise."
        facts.expectedOwnerEmail.isNullOrBlank() || facts.ownerEmail.isNullOrBlank() ->
            "No se pudo verificar el business owner de la empresa."
        !facts.expectedOwnerEmail.equals(facts.ownerEmail, ignoreCase = true) -> "El business owner de la empresa no es el del grupo."
        !facts.moduleInstalled -> "El modulo no esta instalado en el servidor."
        !facts.moduleEnabled -> "El modulo no esta activo en la empresa."
        !facts.compatible -> "La version del modulo es menor que la que la extension necesita."
        else -> null
    }
}

/** One target of an operation as stored in extension_operation_targets. */
data class TargetRecord(val companyCode: String, val status: String, val reason: String?, val attempts: Int)

object TargetStatus {
    const val Pending = "pending"
    const val Applied = "applied"
    const val Failed = "failed"
    const val Skipped = "skipped"
}

object OperationStatus {
    const val Running = "running"
    const val Done = "done"
    const val Partial = "partial"
    const val Failed = "failed"

    fun of(targets: List<TargetRecord>): String = when {
        targets.any { it.status == TargetStatus.Pending } -> Running
        targets.none { it.status == TargetStatus.Applied } && targets.any { it.status == TargetStatus.Failed } -> Failed
        targets.any { it.status == TargetStatus.Failed } -> Partial
        else -> Done
    }
}

data class OperationHeader(val id: Long, val actor: String, val operation: String, val extensionId: String, val sourceCompany: String?, val status: String)

/** Persistence of operations (extension_operations / extension_operation_targets). */
interface ExtensionOperationStore {
    /** Creates the operation with its pending targets in one transaction. */
    fun create(actor: String, operation: String, extensionId: String, sourceCompany: String?, targets: List<String>): Long
    fun targets(operationId: Long): List<TargetRecord>
    fun updateTarget(operationId: Long, companyCode: String, status: String, reason: String?, attempts: Int)
    fun finish(operationId: Long, status: String)
    fun header(operationId: Long): OperationHeader?

    /**
     * Startup recovery: operations still "running" (the server stopped in the middle) end as failed
     * and their pending targets as failed ("interrumpida"). Returns how many operations it closed.
     */
    fun failInterrupted(): Int
}

const val InterruptedReason = "interrumpida"

/**
 * Runs an operation target by target. [validate] returns a reason when a target is refused (it ends
 * as skipped); [execute] runs one target in its own transaction and throws on failure (it ends as
 * failed). A failure never stops or undoes the other targets.
 */
class OperationRunner(private val store: ExtensionOperationStore) {
    fun start(
        actor: String,
        operation: String,
        extensionId: String,
        sourceCompany: String?,
        targets: List<String>,
        validate: (String) -> String?,
        execute: (String) -> Unit,
    ): Long {
        val id = store.create(actor, operation, extensionId, sourceCompany, targets)
        targets.forEach { run(id, it, attempts = 1, validate, execute) }
        store.finish(id, OperationStatus.of(store.targets(id)))
        return id
    }

    /** Runs only the targets that failed, inside the same operation; applied and skipped ones are left alone. */
    fun retryFailed(operationId: Long, validate: (String) -> String?, execute: (String) -> Unit) {
        val failed = store.targets(operationId).filter { it.status == TargetStatus.Failed }
        if (failed.isEmpty()) {
            throw ExtensionException("nothing_to_retry", "La operacion no tiene destinos fallidos.")
        }
        failed.forEach { run(operationId, it.companyCode, it.attempts + 1, validate, execute) }
        store.finish(operationId, OperationStatus.of(store.targets(operationId)))
    }

    private fun run(operationId: Long, company: String, attempts: Int, validate: (String) -> String?, execute: (String) -> Unit) {
        val problem = try {
            validate(company)
        } catch (failure: Exception) {
            store.updateTarget(operationId, company, TargetStatus.Failed, safeReason(failure), attempts)
            return
        }
        if (problem != null) {
            store.updateTarget(operationId, company, TargetStatus.Skipped, problem, attempts)
            return
        }
        try {
            execute(company)
            store.updateTarget(operationId, company, TargetStatus.Applied, null, attempts)
        } catch (failure: Exception) {
            store.updateTarget(operationId, company, TargetStatus.Failed, safeReason(failure), attempts)
        }
    }

    // The reason is shown to the actor and stored: only the messages written for people (ExtensionException)
    // are kept; anything else could carry SQL text or values, so it gets a generic message.
    private fun safeReason(failure: Exception): String = when (failure) {
        is ExtensionException -> failure.message
        is java.sql.SQLException -> "Error de base de datos (${failure.sqlState.orEmpty()})."
        else -> "Error inesperado al procesar esta empresa."
    }
}
