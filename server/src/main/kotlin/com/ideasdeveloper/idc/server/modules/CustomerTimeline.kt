package com.ideasdeveloper.idc.server.modules

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
)

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
)
