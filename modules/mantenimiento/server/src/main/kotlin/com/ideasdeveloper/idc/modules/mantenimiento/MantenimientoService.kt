package com.ideasdeveloper.idc.modules.mantenimiento

import com.ideasdeveloper.idc.server.modules.ModuleAccessService
import com.ideasdeveloper.idc.server.modules.ModulePermissions
import com.ideasdeveloper.idc.ui.UiFieldValues
import java.sql.Connection
import java.util.UUID

/**
 * Operaciones de las pantallas: comprueban el permiso y la visibilidad con el scope del usuario, escriben dentro de una transacción
 * (con su nota de historial) y, después de confirmar, mandan los avisos sin que un fallo de aviso rompa la operación. Una orden
 * que el usuario no ve se informa como inexistente. Todo es síncrono: quien llama lo ejecuta dentro de withContext(Dispatchers.IO).
 */
object MantenimientoService {
    private const val NotFound = "Orden no encontrada."
    private const val NotManager = "Solo quien gestiona mantenimiento puede hacer esto."

    fun scope(permissions: ModulePermissions): MantenimientoScope = MantenimientoScope.from(permissions)

    fun actor(tenant: Connection, access: ModuleAccessService, permissions: ModulePermissions, scope: MantenimientoScope): MantenimientoActor =
        MantenimientoActor(scope.userId, access.userIdentity(tenant, permissions)?.name ?: "Usuario")

    fun visibleOrder(tenant: Connection, scope: MantenimientoScope, id: UUID): Order =
        MantenimientoRepository.getOrderVisible(tenant, scope, id) ?: throw MantenimientoValidationException(NotFound)

    private fun requireManage(scope: MantenimientoScope) {
        if (!scope.canManage) throw MantenimientoValidationException(NotManager)
    }

    private fun missing(what: String): Nothing = throw MantenimientoValidationException(what)

    /** Los activos como opciones: todos los activos si [full] (quien edita) y siempre el [current], aunque esté archivado. */
    fun assetOptions(tenant: Connection, full: Boolean, current: UUID?): AssetOptions {
        val list = if (full) MantenimientoRepository.listAssets(tenant) else emptyList()
        val extra = current?.takeIf { id -> list.none { it.id == id } }?.let { MantenimientoRepository.getAsset(tenant, it) }
        return AssetOptions(list + listOfNotNull(extra))
    }

    private fun lookups(tenant: Connection, current: UUID?): Lookups =
        Lookups(MantenimientoRepository.assignableUsers(tenant), MantenimientoRepository.listProperties(tenant), assetOptions(tenant, full = true, current = current))

    private fun fileIds(values: Map<String, String>): List<UUID> =
        UiFieldValues.files(values["files"].orEmpty()).mapNotNull { MantenimientoActions.uuid(it.id) }

    // ---- órdenes ----

    /** Crea una avería o un proyecto. Responsable, fecha límite y "cotizado por" solo cuentan si quien crea gestiona. */
    fun createOrder(tenant: Connection, access: ModuleAccessService, permissions: ModulePermissions, kind: OrderKind, values: Map<String, String>): Order {
        if (kind == OrderKind.Preventive) missing("Las órdenes preventivas las genera un plan.")
        val scope = scope(permissions)
        val actor = actor(tenant, access, permissions, scope)
        val input = MantenimientoActions.orderInput(kind, values, lookups(tenant, null), scope.canManage)
        val created = MantenimientoRepository.transaction(tenant) {
            val result = MantenimientoRepository.createOrder(tenant, input, actor)
            // El autor enlaza sus propios archivos al reportar.
            MantenimientoRepository.attach(tenant, result.id, fileIds(values), permissions.userId)
            result
        }
        val order = MantenimientoRepository.getOrder(tenant, created.id) ?: missing(NotFound)
        MantenimientoNotify.created(tenant, access.notificationsFor(MantenimientoModule.id), order, scope.userId)
        return order
    }

    /**
     * Guarda el formulario: quien gestiona edita todo; quien trabaja la orden solo costo real y resolución. Quien solo la ve no guarda nada.
     */
    fun saveOrder(tenant: Connection, access: ModuleAccessService, permissions: ModulePermissions, id: UUID, values: Map<String, String>): Order {
        val scope = scope(permissions)
        val before = visibleOrder(tenant, scope, id)
        val actor = actor(tenant, access, permissions, scope)
        val updated = when {
            scope.canEdit(before) -> {
                val input = MantenimientoActions.orderInput(before.kind, values, lookups(tenant, before.assetId), canManage = true, existing = before)
                MantenimientoRepository.transaction(tenant) {
                    val saved = MantenimientoRepository.updateOrder(tenant, id, input, actor) ?: missing(NotFound)
                    applyFiles(tenant, scope, permissions, saved, values)
                    saved
                }
            }
            MantenimientoActions.canWorkNow(scope, before) -> {
                val work = MantenimientoActions.workValues(values, before)
                MantenimientoRepository.updateWork(tenant, id, work.costAmount, work.costCurrency, work.resolution)
                MantenimientoRepository.getOrder(tenant, id) ?: missing(NotFound)
            }
            else -> missing("No tienes permiso para editar esta orden.")
        }
        if (updated.assigneeId != before.assigneeId) MantenimientoNotify.assigned(tenant, access.notificationsFor(MantenimientoModule.id), updated, scope.userId)
        return updated
    }

    /** Enlaza los archivos nuevos y quita los que el usuario sacó del formulario (quien no gestiona solo quita los que subió él). */
    fun applyFiles(tenant: Connection, scope: MantenimientoScope, permissions: ModulePermissions, order: Order, values: Map<String, String>) {
        val current = MantenimientoRepository.listAttachments(tenant, scope, order.id)
        val changes = MantenimientoActions.fileChanges(values, current)
        if (changes.added.isEmpty() && changes.removed.isEmpty()) return
        if (!MantenimientoActions.canAttach(scope, order)) missing("No puedes cambiar los archivos de esta orden.")
        if (changes.added.isNotEmpty()) {
            MantenimientoRepository.attach(tenant, order.id, changes.added.mapNotNull { MantenimientoActions.uuid(it.id) }, permissions.userId)
        }
        val removable = if (scope.canEdit(order)) changes.removed else changes.removed.filter { id -> current.firstOrNull { it.id == id }?.uploadedBy == permissions.userId }
        if (removable.isNotEmpty()) MantenimientoRepository.markDeleted(tenant, order.id, removable)
    }

    fun saveFiles(tenant: Connection, permissions: ModulePermissions, id: UUID, values: Map<String, String>) {
        val scope = scope(permissions)
        val order = visibleOrder(tenant, scope, id)
        MantenimientoRepository.transaction(tenant) { applyFiles(tenant, scope, permissions, order, values) }
    }

    /**
     * Cambia el estado con las reglas de [OrderTransitions]. Al completar se guardan el costo y la resolución del formulario si vienen en
     * [values]; al rechazar [values] trae el motivo.
     */
    fun changeStatus(tenant: Connection, access: ModuleAccessService, permissions: ModulePermissions, id: UUID, to: OrderStatus, values: Map<String, String>): Order {
        val scope = scope(permissions)
        val before = visibleOrder(tenant, scope, id)
        val actor = actor(tenant, access, permissions, scope)
        val after = if (to == OrderStatus.Done) {
            val resolution = values["resolution"]
            val cost = if ("cost" in values) MantenimientoActions.amount(values["cost"], "El costo") else null
            MantenimientoRepository.closeOrder(tenant, id, resolution, cost, actor, scope)
        } else {
            MantenimientoRepository.changeStatus(tenant, id, to, actor, scope, reason = values["reason"].orEmpty())
        }
        MantenimientoNotify.statusChanged(tenant, access.notificationsFor(MantenimientoModule.id), before, after, scope.userId)
        return after
    }

    /** Tomar una orden sin responsable (atómico: si otra persona la tomó antes, avisa). */
    fun take(tenant: Connection, access: ModuleAccessService, permissions: ModulePermissions, id: UUID) {
        val scope = scope(permissions)
        val order = visibleOrder(tenant, scope, id)
        if (!scope.canTake(order)) missing("No puedes tomar esta orden.")
        val actor = actor(tenant, access, permissions, scope)
        if (!MantenimientoRepository.takeOrder(tenant, id, actor)) missing("Otra persona ya tomó la orden.")
    }

    fun addNote(tenant: Connection, access: ModuleAccessService, permissions: ModulePermissions, id: UUID, body: String) {
        val scope = scope(permissions)
        val order = visibleOrder(tenant, scope, id)
        if (!MantenimientoActions.canNote(scope, order)) missing("No puedes anotar en esta orden.")
        MantenimientoRepository.addNote(tenant, id, body, actor(tenant, access, permissions, scope))
    }

    fun addCheck(tenant: Connection, permissions: ModulePermissions, id: UUID, text: String) {
        val scope = scope(permissions)
        val order = visibleOrder(tenant, scope, id)
        if (!MantenimientoActions.canWorkChecklist(scope, order)) missing("No puedes cambiar la lista de esta orden.")
        MantenimientoRepository.addCheckItem(tenant, id, text)
    }

    fun toggleCheck(tenant: Connection, permissions: ModulePermissions, id: UUID, item: UUID, done: Boolean) {
        val scope = scope(permissions)
        val order = visibleOrder(tenant, scope, id)
        if (!MantenimientoActions.canWorkChecklist(scope, order)) missing("No puedes cambiar la lista de esta orden.")
        if (!MantenimientoRepository.setCheckDone(tenant, id, item, done, scope.userId)) missing("Ese elemento ya no existe.")
    }

    fun removeCheck(tenant: Connection, permissions: ModulePermissions, id: UUID, item: UUID) {
        val scope = scope(permissions)
        val order = visibleOrder(tenant, scope, id)
        if (!scope.canEdit(order)) missing(NotManager)
        if (!MantenimientoRepository.removeCheckItem(tenant, id, item)) missing("Ese elemento ya no existe.")
    }

    // ---- Compras ----

    /** Si el botón de compras debe mostrarse: Compras activo y la herramienta usable con los permisos vivos del usuario. */
    fun purchaseAvailable(tenant: Connection, access: ModuleAccessService, permissions: ModulePermissions): Boolean {
        val userId = permissions.userId ?: return false
        return MantenimientoPurchases.available(access, tenant, permissions.companyCode, userId, permissions.serverOwner)
    }

    /** Pide la compra de la orden (solo quien la trabaja). Devuelve el aviso de éxito o lanza el motivo del fallo. */
    fun requestPurchase(tenant: Connection, access: ModuleAccessService, permissions: ModulePermissions, id: UUID): String {
        val scope = scope(permissions)
        val order = visibleOrder(tenant, scope, id)
        if (!scope.canWork(order) || !order.status.isActive) missing("No puedes pedir la compra de esta orden.")
        val userId = permissions.userId ?: missing("No se pudo identificar a tu usuario.")
        return when (val outcome = MantenimientoPurchases.request(access, tenant, permissions.companyCode, userId, permissions.serverOwner, id)) {
            is MantenimientoPurchases.PurchaseOutcome.Created -> "Solicitud de compra ${outcome.folio} creada como borrador en Compras."
            is MantenimientoPurchases.PurchaseOutcome.Busy -> missing(outcome.message)
            is MantenimientoPurchases.PurchaseOutcome.Failed -> missing(outcome.reason)
        }
    }

    // ---- activos ----

    fun saveAsset(tenant: Connection, permissions: ModulePermissions, id: UUID?, values: Map<String, String>): UUID {
        val scope = scope(permissions)
        requireManage(scope)
        val properties = MantenimientoRepository.listProperties(tenant)
        if (id == null) return MantenimientoRepository.createAsset(tenant, MantenimientoActions.assetInput(values, properties))
        val existing = MantenimientoRepository.getAsset(tenant, id) ?: missing("Activo no encontrado.")
        if (!MantenimientoRepository.updateAsset(tenant, id, MantenimientoActions.assetInput(values, properties, existing))) missing("Activo no encontrado.")
        return id
    }

    fun archiveAsset(tenant: Connection, permissions: ModulePermissions, id: UUID, archived: Boolean) {
        requireManage(scope(permissions))
        if (!MantenimientoRepository.archiveAsset(tenant, id, archived)) missing("Activo no encontrado.")
    }

    // ---- planes ----

    fun savePlan(tenant: Connection, permissions: ModulePermissions, id: UUID?, values: Map<String, String>): UUID {
        val scope = scope(permissions)
        requireManage(scope)
        if (id == null) {
            val input = MantenimientoActions.planInput(values, lookups(tenant, null), emptyList())
            return MantenimientoRepository.createPlan(tenant, input, scope.userId)
        }
        val existing = MantenimientoRepository.getPlan(tenant, id) ?: missing("Plan no encontrado.")
        val checklist = MantenimientoRepository.planChecklist(tenant, id).map { it.text }
        val input = MantenimientoActions.planInput(values, lookups(tenant, existing.assetId), checklist, existing)
        if (!MantenimientoRepository.updatePlan(tenant, id, input)) missing("Plan no encontrado.")
        return id
    }

    fun setPlanActive(tenant: Connection, permissions: ModulePermissions, id: UUID, active: Boolean) {
        requireManage(scope(permissions))
        if (!MantenimientoRepository.setPlanActive(tenant, id, active)) missing("Plan no encontrado.")
    }

    /** Cambia la lista de comprobación del plan: [change] recibe los textos actuales y el índice del elemento tocado (-1 si no hay). */
    fun changePlanChecklist(tenant: Connection, permissions: ModulePermissions, id: UUID, itemId: UUID?, change: (List<String>, Int) -> List<String>) {
        requireManage(scope(permissions))
        val plan = MantenimientoRepository.getPlan(tenant, id) ?: missing("Plan no encontrado.")
        val items = MantenimientoRepository.planChecklist(tenant, id)
        val index = itemId?.let { wanted -> items.indexOfFirst { it.id == wanted }.also { if (it < 0) missing("Ese elemento ya no existe.") } } ?: -1
        val changed = change(items.map { it.text }, index)
        if (!MantenimientoRepository.updatePlan(tenant, id, MantenimientoActions.planInputOf(plan, changed))) missing("Plan no encontrado.")
    }

    // ---- ajustes ----

    fun addProperty(tenant: Connection, permissions: ModulePermissions, name: String) {
        requireManage(scope(permissions))
        MantenimientoRepository.addProperty(tenant, name)
    }

    fun renameProperty(tenant: Connection, permissions: ModulePermissions, id: UUID, name: String) {
        requireManage(scope(permissions))
        if (!MantenimientoRepository.renameProperty(tenant, id, name)) missing("Propiedad no encontrada.")
    }

    fun archiveProperty(tenant: Connection, permissions: ModulePermissions, id: UUID, archived: Boolean) {
        requireManage(scope(permissions))
        if (!MantenimientoRepository.archiveProperty(tenant, id, archived)) missing("Propiedad no encontrada.")
    }

    fun moveProperty(tenant: Connection, permissions: ModulePermissions, id: UUID, up: Boolean) {
        requireManage(scope(permissions))
        MantenimientoRepository.moveProperty(tenant, id, up)
    }

    fun saveDueSoon(tenant: Connection, permissions: ModulePermissions, text: String?) {
        requireManage(scope(permissions))
        val days = MantenimientoActions.int(text, "Los días de aviso") ?: missing("Indica los días de aviso (0 a 30).")
        MantenimientoRepository.saveDueSoonDays(tenant, days)
    }

    /**
     * Los tableros que este usuario puede elegir. Solo un usuario de la empresa puede configurar la integración (las tareas se publican
     * con sus permisos): el dueño del servidor no lo es.
     */
    fun tareasChoice(tenant: Connection, access: ModuleAccessService, permissions: ModulePermissions): TareasChoice {
        val scope = scope(permissions)
        val userId = scope.userId ?: return TareasChoice(emptyList(), false, "El dueño del servidor no es usuario de la empresa: configura la integración con un usuario de la empresa.")
        if (!scope.canManage) return TareasChoice(emptyList(), false, NotManager)
        if (!MantenimientoBridge.available(access, tenant, permissions.companyCode, userId, false, MantenimientoTareasSync.ListBoardsTool)) {
            return TareasChoice(emptyList(), false, "Tareas no está activo o tu usuario no tiene permiso para usarlo.")
        }
        val boards = MantenimientoTareasSync.boards(access, tenant, permissions.companyCode, userId, false)
            ?: return TareasChoice(emptyList(), false, "No se pudieron leer los tableros de Tareas.")
        return TareasChoice(boards, true)
    }

    /** Elige el tablero de Tareas (o apaga la integración con "Sin tablero"). El usuario que configura publica las tareas. */
    fun saveTareasBoard(tenant: Connection, access: ModuleAccessService, permissions: ModulePermissions, label: String?) {
        val scope = scope(permissions)
        requireManage(scope)
        val clean = label?.trim().orEmpty()
        if (clean.isEmpty() || clean.equals(MantenimientoAdminScreens.NoBoard, ignoreCase = true)) {
            MantenimientoRepository.saveTareasBoard(tenant, null, null)
            return
        }
        val userId = scope.userId ?: missing("Solo un usuario de la empresa puede configurar la integración.")
        val choice = tareasChoice(tenant, access, permissions)
        if (!choice.editable) missing(choice.reason.ifBlank { "Tareas no está disponible para tu usuario." })
        val board = choice.labels.firstOrNull { it.second.equals(clean, ignoreCase = true) } ?: missing("Tablero no válido: $clean. Elige uno de la lista.")
        MantenimientoRepository.saveTareasBoard(tenant, board.first, userId)
    }

    fun turnOffTareas(tenant: Connection, permissions: ModulePermissions) {
        requireManage(scope(permissions))
        MantenimientoRepository.saveTareasBoard(tenant, null, null)
    }
}
