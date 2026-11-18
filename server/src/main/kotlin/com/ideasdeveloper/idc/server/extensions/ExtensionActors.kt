package com.ideasdeveloper.idc.server.extensions

import com.ideasdeveloper.idc.server.company.application.ServerOwnerAuthorizer
import com.ideasdeveloper.idc.server.modules.ModuleAccessDecision
import com.ideasdeveloper.idc.server.modules.ModuleAccessService
import com.ideasdeveloper.idc.server.modules.UserSessionLookup
import io.ktor.http.HttpHeaders
import io.ktor.server.application.ApplicationCall
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Result of recognising who calls an extension route: an actor, or the refusal to answer with. */
sealed class ExtensionActorLookup {
    data class Found(val actor: ExtensionActor) : ExtensionActorLookup()
    data class Denied(val decision: ModuleAccessDecision) : ExtensionActorLookup()
}

/**
 * Recognises server owners (ServerOwnerAuthorizer, like the routes of /companies) and business
 * owners (the session of the company in X-Company-Code, which must belong to a business_owner).
 * CompanyRoutes keeps its own private copies of these two checks; this is the same rule for the
 * extension routes without editing that file.
 */
class ExtensionActors(
    private val access: ModuleAccessService,
    private val authorizer: ServerOwnerAuthorizer,
    private val connections: ExtensionConnections,
) {
    /** The server owner behind the Bearer token, or a refusal. */
    suspend fun serverOwner(call: ApplicationCall): ExtensionActorLookup {
        val role = bearerToken(call)?.let { token -> withContext(Dispatchers.IO) { authorizer.serverOwnerRole(token) } }
            ?: return ExtensionActorLookup.Denied(ModuleAccessDecision.Forbidden("SERVER_OWNER_REQUIRED", "Debes iniciar sesion como server_owner."))
        return ExtensionActorLookup.Found(ExtensionActor(role, serverOwner = true))
    }

    /**
     * Who acts in the company of X-Company-Code: the business owner of that company, or a server
     * owner (who acts in any company). The actor's companyCode is that company.
     */
    suspend fun currentCompany(call: ApplicationCall): ExtensionActorLookup {
        val token = bearerToken(call)
            ?: return ExtensionActorLookup.Denied(ModuleAccessDecision.Unauthorized("missing_token", "La sesion es requerida."))
        val company = call.request.headers["X-Company-Code"]?.trim()?.takeIf { it.isNotEmpty() }
            ?: return ExtensionActorLookup.Denied(ModuleAccessDecision.Unauthorized("missing_company", "La empresa es requerida."))
        val serverRole = withContext(Dispatchers.IO) { authorizer.serverOwnerRole(token) }
        if (serverRole != null) return ExtensionActorLookup.Found(ExtensionActor(serverRole, serverOwner = true, companyCode = company))
        return when (val session = withContext(Dispatchers.IO) { access.userSession(call) }) {
            is UserSessionLookup.Denied -> ExtensionActorLookup.Denied(session.decision)
            is UserSessionLookup.Granted -> {
                val role = withContext(Dispatchers.IO) { businessOwnerRole(session.companyCode, session.userId) }
                    ?: return ExtensionActorLookup.Denied(ModuleAccessDecision.Forbidden("BUSINESS_OWNER_REQUIRED", "Solo el business owner puede administrar las extensiones."))
                ExtensionActorLookup.Found(ExtensionActor(role, serverOwner = false, companyCode = session.companyCode))
            }
        }
    }

    // PostgreSQL role of the user when it is a business owner of the company, else null.
    private fun businessOwnerRole(companyCode: String, userId: java.util.UUID): String? =
        connections.tenant(companyCode)?.use { tenant ->
            tenant.prepareStatement(
                """
                SELECT users.postgres_role
                FROM application_users users
                JOIN business_owner owner ON owner.user_id = users.id
                WHERE users.id = ? AND users.is_active = TRUE
                """.trimIndent()
            ).use { query ->
                query.setObject(1, userId)
                query.executeQuery().use { rows -> if (rows.next()) rows.getString(1) else null }
            }
        }

    private fun bearerToken(call: ApplicationCall): String? =
        call.request.headers[HttpHeaders.Authorization]?.takeIf { it.startsWith("Bearer ") }?.removePrefix("Bearer ")?.trim()?.takeIf { it.isNotEmpty() }
}
