package com.ideasdeveloper.idc.modules.crm

import com.ideasdeveloper.idc.ui.UiBoardAction
import com.ideasdeveloper.idc.ui.UiFieldOption
import com.ideasdeveloper.idc.ui.UiRequest
import com.ideasdeveloper.idc.ui.UiTone
import java.util.UUID

/**
 * Actions of the board of "Leads" (pipeline op=action): the app sends the action id, the selected cards (ids separated
 * by comma) and, for the actions with options, the chosen value. They run through [CrmBulkActions], so the scope and
 * permission rules are the same as in the bulk bar of the list.
 */
object CrmBoardActions {
    const val Move = "move"
    const val AssignMe = "assign_me"
    const val Assign = "assign"
    const val Unassign = "unassign"
    const val Archive = "archive"
    const val Restore = "restore"
    const val Delete = "delete"

    /** The bulk action (CrmImportScreens.Action*) each board action runs. */
    private val bulk = mapOf(
        Move to CrmImportScreens.ActionStage,
        Assign to CrmImportScreens.ActionAddOwner,
        Unassign to CrmImportScreens.ActionClearOwners,
        Archive to CrmImportScreens.ActionArchive,
        Restore to CrmImportScreens.ActionRestore,
        Delete to CrmImportScreens.ActionDelete,
    )

    /** The bulk action of [id], or null for "assign_me" (which is an assignment of the user) and unknown ids. */
    fun bulkAction(id: String): String? = bulk[id]

    /** Cards of a request: valid ids (once each, in order) and how many entries were not valid ids. */
    class Cards(val ids: List<UUID>, val invalid: Int)

    /** Parses the comma list of ids; more than [CrmBulkActions.MaxSelected] entries, or none, is an error. */
    fun parseCards(raw: String?): Cards {
        val parts = raw.orEmpty().split(',').map { it.trim() }.filter { it.isNotEmpty() }
        if (parts.isEmpty()) throw CrmActionNotice("Marca al menos un lead.", warning = true)
        if (parts.size > CrmBulkActions.MaxSelected) throw CrmActionNotice("Selecciona hasta ${CrmBulkActions.MaxSelected} leads a la vez.")
        val ids = parts.mapNotNull { runCatching { UUID.fromString(it) }.getOrNull() }
        return Cards(ids.distinct(), parts.size - ids.size)
    }

    /** The value an action with options needs: it must be one of [allowed]; the others take none. */
    fun checkValue(id: String, value: String?, allowed: Collection<String>): String {
        if (id != Move && id != Assign) return ""
        val chosen = value.orEmpty().trim()
        if (chosen.isEmpty() || chosen !in allowed) throw CrmActionNotice(if (id == Move) "Elige la etapa." else "Elige el usuario.")
        return chosen
    }

    /**
     * Ids of the actions the user may run. The route already asks crm.leads.manage; assigning needs the assign
     * permission (or seeing every lead), deleting crm.leads.delete, "Asignarme" a user row, restoring the archived column.
     */
    fun available(canManage: Boolean, canAssign: Boolean, canDelete: Boolean, isUser: Boolean, archivedColumn: Boolean): List<String> {
        if (!canManage) return emptyList()
        return buildList {
            add(Move)
            if (canAssign && isUser) add(AssignMe)
            if (canAssign) { add(Assign); add(Unassign) }
            add(Archive)
            if (archivedColumn) add(Restore)
            if (canDelete) add(Delete)
        }
    }

    /** The actions the board declares for the user; [keep] are the filters of the view, sent back with the request. */
    fun declared(context: CrmScreens.Context, keep: Map<String, String>, archivedColumn: Boolean): List<UiBoardAction> {
        val ids = available(
            canManage = context.permissions.has(CrmScreens.Manage),
            canAssign = context.canAssign,
            canDelete = context.permissions.has(CrmScreens.Delete),
            isUser = context.users.any { it.id == context.scope.userId },
            archivedColumn = archivedColumn,
        )
        fun request(id: String, confirm: String? = null) =
            UiRequest(CrmScreens.UiRoute + "/pipeline", params = keep + ("op" to "action") + ("action" to id), confirm = confirm)
        return ids.map { id ->
            when (id) {
                Move -> UiBoardAction(id, "Mover a...", request(id), options = context.stages.map { UiFieldOption(it.name, it.name) })
                AssignMe -> UiBoardAction(id, "Asignarme", request(id))
                Assign -> UiBoardAction(id, "Asignar a...", request(id), options = context.users.map { UiFieldOption(it.name, it.name) })
                Unassign -> UiBoardAction(id, "Quitar asignados", request(id))
                Archive -> UiBoardAction(id, "Archivar", request(id))
                Restore -> UiBoardAction(id, "Restaurar", request(id))
                else -> UiBoardAction(id, "Eliminar", request(id, "Se eliminaran los leads elegidos. No se puede deshacer."), tone = UiTone.Danger)
            }
        }
    }
}
