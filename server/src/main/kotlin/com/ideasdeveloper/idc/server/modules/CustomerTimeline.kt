package com.ideasdeveloper.idc.server.modules

import com.ideasdeveloper.idc.ui.UiAction
import kotlinx.serialization.Serializable
import java.sql.Connection
import java.time.Instant
import java.util.UUID

/**
 * What a module adds to a customer's history (the shared customers of Clientes): a CRM its leads and
 * reservations, a hotspot its WiFi connections, a hotel its stays and special requests. Clientes shows
 * the entries of every installed module together, without knowing any of them, and no module
 * depends on another.
 *
 * Entries are listed only when the module is enabled for the company and the user holds [permission]
 * now. [entries] must apply the same visibility rules as the module's own screens (scopes, ownership)
 * using [CustomerTimelineQuery.permissions], and return at most [CustomerTimelineQuery.limit] entries,
 * newest first. It must only read.
 */
class CustomerTimeline(
    val permission: String,
    val entries: (CustomerTimelineQuery) -> List<CustomerTimelineEntry>,
)

class CustomerTimelineQuery(
    val customerId: UUID,
    val tenant: Connection,
    val permissions: ModulePermissions,
    val access: ModuleAccessService,
    val limit: Int,
)

/** One event of the customer's history, as a module reports it. */
class CustomerTimelineEntry(
    val at: Instant,
    // Short machine word chosen by the module (reservation, lead, note, wifi...), for icons and filters.
    val kind: String,
    // One line for people, e.g. "Reserva 12345: 3 al 6 de mayo".
    val title: String,
    val detail: String = "",
    // Who did it, when a person did (see ModuleAccessService.userIdentity).
    val author: String = "",
) {
    // Where the details are, e.g. the lead in the CRM (UiOpenModule). A property and not a
    // constructor parameter so modules built before it keep working.
    var link: UiAction? = null
}

// A customer timeline of an installed module, as the registry exposes it.
class RegisteredCustomerTimeline(val moduleId: String, val moduleName: String, val timeline: CustomerTimeline)

/** An entry of a customer's history with the module that reported it, as routes and agents get it. */
@Serializable
data class CustomerHistoryEntry(
    val at: String,
    val moduleId: String,
    val moduleName: String,
    val kind: String,
    val title: String,
    val detail: String = "",
    val author: String = "",
    // Where the details are, e.g. UiOpenModule to the CRM lead.
    val link: UiAction? = null,
)

/**
 * An event a module writes in a customer's history of one company: a sale converted in the CRM, a
 * reservation, a service hired. Clientes stores it (see ServerModule.customerHistoryWriter) and
 * shows it in the company's tab of the customer; [link] opens the record with all the details.
 * [sourceRef] identifies the event in the module (e.g. "lead:<id>:won") so writing it twice keeps one.
 */
class CustomerHistoryEvent(
    val customerId: UUID,
    val moduleId: String,
    val kind: String,
    val title: String,
    val detail: String = "",
    val author: String = "",
    val link: UiAction? = null,
    val sourceRef: String? = null,
    val at: Instant = Instant.now(),
)

/** Stores [CustomerHistoryEvent]s in a company's database; the module that owns customers provides it. */
fun interface CustomerHistoryWriter {
    fun write(tenant: Connection, event: CustomerHistoryEvent)
}

/**
 * Told when a module changed a customer's data (name, email, phone, custom fields) in a company's
 * database, so the module that owns customers (Clientes) copies the change to the other companies
 * where the customer is shared. See ModuleAccessService.customerChanged.
 */
fun interface CustomerChangeListener {
    fun changed(access: ModuleAccessService, tenant: Connection, customerId: UUID)
}

/** A note of the customer (tastes, preferences), as other modules show it. */
class CustomerNote(val at: Instant, val body: String, val author: String = "", val companyName: String = "")

/**
 * Reads the customer's notes for other modules (the CRM shows them in the lead), newest first. Only
 * the module that owns customers (Clientes) provides it. See ModuleAccessService.customerNotes.
 */
fun interface CustomerNotesReader {
    fun notes(access: ModuleAccessService, tenant: Connection, customerId: UUID, limit: Int): List<CustomerNote>
}

/** A company of the same business owner (owner group), as modules see it. */
@Serializable
data class GroupCompany(val code: String, val name: String)
