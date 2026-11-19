package com.ideasdeveloper.idc.extensions.inmobiliaria

import com.ideasdeveloper.idc.modules.crm.CrmHost
import com.ideasdeveloper.idc.modules.crm.CrmLead
import com.ideasdeveloper.idc.modules.crm.CrmRepository
import com.ideasdeveloper.idc.modules.crm.CrmUser
import com.ideasdeveloper.idc.modules.crm.CrmWordPressException
import com.ideasdeveloper.idc.modules.crm.WpListing
import java.math.BigDecimal
import java.time.LocalDate
import java.util.UUID

/** Propiedad con lo que cuelga de ella: enlaces, historial de precios y las transacciones que el usuario ve. */
data class PropertyDetail(val property: Property, val links: List<PropertyLink>, val priceChanges: List<PriceChange>, val deals: List<Deal>)

/** Una transaccion tras una operacion, con avisos para el usuario (algo que no se pudo hacer sin deshacer el resto). */
data class DealResult(val deal: Deal, val warnings: List<String> = emptyList())

/** Propiedad tras importar un listado del sitio, con avisos de lo que no se pudo traer. */
data class ImportResult(val property: Property, val warnings: List<String> = emptyList())

/** Datos para abrir una transaccion. Lo que falte toma el valor por defecto (precio de la propiedad, comision de los ajustes, primera etapa abierta). */
data class DealDraft(
    val propertyId: UUID,
    val buyerLeadId: UUID? = null,
    val buyerCustomerId: UUID? = null,
    val stageId: UUID? = null,
    val agreedPrice: BigDecimal? = null,
    val commissionPct: BigDecimal? = null,
    val commissionAmount: BigDecimal? = null,
    val estimatedCloseOn: LocalDate? = null,
    val comments: String = "",
    val parties: List<PartyInput> = emptyList(),
    // Clave de la fuente cuando la transaccion viene de una importacion.
    val importKey: String? = null,
)

/**
 * Operaciones de negocio de la extension, las mismas para las pantallas y las herramientas MCP. Cada una revisa el
 * permiso con [InmobiliariaAccess], valida la entrada y lanza [InmobiliariaException] con un mensaje para el usuario.
 *
 * Corre en el hilo IO de las pantallas y de los manejadores MCP (llamadas bloqueantes sobre la conexion de la empresa).
 * Lo que no puede ser clave foranea (usuarios, clientes, leads) se comprueba aqui: que exista y que el usuario lo vea.
 */
object InmobiliariaService {
    /** Fuente de los items de lead que crea la extension (crm_lead_items.source). */
    const val Source = "inmobiliaria"
    const val MaxLinks = 20
    const val MaxParties = 20

    // ---- Comprobaciones ----

    private fun requireView(s: InmobiliariaSession) {
        if (!s.access.canView) throw InmobiliariaException("No tienes permiso para ver las propiedades y cierres.")
    }

    private fun requireCreate(s: InmobiliariaSession) {
        if (!s.access.canCreate) throw InmobiliariaException("No tienes permiso para crear ni cambiar propiedades y transacciones.")
    }

    /** Usuarios del CRM que pueden ser agentes. */
    fun agents(s: InmobiliariaSession): List<CrmUser> = CrmRepository.users(s.tenant)

    /** El agente por id o por nombre (sin distinguir mayusculas). */
    fun findAgent(s: InmobiliariaSession, wanted: String): CrmUser {
        val users = agents(s)
        return users.firstOrNull { it.id.toString().equals(wanted.trim(), ignoreCase = true) || it.name.equals(wanted.trim(), ignoreCase = true) }
            ?: throw InmobiliariaException("'$wanted' no es un usuario con acceso al CRM. Usuarios: ${users.joinToString { it.name }}.")
    }

    private fun requireAgent(s: InmobiliariaSession, id: UUID?) {
        if (id != null && agents(s).none { it.id == id }) throw InmobiliariaException("Ese usuario no puede ser agente: no tiene acceso al CRM.")
    }

    private fun requireCustomer(s: InmobiliariaSession, id: UUID?, label: String) {
        if (id != null && !CrmRepository.customerInScope(s.tenant, s.scope, id)) throw InmobiliariaException("$label no existe o no esta a tu alcance.")
    }

    private fun visibleLeadOrNull(s: InmobiliariaSession, id: UUID): CrmLead? =
        CrmHost.lead(s.tenant, id)?.takeIf { s.scope.canSee(it.interestId, it.ownerUserIds) }

    private fun visibleLead(s: InmobiliariaSession, id: UUID): CrmLead =
        visibleLeadOrNull(s, id)
            ?: throw InmobiliariaException("El lead no existe o no esta a tu alcance.")

    private fun requireProperty(s: InmobiliariaSession, id: UUID): Property =
        InmobiliariaStore.findProperty(s.tenant, id) ?: throw InmobiliariaException("La propiedad no existe.")

    private fun requireEditableProperty(s: InmobiliariaSession, id: UUID): Property {
        val property = requireProperty(s, id)
        if (!s.access.canEditProperty(property)) {
            throw InmobiliariaException("Solo el agente del listado, quien la capto o un gerente pueden cambiar esta propiedad.")
        }
        return property
    }

    private fun requireDeal(s: InmobiliariaSession, id: UUID): Deal {
        val deal = InmobiliariaStore.findDeal(s.tenant, id)
        // Una transaccion que el usuario no ve se responde igual que una que no existe.
        if (deal == null || !s.access.canSeeDeal(deal)) throw InmobiliariaException("La transaccion no existe o no esta a tu alcance.")
        return deal
    }

    // ---- Lecturas ----

    fun settings(s: InmobiliariaSession): Settings {
        requireView(s)
        return InmobiliariaStore.loadSettings(s.tenant)
    }

    fun searchProperties(s: InmobiliariaSession, filter: PropertyFilter, limit: Int = 20, offset: Int = 0): PropertyPage {
        requireView(s)
        return InmobiliariaStore.searchProperties(s.tenant, filter, limit, offset)
    }

    fun zones(s: InmobiliariaSession): List<String> {
        requireView(s)
        return InmobiliariaStore.zones(s.tenant)
    }

    fun getProperty(s: InmobiliariaSession, id: UUID): PropertyDetail {
        requireView(s)
        val property = requireProperty(s, id)
        return PropertyDetail(
            property,
            InmobiliariaStore.links(s.tenant, id),
            InmobiliariaStore.priceChanges(s.tenant, id),
            InmobiliariaStore.listDeals(s.tenant, s.access, DealFilter(propertyId = id), 50, 0).items,
        )
    }

    /** La propiedad por id (UUID) o por codigo (ERE-0123). */
    fun findPropertyByRef(s: InmobiliariaSession, ref: String): Property {
        requireView(s)
        val wanted = ref.trim()
        val byId = runCatching { UUID.fromString(wanted) }.getOrNull()?.let { InmobiliariaStore.findProperty(s.tenant, it) }
        return byId ?: InmobiliariaStore.findPropertyByCode(s.tenant, wanted) ?: throw InmobiliariaException("No hay una propiedad con el id o codigo '$wanted'.")
    }

    fun stages(s: InmobiliariaSession): List<DealStage> {
        requireView(s)
        return InmobiliariaStore.stages(s.tenant)
    }

    /** La etapa por id o nombre (sin distinguir mayusculas). */
    fun findStage(s: InmobiliariaSession, wanted: String): DealStage {
        val stages = stages(s)
        return stages.firstOrNull { it.id.toString().equals(wanted.trim(), ignoreCase = true) || it.name.equals(wanted.trim(), ignoreCase = true) }
            ?: throw InmobiliariaException("No existe la etapa '$wanted'. Etapas: ${stages.joinToString { it.name }}.")
    }

    fun listDeals(s: InmobiliariaSession, filter: DealFilter, limit: Int = 50, offset: Int = 0): DealPage {
        requireView(s)
        return InmobiliariaStore.listDeals(s.tenant, s.access, filter, limit, offset)
    }

    fun getDeal(s: InmobiliariaSession, id: UUID): Deal {
        requireView(s)
        return requireDeal(s, id)
    }

    /** Cierres estimados por mes de las transacciones abiertas que el usuario ve ([agentId]: solo las de ese agente). */
    fun closingsByMonth(s: InmobiliariaSession, agentId: UUID? = null): List<ClosingMonth> {
        requireView(s)
        return InmobiliariaStore.closingsByMonth(s.tenant, s.access, agentId)
    }

    // ---- Propiedades ----

    private fun checkedFields(s: InmobiliariaSession, fields: PropertyFields, currentOwnerCustomerId: UUID? = null): PropertyFields {
        InmobiliariaInput.checkFields(fields)
        requireAgent(s, fields.listingAgentId)
        // Solo se revisa si cambia: una propiedad no se bloquea porque su vendedor dejo de estar a la vista.
        if (fields.ownerCustomerId != currentOwnerCustomerId) requireCustomer(s, fields.ownerCustomerId, "El vendedor")
        return fields
    }

    /**
     * Capta una propiedad. Sin agente del listado queda el usuario que la capta; sin codigo se genera uno (P-0001).
     * Con precio queda el primer registro del historial de precios.
     */
    fun createProperty(
        s: InmobiliariaSession,
        fields: PropertyFields,
        price: BigDecimal? = null,
        status: PropertyStatus = PropertyStatus.Intake,
        importKey: String? = null,
    ): Property {
        requireCreate(s)
        val checked = checkedFields(s, fields.copy(listingAgentId = fields.listingAgentId ?: s.access.userId))
        val validPrice = price?.let { InmobiliariaInput.requireMoney(it, "El precio") }
        return InmobiliariaStore.inTransaction(s.tenant) {
            val code = checked.code?.takeIf { it.isNotEmpty() } ?: InmobiliariaStore.nextCode(s.tenant)
            val currency = checked.currency ?: InmobiliariaStore.loadSettings(s.tenant).currency
            InmobiliariaStore.createProperty(s.tenant, checked, code, currency, validPrice, status, s.access.userId, importKey).also {
                // Captada a mano con propietario: queda en su historial (las importaciones no: intakeEvents lo decide por importKey).
                InmobiliariaCustomerEvents.intake(s.tenant, it, s.actor.name)
            }
        }
    }

    /** Actualiza los campos descriptivos de una propiedad (no el precio ni el estado). */
    fun updateProperty(s: InmobiliariaSession, id: UUID, fields: PropertyFields): Property {
        requireCreate(s)
        val current = requireEditableProperty(s, id)
        val checked = checkedFields(s, fields, current.ownerCustomerId)
        InmobiliariaStore.inTransaction(s.tenant) {
            InmobiliariaStore.updateProperty(s.tenant, id, checked, checked.code?.takeIf { it.isNotEmpty() } ?: current.code, checked.currency ?: current.currency)
            // Propietario nuevo o cambiado a mano: entrada de captacion para el nuevo.
            if (checked.ownerCustomerId != null && checked.ownerCustomerId != current.ownerCustomerId) {
                InmobiliariaStore.findProperty(s.tenant, id)?.let { InmobiliariaCustomerEvents.intake(s.tenant, it, s.actor.name) }
            }
        }
        return requireProperty(s, id)
    }

    /** Cambia el precio; el anterior queda en el historial y la propiedad pasa a verse Rebajada si el nuevo es menor. */
    fun changePrice(s: InmobiliariaSession, id: UUID, price: BigDecimal, note: String = ""): Property {
        requireCreate(s)
        requireEditableProperty(s, id)
        val valid = InmobiliariaInput.requireMoney(price, "El precio")
        if (note.length > 500) throw InmobiliariaException("La nota del cambio de precio admite hasta 500 caracteres.")
        InmobiliariaStore.changePrice(s.tenant, id, valid, s.access.userId, note.trim())
        return requireProperty(s, id)
    }

    /** Cambia el estado de la propiedad; Vendida guarda la fecha de venta ([soldOn] o hoy). */
    fun changeStatus(s: InmobiliariaSession, id: UUID, status: PropertyStatus, soldOn: LocalDate? = null): Property {
        requireCreate(s)
        InmobiliariaStore.inTransaction(s.tenant) {
            // Se bloquea la fila antes de decidir: dos cambios a la vez no deben pisarse.
            InmobiliariaStore.lockProperty(s.tenant, id)
            requireEditableProperty(s, id)
            // Vendida o Retirada con una transaccion abierta dejaria la venta en el aire.
            if ((status == PropertyStatus.Sold || status == PropertyStatus.Withdrawn) && InmobiliariaStore.openDealCount(s.tenant, id, null) > 0) {
                throw InmobiliariaException("Cierra o cancela primero sus transacciones abiertas.")
            }
            InmobiliariaStore.changeStatus(s.tenant, id, status, soldOn)
        }
        return requireProperty(s, id)
    }

    /** "Publicar": una captacion pasa a En venta. */
    fun publish(s: InmobiliariaSession, id: UUID): Property {
        requireCreate(s)
        val property = requireEditableProperty(s, id)
        if (property.status != PropertyStatus.Intake) throw InmobiliariaException("Solo se publica una propiedad en captacion.")
        InmobiliariaStore.changeStatus(s.tenant, id, PropertyStatus.ForSale)
        return requireProperty(s, id)
    }

    /** Reemplaza los enlaces por portal de la propiedad; cada uno lleva etiqueta (hasta 60) y una URL http/https valida. */
    fun replaceLinks(s: InmobiliariaSession, id: UUID, links: List<LinkInput>): List<PropertyLink> {
        requireCreate(s)
        requireEditableProperty(s, id)
        if (links.size > MaxLinks) throw InmobiliariaException("Una propiedad admite hasta $MaxLinks enlaces.")
        val clean = links.map { link ->
            val label = link.label.trim()
            if (label.isEmpty() || label.length > 60) throw InmobiliariaException("El nombre del enlace debe tener entre 1 y 60 caracteres.")
            LinkInput(label, InmobiliariaInput.requireUrl(link.url, "El enlace '$label'"))
        }
        InmobiliariaStore.replaceLinks(s.tenant, id, clean)
        return InmobiliariaStore.links(s.tenant, id)
    }

    // ---- Lead ----

    /** Vincula la propiedad a un lead visible como un interes (item con source inmobiliaria) y lo anota en su historial. */
    fun linkToLead(s: InmobiliariaSession, propertyId: UUID, leadId: UUID): Boolean {
        requireCreate(s)
        val property = requireProperty(s, propertyId)
        val lead = visibleLead(s, leadId)
        return InmobiliariaStore.inTransaction(s.tenant) {
            val created = linkItem(s, lead.id, property, "interested")
            CrmHost.addActivity(s.tenant, lead.id, "item", "Propiedad vinculada: ${property.code} ${property.title}.", s.actor)
            created
        }
    }

    private fun itemTitle(property: Property) = "${property.code} - ${property.title}"

    private fun linkItem(s: InmobiliariaSession, leadId: UUID, property: Property, status: String, price: BigDecimal? = property.price): Boolean {
        val firstUrl = InmobiliariaStore.links(s.tenant, property.id).firstOrNull()?.url.orEmpty()
        val subtitle = listOf(property.kind.label, property.zone).filter { it.isNotEmpty() }.joinToString(" - ")
        return CrmHost.linkItem(s.tenant, leadId, Source, property.id.toString(), itemTitle(property), subtitle, firstUrl, price, status)
    }

    // ---- Transacciones ----

    /**
     * Revisa las partes. [current] son las partes que la transaccion ya tiene: un cliente que ya estaba en ellas no se vuelve a
     * comprobar (puede haber dejado de estar a la vista), y una parte que llega sin cliente con [PartyInput.keepPartyId] de una parte
     * actual conserva el cliente de esa parte (el id de cliente nunca se toma del formulario).
     */
    private fun checkedParties(s: InmobiliariaSession, parties: List<PartyInput>, current: List<DealParty> = emptyList()): List<PartyInput> {
        if (parties.size > MaxParties) throw InmobiliariaException("Una transaccion admite hasta $MaxParties partes.")
        val users = agents(s).map { it.id }.toSet()
        val knownCustomers = current.mapNotNull { it.customerId }.toSet()
        return parties.map { raw ->
            val kept = raw.keepPartyId?.let { keep -> current.firstOrNull { it.id == keep } }
            val party = if (raw.customerId == null && kept?.customerId != null) raw.copy(customerId = kept.customerId) else raw
            val name = party.name.trim()
            if (name.length > 200) throw InmobiliariaException("El nombre de una parte admite hasta 200 caracteres.")
            if (party.notes.length > 500) throw InmobiliariaException("Las notas de una parte admiten hasta 500 caracteres.")
            if (party.userId == null && party.customerId == null && name.isEmpty()) {
                throw InmobiliariaException("Cada parte necesita un agente, un cliente o un nombre (${party.role.label}).")
            }
            if (party.userId != null && party.userId !in users) throw InmobiliariaException("Una de las partes es un usuario sin acceso al CRM.")
            if (party.customerId !in knownCustomers) requireCustomer(s, party.customerId, "El cliente de una parte")
            party.copy(keepPartyId = null, name = name, sharePct = InmobiliariaInput.requirePercent(party.sharePct, "El porcentaje de una parte"), notes = party.notes.trim())
        }
    }

    private fun checkedEdit(s: InmobiliariaSession, edit: DealEdit, current: DealEdit?): DealEdit {
        edit.agreedPrice?.let { InmobiliariaInput.requireMoney(it, "El precio acordado") }
        edit.commissionAmount?.let { InmobiliariaInput.requireMoney(it, "La comision") }
        InmobiliariaInput.requirePercent(edit.commissionPct, "El porcentaje de comision")
        if (edit.comments.length > 5000) throw InmobiliariaException("Los comentarios admiten hasta 5000 caracteres.")
        if (!InmobiliariaInput.isCurrency(edit.currency)) throw InmobiliariaException("La moneda es un codigo de 3 letras mayusculas (USD).")
        // Solo se revisa lo que cambia: una transaccion ya abierta no debe bloquearse porque un lead dejo de estar a la vista.
        val leadId = edit.buyerLeadId
        if (leadId != null && leadId != current?.buyerLeadId) visibleLead(s, leadId)
        if (edit.buyerCustomerId != current?.buyerCustomerId) requireCustomer(s, edit.buyerCustomerId, "El comprador")
        return edit.copy(
            agreedPrice = edit.agreedPrice?.setScale(2),
            commissionAmount = edit.commissionAmount?.setScale(2),
            commissionPct = edit.commissionPct.setScale(3),
            comments = edit.comments.trim(),
        )
    }

    /** Efectos de entrar a una etapa que hay que completar fuera de la transaccion de base de datos. */
    private class StageEffects(val warnings: MutableList<String> = mutableListOf(), var sale: WordPressSale? = null, var propertyDenied: Boolean = false)

    private class WordPressSale(val listingId: Long, val price: BigDecimal?, val date: LocalDate)

    /**
     * Abre una transaccion de una propiedad. Si hay lead comprador su item pasa a Elegida; si la propiedad estaba En venta
     * pasa a En negociacion. Crea las etapas de la plantilla si la empresa aun no tiene ninguna.
     *
     * Con [applyPropertyEffects] en falso (importaciones) la transaccion se crea sin tocar el estado de la propiedad ni exigir que no
     * este vendida o retirada; con [external] en falso no se llama a WordPress.
     */
    fun createDeal(s: InmobiliariaSession, draft: DealDraft, applyPropertyEffects: Boolean = true, external: Boolean = true): DealResult {
        requireCreate(s)
        val effects = StageEffects()
        val deal = InmobiliariaStore.inTransaction(s.tenant) {
            InmobiliariaStore.lockProperty(s.tenant, draft.propertyId)
            val property = requireProperty(s, draft.propertyId)
            if (applyPropertyEffects && (property.status == PropertyStatus.Sold || property.status == PropertyStatus.Withdrawn)) {
                throw InmobiliariaException("No se puede abrir una transaccion de una propiedad ${property.status.label.lowercase()}.")
            }
            val stages = InmobiliariaStore.stages(s.tenant).ifEmpty {
                InmobiliariaStore.seedTemplate(s.tenant)
                InmobiliariaStore.stages(s.tenant)
            }
            val stage = if (draft.stageId != null) {
                stages.firstOrNull { it.id == draft.stageId } ?: throw InmobiliariaException("La etapa ya no existe.")
            } else {
                stages.firstOrNull { it.outcome == DealOutcome.Open } ?: throw InmobiliariaException("No hay etapas abiertas: crea una en las etapas de cierres.")
            }
            val settings = InmobiliariaStore.loadSettings(s.tenant)
            val customer = draft.buyerCustomerId ?: draft.buyerLeadId?.let { visibleLead(s, it).customerId }
            val edit = checkedEdit(
                s,
                DealEdit(
                    buyerLeadId = draft.buyerLeadId,
                    buyerCustomerId = customer,
                    agreedPrice = draft.agreedPrice ?: property.price,
                    commissionPct = draft.commissionPct ?: settings.defaultCommissionPct,
                    commissionAmount = draft.commissionAmount,
                    currency = property.currency,
                    estimatedCloseOn = draft.estimatedCloseOn,
                    closedOn = null,
                    comments = draft.comments,
                ),
                null,
            )
            val parties = checkedParties(s, draft.parties)
            val id = InmobiliariaStore.createDeal(s.tenant, property.id, stage.id, edit, s.access.userId, draft.importKey)
            InmobiliariaStore.replaceParties(s.tenant, id, parties)
            val created = requireNotNull(InmobiliariaStore.findDeal(s.tenant, id)) { "La transaccion recien creada no existe" }
            enterStage(s, created, null, stage, null, effects, applyPropertyEffects, external)
            requireNotNull(InmobiliariaStore.findDeal(s.tenant, id))
        }
        finishWordPress(s, effects)
        return DealResult(deal, effects.warnings)
    }

    /**
     * Cambia los datos de una transaccion; [parties] no nulo reemplaza sus partes. La etapa y la fecha real de cierre se cambian con
     * [moveDeal] (el [DealEdit.closedOn] recibido se ignora). Si la transaccion esta ganada y cambia el precio acordado, el item del lead
     * comprador (si el usuario lo ve) toma el nuevo precio.
     */
    fun updateDeal(s: InmobiliariaSession, id: UUID, edit: DealEdit, parties: List<PartyInput>? = null): Deal {
        requireCreate(s)
        return InmobiliariaStore.inTransaction(s.tenant) {
            InmobiliariaStore.lockDealAndProperty(s.tenant, id)
            val current = requireDeal(s, id)
            if (!s.access.canEditDeal(current)) throw InmobiliariaException("No puedes cambiar esta transaccion.")
            val checked = checkedEdit(s, edit.copy(closedOn = current.closedOn), DealEdit.of(current))
            InmobiliariaStore.updateDeal(s.tenant, id, checked)
            if (parties != null) InmobiliariaStore.replaceParties(s.tenant, id, checkedParties(s, parties, current.parties))
            if (current.stageOutcome == DealOutcome.Won && checked.agreedPrice != current.agreedPrice) syncSoldItemPrice(s, current, checked.agreedPrice)
            requireNotNull(InmobiliariaStore.findDeal(s.tenant, id))
        }
    }

    // Deja en el item del lead comprador (si el usuario lo ve) el precio de la venta cerrada.
    private fun syncSoldItemPrice(s: InmobiliariaSession, deal: Deal, price: BigDecimal?) {
        val leadId = deal.buyerLeadId ?: return
        if (price == null || visibleLeadOrNull(s, leadId) == null) return
        val existing = CrmHost.items(s.tenant, leadId, Source).firstOrNull { it.externalId == deal.propertyId.toString() } ?: return
        CrmHost.setItemStatus(s.tenant, existing, existing.status, price)
    }

    /**
     * Mueve la transaccion de etapa y aplica sus efectos:
     * - Ganada: fecha de cierre (la dada, la que ya tenia u hoy), propiedad Vendida con su fecha, item del lead Vendida, actividad en el
     *   lead y, si WordPress esta activo y la propiedad tiene listado del sitio, se marca vendida alla (si eso falla, queda un aviso y el cierre se mantiene).
     * - Perdida: la propiedad vuelve a En venta si estaba En negociacion y no tiene otra transaccion abierta.
     * - Abierta: la propiedad En venta pasa a En negociacion.
     * Salir de una etapa ganada deshace la venta (propiedad, fecha de cierre e item). Una propiedad solo admite una transaccion ganada.
     * El estado de la propiedad sale de [InmobiliariaRules.propertyStatusAfter].
     * Con [applyPropertyEffects] en falso (importaciones) no se toca la propiedad; con [external] en falso no se llama a WordPress.
     */
    fun moveDeal(
        s: InmobiliariaSession,
        dealId: UUID,
        stageId: UUID,
        closedOn: LocalDate? = null,
        applyPropertyEffects: Boolean = true,
        external: Boolean = true,
    ): DealResult {
        requireCreate(s)
        val effects = StageEffects()
        val deal = InmobiliariaStore.inTransaction(s.tenant) {
            // Se bloquea la transaccion y su propiedad antes de decidir la etapa y el estado.
            InmobiliariaStore.lockDealAndProperty(s.tenant, dealId)
            val current = requireDeal(s, dealId)
            if (!s.access.canEditDeal(current)) throw InmobiliariaException("No puedes mover esta transaccion.")
            val stage = InmobiliariaStore.stages(s.tenant).firstOrNull { it.id == stageId } ?: throw InmobiliariaException("La etapa ya no existe.")
            if (current.stageId != stage.id) {
                InmobiliariaStore.moveDeal(s.tenant, dealId, stage.id)
                enterStage(s, current, current.stageOutcome, stage, closedOn, effects, applyPropertyEffects, external)
            } else if (stage.outcome == DealOutcome.Won && closedOn != null) {
                InmobiliariaStore.setClosedOn(s.tenant, dealId, closedOn)
                // La fecha de venta de la propiedad sigue a la fecha de cierre de su venta.
                val property = InmobiliariaStore.findProperty(s.tenant, current.propertyId)
                if (applyPropertyEffects && property != null && property.status == PropertyStatus.Sold && property.soldOn != closedOn) {
                    if (s.access.canEditProperty(property)) InmobiliariaStore.changeStatus(s.tenant, property.id, PropertyStatus.Sold, closedOn)
                    else effects.warnings += PropertyNotUpdatedWarning
                }
            }
            requireNotNull(InmobiliariaStore.findDeal(s.tenant, dealId))
        }
        finishWordPress(s, effects)
        return DealResult(deal, effects.warnings)
    }

    // Efectos de entrar a [stage] (previous = resultado de la etapa de la que sale; nulo al abrir la transaccion).
    // El estado de la PROPIEDAD y WordPress solo cambian si el usuario puede editarla (agente del listado, quien la capto o gerente);
    // si no, se mueve solo la transaccion y queda un aviso. El item del lead comprador sigue la regla de visibilidad del lead.
    private fun enterStage(
        s: InmobiliariaSession,
        deal: Deal,
        previous: DealOutcome?,
        stage: DealStage,
        requestedClose: LocalDate?,
        effects: StageEffects,
        applyPropertyEffects: Boolean = true,
        external: Boolean = true,
    ) {
        val tenant = s.tenant
        val property = InmobiliariaStore.findProperty(tenant, deal.propertyId) ?: return
        val canEditProperty = applyPropertyEffects && s.access.canEditProperty(property)
        val lead = deal.buyerLeadId?.let { visibleLeadOrNull(s, it) }
        if (deal.buyerLeadId != null && lead == null) effects.warnings += "No se actualizo el lead comprador: no esta a tu alcance."
        // Aplica un cambio de la propiedad solo con permiso; sin permiso lo recuerda para avisar una vez.
        val onProperty: (() -> Unit) -> Unit = { change -> if (canEditProperty) change() else if (applyPropertyEffects) effects.propertyDenied = true }
        val otherWon = InmobiliariaStore.wonDealCount(tenant, property.id, deal.id)
        if (stage.outcome == DealOutcome.Won && otherWon > 0) throw InmobiliariaException("Esa propiedad ya tiene una transaccion cerrada.")
        val otherOpen = InmobiliariaStore.openDealCount(tenant, property.id, deal.id)
        val newStatus = InmobiliariaRules.propertyStatusAfter(previous, stage.outcome, property.status, otherOpen, otherWon)
        when (stage.outcome) {
            DealOutcome.Won -> {
                val closed = requestedClose ?: deal.closedOn ?: LocalDate.now()
                InmobiliariaStore.setClosedOn(tenant, deal.id, closed)
                if (newStatus != null || property.soldOn != closed) onProperty { InmobiliariaStore.changeStatus(tenant, property.id, PropertyStatus.Sold, closed) }
                if (lead != null) {
                    setLeadItem(s, lead, property, "sold", deal.agreedPrice, "Venta cerrada: ${itemTitle(property)}.")
                    addLeadActivity(s, lead, "Transaccion cerrada en la etapa ${stage.name}.")
                }
                if (canEditProperty && external) property.wordpressListingId?.let { effects.sale = WordPressSale(it, deal.agreedPrice, closed) }
                // Historial del comprador y del vendedor; las importaciones tambien, con la fecha real de cierre.
                InmobiliariaCustomerEvents.sold(tenant, deal, property, closed, s.actor.name)
            }
            DealOutcome.Lost, DealOutcome.Open -> {
                undoSale(s, deal, property, previous, effects)
                newStatus?.let { status -> onProperty { InmobiliariaStore.changeStatus(tenant, property.id, status) } }
                if (lead != null) {
                    if (stage.outcome == DealOutcome.Lost) setLeadItem(s, lead, property, "discarded", null, "Transaccion cancelada: ${itemTitle(property)}.", onlyIfLinked = true)
                    else setLeadItem(s, lead, property, "chosen", deal.agreedPrice, "Transaccion abierta (${stage.name}): ${itemTitle(property)}.")
                }
            }
        }
        if (effects.propertyDenied) effects.warnings += PropertyNotUpdatedWarning
    }

    private const val PropertyNotUpdatedWarning = "La propiedad no se actualizo: solo su agente del listado o un gerente pueden cambiar su estado."

    // Salir de una etapa ganada: la transaccion pierde su fecha de cierre (el estado de la propiedad lo decide propertyStatusAfter).
    private fun undoSale(s: InmobiliariaSession, deal: Deal, property: Property, previous: DealOutcome?, effects: StageEffects) {
        if (previous != DealOutcome.Won) return
        InmobiliariaStore.setClosedOn(s.tenant, deal.id, null)
        if (property.wordpressListingId != null) effects.warnings += "La propiedad sigue marcada vendida en el sitio WordPress; cambiala alli si hace falta."
    }

    // Anota en el historial del lead, salvo que este archivado.
    private fun addLeadActivity(s: InmobiliariaSession, lead: CrmLead, text: String) {
        if (lead.archivedAt == null) CrmHost.addActivity(s.tenant, lead.id, "item", text, s.actor)
    }

    // Deja el item del lead de la propiedad en [status] (lo crea si falta, salvo [onlyIfLinked]) y lo anota en el historial del lead.
    private fun setLeadItem(s: InmobiliariaSession, lead: CrmLead, property: Property, status: String, price: BigDecimal?, activity: String, onlyIfLinked: Boolean = false) {
        val existing = CrmHost.items(s.tenant, lead.id, Source).firstOrNull { it.externalId == property.id.toString() }
        when {
            existing == null && onlyIfLinked -> return
            existing == null -> linkItem(s, lead.id, property, status, price)
            existing.status != status -> CrmHost.setItemStatus(s.tenant, existing, status, price ?: existing.price)
        }
        addLeadActivity(s, lead, activity)
    }

    // Marca vendido el listado del sitio una vez confirmada la transaccion de base de datos: si falla, el cierre ya esta hecho.
    private fun finishWordPress(s: InmobiliariaSession, effects: StageEffects) {
        val sale = effects.sale ?: return
        if (!CrmHost.wordpressActive(s.tenant)) return
        try {
            CrmHost.wordpressMarkSold(s.tenant, sale.listingId, sale.price, sale.date)
        } catch (failure: CrmWordPressException) {
            effects.warnings += "El cierre quedo guardado, pero no se pudo marcar vendida en el sitio WordPress."
        } catch (failure: Exception) {
            effects.warnings += "El cierre quedo guardado, pero fallo la comunicacion con el sitio web."
        }
    }

    // ---- Etapas ----

    /** Guarda las etapas de las transacciones (permiso de configuracion). Al menos una abierta y una ganada. */
    fun saveStages(s: InmobiliariaSession, inputs: List<StageInput>) {
        if (!s.access.canEditStages) throw InmobiliariaException("No tienes permiso para editar las etapas.")
        val clean = inputs.map { it.copy(name = it.name.trim()) }
        if (clean.any { it.name.isEmpty() || it.name.length > 60 }) throw InmobiliariaException("El nombre de cada etapa debe tener entre 1 y 60 caracteres.")
        if (clean.size > 30) throw InmobiliariaException("Hay demasiadas etapas (maximo 30).")
        if (clean.map { it.name.lowercase() }.toSet().size != clean.size) throw InmobiliariaException("Los nombres de las etapas no pueden repetirse.")
        if (clean.none { it.outcome == DealOutcome.Open }) throw InmobiliariaException("Debe haber al menos una etapa abierta.")
        if (clean.none { it.outcome == DealOutcome.Won }) throw InmobiliariaException("Debe haber al menos una etapa ganada.")
        InmobiliariaStore.saveStages(s.tenant, clean)
    }

    /** Ajustes: moneda y comision por defecto (permiso de configuracion). */
    fun saveSettings(s: InmobiliariaSession, settings: Settings) {
        if (!s.access.canEditStages) throw InmobiliariaException("No tienes permiso para cambiar los ajustes.")
        if (!InmobiliariaInput.isCurrency(settings.currency)) throw InmobiliariaException("La moneda es un codigo de 3 letras mayusculas (USD).")
        InmobiliariaStore.saveSettings(s.tenant, settings.copy(defaultCommissionPct = InmobiliariaInput.requirePercent(settings.defaultCommissionPct, "La comision por defecto")))
    }

    // ---- WordPress ----

    // Palabras completas (en minusculas) de los tipos del sitio que corresponden a cada tipo de propiedad; el orden decide los empates.
    private val wordPressKinds = listOf(
        setOf("casa", "casas", "house", "houses", "home", "homes") to PropertyKind.House,
        setOf("development") to PropertyKind.DevelopmentLot,
        setOf("terreno", "terrenos", "lote", "lotes", "land", "lands", "lot", "lots") to PropertyKind.Land,
        setOf("condo", "condos", "condominio", "condominios", "condominium", "apartment", "apartamento", "departamento", "depto") to PropertyKind.Condo,
        setOf("comercial", "commercial", "local", "oficina", "office") to PropertyKind.Commercial,
    )

    private val nonLetters = Regex("[^\\p{L}]+")

    /** Tipo de propiedad que corresponde a los tipos del sitio WordPress, por palabra completa (Otro si ninguno coincide). */
    fun kindFromWordPress(types: List<String>): PropertyKind {
        val perType = types.map { type -> type.lowercase().split(nonLetters).filter { it.isNotEmpty() } }
        val words = perType.flatten().toSet()
        // "lote en desarrollo" es un lote dentro de un desarrollo, no un terreno suelto.
        val lotInDevelopment = perType.any { list -> list.windowed(2).any { (first, second) -> first in setOf("lote", "lotes") && second == "en" } }
        if (lotInDevelopment && words.none { it in wordPressKinds.first().first }) return PropertyKind.DevelopmentLot
        return wordPressKinds.firstOrNull { (keywords, _) -> words.any { it in keywords } }?.second ?: PropertyKind.Other
    }

    /**
     * Trae los datos de un listado de WordPress (solo si esa extension esta activa). Sin [propertyId] capta una propiedad nueva;
     * con [propertyId] completa solo lo que la propiedad tiene vacio y vincula el listado. Un listado ya importado no se duplica.
     * Todo se guarda en una sola transaccion; un precio del sitio que no cabe en el formato se omite con un aviso.
     */
    fun importFromWordPress(s: InmobiliariaSession, listingId: Long, propertyId: UUID? = null): ImportResult {
        requireCreate(s)
        CrmHost.wordpressInactiveReason(s.tenant)?.let { throw InmobiliariaException(it) }
        if (!CrmHost.wordpressActive(s.tenant)) throw InmobiliariaException("WordPress no esta configurado: conectalo antes en Integraciones.")
        InmobiliariaStore.findPropertyByWordPressListing(s.tenant, listingId)?.let { existing ->
            if (existing.id != propertyId) throw InmobiliariaException("Ese listado ya esta importado como ${existing.code}.")
        }
        val listing = try {
            CrmHost.wordpressListing(s.tenant, listingId)
        } catch (failure: CrmWordPressException) {
            throw InmobiliariaException(failure.message.orEmpty())
        }
        val warnings = mutableListOf<String>()
        val property = InmobiliariaStore.inTransaction(s.tenant) {
            if (propertyId == null) createFromListing(s, listing, warnings) else completeFromListing(s, requireEditableProperty(s, propertyId), listing, warnings)
        }
        return ImportResult(property, warnings)
    }

    // Lo que el sitio manda como texto, solo si cabe en los campos de la propiedad (si no, se deja vacio).
    internal fun listingArea(text: String): BigDecimal? =
        runCatching { InmobiliariaInput.parseArea(text.filter { it.isDigit() || it == '.' || it == ',' }) }.getOrNull()

    private fun listingBathrooms(text: String): BigDecimal? = runCatching { InmobiliariaInput.parseBathrooms(text) }.getOrNull()

    // El precio del sitio con las mismas reglas que un precio escrito a mano; si no vale, se importa sin precio y se avisa.
    private fun listingPrice(listing: WpListing, warnings: MutableList<String>): BigDecimal? {
        val price = listing.priceValue ?: return null
        return runCatching { InmobiliariaInput.requireMoney(price) }.getOrNull()
            ?: run { warnings += "El precio del sitio web no tiene un formato valido y no se importo."; null }
    }

    private fun createFromListing(s: InmobiliariaSession, listing: WpListing, warnings: MutableList<String>): Property {
        val fields = PropertyFields(
            code = null,
            title = listing.title.take(300),
            kind = kindFromWordPress(listing.propertyTypes),
            zone = listing.city.take(120),
            builtAreaM2 = listingArea(listing.sqft),
            bedrooms = listing.beds.toIntOrNull()?.takeIf { it in 0..100 },
            bathrooms = listingBathrooms(listing.baths),
            wordpressListingId = listing.id,
        )
        val property = createProperty(s, fields, listingPrice(listing, warnings))
        addListingLink(s, property, listing)
        return property
    }

    private fun completeFromListing(s: InmobiliariaSession, property: Property, listing: WpListing, warnings: MutableList<String>): Property {
        val fields = PropertyFields(
            code = property.code,
            title = property.title,
            kind = property.kind,
            zone = property.zone.ifEmpty { listing.city.take(120) },
            development = property.development,
            listingAgentId = property.listingAgentId,
            ownerCustomerId = property.ownerCustomerId,
            lotAreaM2 = property.lotAreaM2,
            builtAreaM2 = property.builtAreaM2 ?: listingArea(listing.sqft),
            bedrooms = property.bedrooms ?: listing.beds.toIntOrNull()?.takeIf { it in 0..100 },
            bathrooms = property.bathrooms ?: listingBathrooms(listing.baths),
            currency = property.currency,
            description = property.description,
            notes = property.notes,
            wordpressListingId = listing.id,
            publishedOn = property.publishedOn,
        )
        updateProperty(s, property.id, fields)
        if (property.price == null) listingPrice(listing, warnings)?.let { changePrice(s, property.id, it, "Precio traido del sitio web") }
        addListingLink(s, property, listing)
        return requireProperty(s, property.id)
    }

    // Agrega el enlace del listado del sitio web si es valido y la propiedad aun no lo tiene.
    private fun addListingLink(s: InmobiliariaSession, property: Property, listing: WpListing) {
        if (!InmobiliariaInput.isValidUrl(listing.url)) return
        val current = InmobiliariaStore.links(s.tenant, property.id)
        if (current.any { it.url == listing.url } || current.size >= MaxLinks) return
        replaceLinks(s, property.id, current.map { LinkInput(it.label, it.url) } + LinkInput("Sitio web", listing.url))
    }
}
