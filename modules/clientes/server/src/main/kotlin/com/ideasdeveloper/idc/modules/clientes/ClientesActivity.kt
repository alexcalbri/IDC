package com.ideasdeveloper.idc.modules.clientes

import com.ideasdeveloper.idc.server.modules.CustomerTimeline
import com.ideasdeveloper.idc.server.modules.CustomerTimelineEntry
import com.ideasdeveloper.idc.server.modules.ModuleAccessService
import com.ideasdeveloper.idc.server.modules.ModulePermissions
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import java.sql.Connection
import java.time.OffsetDateTime
import java.util.UUID

/** Who created, edited or archived each customer and what changed (customer_activity). */
object ClientesActivity {
    // Who made a change: the user's name, plus the agent when an AI agent made it.
    fun author(tenant: Connection, permissions: ModulePermissions, access: ModuleAccessService, agentName: String? = null): Pair<UUID?, String> {
        val identity = access.userIdentity(tenant, permissions)
        val name = identity?.name ?: if (permissions.serverOwner) "Dueno del servidor" else "Usuario"
        val shown = (if (permissions.serverOwner) "$name (dueno del servidor)" else name) + (agentName?.let { " (agente $it)" } ?: "")
        // Server owners are central users: their id does not exist in this company's users.
        return permissions.userId?.takeUnless { permissions.serverOwner } to shown
    }

    fun record(tenant: Connection, customerId: UUID, kind: String, body: String, author: Pair<UUID?, String>) {
        tenant.prepareStatement(
            "INSERT INTO customer_activity (id, customer_id, kind, body, user_id, author) VALUES (?, ?, ?, ?, ?, ?)"
        ).use { insert ->
            insert.setObject(1, UUID.randomUUID())
            insert.setObject(2, customerId)
            insert.setString(3, kind)
            insert.setString(4, body.take(5_000))
            insert.setObject(5, author.first)
            insert.setString(6, author.second)
            insert.executeUpdate()
        }
    }

    /** "Campo: antes -> despues" for each changed value, or null when nothing changed. */
    fun changes(before: CustomerResponse, after: CreateCustomerRequest, fieldLabels: Map<String, String>): String? {
        fun shown(value: String) = value.ifBlank { "(vacio)" }
        val lines = buildList {
            if (before.displayName != after.displayName.trim()) add("Nombre: ${shown(before.displayName)} -> ${shown(after.displayName.trim())}")
            if (before.primaryEmail != after.primaryEmail.trim()) add("Correo: ${shown(before.primaryEmail)} -> ${shown(after.primaryEmail.trim())}")
            if (before.primaryPhone != after.primaryPhone.trim()) add("Telefono: ${shown(before.primaryPhone)} -> ${shown(after.primaryPhone.trim())}")
            (before.dynamicFields.keys + after.dynamicFields.keys).sorted().forEach { key ->
                val old = text(before.dynamicFields[key])
                val new = text(after.dynamicFields[key])
                if (old != new) add("${fieldLabels[key] ?: key}: ${shown(old)} -> ${shown(new)}")
            }
        }
        return lines.takeIf { it.isNotEmpty() }?.joinToString("\n")
    }

    private fun text(value: JsonElement?): String = when (value) {
        null, JsonNull -> ""
        is JsonPrimitive -> value.contentOrNull.orEmpty()
        else -> value.toString()
    }

    // Clientes' own part of the customer's history, like any other module's (CustomerTimeline).
    val timeline = CustomerTimeline(permission = "clientes.view") { query ->
        query.tenant.prepareStatement(
            "SELECT kind, body, author, created_at FROM customer_activity WHERE customer_id = ? ORDER BY created_at DESC LIMIT ?"
        ).use { statement ->
            statement.setObject(1, query.customerId)
            statement.setInt(2, query.limit)
            statement.executeQuery().use { rows ->
                buildList {
                    while (rows.next()) {
                        val kind = rows.getString("kind")
                        add(
                            CustomerTimelineEntry(
                                at = rows.getObject("created_at", OffsetDateTime::class.java).toInstant(),
                                kind = kind,
                                title = when (kind) {
                                    "created" -> "Cliente creado"
                                    "archived" -> "Cliente archivado"
                                    else -> "Datos del cliente modificados"
                                },
                                detail = rows.getString("body"),
                                author = rows.getString("author"),
                            )
                        )
                    }
                }
            }
        }
    }
}
