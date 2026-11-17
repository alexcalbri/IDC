package com.ideasdeveloper.idc.modules.crm

import java.util.UUID

/** Actions on many leads at once, shared by the bulk bar of the list view of "Leads" and the agent tools. */
object CrmBulkActions {
    // Most checkboxes read from one request.
    const val MaxSelected = 500

    /**
     * Ids the bulk form selected, always inside [pageIds] (the leads of the page the form was drawn for):
     * with "all" on, the whole page; otherwise the pages leads whose checkbox is "true". Ids that are not
     * on the page are ignored, so a stale or forged form never reaches other leads.
     */
    fun selectedIds(values: Map<String, String>, pageIds: List<UUID>): List<UUID> {
        if (values["all"] == "true") return pageIds.take(MaxSelected)
        val checked = values.filter { it.key.startsWith("lead:") && it.value == "true" }.keys.take(MaxSelected)
            .mapNotNull { runCatching { UUID.fromString(it.removePrefix("lead:")) }.getOrNull() }.toSet()
        return pageIds.filter { it in checked }
    }

    /**
     * The user [value] names: only the "unassigned" option (CrmForms.Unassigned) leaves the lead without a
     * responsible user; an empty or unknown name is an error, never a silent unassign.
     */
    fun ownerFor(users: List<CrmUser>, value: String): UUID? {
        if (value == CrmForms.Unassigned) return null
        return users.firstOrNull { it.name == value }?.id ?: throw CrmActionNotice("Elige el responsable.")
    }

    /** "3 lead(s) movidos a X. 2 omitidos: motivo (2)." ; nothing done is a warning. */
    fun summary(done: Int, doneText: String, skipped: Map<String, Int>): String {
        val omitted = skipped.values.sum()
        if (omitted == 0) return "$done $doneText"
        val reasons = skipped.entries.joinToString("; ") { "${it.key} (${it.value})" }
        val text = "$done actualizados, $omitted omitidos: $reasons."
        if (done == 0) throw CrmActionNotice(text, warning = true)
        return "$done $doneText $text"
    }

    /**
     * Applies [action] (one of the CrmImportScreens.Action* labels) to [leads], which must already be
     * filtered to the user's scope. [value] is the stage name or the owner name ("unassigned" option: none).
     * A lead that cannot take the action is skipped and counted. Throws [CrmActionNotice]; returns the success message.
     */
    fun apply(context: CrmScreens.Context, leads: List<CrmLead>, action: String, value: String, actor: CrmActor): String {
        val tenant = context.tenant
        if (leads.isEmpty()) throw CrmActionNotice("Marca al menos un lead.", warning = true)
        val skipped = linkedMapOf<String, Int>()
        fun skip(reason: String, count: Int = 1) { skipped[reason] = (skipped[reason] ?: 0) + count }
        return when (action) {
            CrmImportScreens.ActionStage -> {
                val stage = context.stages.firstOrNull { it.name == value } ?: throw CrmActionNotice("Elige la etapa.")
                var done = 0
                leads.forEach { lead ->
                    if (CrmRepository.wonNeedsReservation(tenant, lead.id, stage)) {
                        skip("tienen habitaciones elegidas sin reserva: confirma la reserva desde su ficha")
                        return@forEach
                    }
                    try {
                        CrmRepository.moveLead(tenant, lead, stage, context.stages, actor)
                        done++
                    } catch (failure: CrmValidationException) {
                        skip(failure.message.orEmpty().ifBlank { "no se pudieron mover" })
                    }
                }
                summary(done, "lead(s) movidos a ${stage.name}.", skipped)
            }
            CrmImportScreens.ActionOwner -> {
                if (!context.canAssign) throw CrmActionNotice("No tienes permiso para asignar leads.")
                val owner = ownerFor(context.users, value)
                var done = 0
                leads.forEach { lead ->
                    try {
                        // The change and its history entry go together.
                        CrmRepository.transaction(tenant) {
                            tenant.prepareStatement("UPDATE crm_leads SET owner_user_id = ?, updated_at = CURRENT_TIMESTAMP WHERE id = ?").use { it.setObject(1, owner); it.setObject(2, lead.id); it.executeUpdate() }
                            CrmRepository.addActivity(tenant, lead.id, "owner", "Responsable: ${context.names.user(owner)} (en lote).", actor)
                        }
                        owner?.takeIf { it != lead.ownerUserId && it != context.scope.userId }?.let { CrmNotify.assigned(tenant, lead.id, it) }
                        done++
                    } catch (failure: CrmValidationException) {
                        skip(failure.message.orEmpty().ifBlank { "no se pudieron asignar" })
                    }
                }
                summary(done, "lead(s) asignados a ${context.names.user(owner)}.", skipped)
            }
            CrmImportScreens.ActionArchive -> {
                val archived = leads.count { CrmRepository.archiveLead(tenant, it.id, actor) }
                if (archived < leads.size) skip("ya estaban archivados", leads.size - archived)
                summary(archived, "lead(s) archivados.", skipped)
            }
            CrmImportScreens.ActionRestore -> {
                val restored = leads.count { CrmRepository.restoreLead(tenant, it.id, actor) }
                if (restored < leads.size) skip("ya estaban abiertos", leads.size - restored)
                summary(restored, "lead(s) restaurados.", skipped)
            }
            CrmImportScreens.ActionDelete -> {
                if (!context.permissions.has(CrmScreens.Delete)) throw CrmActionNotice("No tienes permiso para eliminar leads.")
                leads.forEach { CrmRepository.deleteLead(tenant, it.id) }
                "${leads.size} lead(s) eliminados (los clientes quedan en Clientes)."
            }
            else -> throw CrmActionNotice("Elige la accion.")
        }
    }
}
