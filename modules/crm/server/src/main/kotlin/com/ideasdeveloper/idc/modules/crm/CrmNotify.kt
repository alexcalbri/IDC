package com.ideasdeveloper.idc.modules.crm

import com.ideasdeveloper.idc.server.modules.MailException
import com.ideasdeveloper.idc.server.modules.MailSender
import com.ideasdeveloper.idc.server.modules.SmtpSettings
import java.sql.Connection
import java.time.LocalDate
import java.time.LocalDateTime
import java.util.UUID

data class CrmUserSettings(
    val userId: UUID,
    val email: String,
    val notifyNewLeads: Boolean,
    val notifyAssigned: Boolean,
    val dailyDigest: Boolean,
)

/** Email notifications of the CRM. Messages are queued in crm_outbox and sent by a job. */
object CrmNotify {
    private const val MaxAttempts = 5

    fun userSettings(tenant: Connection): Map<UUID, CrmUserSettings> =
        tenant.prepareStatement("SELECT user_id, email, notify_new_leads, notify_assigned, daily_digest FROM crm_user_settings").use { query ->
            query.executeQuery().use { rows ->
                buildMap {
                    while (rows.next()) {
                        val id = rows.getObject(1, UUID::class.java)
                        put(id, CrmUserSettings(id, rows.getString(2), rows.getBoolean(3), rows.getBoolean(4), rows.getBoolean(5)))
                    }
                }
            }
        }

    fun saveUserSettings(tenant: Connection, settings: CrmUserSettings) {
        if (settings.email.isNotEmpty() && !CrmContacts.isEmail(settings.email)) throw CrmValidationException("Escribe un correo valido o dejalo vacio.")
        tenant.prepareStatement(
            """
            INSERT INTO crm_user_settings (user_id, email, notify_new_leads, notify_assigned, daily_digest) VALUES (?, ?, ?, ?, ?)
            ON CONFLICT (user_id) DO UPDATE SET email = EXCLUDED.email, notify_new_leads = EXCLUDED.notify_new_leads,
                notify_assigned = EXCLUDED.notify_assigned, daily_digest = EXCLUDED.daily_digest
            """.trimIndent()
        ).use { upsert ->
            upsert.setObject(1, settings.userId)
            upsert.setString(2, settings.email)
            upsert.setBoolean(3, settings.notifyNewLeads)
            upsert.setBoolean(4, settings.notifyAssigned)
            upsert.setBoolean(5, settings.dailyDigest)
            upsert.executeUpdate()
        }
    }

    /** Which leads a company user sees, as [CrmRepository.scope] does for a signed-in user. */
    fun userScope(tenant: Connection, userId: UUID): CrmScope {
        val owner = tenant.prepareStatement("SELECT 1 FROM business_owner WHERE user_id = ?").use { query ->
            query.setObject(1, userId)
            query.executeQuery().use { it.next() }
        }
        val granted = tenant.prepareStatement("SELECT permission_id FROM application_user_permissions WHERE user_id = ?").use { query ->
            query.setObject(1, userId)
            query.executeQuery().use { rows -> buildSet { while (rows.next()) add(rows.getString(1)) } }
        }
        return CrmScope(
            userId = userId,
            seesAll = owner || CrmScreens.SeeAll in granted,
            reception = owner || CrmScreens.Reception in granted,
            interestsEnabled = CrmRepository.interestsEnabled(tenant),
            interests = CrmRepository.userInterests(tenant)[userId].orEmpty(),
        )
    }

    fun enqueue(tenant: Connection, kind: String, to: String, email: CrmEmail, leadId: UUID? = null) {
        tenant.prepareStatement("INSERT INTO crm_outbox (id, lead_id, kind, to_email, subject, html, text) VALUES (?, ?, ?, ?, ?, ?, ?)").use { insert ->
            insert.setObject(1, UUID.randomUUID())
            insert.setObject(2, leadId)
            insert.setString(3, kind)
            insert.setString(4, to)
            insert.setString(5, email.subject.take(300))
            insert.setString(6, email.html)
            insert.setString(7, email.text)
            insert.executeUpdate()
        }
    }

    // New lead: users that asked for it and see every lead (administrators and brokers).
    fun newLead(tenant: Connection, leadId: UUID) {
        val lead = CrmRepository.lead(tenant, leadId) ?: return
        userSettings(tenant).values.filter { it.notifyNewLeads && it.email.isNotEmpty() }.forEach { user ->
            val scope = userScope(tenant, user.userId)
            if (!scope.seesAll || !scope.canSee(lead.interestId, lead.ownerUserId)) return@forEach
            enqueue(tenant, "new_lead", user.email, leadEmail("Lead nuevo: ${lead.name}", "Entro un lead nuevo en el CRM.", tenant, lead), lead.id)
        }
    }

    // A lead was assigned to [userId].
    fun assigned(tenant: Connection, leadId: UUID, userId: UUID) {
        val user = userSettings(tenant)[userId]?.takeIf { it.notifyAssigned && it.email.isNotEmpty() } ?: return
        val lead = CrmRepository.lead(tenant, leadId) ?: return
        enqueue(tenant, "assigned", user.email, leadEmail("Te asignaron a ${lead.name}", "Te asignaron este lead en el CRM.", tenant, lead), lead.id)
    }

    private fun leadEmail(subject: String, intro: String, tenant: Connection, lead: CrmLead): CrmEmail {
        val interest = lead.interestId?.let { id -> CrmRepository.interests(tenant).firstOrNull { it.id == id }?.name }
        val lines = listOfNotNull(
            intro,
            "",
            "Nombre: ${lead.name}",
            lead.email.takeIf { it.isNotEmpty() }?.let { "Correo: $it" },
            lead.phone.takeIf { it.isNotEmpty() }?.let { "Telefono: $it" },
            interest?.let { "Interes: $it" },
            lead.source.takeIf { it.isNotEmpty() }?.let { "Origen: $it" },
            lead.message.takeIf { it.isNotBlank() }?.let { "\nMensaje:\n${it.take(1000)}" },
        )
        return CrmMail.plain(subject, lines.joinToString("\n"))
    }

    /**
     * Daily digest at the company's digest hour: each user that asked for it receives their open
     * leads with a follow-up due (overdue or today); users that see every lead also get the count
     * of open leads without responsible. Sent once per day.
     */
    fun digestIfDue(tenant: Connection, now: LocalDateTime) {
        val (hour, sentOn) = tenant.prepareStatement("SELECT digest_hour, digest_sent_on FROM crm_settings WHERE id = 1").use { query ->
            query.executeQuery().use { rows -> if (rows.next()) rows.getInt(1) to rows.getObject(2, LocalDate::class.java) else return }
        }
        val today = now.toLocalDate()
        if (now.hour < hour || sentOn == today) return
        val names = CrmNames(CrmRepository.users(tenant), CrmRepository.interests(tenant))
        userSettings(tenant).values.filter { it.dailyDigest && it.email.isNotEmpty() }.forEach { user ->
            val scope = userScope(tenant, user.userId)
            val due = CrmRepository.followUps(tenant, scope, CrmLeadFilter(ownerUserId = user.userId), today, 50)
            val unassigned = if (scope.seesAll) CrmRepository.count(tenant, scope, CrmLeadFilter(unassigned = true, openOnly = true)) else 0
            if (due.isEmpty() && unassigned == 0) return@forEach
            val lines = buildList {
                add("Resumen de tus leads, ${today}.")
                add("")
                if (due.isEmpty()) add("No tienes seguimientos vencidos ni para hoy.") else {
                    add("Seguimientos (${due.size}):")
                    due.forEach { add("- ${it.name} - ${it.followUpOn}${if (it.followUpOn!!.isBefore(today)) " (vencido)" else ""}") }
                }
                if (unassigned > 0) {
                    add("")
                    add("Leads abiertos sin responsable: $unassigned.")
                }
                add("")
                add("Usuario: ${names.user(user.userId)}")
            }
            enqueue(tenant, "digest", user.email, CrmMail.plain("Tus leads de hoy", lines.joinToString("\n")))
        }
        tenant.prepareStatement("UPDATE crm_settings SET digest_sent_on = ? WHERE id = 1").use { it.setObject(1, today); it.executeUpdate() }
    }

    /** Sends up to [limit] queued emails; failed ones are retried up to [MaxAttempts] times. */
    fun sendQueued(tenant: Connection, smtp: SmtpSettings?, limit: Int): Int {
        if (smtp == null) return 0
        data class Pending(val id: UUID, val to: String, val subject: String, val html: String, val text: String)
        val pending = tenant.prepareStatement(
            "SELECT id, to_email, subject, html, text FROM crm_outbox WHERE sent_at IS NULL AND attempts < ? ORDER BY created_at LIMIT ?"
        ).use { query ->
            query.setInt(1, MaxAttempts)
            query.setInt(2, limit)
            query.executeQuery().use { rows ->
                buildList { while (rows.next()) add(Pending(rows.getObject(1, UUID::class.java), rows.getString(2), rows.getString(3), rows.getString(4), rows.getString(5))) }
            }
        }
        var sent = 0
        pending.forEach { mail ->
            val error = try {
                MailSender.sendHtml(smtp, mail.to, mail.subject, mail.html, mail.text)
                sent++
                null
            } catch (failure: MailException) {
                failure.message.orEmpty()
            }
            tenant.prepareStatement(
                "UPDATE crm_outbox SET attempts = attempts + 1, sent_at = CASE WHEN ? THEN CURRENT_TIMESTAMP END, last_error = ? WHERE id = ?"
            ).use { update ->
                update.setBoolean(1, error == null)
                update.setString(2, error.orEmpty().take(500))
                update.setObject(3, mail.id)
                update.executeUpdate()
            }
        }
        // Sent mail is kept 30 days.
        tenant.prepareStatement("DELETE FROM crm_outbox WHERE sent_at < CURRENT_TIMESTAMP - INTERVAL '30 days'").use { it.executeUpdate() }
        return sent
    }
}
