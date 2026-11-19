package com.ideasdeveloper.idc.modules.tareas

import com.ideasdeveloper.idc.server.modules.CustomerHistoryEvent
import com.ideasdeveloper.idc.server.modules.ModuleAccessService
import com.ideasdeveloper.idc.ui.UiOpenModule
import org.slf4j.LoggerFactory
import java.sql.Connection

/** What the module writes in the customer's history (Clientes): the moment a task of that customer is completed. */
object TareasCustomerEvents {
    private val log = LoggerFactory.getLogger(TareasCustomerEvents::class.java)

    /** Written once per task (same sourceRef); does nothing when the task has no customer or Clientes is not there. */
    fun completed(tenant: Connection, access: ModuleAccessService, task: Task, author: String) {
        val customerId = task.customerId ?: return
        runCatching {
            access.recordCustomerEvent(
                tenant,
                CustomerHistoryEvent(
                    customerId = customerId,
                    moduleId = TareasModule.id,
                    kind = "task_done",
                    // A private board's task title is not shown to everyone who reads the customer's history.
                    title = if (task.boardVisibility == BoardVisibility.Private) "Tarea completada" else "Tarea completada: ${task.title}".take(200),
                    detail = if (task.boardVisibility == BoardVisibility.Private) "" else task.boardName,
                    author = author,
                    link = UiOpenModule(TareasModule.id, "task", mapOf("id" to task.id.toString())),
                    sourceRef = "task:${task.id}:done",
                ),
            )
        }.onFailure { log.warn("No se pudo escribir en el historial del cliente ({}).", it.javaClass.simpleName) }
    }
}
