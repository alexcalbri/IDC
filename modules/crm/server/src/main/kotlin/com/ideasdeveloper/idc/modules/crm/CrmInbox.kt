package com.ideasdeveloper.idc.modules.crm

import com.ideasdeveloper.idc.server.modules.MailException
import com.ideasdeveloper.idc.server.modules.MailSender
import com.ideasdeveloper.idc.server.modules.SmtpSettings
import java.sql.Connection
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID

/**
 * A contact or message arriving from a channel (form, email, WhatsApp, Messenger, Instagram,
 * Encuentra24, import). [identity] is the id the channel uses for the person (PSID, IGSID...);
 * email and phone are identities of their own.
 */
data class CrmInbound(
    val channel: String,
    val name: String,
    val email: String = "",
    val phone: String = "",
    val identity: String? = null,
    val message: String = "",
    val subject: String = "",
    // Id of the message in the channel, so a retried delivery is not stored twice.
    val messageId: String? = null,
    // Origin shown on the lead, e.g. "WhatsApp" or "Formulario - Casa Las Colinas".
    val source: String = "",
    val interestName: String? = null,
    val suggestedOwnerUserId: UUID? = null,
    val language: String = "",
    val details: String = "",
    val sentAt: Instant? = null,
)

sealed interface CrmIntakeResult {
    data class Created(val leadId: UUID) : CrmIntakeResult
    data class Updated(val leadId: UUID) : CrmIntakeResult
    data object Duplicate : CrmIntakeResult
    data class Blocked(val reason: String) : CrmIntakeResult
    data class Ignored(val reason: String) : CrmIntakeResult
}

data class CrmMessage(
    val direction: String,
    val channel: String,
    val subject: String,
    val body: String,
    val status: String,
    val error: String,
    val author: String,
    val createdAt: Instant,
)

/** Turns inbound contacts into leads and keeps the conversation of each lead. */
object CrmInbox {
    val channelLabels = linkedMapOf(
        "form" to "Formulario",
        "email" to "Correo",
        "whatsapp" to "WhatsApp",
        "messenger" to "Messenger",
        "instagram" to "Instagram",
        "encuentra24" to "Encuentra24",
        "import" to "Importacion",
    )

    fun label(channel: String) = channelLabels[channel] ?: channel

    /**
     * Finds the person by channel identity, email or phone (in that order) and adds the message to
     * their open lead, creating the customer and the lead when needed. A lead whose email and phone
     * belong to different customers is linked by email and flagged for review, never merged.
     */
    fun receive(tenant: Connection, inbound: CrmInbound, onNewLead: (UUID) -> Unit = {}): CrmIntakeResult {
        val email = CrmContacts.fixEmail(inbound.email).takeIf { CrmContacts.isEmail(it) }.orEmpty()
        val phone = CrmContacts.normalizePhone(inbound.phone)
        val identity = inbound.identity?.trim()?.takeIf { it.isNotEmpty() }
        if (email.isEmpty() && phone.isEmpty() && identity == null) return CrmIntakeResult.Ignored("Sin correo, telefono ni identificador.")
        if (inbound.messageId != null && messageExists(tenant, inbound.channel, inbound.messageId)) return CrmIntakeResult.Duplicate

        val byIdentity = identity?.let { customerByIdentity(tenant, inbound.channel, it) }
        val byEmail = email.takeIf { it.isNotEmpty() }?.let { customerByEmail(tenant, it) }
        val byPhone = phone.takeIf { it.isNotEmpty() }?.let { customerByIdentity(tenant, "phone", it) ?: customerByPhone(tenant, it) }
        val customerId = byIdentity ?: byEmail ?: byPhone
        val review = if (byEmail != null && byPhone != null && byEmail != byPhone) {
            "El telefono $phone pertenece a otro cliente; revisa si es la misma persona."
        } else ""
        val openLead = customerId?.let { openLead(tenant, it) }

        val spam = CrmSpam.load(tenant)
        if (inbound.channel in spam.channels && !(spam.skipKnownLeads && openLead != null)) {
            CrmSpam.check(spam, listOf(inbound.name, inbound.subject, inbound.message).joinToString("\n"), email)?.let { reason ->
                CrmSpam.log(tenant, inbound.channel, email.ifEmpty { phone.ifEmpty { identity.orEmpty() } }, reason, inbound.message)
                return CrmIntakeResult.Blocked(reason)
            }
        }

        return transaction(tenant) {
            val customer = customerId ?: createCustomer(tenant, inbound.name, email, phone, identity, inbound.channel)
            if (customerId != null) fillMissingContact(tenant, customer, email, phone)
            identity?.let { addIdentity(tenant, customer, inbound.channel, it) }
            if (phone.isNotEmpty()) addIdentity(tenant, customer, "phone", phone)
            val (leadId, created) = if (openLead != null) {
                if (review.isNotEmpty()) setReview(tenant, openLead, review)
                openLead to false
            } else {
                createLead(tenant, customer, inbound, review) to true
            }
            if (inbound.message.isNotBlank()) {
                addMessage(tenant, leadId, "in", inbound.channel, inbound.subject, inbound.message, inbound.messageId, "received", "", null, inbound.name, inbound.sentAt)
            }
            if (created) CrmIntakeResult.Created(leadId) else CrmIntakeResult.Updated(leadId)
        }.also { if (it is CrmIntakeResult.Created) onNewLead(it.leadId) }
    }

    private fun createLead(tenant: Connection, customer: UUID, inbound: CrmInbound, review: String): UUID {
        val stage = CrmRepository.stages(tenant).firstOrNull { it.kind == StageKind.Open } ?: CrmRepository.stages(tenant).first()
        val interest = inbound.interestName?.takeIf { CrmRepository.interestsEnabled(tenant) }?.let { name ->
            CrmRepository.interests(tenant).firstOrNull { it.name.equals(name, ignoreCase = true) }?.id
        }
        val source = inbound.source.ifBlank { label(inbound.channel) }.take(120)
        val id = UUID.randomUUID()
        tenant.prepareStatement(
            """
            INSERT INTO crm_leads (id, customer_id, stage_id, interest_id, source, language, message, details, suggested_owner_user_id, review_reason)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """.trimIndent()
        ).use { insert ->
            insert.setObject(1, id)
            insert.setObject(2, customer)
            insert.setObject(3, stage.id)
            insert.setObject(4, interest)
            insert.setString(5, source)
            insert.setString(6, inbound.language.take(40))
            insert.setString(7, inbound.message.take(5000))
            insert.setString(8, inbound.details.take(5000))
            insert.setObject(9, inbound.suggestedOwnerUserId)
            insert.setString(10, review)
            insert.executeUpdate()
        }
        CrmRepository.addActivity(tenant, id, "created", "Lead creado en ${stage.name}. Origen: $source.", CrmActor(null, label(inbound.channel)), toStage = stage.id)
        return id
    }

    private fun createCustomer(tenant: Connection, name: String, email: String, phone: String, identity: String?, channel: String): UUID {
        val id = UUID.randomUUID()
        val display = name.trim().ifEmpty { phone.ifEmpty { email.ifEmpty { "Contacto de ${label(channel)} ${identity.orEmpty().takeLast(6)}" } } }.take(200)
        tenant.prepareStatement(
            "INSERT INTO customers (id, display_name, primary_email, primary_phone, status, customer_type) VALUES (?, ?, ?, ?, 'prospect', 'crm')"
        ).use { insert ->
            insert.setObject(1, id)
            insert.setString(2, display)
            insert.setString(3, email)
            insert.setString(4, phone)
            insert.executeUpdate()
        }
        return id
    }

    // Adds the email or phone a known customer did not have yet; existing values are never replaced.
    private fun fillMissingContact(tenant: Connection, customer: UUID, email: String, phone: String) {
        tenant.prepareStatement(
            """
            UPDATE customers SET primary_email = CASE WHEN primary_email = '' THEN ? ELSE primary_email END,
                primary_phone = CASE WHEN primary_phone = '' THEN ? ELSE primary_phone END
            WHERE id = ? AND (primary_email = '' AND ? <> '' OR primary_phone = '' AND ? <> '')
            """.trimIndent()
        ).use { update ->
            update.setString(1, email)
            update.setString(2, phone)
            update.setObject(3, customer)
            update.setString(4, email)
            update.setString(5, phone)
            update.executeUpdate()
        }
    }

    private fun customerByIdentity(tenant: Connection, channel: String, externalId: String): UUID? =
        tenant.prepareStatement("SELECT customer_id FROM crm_identities WHERE channel = ? AND external_id = ?").use { query ->
            query.setString(1, channel)
            query.setString(2, externalId)
            query.executeQuery().use { rows -> if (rows.next()) rows.getObject(1, UUID::class.java) else null }
        }

    private fun customerByEmail(tenant: Connection, email: String): UUID? =
        tenant.prepareStatement("SELECT id FROM customers WHERE lower(primary_email) = lower(?) AND status <> 'archived' ORDER BY created_at LIMIT 1").use { query ->
            query.setString(1, email)
            query.executeQuery().use { rows -> if (rows.next()) rows.getObject(1, UUID::class.java) else null }
        }

    private fun customerByPhone(tenant: Connection, phone: String): UUID? =
        tenant.prepareStatement("SELECT id FROM customers WHERE primary_phone = ? AND status <> 'archived' ORDER BY created_at LIMIT 1").use { query ->
            query.setString(1, phone)
            query.executeQuery().use { rows -> if (rows.next()) rows.getObject(1, UUID::class.java) else null }
        }

    fun addIdentity(tenant: Connection, customer: UUID, channel: String, externalId: String) {
        tenant.prepareStatement(
            "INSERT INTO crm_identities (id, customer_id, channel, external_id) VALUES (?, ?, ?, ?) ON CONFLICT (channel, external_id) DO NOTHING"
        ).use { insert ->
            insert.setObject(1, UUID.randomUUID())
            insert.setObject(2, customer)
            insert.setString(3, channel)
            insert.setString(4, externalId.take(200))
            insert.executeUpdate()
        }
    }

    // Identity of a lead's customer on a channel (e.g. its Messenger PSID), for replying there.
    fun identity(tenant: Connection, customer: UUID, channel: String): String? =
        tenant.prepareStatement("SELECT external_id FROM crm_identities WHERE customer_id = ? AND channel = ? ORDER BY created_at DESC LIMIT 1").use { query ->
            query.setObject(1, customer)
            query.setString(2, channel)
            query.executeQuery().use { rows -> if (rows.next()) rows.getString(1) else null }
        }

    private fun openLead(tenant: Connection, customer: UUID): UUID? =
        tenant.prepareStatement("SELECT id FROM crm_leads WHERE customer_id = ? AND closed_at IS NULL ORDER BY updated_at DESC LIMIT 1").use { query ->
            query.setObject(1, customer)
            query.executeQuery().use { rows -> if (rows.next()) rows.getObject(1, UUID::class.java) else null }
        }

    private fun setReview(tenant: Connection, lead: UUID, reason: String) {
        tenant.prepareStatement("UPDATE crm_leads SET review_reason = ? WHERE id = ? AND review_reason = ''").use {
            it.setString(1, reason)
            it.setObject(2, lead)
            it.executeUpdate()
        }
    }

    private fun messageExists(tenant: Connection, channel: String, externalId: String): Boolean =
        tenant.prepareStatement("SELECT 1 FROM crm_messages WHERE channel = ? AND external_id = ?").use { query ->
            query.setString(1, channel)
            query.setString(2, externalId)
            query.executeQuery().use { it.next() }
        }

    fun addMessage(
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
    ) {
        tenant.prepareStatement(
            """
            INSERT INTO crm_messages (id, lead_id, direction, channel, subject, body, external_id, status, error, user_id, author, created_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, COALESCE(?, CURRENT_TIMESTAMP))
            ON CONFLICT (channel, external_id) DO NOTHING
            """.trimIndent()
        ).use { insert ->
            insert.setObject(1, UUID.randomUUID())
            insert.setObject(2, leadId)
            insert.setString(3, direction)
            insert.setString(4, channel)
            insert.setString(5, subject.take(300))
            insert.setString(6, body.take(20000))
            insert.setString(7, externalId)
            insert.setString(8, status)
            insert.setString(9, error.take(1000))
            insert.setObject(10, userId)
            insert.setString(11, author.take(200))
            insert.setTimestamp(12, at?.let { Timestamp.from(it) })
            insert.executeUpdate()
        }
        tenant.prepareStatement("UPDATE crm_leads SET updated_at = CURRENT_TIMESTAMP WHERE id = ?").use { it.setObject(1, leadId); it.executeUpdate() }
    }

    fun messages(tenant: Connection, leadId: UUID, limit: Int): List<CrmMessage> = tenant.prepareStatement(
        "SELECT direction, channel, subject, body, status, error, author, created_at FROM crm_messages WHERE lead_id = ? ORDER BY created_at DESC LIMIT ?"
    ).use { query ->
        query.setObject(1, leadId)
        query.setInt(2, limit)
        query.executeQuery().use { rows ->
            buildList {
                while (rows.next()) add(
                    CrmMessage(rows.getString(1), rows.getString(2), rows.getString(3), rows.getString(4), rows.getString(5), rows.getString(6), rows.getString(7), rows.getTimestamp(8).toInstant())
                )
            }
        }
    }

    /** Values of the template placeholders for a lead. */
    fun templateValues(tenant: Connection, lead: CrmLead, names: CrmNames, stages: List<CrmStage>, extra: Map<String, String> = emptyMap()): Map<String, String> {
        val code = tenant.prepareStatement("SELECT customer_code FROM customers WHERE id = ?").use { query ->
            query.setObject(1, lead.customerId)
            query.executeQuery().use { rows -> if (rows.next()) rows.getString(1).orEmpty() else "" }
        }
        return mapOf(
            "nombre" to lead.name,
            "correo" to lead.email,
            "telefono" to lead.phone,
            "codigo_cliente" to code,
            "responsable" to if (lead.ownerUserId == null) "" else names.user(lead.ownerUserId),
            "etapa" to stages.firstOrNull { it.id == lead.stageId }?.name.orEmpty(),
            "interes" to if (lead.interestId == null) "" else names.interest(lead.interestId),
            "checkin" to lead.targetStart?.toString().orEmpty(),
            "checkout" to lead.targetEnd?.toString().orEmpty(),
            "valor" to lead.estimatedValue?.toPlainString().orEmpty(),
        ) + extra
    }

    private fun <T> transaction(tenant: Connection, block: () -> T): T {
        val previous = tenant.autoCommit
        tenant.autoCommit = false
        try {
            return block().also { tenant.commit() }
        } catch (failure: Exception) {
            tenant.rollback()
            throw failure
        } finally {
            tenant.autoCommit = previous
        }
    }
}

class CrmChannelException(message: String) : Exception(message)

/** A channel the CRM can reply through. Channel integrations register themselves in [CrmChannels]. */
interface CrmChannel {
    val id: String
    val label: String

    // Why the lead cannot be reached on this channel, or null when it can.
    fun unavailable(context: CrmChannelContext, lead: CrmLead): String?

    // Sends the message and returns the channel's message id when it gives one.
    fun send(context: CrmChannelContext, lead: CrmLead, email: CrmEmail): String?
}

class CrmChannelContext(val tenant: Connection, val serverMail: SmtpSettings?)

object CrmEmailChannel : CrmChannel {
    override val id = "email"
    override val label = "Correo"

    override fun unavailable(context: CrmChannelContext, lead: CrmLead): String? = when {
        lead.email.isBlank() -> "El lead no tiene correo."
        CrmMail.load(context.tenant).smtp(context.serverMail) == null -> "Configura el correo del CRM (SMTP)."
        else -> null
    }

    override fun send(context: CrmChannelContext, lead: CrmLead, email: CrmEmail): String? {
        val smtp = CrmMail.load(context.tenant).smtp(context.serverMail) ?: throw CrmChannelException("Configura el correo del CRM (SMTP).")
        if (email.subject.isBlank()) throw CrmChannelException("Escribe el asunto del correo.")
        try {
            MailSender.sendHtml(smtp, lead.email, email.subject, email.html, email.text)
        } catch (failure: MailException) {
            throw CrmChannelException(failure.message ?: "No se pudo enviar el correo.")
        }
        return null
    }
}

object CrmChannels {
    // Channels in the order offered to users; channel integrations are added to this list.
    val all: List<CrmChannel> = listOf(CrmEmailChannel)

    fun find(id: String) = all.firstOrNull { it.id == id }
}
