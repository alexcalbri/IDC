package com.ideasdeveloper.idc.modules.crm

import com.ideasdeveloper.idc.server.extensions.ExtensionStatus
import java.math.BigDecimal
import java.sql.Connection
import java.sql.PreparedStatement
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/**
 * Superficie pública y estable del CRM para sus extensiones; cambia con cuidado (las extensiones se compilan contra ella).
 *
 * Solo delega en los objetos internos del CRM: no tiene lógica propia. Los secretos nunca salen crudos: los ajustes
 * de Cloudbeds ya llegan con la API key abierta, así que la extensión no toca CrmSecrets.
 */
object CrmHost {
    // ---- Leads y actividad ----

    fun lead(tenant: Connection, leadId: UUID): CrmLead? = CrmRepository.lead(tenant, leadId)

    fun addActivity(tenant: Connection, leadId: UUID, kind: String, text: String, actor: CrmActor) =
        CrmRepository.addActivity(tenant, leadId, kind, text, actor)

    /** Archiva el lead si sigue abierto; devuelve las filas cambiadas (0 si ya estaba archivado). */
    fun archiveLeadIfOpen(tenant: Connection, leadId: UUID): Int =
        tenant.prepareStatement("UPDATE crm_leads SET archived_at = CURRENT_TIMESTAMP WHERE id = ? AND archived_at IS NULL").use { it.setObject(1, leadId); it.executeUpdate() }

    // ---- Items del lead (habitaciones de Cloudbeds) ----

    /** Items del lead de una fuente (null = todas). */
    fun items(tenant: Connection, leadId: UUID, source: String? = null): List<CrmItem> = CrmItems.list(tenant, leadId, source)

    /** Habitaciones de Cloudbeds elegidas (source=cloudbeds, status=chosen) en el lead. */
    fun chosenRooms(tenant: Connection, leadId: UUID): List<CrmItem> =
        CrmItems.list(tenant, leadId, CrmCloudbedsStore.Source).filter { it.status == "chosen" }

    fun findItem(tenant: Connection, leadId: UUID, itemId: UUID): CrmItem? = CrmItems.find(tenant, leadId, itemId)

    /** Enlaza (o refresca) un item del lead; true si era nuevo. */
    fun linkItem(
        tenant: Connection,
        leadId: UUID,
        source: String,
        externalId: String,
        title: String,
        subtitle: String = "",
        url: String = "",
        price: BigDecimal? = null,
        status: String = "interested",
        startsOn: LocalDate? = null,
        endsOn: LocalDate? = null,
        data: Map<String, String> = emptyMap(),
    ): Boolean = CrmItems.link(tenant, leadId, source, externalId, title, subtitle, url, price, status, startsOn, endsOn, data)

    fun setItemStatus(tenant: Connection, item: CrmItem, status: String, price: BigDecimal? = item.price) = CrmItems.setStatus(tenant, item, status, price)

    fun setItemData(tenant: Connection, item: CrmItem, data: Map<String, String>) = CrmItems.setData(tenant, item, data)

    fun removeItem(tenant: Connection, item: CrmItem): Boolean = CrmItems.remove(tenant, item)

    // ---- Plantillas, correo, notificaciones e inbox ----

    fun templates(tenant: Connection): List<CrmTemplate> = CrmMail.templates(tenant)

    fun renderFor(tenant: Connection, template: CrmTemplate, lead: CrmLead, vars: Map<String, String> = emptyMap()): RenderedMail =
        CrmTemplateMailer.renderFor(tenant, template, lead, vars)

    fun enqueueMail(tenant: Connection, kind: String, to: String, email: CrmEmail, leadId: UUID? = null) =
        CrmNotify.enqueue(tenant, kind, to, email, leadId)

    fun inboxAdd(
        tenant: Connection,
        leadId: UUID,
        direction: String,
        channel: String,
        subject: String,
        body: String,
        externalId: String?,
        status: String,
        error: String,
        userId: UUID?,
        author: String,
        at: Instant? = null,
    ) = CrmInbox.addMessage(tenant, leadId, direction, channel, subject, body, externalId, status, error, userId, author, at)

    /** True si el contacto del canal aun no tiene nombre (solo el de relleno): vale la pena pedirlo al canal. */
    fun inboxNeedsName(tenant: Connection, channel: String, identity: String): Boolean = CrmInbox.needsName(tenant, channel, identity)

    /** Pone el nombre al contacto del canal si aun no tiene uno; true si lo cambio. */
    fun inboxFillName(tenant: Connection, channel: String, identity: String, name: String): Boolean = CrmInbox.fillName(tenant, channel, identity, name)

    /** Identidades del canal cuyo contacto sigue sin nombre (hasta [limit]). */
    fun inboxUnnamedIdentities(tenant: Connection, channel: String, limit: Int): List<String> = CrmInbox.unnamedIdentities(tenant, channel, limit)

    /** Nombre visible de un canal ("whatsapp" -> "WhatsApp"). */
    fun inboxLabel(channel: String): String = CrmInbox.label(channel)

    /** Entrada única de un contacto de un canal externo (crea o actualiza el lead); [onNewLead] corre si el lead es nuevo. */
    fun receive(tenant: Connection, inbound: CrmInbound, onNewLead: (UUID) -> Unit = {}): CrmIntakeResult = CrmInbox.receive(tenant, inbound, onNewLead)

    /** El lead abierto más reciente del cliente con ese correo, o null. */
    fun openLeadIdByEmail(tenant: Connection, email: String): UUID? {
        if (email.isBlank()) return null
        return tenant.prepareStatement(
            """
            SELECT l.id FROM crm_leads l JOIN customers c ON c.id = l.customer_id
            WHERE lower(c.primary_email) = lower(?) AND l.closed_at IS NULL ORDER BY l.updated_at DESC LIMIT 1
            """.trimIndent()
        ).use { query ->
            query.setString(1, email)
            query.executeQuery().use { rows -> if (rows.next()) rows.getObject(1, UUID::class.java) else null }
        }
    }

    /** Avisa a los responsables de un lead nuevo. */
    fun notifyNewLead(tenant: Connection, leadId: UUID) = CrmNotify.newLead(tenant, leadId)

    // ---- Salida y archivado ----

    const val ExitActivityText = CrmExit.ActivityText

    fun loadExit(tenant: Connection): CrmExitAutomation = CrmExit.load(tenant)

    fun archivesOnCheckout(config: CrmExitAutomation, archiveOnCheckout: Boolean, stageId: UUID, archived: Boolean, restored: Boolean = false): Boolean =
        CrmExit.archivesOnCheckout(config, archiveOnCheckout, stageId, archived, restored)

    /** Mueve el lead a la primera etapa de ganado si sigue abierto (no hace nada si ya está archivado o no hay etapa). */
    fun winLead(tenant: Connection, lead: CrmLead, stages: List<CrmStage>, actor: CrmActor) {
        stages.firstOrNull { it.kind == StageKind.Won }?.takeIf { lead.archivedAt == null }?.let { won -> CrmRepository.moveLead(tenant, lead, won, stages, actor) }
    }

    // ---- Contactos ----

    fun isEmail(value: String): Boolean = CrmContacts.isEmail(value)

    fun splitName(name: String): Pair<String, String> = CrmContacts.splitName(name)

    fun normalizePhone(raw: String): String = CrmContacts.normalizePhone(raw)

    // ---- Secretos ----

    /** True si el secreto guardado no se pudo abrir (llave cambiada): no debe enviarse. */
    fun isSecretUnreadable(value: String?): Boolean = CrmSecrets.isUnreadable(value)

    /** True si el valor de un formulario significa "no cambiar el secreto" (la mascara o el aviso de ilegible). */
    fun isSecretPlaceholder(value: String?): Boolean = CrmSecrets.isPlaceholder(value)

    /** Cifra un secreto para guardarlo en la base de la empresa (lanza [CrmValidationException] sin llave maestra). */
    fun sealSecret(tenant: Connection, raw: String?): String? = CrmSecrets.seal(tenant, raw)

    /** Lo que muestran las pantallas de un secreto guardado: vacio, la mascara o el aviso de ilegible. */
    fun maskSecret(value: String?): String = CrmSecrets.mask(value)

    /** Texto que se muestra en lugar de un secreto ilegible. */
    val UnreadableSecretText: String get() = CrmSecrets.UnreadableText

    /** Abre un secreto guardado; si no se puede abrir devuelve el texto marcado como ilegible (ver [isSecretUnreadable]). */
    fun openSecret(tenant: Connection, stored: String?): String? = CrmSecrets.open(tenant, stored)

    // ---- Estados de reserva ----

    /** Estados de una reserva que aún esperan su check-in. */
    val CheckInStatuses: Set<String> get() = CrmAutomationRequirements.CheckInStatuses

    // ---- Compuerta de la extensión ----

    /** Motivo por el que la extensión [extensionId] no está activa en la empresa, o null si lo está. */
    fun extensionInactiveReason(tenant: Connection, extensionId: String): String? = CrmExtensions.inactive(tenant, extensionId)

    fun extensionEnabled(tenant: Connection, extensionId: String): Boolean = CrmExtensions.isEnabled(tenant, extensionId)

    /** Motivo por el que Cloudbeds no está activo en la empresa, o null si lo está. */
    fun cloudbedsInactiveReason(tenant: Connection): String? = extensionInactiveReason(tenant, CrmExtensionIds.Cloudbeds)

    fun cloudbedsEnabled(tenant: Connection): Boolean = extensionEnabled(tenant, CrmExtensionIds.Cloudbeds)

    // ---- Ajustes de Cloudbeds (la API key llega ya abierta) ----

    // Los ajustes viven en la tabla de la extension: sin la extension instalada se ven como "deshabilitado" y no se escribe nada.

    private val CloudbedsDisabled = CrmCloudbedsSettings(
        enabled = false, apiKey = null, propertyId = null, paymentMethod = "cash", welcomeEnabled = false, welcomeTemplateId = null,
        welcomeDaysBefore = 1, welcomeHour = 9, archiveOnCheckout = true, lastError = "", defaultCountry = "",
    )

    fun cloudbedsSettings(tenant: Connection): CrmCloudbedsSettings = CrmModuleExtensions.cloudbeds()?.settings(this, tenant) ?: CloudbedsDisabled

    fun saveCloudbedsForm(tenant: Connection, values: Map<String, String>, templates: List<CrmTemplate>) {
        val provider = CrmModuleExtensions.cloudbeds() ?: throw CrmValidationException(CrmExtensions.inactiveMessage(CrmExtensionIds.Cloudbeds))
        provider.saveSettings(this, tenant, values, templates)
    }

    fun recordCloudbedsError(tenant: Connection, error: String) {
        CrmModuleExtensions.cloudbeds()?.recordError(this, tenant, error)
    }

    fun setWelcomeEnabled(tenant: Connection, enabled: Boolean) {
        CrmModuleExtensions.cloudbeds()?.setWelcomeEnabled(this, tenant, enabled)
    }

    // ---- Ajustes y acciones de WordPress (el secreto llega ya abierto) ----

    // Los ajustes viven en la tabla de la extension: sin la extension instalada se ven como "deshabilitado" y no se escribe nada.

    private val WordPressDisabled = CrmWordPressSettings(enabled = false, siteUrl = null, secret = null, siteName = "", lastEventAt = null, lastError = "")

    private fun wordpress(): WordPressProvider = CrmModuleExtensions.wordpress() ?: throw CrmWordPressException(CrmExtensions.inactiveMessage(CrmExtensionIds.WordPress))

    /** Motivo por el que WordPress no está activo en la empresa, o null si lo está. */
    fun wordpressInactiveReason(tenant: Connection): String? = extensionInactiveReason(tenant, CrmExtensionIds.WordPress)

    fun wordpressEnabled(tenant: Connection): Boolean = extensionEnabled(tenant, CrmExtensionIds.WordPress)

    /** WordPress listo y con su extensión activa; false si la extensión no está instalada. */
    fun wordpressActive(tenant: Connection): Boolean = CrmModuleExtensions.wordpress()?.active(this, tenant) ?: false

    fun wordpressSettings(tenant: Connection): CrmWordPressSettings = CrmModuleExtensions.wordpress()?.settings(this, tenant) ?: WordPressDisabled

    fun saveWordPressForm(tenant: Connection, values: Map<String, String>) {
        val provider = CrmModuleExtensions.wordpress() ?: throw CrmValidationException(CrmExtensions.inactiveMessage(CrmExtensionIds.WordPress))
        provider.saveForm(this, tenant, values)
    }

    fun wordpressNewSecret(tenant: Connection): String {
        val provider = CrmModuleExtensions.wordpress() ?: throw CrmValidationException(CrmExtensions.inactiveMessage(CrmExtensionIds.WordPress))
        return provider.newSecret(this, tenant)
    }

    fun wordpressPing(tenant: Connection): String = wordpress().ping(this, tenant)

    fun wordpressSearch(tenant: Connection, text: String, filters: Map<String, String>, minPrice: String, maxPrice: String, page: Int): WpSearchResult =
        wordpress().search(this, tenant, text, filters, minPrice, maxPrice, page)

    fun wordpressListing(tenant: Connection, id: Long): WpListing = wordpress().listing(this, tenant, id)

    fun wordpressTaxonomies(tenant: Connection): Map<String, List<String>> = wordpress().taxonomies(this, tenant)

    fun wordpressMarkSold(tenant: Connection, id: Long, price: BigDecimal?, date: LocalDate) = wordpress().markSold(this, tenant, id, price, date)

    fun wordpressSyncAgents(tenant: Connection, users: List<CrmUser>, userEmails: Map<UUID, String>): Int = wordpress().syncAgents(this, tenant, users, userEmails)

    fun wordpressAgents(tenant: Connection): List<WpAgent> = CrmModuleExtensions.wordpress()?.agents(this, tenant).orEmpty()

    fun wordpressMapAgent(tenant: Connection, wpUserId: Long, userId: UUID?) {
        CrmModuleExtensions.wordpress()?.mapAgent(this, tenant, wpUserId, userId)
    }

    fun wordpressLinkListing(tenant: Connection, leadId: UUID, listing: WpListing, activity: String?) {
        CrmModuleExtensions.wordpress()?.linkListing(this, tenant, leadId, listing, activity)
    }

    fun recordWordPressError(tenant: Connection, error: String) {
        CrmModuleExtensions.wordpress()?.recordError(this, tenant, error)
    }

    // ---- Ajustes y acciones de Meta (los secretos llegan ya abiertos) ----

    // Los ajustes viven en las tablas de la extension: sin la extension instalada se ven como "deshabilitado" y no se escribe nada.

    private val MetaDisabled = CrmMetaSettings(false, null, null, null, null, null, null, null, null, null, true)

    private fun meta(): MetaProvider = CrmModuleExtensions.meta() ?: throw CrmMetaException(CrmExtensions.inactiveMessage(CrmExtensionIds.Meta))

    private fun metaForWrite(): MetaProvider = CrmModuleExtensions.meta() ?: throw CrmValidationException(CrmExtensions.inactiveMessage(CrmExtensionIds.Meta))

    /** Motivo por el que Meta no esta activo en la empresa, o null si lo esta. */
    fun metaInactiveReason(tenant: Connection): String? = extensionInactiveReason(tenant, CrmExtensionIds.Meta)

    fun metaEnabled(tenant: Connection): Boolean = extensionEnabled(tenant, CrmExtensionIds.Meta)

    /** Meta activado y con su extension activa; false si la extension no esta instalada. */
    fun metaActive(tenant: Connection): Boolean = CrmModuleExtensions.meta()?.active(this, tenant) ?: false

    // Las tablas ext_meta__ solo existen donde se instalo la extension (la migracion corre por empresa); sin instalar se
    // ve deshabilitado y no se consulta la tabla (tenant_extensions, del core, siempre existe), para no lanzar SQLException.
    private fun metaTablesPresent(tenant: Connection): Boolean =
        CrmExtensions.status(tenant, CrmExtensionIds.Meta).let { it != null && it != ExtensionStatus.Removed }

    fun metaSettings(tenant: Connection): CrmMetaSettings {
        val provider = CrmModuleExtensions.meta() ?: return MetaDisabled
        return if (metaTablesPresent(tenant)) provider.settings(this, tenant) else MetaDisabled
    }

    fun saveMeta(tenant: Connection, settings: CrmMetaSettings) = metaForWrite().save(this, tenant, settings)

    fun metaMerge(current: CrmMetaSettings, values: Map<String, String>): CrmMetaSettings = metaForWrite().merge(current, values)

    fun metaToFormValues(settings: CrmMetaSettings): Map<String, String> = CrmModuleExtensions.meta()?.toFormValues(settings).orEmpty()

    fun metaSetup(tenant: Connection, settings: CrmMetaSettings, page: Boolean = true, whatsApp: Boolean = true): MetaSetup =
        meta().setup(this, tenant, settings, page, whatsApp)

    fun metaLog(tenant: Connection, event: String, detail: String = "") {
        CrmModuleExtensions.meta()?.log(this, tenant, event, detail)
    }

    fun metaRecent(tenant: Connection): List<MetaLogEntry> {
        val provider = CrmModuleExtensions.meta() ?: return emptyList()
        return if (metaTablesPresent(tenant)) provider.recent(this, tenant) else emptyList()
    }

    fun metaFillNames(tenant: Connection, settings: CrmMetaSettings, limit: Int = 100): MetaNameFill = meta().fillNames(this, tenant, settings, limit)

    fun metaSendWhatsApp(settings: CrmMetaSettings, toDigits: String, text: String): String = meta().sendWhatsApp(this, settings, toDigits, text)

    fun metaSendPage(settings: CrmMetaSettings, recipient: String, text: String, platform: String): String = meta().sendPage(this, settings, recipient, text, platform)

    fun metaDisconnect(tenant: Connection, company: String) = metaForWrite().disconnect(this, tenant, company)

    // App de Meta del servidor (vive en el espacio del servidor).

    fun metaServerApp(): CrmMetaServerApp? = CrmModuleExtensions.meta()?.serverApp(this)

    fun metaServerWorkspace(): String? = CrmModuleExtensions.meta()?.serverWorkspace(this)

    fun metaLoadApp(tenant: Connection): CrmMetaServerApp = CrmModuleExtensions.meta()?.loadApp(this, tenant) ?: CrmMetaServerApp()

    fun metaSaveApp(tenant: Connection, app: CrmMetaServerApp) = metaForWrite().saveApp(this, tenant, app)

    fun metaMergeApp(current: CrmMetaServerApp, values: Map<String, String>): CrmMetaServerApp = metaForWrite().mergeApp(current, values)

    fun metaAppFormValues(app: CrmMetaServerApp): Map<String, String> = CrmModuleExtensions.meta()?.appFormValues(app).orEmpty()

    fun metaNewTicket(tenant: Connection, createdBy: String): String = metaForWrite().newTicket(this, tenant, createdBy)

    /** Enlace de la pagina de conexion de Meta de la empresa (la sirve la extension). */
    fun metaConnectUrl(publicBase: String, company: String, ticket: String): String = "$publicBase${MetaPaths.ConnectPath}/$company?t=$ticket"

    // ---- Ajustes e importación de Encuentra24 (la API key llega ya abierta) ----

    fun encuentra24Settings(tenant: Connection): CrmEncuentra24Settings = CrmModuleExtensions.encuentra24()?.settings(this, tenant) ?: CrmEncuentra24Ads.Disabled

    fun saveEncuentra24Form(tenant: Connection, values: Map<String, String>) {
        val provider = CrmModuleExtensions.encuentra24() ?: throw CrmValidationException(CrmExtensions.inactiveMessage(CrmExtensionIds.Encuentra24))
        provider.saveForm(this, tenant, values)
    }

    fun encuentra24Sync(tenant: Connection, from: LocalDate?): CrmEncuentra24SyncResult {
        val provider = CrmModuleExtensions.encuentra24() ?: throw CrmEncuentra24Exception(CrmExtensions.inactiveMessage(CrmExtensionIds.Encuentra24))
        return provider.sync(this, tenant, from)
    }

    // ---- Reservas ----

    fun saveReservation(tenant: Connection, leadId: UUID, result: CbReservationResult, start: LocalDate, end: LocalDate, rooms: String, email: String) =
        CrmReservations.save(tenant, leadId, result, start, end, rooms, email)

    fun reservationsForLead(tenant: Connection, leadId: UUID): List<CrmReservation> = CrmReservations.forLead(tenant, leadId)

    fun queryReservations(tenant: Connection, condition: String, bind: (PreparedStatement) -> Unit = {}): List<CrmReservation> =
        CrmReservations.query(tenant, condition, bind)

    fun markReservationCheckedIn(tenant: Connection, reservationId: UUID) = CrmReservations.markCheckedIn(tenant, reservationId)

    fun markReservationWelcomeSent(tenant: Connection, reservationId: UUID) = CrmReservations.markWelcomeSent(tenant, reservationId)

    fun updateReservationStatus(tenant: Connection, reservationId: UUID, status: String) = CrmReservations.updateStatus(tenant, reservationId, status)
}
