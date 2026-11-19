package com.ideasdeveloper.idc.extensions.inmobiliaria

import com.ideasdeveloper.idc.modules.crm.CrmActor
import com.ideasdeveloper.idc.modules.crm.CrmScope
import com.ideasdeveloper.idc.modules.crm.CrmScreens
import com.ideasdeveloper.idc.server.modules.ModulePermissions
import java.sql.Connection
import java.util.UUID

/**
 * Reglas de permiso de la extension, en un solo sitio. Se apoyan en los permisos del CRM:
 * - ver (crm.view): todo el inventario completo y las pantallas de cierres.
 * - crear/editar (crm.leads.manage): captar propiedades y abrir o mover transacciones.
 * - ver todo (crm.leads.all): gerente; edita cualquier propiedad y ve todas las transacciones.
 * - etapas (crm.settings).
 *
 * Un agente edita el precio y el estado de las propiedades de las que es agente del listado o que creo; ve las
 * transacciones en las que es parte, que creo o cuya propiedad lista. Cada empresa tiene su propia base de datos:
 * estas reglas filtran por usuario dentro de ella.
 */
class InmobiliariaAccess(private val permissions: ModulePermissions, val scope: CrmScope) {
    /** El usuario que actua; nulo para el dueno del servidor, que no es un usuario de la empresa. */
    val userId: UUID? get() = scope.userId

    val canView: Boolean get() = permissions.has(CrmScreens.View)
    val canCreate: Boolean get() = permissions.has(CrmScreens.Manage)
    val seesAll: Boolean get() = permissions.has(CrmScreens.SeeAll)
    val canEditStages: Boolean get() = permissions.has(CrmScreens.Settings)

    fun canEditProperty(property: Property): Boolean = canEditProperty(property.listingAgentId, property.createdBy)

    fun canEditProperty(listingAgentId: UUID?, createdBy: UUID?): Boolean {
        if (!canCreate) return false
        if (seesAll) return true
        val user = userId ?: return false
        return listingAgentId == user || createdBy == user
    }

    fun canSeeDeal(deal: Deal): Boolean = canSeeDeal(deal.createdBy, deal.parties.mapNotNull { it.userId }, deal.propertyListingAgentId)

    fun canSeeDeal(createdBy: UUID?, partyUserIds: Collection<UUID>, listingAgentId: UUID?): Boolean {
        if (!canView) return false
        if (seesAll) return true
        val user = userId ?: return false
        return createdBy == user || user in partyUserIds || listingAgentId == user
    }

    fun canEditDeal(deal: Deal): Boolean = canCreate && canSeeDeal(deal)
}

/** Lo que una operacion necesita saber de quien la pide: la empresa (conexion), sus permisos y su nombre para el historial. */
class InmobiliariaSession(val tenant: Connection, val access: InmobiliariaAccess, val actor: CrmActor) {
    val scope: CrmScope get() = access.scope

    companion object {
        fun of(tenant: Connection, permissions: ModulePermissions, scope: CrmScope, actor: CrmActor) =
            InmobiliariaSession(tenant, InmobiliariaAccess(permissions, scope), actor)
    }
}
