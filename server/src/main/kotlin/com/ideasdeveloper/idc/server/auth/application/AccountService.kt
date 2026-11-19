package com.ideasdeveloper.idc.server.auth.application

import com.ideasdeveloper.idc.server.auth.infrastructure.database.LoginDatabases
import com.ideasdeveloper.idc.server.auth.infrastructure.security.ApplicationPasswordHasher
import com.ideasdeveloper.idc.server.auth.infrastructure.security.SessionTokenGenerator
import com.ideasdeveloper.idc.server.modules.MailException
import com.ideasdeveloper.idc.server.modules.MailSender
import com.ideasdeveloper.idc.server.modules.SmtpSettings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
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
import java.util.concurrent.ConcurrentHashMap
import javax.sql.DataSource

class AccountException(val code: String, message: String) : Exception(message)

/**
 * Regla del cambio de correo propio: cambiarlo exige la contrasena actual (si no, una sesion robada
 * redirigiria "olvide mi contrasena" a otro correo). Comparacion sin mayusculas ni espacios.
 */
object EmailChangeRule {
    // Solo A-Z pasan a minuscula: lowercase() de Unicode trata, por ejemplo, el signo Kelvin (U+212A) como "k".
    private fun normalize(email: String): String =
        email.trim().map { if (it in 'A'..'Z') it + 32 else it }.joinToString("")

    fun isChange(storedEmail: String?, requestedEmail: String): Boolean =
        normalize(storedEmail.orEmpty()) != normalize(requestedEmail)

    /**
     * Lanza PASSWORD_REQUIRED, INVALID_PASSWORD o TOO_MANY_ATTEMPTS; [passwordMatches] solo se llama si el
     * correo cambia y hay contrasena. Con [attempts] y [attemptKey] cuenta los fallos por usuario.
     */
    fun check(
        storedEmail: String?,
        requestedEmail: String,
        currentPassword: String?,
        attempts: PasswordAttemptLimiter? = null,
        attemptKey: String = "",
        passwordMatches: (String) -> Boolean,
    ) {
        if (!isChange(storedEmail, requestedEmail)) return
        PasswordConfirmation.require(
            currentPassword, attempts, attemptKey, "Escribe tu contrasena actual para cambiar el correo.", passwordMatches,
        )
    }
}

/** Confirmacion de la contrasena actual antes de un cambio sensible, con tope de fallos por usuario. */
object PasswordConfirmation {
    /** El business owner confirma la contrasena al cambiar su correo o al poner una contrasena nueva. */
    fun ownerChangeNeedsPassword(storedEmail: String?, requestedEmail: String, newPassword: String): Boolean =
        newPassword.isNotEmpty() || EmailChangeRule.isChange(storedEmail, requestedEmail)

    fun require(
        currentPassword: String?,
        attempts: PasswordAttemptLimiter?,
        attemptKey: String,
        requiredMessage: String,
        passwordMatches: (String) -> Boolean,
    ) {
        if (currentPassword.isNullOrEmpty()) throw AccountException("PASSWORD_REQUIRED", requiredMessage)
        // El bloqueo se mira antes de comparar: un usuario bloqueado no sirve de oraculo de contrasenas.
        if (attempts != null && attempts.isBlocked(attemptKey)) {
            throw AccountException("TOO_MANY_ATTEMPTS", "Demasiados intentos. Espera unos minutos e intentalo de nuevo.")
        }
        if (!passwordMatches(currentPassword)) {
            attempts?.recordFailure(attemptKey)
            throw AccountException("INVALID_PASSWORD", "La contrasena actual no es correcta.")
        }
    }
}

/**
 * Tope de intentos FALLIDOS de contrasena actual por usuario y empresa (en memoria: la tabla de throttles
 * del reset tiene ventana de 24 h y su propio tope, y compartirla mezclaria ambos contadores). Un acierto no cuenta.
 */
class PasswordAttemptLimiter(
    private val maxFailures: Int = MaxFailures,
    private val window: Duration = Window,
    private val clock: () -> Instant = Instant::now,
) {
    private class Entry(val windowStart: Instant, var failures: Int)

    private val entries = ConcurrentHashMap<String, Entry>()

    companion object {
        const val MaxFailures = 5
        val Window: Duration = Duration.ofMinutes(15)
        const val MaxEntries = 10_000
    }

    fun isBlocked(key: String): Boolean {
        val entry = entries[key] ?: return false
        synchronized(entry) { return entry.failures >= maxFailures && live(entry, clock()) }
    }

    fun recordFailure(key: String) {
        val now = clock()
        if (entries.size >= MaxEntries) entries.entries.removeIf { !live(it.value, now) }
        if (entries.size >= MaxEntries && !entries.containsKey(key)) return
        val entry = entries.compute(key) { _, old -> if (old == null || !live(old, now)) Entry(now, 0) else old }!!
        synchronized(entry) { entry.failures++ }
    }

    private fun live(entry: Entry, now: Instant) = entry.windowStart.plus(window).isAfter(now)
}

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
    // Verifica la contrasena actual como el login (LoginDatabases.verifyPassword); sin ella no se puede cambiar el correo.
    private val verifyPassword: (companyCode: String?, username: String, password: String) -> Boolean = { _, _, _ -> false },
    private val clock: () -> Instant = Instant::now,
    // Fallos de contrasena actual por usuario; se comparte con CompanySelfService para que ambas rutas sumen en el mismo contador.
    private val passwordAttempts: PasswordAttemptLimiter = PasswordAttemptLimiter(),
) {
    private val log = LoggerFactory.getLogger(AccountService::class.java)
    private val tokens = SessionTokenGenerator()
    private val random = SecureRandom()
    private val background = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    // Solo para duenos del servidor (base central, sin la tabla por empresa); las empresas usan la base.
    private val ownerThrottles = MemoryThrottles()
    private val codePattern = Regex("[0-9]{6}")
    private val identifierPattern = Regex("[a-z][a-z0-9_]{0,62}")

    companion object {
        val CodeLifetime: Duration = Duration.ofMinutes(15)
        val ResendAfter: Duration = Duration.ofMinutes(1)
        const val MaxAttempts = 5
        val Window: Duration = Duration.ofHours(24)
        const val MaxCodesPerWindow = 5
        const val MaxFailuresPerWindow = 15
        const val MaxThrottleEntries = 10_000
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

    fun updateProfile(
        accessToken: String,
        companyCode: String?,
        displayName: String,
        email: String,
        currentPassword: String? = null,
    ): AccountProfile {
        UserProfileRules.problem(displayName, email)?.let { throw AccountException("INVALID_PROFILE", it.replaceFirstChar(Char::uppercase)) }
        return database(companyCode).use { connection ->
            val account = sessionAccount(connection, accessToken, companyCode == null)
                ?: throw AccountException("INVALID_SESSION", "La sesion no es valida.")
            // Cambiar el correo propio exige la contrasena actual; cambiar solo el nombre no.
            val emailChanges = EmailChangeRule.isChange(account.email, email)
            val attemptKey = "${companyCode.orEmpty()}/${account.username}"
            EmailChangeRule.check(account.email, email, currentPassword, passwordAttempts, attemptKey) { password -> verifyPassword(companyCode, account.username, password) }
            // El cambio de correo y el cierre de las demas sesiones van en una sola transaccion.
            connection.autoCommit = false
            try {
                connection.prepareStatement("UPDATE application_users SET display_name = ?, email = ? WHERE id = ?").use { update ->
                    update.setString(1, displayName.trim())
                    update.setString(2, email.trim())
                    update.setObject(3, account.id)
                    update.executeUpdate()
                }
                // Con otro correo, las demas sesiones (la de un posible atacante) se cierran; la actual se conserva.
                if (emailChanges) {
                    connection.prepareStatement(
                        "UPDATE application_sessions SET revoked_at = CURRENT_TIMESTAMP WHERE user_id = ? AND revoked_at IS NULL AND token_hash <> ?"
                    ).use { revoke ->
                        revoke.setObject(1, account.id)
                        revoke.setString(2, tokens.hash(accessToken))
                        revoke.executeUpdate()
                    }
                }
                connection.commit()
            } catch (failure: Throwable) {
                runCatching { connection.rollback() }
                throw failure
            } finally {
                connection.autoCommit = true
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
        // Todo el trabajo (consulta, codigo y correo SMTP) va en segundo plano: la respuesta tarda
        // lo mismo exista o no el usuario y tenga o no correo.
        background.launch {
            try {
                issueResetCode(settings, name, companyCode)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                log.warn("Password reset request for {} failed", name, failure)
            }
        }
    }

    private fun issueResetCode(settings: SmtpSettings, name: String, companyCode: String?) {
        val (account, code) = databaseOrNull(companyCode)?.use { connection ->
            val account = accountByUsername(connection, name, companyCode == null)
                ?.takeIf { it.email != null && UserProfileRules.validEmail(it.email) } ?: return@use null
            val now = clock()
            // Tope acumulado en la ventana: no se reinicia al pedir otro codigo y la espera crece con cada envio.
            // La fila solo se crea si el usuario existe. La plaza se reserva con una sola sentencia atomica
            // y se devuelve si no se emite.
            val store = throttles(connection, companyCode)
            val reservation = store.reserve(name, now) ?: return@use null
            val previousLast = reservation.previousLast
            var kept = false
            try {
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
                kept = true
                account to code
            } finally {
                // Si la conexion quedo en error, devolver la plaza no debe tapar la excepcion original.
                if (!kept) runCatching { store.release(name, previousLast) }
                    .onFailure { log.warn("No se pudo devolver la plaza de envio de {}", name, it) }
            }
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
            log.warn("Password reset code for {} could not be sent", account.username, failure)
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
            val store = throttles(connection, companyCode)
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
            // Tope acumulado de intentos fallidos por usuario en la ventana; pedir otro codigo no lo reinicia.
            // Se reserva el intento ANTES de comparar, con suma y chequeo en una sola sentencia (atomico entre
            // peticiones concurrentes); la fila se crea unicamente si el usuario existe y tiene un codigo vigente.
            if (!store.claimFailure(name, clock())) throw invalid
            // Igual para los intentos de este codigo: si ya se agotaron (carrera), no se compara.
            val remaining = connection.prepareStatement(
                "UPDATE password_reset_codes SET attempts = attempts + 1 WHERE user_id = ? AND attempts < ? RETURNING attempts"
            ).use { update ->
                update.setObject(1, account.id)
                update.setInt(2, MaxAttempts)
                update.executeQuery().use { rows -> rows.next() }
            }
            if (!remaining) {
                store.refundFailure(name)
                deleteCode(connection, account.id)
                throw invalid
            }
            if (!MessageDigest.isEqual(hash.toByteArray(), codeHash(account.id, code.trim()).toByteArray())) throw invalid
            deleteCode(connection, account.id)
            setPassword(connection, account, companyCode, newPassword)
            store.clear(name)
        }
    }

    // Plaza reservada para emitir un codigo; guarda el ultimo envio anterior para devolverla si no se emite.
    private class Reservation(val previousLast: Instant?)

    // Contadores acumulados de una ventana de 24 h por usuario.
    private interface ThrottleStore {
        /** Reserva una plaza de envio, o null si se agotaron los codigos de la ventana o aun no toca reenviar. */
        fun reserve(name: String, now: Instant): Reservation?
        fun release(name: String, previousLast: Instant?)
        /** Suma un intento fallido si no se alcanzo el tope de la ventana (una sola operacion); false si ya se agoto. */
        fun claimFailure(name: String, now: Instant): Boolean
        fun refundFailure(name: String)
        fun clear(name: String)
    }

    private fun throttles(connection: Connection, companyCode: String?): ThrottleStore =
        if (companyCode == null) ownerThrottles else DatabaseThrottles(connection)

    // Empresas: tabla account_reset_throttle de su base (tenant V008); sobrevive a reinicios.
    // Cada operacion es una sola sentencia, asi que la reserva es atomica entre peticiones y servidores.
    private class DatabaseThrottles(private val connection: Connection) : ThrottleStore {
        private fun utc(instant: Instant): OffsetDateTime = instant.atOffset(ZoneOffset.UTC)

        override fun reserve(name: String, now: Instant): Reservation? {
            // Crea la fila o reinicia los contadores si la ventana ya vencio.
            connection.prepareStatement(
                """
                INSERT INTO account_reset_throttle (user_name, window_start, issued, failures, last_issued)
                VALUES (?, ?, 0, 0, NULL)
                ON CONFLICT (user_name) DO UPDATE
                SET window_start = EXCLUDED.window_start, issued = 0, failures = 0, last_issued = NULL
                WHERE account_reset_throttle.window_start <= ?
                """.trimIndent()
            ).use { upsert ->
                upsert.setString(1, name)
                upsert.setObject(2, utc(now))
                upsert.setObject(3, utc(now.minus(Window)))
                upsert.executeUpdate()
            }
            // La espera entre envios crece con cada uno: ResendAfter * 2^(emitidos - 1), con tope de 2^9.
            return connection.prepareStatement(
                """
                WITH old AS (SELECT user_name, last_issued FROM account_reset_throttle WHERE user_name = ? FOR UPDATE)
                UPDATE account_reset_throttle t
                SET issued = t.issued + 1, last_issued = ?
                FROM old
                WHERE t.user_name = old.user_name
                  AND t.issued < ?
                  AND (t.last_issued IS NULL
                       OR t.last_issued + make_interval(secs => ? * (1 << (least(greatest(t.issued, 1), 10) - 1))) <= ?)
                RETURNING old.last_issued
                """.trimIndent()
            ).use { update ->
                update.setString(1, name)
                update.setObject(2, utc(now))
                update.setInt(3, MaxCodesPerWindow)
                update.setDouble(4, ResendAfter.seconds.toDouble())
                update.setObject(5, utc(now))
                update.executeQuery().use { rows ->
                    if (rows.next()) Reservation(rows.getObject(1, OffsetDateTime::class.java)?.toInstant()) else null
                }
            }
        }

        override fun release(name: String, previousLast: Instant?) {
            connection.prepareStatement(
                "UPDATE account_reset_throttle SET issued = greatest(issued - 1, 0), last_issued = ? WHERE user_name = ?"
            ).use { update ->
                update.setObject(1, previousLast?.let { utc(it) }, java.sql.Types.TIMESTAMP_WITH_TIMEZONE)
                update.setString(2, name)
                update.executeUpdate()
            }
        }

        override fun claimFailure(name: String, now: Instant): Boolean = connection.prepareStatement(
            """
            INSERT INTO account_reset_throttle (user_name, window_start, issued, failures, last_issued)
            VALUES (?, ?, 0, 1, NULL)
            ON CONFLICT (user_name) DO UPDATE SET
                window_start = CASE WHEN account_reset_throttle.window_start <= ? THEN EXCLUDED.window_start ELSE account_reset_throttle.window_start END,
                issued = CASE WHEN account_reset_throttle.window_start <= ? THEN 0 ELSE account_reset_throttle.issued END,
                failures = CASE WHEN account_reset_throttle.window_start <= ? THEN 1 ELSE account_reset_throttle.failures + 1 END,
                last_issued = CASE WHEN account_reset_throttle.window_start <= ? THEN NULL ELSE account_reset_throttle.last_issued END
            WHERE account_reset_throttle.window_start <= ? OR account_reset_throttle.failures < ?
            RETURNING failures
            """.trimIndent()
        ).use { upsert ->
            // Suma y chequeo del tope en una sola sentencia: sin fila devuelta, el tope ya se alcanzo.
            val expired = utc(now.minus(Window))
            upsert.setString(1, name)
            upsert.setObject(2, utc(now))
            for (index in 3..7) upsert.setObject(index, expired)
            upsert.setInt(8, MaxFailuresPerWindow)
            upsert.executeQuery().use { rows -> rows.next() }
        }

        override fun refundFailure(name: String) {
            connection.prepareStatement(
                "UPDATE account_reset_throttle SET failures = greatest(failures - 1, 0) WHERE user_name = ?"
            ).use { update ->
                update.setString(1, name)
                update.executeUpdate()
            }
        }

        override fun clear(name: String) {
            connection.prepareStatement("DELETE FROM account_reset_throttle WHERE user_name = ?").use { delete ->
                delete.setString(1, name)
                delete.executeUpdate()
            }
        }
    }

    // Duenos del servidor: la base central no tiene la tabla por empresa, asi que sus contadores
    // siguen en memoria (pocos usuarios; se pierden al reiniciar).
    private inner class MemoryThrottles : ThrottleStore {
        private inner class Entry(val windowStart: Instant) {
            var issued = 0
            var failures = 0
            var lastIssued: Instant? = null
        }

        private val entries = ConcurrentHashMap<String, Entry>()

        private fun current(name: String, now: Instant, create: Boolean): Entry? {
            val live = entries[name]?.takeIf { !it.windowStart.plus(Window).isBefore(now) }
            if (live != null || !create) return live
            if (entries.size >= MaxThrottleEntries) {
                entries.entries.removeIf { it.value.windowStart.plus(Window).isBefore(now) }
                if (entries.size >= MaxThrottleEntries) return null
            }
            return entries.compute(name) { _, old ->
                if (old == null || old.windowStart.plus(Window).isBefore(now)) Entry(now) else old
            }
        }

        override fun reserve(name: String, now: Instant): Reservation? {
            val entry = current(name, now, create = true) ?: return null
            synchronized(entry) {
                if (entry.issued >= MaxCodesPerWindow) return null
                val last = entry.lastIssued
                if (last != null && last.plus(ResendAfter.multipliedBy(1L shl (entry.issued.coerceIn(1, 10) - 1))).isAfter(now)) return null
                entry.issued++
                entry.lastIssued = now
                return Reservation(last)
            }
        }

        override fun release(name: String, previousLast: Instant?) {
            val entry = entries[name] ?: return
            synchronized(entry) {
                // Nunca negativo: la ventana pudo reiniciarse entre reservar y devolver.
                entry.issued = (entry.issued - 1).coerceAtLeast(0)
                entry.lastIssued = previousLast
            }
        }

        override fun claimFailure(name: String, now: Instant): Boolean {
            // Con la tabla llena no se cuenta (igual que antes), pero tampoco se bloquea al dueno.
            val entry = current(name, now, create = true) ?: return true
            synchronized(entry) {
                if (entry.failures >= MaxFailuresPerWindow) return false
                entry.failures++
                return true
            }
        }

        override fun refundFailure(name: String) {
            val entry = entries[name] ?: return
            synchronized(entry) { entry.failures = (entry.failures - 1).coerceAtLeast(0) }
        }

        override fun clear(name: String) {
            entries.remove(name)
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
