package com.ideasdeveloper.idc.modules.crm

import com.ideasdeveloper.idc.server.modules.ModuleAccessService
import com.ideasdeveloper.idc.server.modules.ModuleNotifier
import java.sql.Connection
import java.time.DateTimeException
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import java.util.UUID

/** Time zone of a company: crm_settings.exit_time_zone when set and valid, else the server's. Shared by date-based jobs. */
object CrmZone {
    fun of(tenant: Connection): ZoneId {
        val name = tenant.prepareStatement("SELECT exit_time_zone FROM crm_settings WHERE id = 1").use { query ->
            query.executeQuery().use { rows -> if (rows.next()) rows.getString(1).orEmpty() else "" }
        }
        return parse(name)
    }

    /** An unknown or empty zone name falls back to [fallback]. */
    fun parse(name: String, fallback: ZoneId = ZoneId.systemDefault()): ZoneId =
        if (name.isBlank()) fallback else try {
            ZoneId.of(name.trim())
        } catch (failure: DateTimeException) {
            fallback
        }

    /** Today in [zone] and whether [hour] has passed there. */
    fun gate(zone: ZoneId, hour: Int, now: Instant): CrmExit.Gate {
        val local = now.atZone(zone)
        return CrmExit.Gate(local.toLocalDate(), !local.toLocalTime().isBefore(LocalTime.of(hour, 0)))
    }
}

/**
 * Events of a lead that several parts of the CRM react to. Every inbound channel goes through [CrmInbox.receive],
 * which calls [messageReceived] once per stored message.
 */
object CrmLeadEvents {
    // Set when the module routes are installed; gives the notifier of the Core to code without a request (webhooks, jobs).
    @Volatile
    var access: ModuleAccessService? = null

    private fun notifier(): ModuleNotifier? = access?.notificationsFor(CrmModule.id)

    /** A new inbound message was stored in [leadId] (a new or existing lead). */
    fun messageReceived(tenant: Connection, leadId: UUID, message: String, channel: String, notifier: ModuleNotifier? = notifier()) {
        CrmNotify.newMessage(tenant, notifier, leadId, message, channel)
        // Rules "mensaje nuevo entrante" (best effort: they never fail the message that was already stored).
        CrmAutomationEvents.messageReceived(tenant, leadId)
    }
}
