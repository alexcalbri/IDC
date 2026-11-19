package com.ideasdeveloper.idc.modules.tareas

import com.ideasdeveloper.idc.server.modules.CustomerTimeline
import com.ideasdeveloper.idc.server.modules.CustomerTimelineEntry
import com.ideasdeveloper.idc.ui.UiOpenModule

/**
 * What Tareas adds to a customer's history in Clientes: the tasks linked to the customer, when they were created and
 * when they were completed. It is computed when read (nothing is written in customer_history), so only who may see
 * the task sees it: the same rule as the module's lists ([TareasScope.seesTask]), and archived tasks never appear.
 */
object TareasCustomerTimeline {
    val timeline = CustomerTimeline(permission = TareasModule.View) { query ->
        val scope = TareasService.scope(query.tenant, query.permissions)
        entries(TareasRepository.customerTasks(query.tenant, scope, query.customerId, query.limit), scope, query.limit)
    }

    /**
     * The entries of [tasks] the [scope] sees, newest first, at most [limit]. Pure: it does not touch the database, and it
     * repeats the visibility and archive checks so the rule holds whatever the caller passes.
     */
    fun entries(tasks: List<Task>, scope: TareasScope, limit: Int): List<CustomerTimelineEntry> =
        tasks.asSequence()
            .filter { it.archivedAt == null && scope.seesTask(it) }
            .flatMap { task ->
                val link = UiOpenModule(TareasModule.id, "task", mapOf("id" to task.id.toString()))
                val completedAt = task.completedAt?.takeIf { task.status == TaskStatus.Done }
                listOfNotNull(
                    CustomerTimelineEntry(
                        at = task.createdAt,
                        kind = "task_created",
                        title = "Tarea: ${task.title}".take(300),
                        detail = "${task.boardName} · ${task.status.label}",
                    ).also { it.link = link },
                    completedAt?.let {
                        CustomerTimelineEntry(
                            at = it,
                            kind = "task_done",
                            title = "Tarea completada: ${task.title}".take(300),
                            detail = task.boardName,
                        ).also { entry -> entry.link = link }
                    },
                )
            }
            .sortedByDescending { it.at }
            .take(limit.coerceAtLeast(0))
            .toList()
}
