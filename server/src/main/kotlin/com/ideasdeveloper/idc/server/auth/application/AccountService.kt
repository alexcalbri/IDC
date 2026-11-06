package com.ideasdeveloper.idc.server.auth.application

import com.ideasdeveloper.idc.server.auth.infrastructure.database.LoginDatabases
import com.ideasdeveloper.idc.server.auth.infrastructure.security.ApplicationPasswordHasher
import com.ideasdeveloper.idc.server.auth.infrastructure.security.SessionTokenGenerator
import com.ideasdeveloper.idc.server.modules.MailException
import com.ideasdeveloper.idc.server.modules.MailSender
import com.ideasdeveloper.idc.server.modules.SmtpSettings
import kotlinx.serialization.Serializable
import org.postgresql.ds.PGSimpleDataSource
import org.slf4j.LoggerFactory
import java.security.MessageDigest
import java.security.SecureRandom
import java.sql.Connection
import java.time.Duration
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.HexFormat
import java.util.UUID
import javax.sql.DataSource

class AccountException(val code: String, message: String) : Exception(message)

@Serializable
data class AccountProfile(
    val username: String,
    val displayName: String?,
    val email: String?,
    val profileComplete: Boolean,
    val userCode: String,
)

/** How a password is set for each kind of user; owners sign in with a database role (CompanyProvisioningService). */
class AccountPasswords(
    val serverOwner: (userId: UUID, password: String) -> Unit,
    val businessOwner: (companyCode: String, password: String) -> Unit,
)

/**
 * The signed-in user's own account: name and email (required for every user) and password recovery
 * with a code sent to that email. A company code means a company user (its tenant database); without
 * one, a server owner (central database), exactly like the login.
 */
class AccountService(
    private val central: DataSource,
    private val loginDatabases: LoginDatabases,
    private val mail: () -> SmtpSettings?,
    private val passwords: AccountPasswords,
    private val clock: () -> Instant = Instant::now,
) {
    private val log = LoggerFactory.getLogger(AccountService::class.java)
    private val tokens = SessionTokenGenerator()
    private val random = SecureRandom()
    private val codePattern = Regex("[0-9]{6}")
    private val identifierPattern = Regex("[a-z][a-z0-9_]{0,62}")

    companion object {
        val CodeLifetime: Duration = Duration.ofMinutes(15)
        val ResendAfter: Duration = Duration.ofMinutes(1)
        const val MaxAttempts = 5
    }

    private class Account(val id: UUID, val username: String, val displayName: String?, val email: String?, val userCode: String?) {
        fun profile(userCode: String) = AccountProfile(
            username, displayName, email, UserProfileRules.isComplete(displayName, email), userCode,
        )
    }

    fun profile(accessToken: String, companyCode: String?): AccountProfile = database(companyCode).use { connection ->
        val account = sessionAccount(connection, accessToken, companyCode == null)
            ?: throw AccountException("INVALID_SESSION", "La sesion no es valida.")
        account.profile(ApplicationUserCodes.ensure(connection, account.id))
    }

    fun updateProfile(accessToken: String, companyCode: String?, displayName: String, email: String): AccountProfile {
        UserProfileRules.problem(displayName, email)?.let { throw AccountException("INVALID_PROFILE", it.replaceFirstChar(Char::uppercase)) }
        return database(companyCode).use { connection ->
            val account = sessionAccount(connection, accessToken, companyCode == null)
                ?: throw AccountException("INVALID_SESSION", "La sesion no es valida.")
            connection.prepareStatement("UPDATE application_users SET display_name = ?, email = ? WHERE id = ?").use { update ->
                update.setString(1, displayName.trim())
                update.setString(2, email.trim())
                update.setObject(3, account.id)
                update.executeUpdate()
            }
            Account(account.id, account.username, displayName.trim(), email.trim(), account.userCode)
                .profile(ApplicationUserCodes.ensure(connection, account.id))
        }
    }

    /**
     * Emails a reset code when the user exists, is active and has an email. The answer never says
     * whether the user exists; it only fails when the server cannot send email at all.
     */
    fun requestPasswordReset(username: String, companyCode: String?) {
        val settings = mail()?.takeIf { it.isUsable }
            ?: throw AccountException("MAIL_NOT_CONFIGURED", "El servidor no tiene correo configurado. Pide al administrador que cambie tu contrasena.")
        val name = username.trim()
        if (!identifierPattern.matches(name) || (companyCode != null && !identifierPattern.matches(companyCode))) return
        val (account, code) = databaseOrNull(companyCode)?.use { connection ->
            val account = accountByUsername(connection, name, companyCode == null)
                ?.takeIf { it.email != null && UserProfileRules.validEmail(it.email) } ?: return@use null
            val now = clock()
            val recent = connection.prepareStatement("SELECT created_at FROM password_reset_codes WHERE user_id = ?").use { query ->
                query.setObject(1, account.id)
                query.executeQuery().use { rows -> if (rows.next()) rows.getObject(1, OffsetDateTime::class.java).toInstant() else null }
            }
            if (recent != null && recent.plus(ResendAfter).isAfter(now)) return@use null
            val code = (0 until 6).joinToString("") { random.nextInt(10).toString() }
            connection.prepareStatement(
                """
                INSERT INTO password_reset_codes (user_id, code_hash, expires_at, attempts, created_at)
                VALUES (?, ?, ?, 0, ?)
                ON CONFLICT (user_id) DO UPDATE SET code_hash = EXCLUDED.code_hash, expires_at = EXCLUDED.expires_at,
                    attempts = 0, created_at = EXCLUDED.created_at
                """.trimIndent()
            ).use { upsert ->
                upsert.setObject(1, account.id)
                upsert.setString(2, codeHash(account.id, code))
                upsert.setObject(3, now.plus(CodeLifetime).atOffset(ZoneOffset.UTC))
                upsert.setObject(4, now.atOffset(ZoneOffset.UTC))
                upsert.executeUpdate()
            }
            account to code
        } ?: return
        try {
            MailSender.sendText(
                settings,
                account.email!!,
                "Codigo para cambiar tu contrasena de IdeasCore",
                "Hola ${account.displayName ?: account.username}:\n\n" +
                    "Tu codigo para cambiar la contrasena del usuario ${account.username}" +
                    (companyCode?.let { " (empresa $it)" } ?: " (dueno del servidor)") + " es:\n\n    $code\n\n" +
                    "Vence en ${CodeLifetime.toMinutes()} minutos. Si no lo pediste, ignora este correo; tu contrasena no cambia.\n",
            )
        } catch (failure: MailException) {
            log.warn("Password reset code for {} could not be sent: {}", account.username, failure.message)
        }
    }

    /** Sets the new password when the code is right and still valid, and closes the user's sessions. */
    fun confirmPasswordReset(username: String, companyCode: String?, code: String, newPassword: String) {
        val invalid = AccountException("INVALID_CODE", "El codigo no es valido o ya vencio. Pide uno nuevo.")
        val name = username.trim()
        if (!identifierPattern.matches(name) || (companyCode != null && !identifierPattern.matches(companyCode))) throw invalid
        if (!codePattern.matches(code.trim())) throw invalid
        if (newPassword.length < 12) throw AccountException("WEAK_PASSWORD", "La contrasena debe tener al menos 12 caracteres.")
        // Server owners sign in to PostgreSQL itself, with the installer's rule.
        if (companyCode == null && (newPassword.length < 16 || newPassword.any { it !in ' '..'~' })) {
            throw AccountException("WEAK_PASSWORD", "La contrasena del dueno del servidor debe tener al menos 16 caracteres ASCII.")
        }
        val connection = databaseOrNull(companyCode) ?: throw invalid
        connection.use {
            val account = accountByUsername(connection, name, companyCode == null) ?: throw invalid
            val (hash, expiresAt, attempts) = connection.prepareStatement(
                "SELECT code_hash, expires_at, attempts FROM password_reset_codes WHERE user_id = ?"
            ).use { query ->
                query.setObject(1, account.id)
                query.executeQuery().use { rows ->
                    if (rows.next()) Triple(rows.getString(1), rows.getObject(2, OffsetDateTime::class.java).toInstant(), rows.getInt(3)) else null
                }
            } ?: throw invalid
            if (attempts >= MaxAttempts || !expiresAt.isAfter(clock())) {
                deleteCode(connection, account.id)
                throw invalid
            }
            if (!MessageDigest.isEqual(hash.toByteArray(), codeHash(account.id, code.trim()).toByteArray())) {
                connection.prepareStatement("UPDATE password_reset_codes SET attempts = attempts + 1 WHERE user_id = ?").use { update ->
                    update.setObject(1, account.id)
                    update.executeUpdate()
                }
                throw invalid
            }
            deleteCode(connection, account.id)
            setPassword(connection, account, companyCode, newPassword)
        }
    }

    private fun setPassword(connection: Connection, account: Account, companyCode: String?, password: String) {
        if (companyCode == null) return passwords.serverOwner(account.id, password)
        val businessOwner = connection.prepareStatement("SELECT 1 FROM business_owner WHERE user_id = ?").use { query ->
            query.setObject(1, account.id)
            query.executeQuery().use { it.next() }
        }
        if (businessOwner) return passwords.businessOwner(companyCode, password)
        // Other users sign in with an application password.
        val hash = ApplicationPasswordHasher.hash(password)
        connection.prepareStatement(
            """
            INSERT INTO application_user_credentials (user_id, password_salt, password_hash, iterations, updated_at)
            VALUES (?, ?, ?, ?, CURRENT_TIMESTAMP)
            ON CONFLICT (user_id) DO UPDATE SET password_salt = EXCLUDED.password_salt, password_hash = EXCLUDED.password_hash,
                iterations = EXCLUDED.iterations, updated_at = CURRENT_TIMESTAMP
            """.trimIndent()
        ).use { upsert ->
            upsert.setObject(1, account.id)
            upsert.setString(2, hash.salt)
            upsert.setString(3, hash.hash)
            upsert.setInt(4, hash.iterations)
            upsert.executeUpdate()
        }
        connection.prepareStatement("UPDATE application_sessions SET revoked_at = CURRENT_TIMESTAMP WHERE user_id = ? AND revoked_at IS NULL").use { revoke ->
            revoke.setObject(1, account.id)
            revoke.executeUpdate()
        }
    }

    private fun deleteCode(connection: Connection, userId: UUID) {
        connection.prepareStatement("DELETE FROM password_reset_codes WHERE user_id = ?").use { delete ->
            delete.setObject(1, userId)
            delete.executeUpdate()
        }
    }

    private fun codeHash(userId: UUID, code: String): String =
        HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest("$userId:$code".toByteArray(Charsets.UTF_8)))

    // The central database for server owners (no company), or the active company's tenant database.
    private fun database(companyCode: String?): Connection =
        databaseOrNull(companyCode) ?: throw AccountException("COMPANY_NOT_FOUND", "La empresa no esta activa.")

    private fun databaseOrNull(companyCode: String?): Connection? {
        if (companyCode == null) return central.connection
        val databaseName = central.connection.use { connection ->
            connection.prepareStatement("SELECT database_name FROM companies WHERE code = ? AND is_active = TRUE").use { query ->
                query.setString(1, companyCode)
                query.executeQuery().use { rows -> if (rows.next()) rows.getString(1) else null }
            }
        } ?: return null
        val tenant = loginDatabases.tenantConfig(databaseName) ?: return null
        return PGSimpleDataSource().apply {
            setURL(tenant.jdbcUrl)
            user = tenant.user
            password = tenant.password
        }.connection
    }

    // In the central database only server owners have accounts that sign in.
    private fun ownerFilter(server: Boolean) = if (server) "AND EXISTS (SELECT 1 FROM server_owners owners WHERE owners.user_id = users.id)" else ""

    private fun sessionAccount(connection: Connection, accessToken: String, server: Boolean): Account? = connection.prepareStatement(
        """
        SELECT users.id, users.postgres_role, users.display_name, users.email, users.user_code
        FROM application_sessions sessions
        JOIN application_users users ON users.id = sessions.user_id
        WHERE sessions.token_hash = ?
          AND sessions.revoked_at IS NULL
          AND sessions.expires_at > CURRENT_TIMESTAMP
          AND users.is_active
          ${ownerFilter(server)}
        """.trimIndent()
    ).use { query ->
        query.setString(1, tokens.hash(accessToken))
        query.executeQuery().use { rows -> if (rows.next()) account(rows) else null }
    }

    private fun accountByUsername(connection: Connection, username: String, server: Boolean): Account? = connection.prepareStatement(
        """
        SELECT users.id, users.postgres_role, users.display_name, users.email, users.user_code
        FROM application_users users
        WHERE users.postgres_role = ? AND users.is_active
          ${ownerFilter(server)}
        """.trimIndent()
    ).use { query ->
        query.setString(1, username)
        query.executeQuery().use { rows -> if (rows.next()) account(rows) else null }
    }

    private fun account(rows: java.sql.ResultSet) = Account(
        id = rows.getObject(1, UUID::class.java),
        username = rows.getString(2),
        displayName = rows.getString(3),
        email = rows.getString(4),
        userCode = rows.getString(5),
    )
}
