package com.ideasdeveloper.idc.modules.tareas

import java.sql.Connection
import java.time.LocalDate
import java.util.UUID

/**
 * What another module (for example mantenimiento) asks Tareas to keep: one task per [source] + [externalRef].
 * Nobody uses it in 0.1.0; the contract is documented in the module README.
 */
data class ExternalTaskRequest(
    val source: String,
    val externalRef: String,
    val boardId: UUID,
    val groupName: String? = null,
    val title: String,
    val detail: String = "",
    val priority: TaskPriority = TaskPriority.Medium,
    val dueDate: LocalDate? = null,
    val assignees: List<UUID> = emptyList(),
) {
    /** Messages describing what is wrong; empty when the request can be stored. */
    fun validate(): List<String> = buildList {
        if (!SourcePattern.matches(source)) add("source debe cumplir [a-z][a-z0-9_]{0,39}.")
        if (source in ReservedSources) add("source no puede ser ${ReservedSources.joinToString(" ni ")}.")
        if (externalRef.isBlank() || externalRef.length > MaxExternalRef) add("external_ref debe tener entre 1 y $MaxExternalRef caracteres.")
        if (groupName != null && groupName.trim().length > 80) add("El nombre del grupo admite hasta 80 caracteres.")
        addAll(asInput(null).validate())
    }

    fun asInput(groupId: UUID?, reviewerId: UUID? = null, customerId: UUID? = null) =
        TaskInput(boardId, groupId, title, detail, priority, dueDate, reviewerId, customerId, assignees)

    companion object {
        val SourcePattern = Regex("[a-z][a-z0-9_]{0,39}")
        val ReservedSources = setOf("manual", "agent")
        const val MaxExternalRef = 200
    }
}

object TareasExternal {
    /**
     * Creates the task identified by source + external_ref, or updates its title, detail, priority, due date, group and
     * responsibles when it already exists (its state, reviewer, customer and checklist are left as they are). Returns its id.
     * The caller owns the transaction and has already checked that the assignees can use the module.
     */
    fun upsertTask(tenant: Connection, request: ExternalTaskRequest): UUID {
        request.validate().firstOrNull()?.let { throw TareasValidationException(it) }
        return TareasRepository.transaction(tenant) {
            val groupId = request.groupName?.takeIf { it.isNotBlank() }?.let { TareasRepository.findOrCreateGroup(tenant, request.boardId, it) }
            val existing = TareasRepository.findByExternalRef(tenant, request.boardId, request.source, request.externalRef)
            // A task created at the same moment by another request comes back as not created and is updated below.
            val outcome = if (existing == null) {
                TareasRepository.createIfAbsent(tenant, request.asInput(groupId), actorId = null, source = request.source, externalRef = request.externalRef)
            } else {
                null
            }
            if (outcome?.created == true) return@transaction outcome.id
            val task = existing ?: outcome?.id?.let { TareasRepository.get(tenant, it) } ?: throw TareasValidationException("No se pudo guardar la tarea.")
            TareasRepository.update(tenant, task.id, request.asInput(groupId ?: task.groupId, task.reviewerId, task.customerId))
            TareasRepository.setAssignees(tenant, task.id, request.assignees)
            task.id
        }
    }
}
