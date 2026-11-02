package com.ideasdeveloper.idc.modules.agentes

import com.ideasdeveloper.idc.server.auth.infrastructure.security.SessionTokenGenerator
import java.sql.Connection
import java.sql.ResultSet
import java.sql.Timestamp
import java.sql.Types
import java.time.Duration
import java.time.Instant
import java.util.UUID

data class AgentToken(
    val id: UUID,
    val userId: UUID,
    val serverOwner: Boolean,
    val userName: String,
    val name: String,
    val hint: String,
    // Modules the agent may use; null = every module the user can use.
    val allowedModules: Set<String>?,
    val readOnly: Boolean,
    val createdAt: Instant,
    val expiresAt: Instant?,
    val lastUsedAt: Instant?,
    val revokedAt: Instant?,
    val revokedBy: String?,
    // OAuth client id for connections authorized through OAuth (the client's name is [name]).
    val oauthClient: String? = null,
) {
    fun isActive(now: Instant = Instant.now()): Boolean = revokedAt == null && (expiresAt == null || expiresAt.isAfter(now))

    fun allowsModule(moduleId: String): Boolean = allowedModules == null || moduleId in allowedModules
}

data class OAuthTokens(val token: AgentToken, val accessToken: String, val refreshToken: String)

data class AgentActivity(val tool: String, val outcome: String, val detail: String, val createdAt: Instant)

// Who creates a token: a tenant user or a server owner in the server workspace.
data class AgentOwner(val userId: UUID, val serverOwner: Boolean, val name: String)

/**
 * Agent tokens: "idcmcp.<company>.<secret>". The company travels in the token so an agent only needs
 * the URL and one header; only the SHA-256 of the whole token is stored, so it is shown once.
 */
object AgentTokens {
    const val Prefix = "idcmcp"
    const val RefreshPrefix = "idcmcpr"
    private const val Columns =
        "id, user_id, server_owner, user_name, name, token_hint, allowed_modules, read_only, created_at, expires_at, last_used_at, revoked_at, revoked_by, oauth_client"
    private val companyPattern = Regex("[a-z][a-z0-9_]{0,62}")
    private val secretPattern = Regex("[A-Za-z0-9_-]{43}")
    private val generator = SessionTokenGenerator()

    // Company code of a well-formed token (access tokens by default, or [prefix]), or null.
    fun companyOf(token: String, prefix: String = Prefix): String? {
        val parts = token.split('.')
        if (parts.size != 3 || parts[0] != prefix || !companyPattern.matches(parts[1]) || !secretPattern.matches(parts[2])) return null
        return parts[1]
    }

    private fun newValue(prefix: String, companyCode: String) = "$prefix.$companyCode.${generator.generate().value}"

    fun hash(token: String): String = generator.hash(token)

    fun moduleEnabled(tenant: Connection): Boolean =
        tenant.prepareStatement("SELECT 1 FROM tenant_modules WHERE module_id = ? AND status = 'enabled'").use { query ->
            query.setString(1, AgentesModule.id)
            query.executeQuery().use { rows -> rows.next() }
        }

    /** Creates a token and returns it with the full value, which is not stored and cannot be shown again. */
    fun create(
        tenant: Connection,
        companyCode: String,
        owner: AgentOwner,
        name: String,
        allowedModules: Set<String>?,
        readOnly: Boolean,
        expiresAt: Instant?,
    ): Pair<AgentToken, String> {
        val secret = generator.generate().value
        val token = "$Prefix.$companyCode.$secret"
        val id = UUID.randomUUID()
        tenant.prepareStatement(
            """
            INSERT INTO agent_tokens (id, user_id, server_owner, user_name, name, token_hash, token_hint, allowed_modules, read_only, expires_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """.trimIndent()
        ).use { insert ->
            insert.setObject(1, id)
            insert.setObject(2, owner.userId)
            insert.setBoolean(3, owner.serverOwner)
            insert.setString(4, owner.name)
            insert.setString(5, name)
            insert.setString(6, hash(token))
            insert.setString(7, secret.take(6))
            if (allowedModules == null) insert.setNull(8, Types.ARRAY) else insert.setArray(8, tenant.createArrayOf("text", allowedModules.sorted().toTypedArray()))
            insert.setBoolean(9, readOnly)
            insert.setTimestamp(10, expiresAt?.let(Timestamp::from))
            insert.executeUpdate()
        }
        return find(tenant, id)!! to token
    }

    // Tokens of one user (newest first), or of everybody when [userId] is null.
    fun list(tenant: Connection, userId: UUID?): List<AgentToken> =
        tenant.prepareStatement(
            "SELECT $Columns FROM agent_tokens WHERE (CAST(? AS uuid) IS NULL OR user_id = ?) ORDER BY revoked_at IS NOT NULL, created_at DESC LIMIT 500"
        ).use { query ->
            query.setObject(1, userId)
            query.setObject(2, userId)
            query.executeQuery().use { rows -> buildList { while (rows.next()) add(rows.token()) } }
        }

    fun find(tenant: Connection, id: UUID): AgentToken? =
        tenant.prepareStatement("SELECT $Columns FROM agent_tokens WHERE id = ?").use { query ->
            query.setObject(1, id)
            query.executeQuery().use { rows -> if (rows.next()) rows.token() else null }
        }

    // The active token with this value, or null (unknown, revoked or expired; OAuth access tokens also expire on their own).
    fun resolve(tenant: Connection, token: String): AgentToken? =
        tenant.prepareStatement(
            "SELECT $Columns FROM agent_tokens WHERE token_hash = ? AND (access_expires_at IS NULL OR access_expires_at > CURRENT_TIMESTAMP)"
        ).use { query ->
            query.setString(1, hash(token))
            query.executeQuery().use { rows -> if (rows.next()) rows.token() else null }
        }?.takeIf { it.isActive() }

    /**
     * Creates a connection authorized through OAuth and returns it with its first access token
     * (valid [accessLifetime]) and refresh token. Neither value is stored.
     */
    fun createOAuth(
        tenant: Connection,
        companyCode: String,
        owner: AgentOwner,
        clientId: String,
        clientName: String,
        allowedModules: Set<String>?,
        readOnly: Boolean,
        expiresAt: Instant?,
        accessLifetime: Duration,
    ): OAuthTokens {
        val access = newValue(Prefix, companyCode)
        val refresh = newValue(RefreshPrefix, companyCode)
        val id = UUID.randomUUID()
        tenant.prepareStatement(
            """
            INSERT INTO agent_tokens (id, user_id, server_owner, user_name, name, token_hash, token_hint, allowed_modules, read_only, expires_at,
                                      oauth_client, refresh_hash, access_expires_at)
            VALUES (?, ?, ?, ?, ?, ?, 'oauth', ?, ?, ?, ?, ?, ?)
            """.trimIndent()
        ).use { insert ->
            insert.setObject(1, id)
            insert.setObject(2, owner.userId)
            insert.setBoolean(3, owner.serverOwner)
            insert.setString(4, owner.name)
            insert.setString(5, clientName.take(80).ifBlank { "Agente OAuth" })
            insert.setString(6, hash(access))
            if (allowedModules == null) insert.setNull(7, Types.ARRAY) else insert.setArray(7, tenant.createArrayOf("text", allowedModules.sorted().toTypedArray()))
            insert.setBoolean(8, readOnly)
            insert.setTimestamp(9, expiresAt?.let(Timestamp::from))
            insert.setString(10, clientId)
            insert.setString(11, hash(refresh))
            insert.setTimestamp(12, Timestamp.from(Instant.now().plus(accessLifetime)))
            insert.executeUpdate()
        }
        return OAuthTokens(find(tenant, id)!!, access, refresh)
    }

    /**
     * Exchanges a refresh token of an active OAuth connection for a new access token and a new
     * refresh token (the old one stops working). Null when the refresh token is unknown, already
     * used, or its connection was revoked or expired.
     */
    fun refresh(tenant: Connection, refreshToken: String, clientId: String, companyCode: String, accessLifetime: Duration): OAuthTokens? {
        val access = newValue(Prefix, companyCode)
        val refresh = newValue(RefreshPrefix, companyCode)
        val id = tenant.prepareStatement(
            """
            UPDATE agent_tokens
            SET token_hash = ?, refresh_hash = ?, access_expires_at = ?, last_used_at = CURRENT_TIMESTAMP
            WHERE refresh_hash = ? AND oauth_client = ? AND revoked_at IS NULL AND (expires_at IS NULL OR expires_at > CURRENT_TIMESTAMP)
            RETURNING id
            """.trimIndent()
        ).use { update ->
            update.setString(1, hash(access))
            update.setString(2, hash(refresh))
            update.setTimestamp(3, Timestamp.from(Instant.now().plus(accessLifetime)))
            update.setString(4, hash(refreshToken))
            update.setString(5, clientId)
            update.executeQuery().use { rows -> if (rows.next()) rows.getObject(1, UUID::class.java) else null }
        } ?: return null
        return OAuthTokens(find(tenant, id)!!, access, refresh)
    }

    // Revokes the OAuth connection of an access or refresh token issued to [clientId] (RFC 7009); false when unknown.
    fun revokeByValue(tenant: Connection, value: String, clientId: String, by: String): Boolean =
        tenant.prepareStatement(
            "UPDATE agent_tokens SET revoked_at = CURRENT_TIMESTAMP, revoked_by = ? WHERE (token_hash = ? OR refresh_hash = ?) AND oauth_client = ? AND revoked_at IS NULL"
        ).use { update ->
            update.setString(1, by)
            update.setString(2, hash(value))
            update.setString(3, hash(value))
            update.setString(4, clientId)
            update.executeUpdate() == 1
        }

    fun revoke(tenant: Connection, id: UUID, by: String): Boolean =
        tenant.prepareStatement("UPDATE agent_tokens SET revoked_at = CURRENT_TIMESTAMP, revoked_by = ? WHERE id = ? AND revoked_at IS NULL").use { update ->
            update.setString(1, by)
            update.setObject(2, id)
            update.executeUpdate() == 1
        }

    // Records the last use at most once a minute, so busy agents do not write on every call.
    fun touch(tenant: Connection, id: UUID) {
        tenant.prepareStatement(
            "UPDATE agent_tokens SET last_used_at = CURRENT_TIMESTAMP WHERE id = ? AND (last_used_at IS NULL OR last_used_at < CURRENT_TIMESTAMP - INTERVAL '1 minute')"
        ).use { update ->
            update.setObject(1, id)
            update.executeUpdate()
        }
    }

    fun log(tenant: Connection, tokenId: UUID, tool: String, outcome: String, detail: String = "") {
        tenant.prepareStatement("INSERT INTO agent_activity (token_id, tool, outcome, detail) VALUES (?, ?, ?, ?)").use { insert ->
            insert.setObject(1, tokenId)
            insert.setString(2, tool.take(128))
            insert.setString(3, outcome)
            insert.setString(4, detail.take(500))
            insert.executeUpdate()
        }
    }

    fun activity(tenant: Connection, tokenId: UUID, limit: Int): List<AgentActivity> =
        tenant.prepareStatement("SELECT tool, outcome, detail, created_at FROM agent_activity WHERE token_id = ? ORDER BY created_at DESC LIMIT ?").use { query ->
            query.setObject(1, tokenId)
            query.setInt(2, limit)
            query.executeQuery().use { rows ->
                buildList { while (rows.next()) add(AgentActivity(rows.getString(1), rows.getString(2), rows.getString(3), rows.getTimestamp(4).toInstant())) }
            }
        }

    // Keeps the activity of the last [days] days.
    fun purgeActivity(tenant: Connection, days: Int) {
        tenant.prepareStatement("DELETE FROM agent_activity WHERE created_at < CURRENT_TIMESTAMP - make_interval(days => ?)").use { delete ->
            delete.setInt(1, days)
            delete.executeUpdate()
        }
    }

    private fun ResultSet.token() = AgentToken(
        id = getObject("id", UUID::class.java),
        userId = getObject("user_id", UUID::class.java),
        serverOwner = getBoolean("server_owner"),
        userName = getString("user_name"),
        name = getString("name"),
        hint = getString("token_hint"),
        allowedModules = getArray("allowed_modules")?.let { array -> (array.array as Array<*>).map { it.toString() }.toSet() },
        readOnly = getBoolean("read_only"),
        createdAt = getTimestamp("created_at").toInstant(),
        expiresAt = getTimestamp("expires_at")?.toInstant(),
        lastUsedAt = getTimestamp("last_used_at")?.toInstant(),
        revokedAt = getTimestamp("revoked_at")?.toInstant(),
        revokedBy = getString("revoked_by"),
        oauthClient = getString("oauth_client"),
    )
}
