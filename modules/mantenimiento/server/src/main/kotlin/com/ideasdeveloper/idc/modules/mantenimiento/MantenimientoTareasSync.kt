package com.ideasdeveloper.idc.modules.mantenimiento

import com.ideasdeveloper.idc.server.modules.ModuleAccessService
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import org.slf4j.LoggerFactory
import java.sql.Connection
import java.util.UUID

/**
 * Integración opcional con Tareas (apagada por defecto): cada orden con responsable se refleja en una tarea del tablero que el
 * gestor eligió en Ajustes. Se publica con los permisos VIVOS del usuario que configuró (ver [MantenimientoBridge]); si Tareas no
 * está disponible la bandera tareas_sync_needed queda puesta y el trabajo reintenta.
 */
object MantenimientoTareasSync {
    private val log = LoggerFactory.getLogger(MantenimientoTareasSync::class.java)

    const val ListBoardsTool = "tareas_list_boards"
    const val CreateTaskTool = "tareas_create_task"
    const val UpdateTaskTool = "tareas_update_task"
    const val BatchSize = 50
    private const val MaxTitle = 300
    private const val MaxDetail = 20000

    /** La referencia estable de la tarea de una orden: hace idempotente la creación. */
    fun externalRef(orderId: UUID): String = "mantenimiento:order:$orderId"

    fun taskTitle(order: Order): String = "${order.displayNumber} · ${order.title}".take(MaxTitle)

    fun taskDetail(order: Order): String = buildString {
        listOfNotNull(order.propertyName, order.assetName).takeIf { it.isNotEmpty() }?.let { appendLine(it.joinToString(" · ")) }
        if (order.description.isNotBlank()) append(order.description.trim())
    }.trim().take(MaxDetail)

    /** El estado de la tarea que corresponde al estado de la orden (códigos de Tareas). */
    fun taskStatus(status: OrderStatus): String = when (status) {
        OrderStatus.InProgress -> "in_progress"
        OrderStatus.Done -> "done"
        OrderStatus.Cancelled, OrderStatus.Rejected -> "cancelled"
        OrderStatus.Requested, OrderStatus.Quoting, OrderStatus.Open, OrderStatus.OnHold -> "pending"
    }

    /** Argumentos de tareas_create_task. [assigneeRef] es el correo o el id del responsable; null crea la tarea sin responsable. */
    fun createArguments(order: Order, boardId: UUID, assigneeRef: String?): JsonObject = buildJsonObject {
        put("board_id", boardId.toString())
        put("title", taskTitle(order))
        put("detail", taskDetail(order))
        put("priority", order.priority.id)
        order.dueDate?.let { put("due_date", it.toString()) }
        if (assigneeRef != null) put("assignees", JsonArray(listOf(JsonPrimitive(assigneeRef))))
        put("external_ref", externalRef(order.id))
    }

    /** Argumentos de tareas_update_task: título, detalle, prioridad, fecha (vacía la quita), estado y responsable. */
    fun updateArguments(order: Order, taskId: UUID, assigneeRef: String?): JsonObject = buildJsonObject {
        put("id", taskId.toString())
        put("title", taskTitle(order))
        put("detail", taskDetail(order))
        put("priority", order.priority.id)
        put("due_date", order.dueDate?.toString().orEmpty())
        put("status", taskStatus(order.status))
        if (assigneeRef != null) put("assignees", JsonArray(listOf(JsonPrimitive(assigneeRef))))
    }

    /** El id de la tarea en la respuesta de tareas_create_task (`task.id`). */
    fun taskIdOf(json: JsonElement): UUID? =
        runCatching { UUID.fromString((json.jsonObject["task"]?.jsonObject?.get("id") as? JsonPrimitive)?.contentOrNull) }.getOrNull()

    /** Los tableros que el usuario puede ver en Tareas, para elegir el de Ajustes. Null si Tareas no está disponible para él. */
    fun boards(access: ModuleAccessService, tenant: Connection, companyCode: String, userId: UUID, serverOwner: Boolean): List<Pair<UUID, String>>? {
        val result = MantenimientoBridge.call(access, tenant, companyCode, userId, serverOwner, ListBoardsTool, JsonObject(emptyMap()))
        val json = (result as? MantenimientoBridge.BridgeResult.Ok)?.json ?: return null
        val items = runCatching { json.jsonObject["boards"] as? JsonArray }.getOrNull() ?: return emptyList()
        return items.mapNotNull { item ->
            val board = runCatching { item.jsonObject }.getOrNull() ?: return@mapNotNull null
            val id = runCatching { UUID.fromString((board["id"] as? JsonPrimitive)?.contentOrNull) }.getOrNull() ?: return@mapNotNull null
            id to ((board["name"] as? JsonPrimitive)?.contentOrNull.orEmpty())
        }
    }

    /** Pura: el publicador puede publicar si tiene acceso al módulo y gestiona mantenimiento (los dueños lo tienen todo). */
    fun canPublish(permissions: com.ideasdeveloper.idc.server.modules.ModulePermissions?): Boolean =
        permissions != null && permissions.has(MantenimientoModule.Manage)

    /**
     * Cuerpo del trabajo tareas-sync: publica las órdenes marcadas (lote de [BatchSize]). Un fallo por orden se registra y la bandera
     * queda para el siguiente intento. Devuelve cuántas órdenes se sincronizaron.
     */
    fun run(access: ModuleAccessService, tenant: Connection, companyCode: String): Int {
        val settings = MantenimientoRepository.settings(tenant)
        val boardId = settings.tareasBoardId ?: return 0
        val publisher = settings.tareasPublisherId ?: return 0
        // El publicador debe seguir gestionando mantenimiento (o ser dueño del negocio) con sus permisos de ahora: si no, no se publica
        // nada en su nombre. El ajuste no se borra: si recupera el permiso, se retoma.
        val live = try {
            access.userAccess(tenant, companyCode, publisher, false)?.permissions(MantenimientoModule.id)
        } catch (stop: java.util.concurrent.CancellationException) {
            throw stop
        } catch (failure: Exception) {
            log.warn("No se pudo comprobar al usuario que publica en Tareas ({}).", failure.javaClass.simpleName)
            return 0
        }
        if (!canPublish(live)) {
            log.warn("El usuario que publica en Tareas ya no gestiona Mantenimiento: no se publica nada hasta que se reasigne en Ajustes.")
            return 0
        }
        var synced = 0
        MantenimientoRepository.syncCandidates(tenant, BatchSize).forEach { order ->
            try {
                if (syncOrder(access, tenant, companyCode, boardId, publisher, order)) synced++
            } catch (stop: InterruptedException) {
                throw stop
            } catch (stop: java.util.concurrent.CancellationException) {
                throw stop
            } catch (failure: Exception) {
                log.warn("No se pudo sincronizar la orden {} con Tareas ({}).", order.displayNumber, failure.javaClass.simpleName)
            }
        }
        return synced
    }

    private fun syncOrder(access: ModuleAccessService, tenant: Connection, companyCode: String, boardId: UUID, publisher: UUID, order: Order): Boolean {
        val taskId = order.tareasTaskId
        // Sin tarea y sin nada que publicar (cerrada o sin responsable): no hay nada que reflejar.
        if (taskId == null && (!order.status.isActive || order.assigneeId == null)) {
            MantenimientoRepository.clearSyncFlag(tenant, order.id, order.updatedAt)
            return false
        }
        val assigneeRef = order.assigneeId?.let { MantenimientoRepository.userById(tenant, it)?.email ?: it.toString() }
        if (taskId == null) {
            val created = callWithAssigneeFallback(access, tenant, companyCode, publisher, CreateTaskTool, createArguments(order, boardId, assigneeRef), assigneeRef)
            val newId = taskIdOf(created ?: return false.also { log.warn("Tareas no creó la tarea de {}.", order.displayNumber) })
                ?: return false.also { log.warn("Tareas no devolvió el id de la tarea de {}.", order.displayNumber) }
            MantenimientoRepository.setTareasTask(tenant, order.id, newId)
        } else {
            callWithAssigneeFallback(access, tenant, companyCode, publisher, UpdateTaskTool, updateArguments(order, taskId, assigneeRef), assigneeRef)
                ?: return false.also { log.warn("Tareas no actualizó la tarea de {}.", order.displayNumber) }
        }
        MantenimientoRepository.clearSyncFlag(tenant, order.id, order.updatedAt)
        return true
    }

    // Si Tareas rechaza al responsable (no usa Tareas), se publica la tarea sin responsable en lugar de no publicarla.
    private fun callWithAssigneeFallback(
        access: ModuleAccessService,
        tenant: Connection,
        companyCode: String,
        publisher: UUID,
        tool: String,
        arguments: JsonObject,
        assigneeRef: String?,
    ): JsonElement? {
        val first = MantenimientoBridge.call(access, tenant, companyCode, publisher, false, tool, arguments)
        if (first is MantenimientoBridge.BridgeResult.Ok) return first.json
        if (assigneeRef == null) return null
        val without = JsonObject(arguments - "assignees")
        return (MantenimientoBridge.call(access, tenant, companyCode, publisher, false, tool, without) as? MantenimientoBridge.BridgeResult.Ok)?.json
    }
}
