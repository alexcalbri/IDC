package com.ideasdeveloper.idc.modules.crm

/** Actions on many leads at once, shared by the "Acciones en lote" screen and the agent tools. */
object CrmBulkActions {
    /**
     * Applies [action] (one of the CrmImportScreens.Action* labels) to [leads], which must already be
     * filtered to the user's scope. [value] is the stage name or the owner name (blank: unassigned).
     * Throws [CrmActionNotice]; returns the success message.
     */
    fun apply(context: CrmScreens.Context, leads: List<CrmLead>, action: String, value: String, actor: CrmActor): String {
        val tenant = context.tenant
        if (leads.isEmpty()) throw CrmActionNotice("Marca al menos un lead.", warning = true)
        return when (action) {
            CrmImportScreens.ActionStage -> {
                val stage = context.stages.firstOrNull { it.name == value } ?: throw CrmActionNotice("Elige la etapa.")
                leads.forEach { CrmRepository.moveLead(tenant, it, stage, context.stages, actor) }
                "${leads.size} lead(s) movidos a ${stage.name}."
            }
            CrmImportScreens.ActionOwner -> {
                if (!context.canAssign) throw CrmActionNotice("No tienes permiso para asignar leads.")
                val owner = context.users.firstOrNull { it.name == value }?.id
                leads.forEach { lead ->
                    tenant.prepareStatement("UPDATE crm_leads SET owner_user_id = ?, updated_at = CURRENT_TIMESTAMP WHERE id = ?").use { it.setObject(1, owner); it.setObject(2, lead.id); it.executeUpdate() }
                    CrmRepository.addActivity(tenant, lead.id, "owner", "Responsable: ${context.names.user(owner)} (en lote).", actor)
                    owner?.takeIf { it != lead.ownerUserId && it != context.scope.userId }?.let { CrmNotify.assigned(tenant, lead.id, it) }
                }
                "${leads.size} lead(s) asignados a ${context.names.user(owner)}."
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
