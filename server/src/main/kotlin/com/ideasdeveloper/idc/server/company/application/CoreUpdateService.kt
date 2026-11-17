package com.ideasdeveloper.idc.server.company.application

import kotlinx.serialization.Serializable
import java.sql.Connection
import java.sql.ResultSet
import java.sql.SQLException
import java.time.Duration
import java.time.Instant
import java.util.UUID
import javax.sql.DataSource

@Serializable
data class CoreUpdateRequestDto(
    val id: Long,
    val targetVersion: String,
    val fromVersion: String,
    val status: String,
    val requestedAt: String,
    val startedAt: String? = null,
    val finishedAt: String? = null,
    val message: String? = null,
    val logTail: String? = null,
    val stalePending: Boolean = false,
)

@Serializable
data class CoreUpdateStatusResponse(
    val currentVersion: String,
    val latestVersion: String? = null,
    val updateAvailable: Boolean,
    val request: CoreUpdateRequestDto? = null,
)

/** Fila de core_update_requests tal como la guarda la base de datos. */
data class CoreUpdateRow(
    val id: Long,
    val targetVersion: String,
    val fromVersion: String,
    val status: String,
    val requestedBy: String,
    val requestedAt: Instant,
    val startedAt: Instant?,
    val finishedAt: Instant?,
    val message: String?,
    val logTail: String?,
)

/** Ya hay una solicitud pending o running (indice unico). */
class CoreUpdateActiveException : RuntimeException("Ya hay una actualizacion en curso.")

/** Acceso a datos de las solicitudes; se separa para poder probar la logica sin base de datos. */
interface CoreUpdateStore {
    fun latest(): CoreUpdateRow?

    /** Inserta una solicitud pending y la audita. Lanza [CoreUpdateActiveException] si ya hay una activa. */
    fun insertPending(targetVersion: String, fromVersion: String, requestedBy: String): CoreUpdateRow
}

sealed interface CoreUpdateOutcome {
    data class Accepted(val request: CoreUpdateRequestDto) : CoreUpdateOutcome
    data object CatalogUnavailable : CoreUpdateOutcome
    data object UpToDate : CoreUpdateOutcome
    data object InProgress : CoreUpdateOutcome
}

class CoreUpdateService(
    private val store: CoreUpdateStore,
    private val currentVersion: String,
    /** Ultima version Core del catalogo remoto, o null si no se pudo leer. */
    private val latestVersion: () -> String?,
    private val now: () -> Instant = Instant::now,
) {
    @Volatile private var cachedLatest: String? = null
    @Volatile private var cachedAt: Instant? = null

    /**
     * Ultima version del catalogo con cache: se refresca cada [CacheRefreshAfter] y, si la lectura
     * falla, se sigue devolviendo la ultima buena hasta [CacheKeepFor]. Solo se guardan lecturas buenas.
     */
    private fun cachedLatestVersion(): String? {
        val current = now()
        val at = cachedAt
        val value = cachedLatest
        if (value != null && at != null && Duration.between(at, current) < CacheRefreshAfter) return value
        val fresh = latestVersion()?.trim()?.takeIf { it.isNotEmpty() }
        if (fresh != null) {
            synchronized(this) {
                cachedLatest = fresh
                cachedAt = current
            }
            return fresh
        }
        return if (value != null && at != null && Duration.between(at, current) < CacheKeepFor) value else null
    }

    fun status(): CoreUpdateStatusResponse {
        val last = store.latest()
        // Con una solicitud activa no se consulta el catalogo: el destino ya esta decidido.
        val active = last != null && (last.status == "pending" || last.status == "running")
        val latest = if (active) last?.targetVersion else cachedLatestVersion()
        return CoreUpdateStatusResponse(
            currentVersion = currentVersion,
            latestVersion = latest,
            updateAvailable = isUpdateAvailable(currentVersion, latest),
            request = last?.let { toDto(it, now()) },
        )
    }

    fun request(requestedBy: String): CoreUpdateOutcome {
        // Lectura fresca (sin cache): es la que decide a que version se actualiza.
        val latest = latestVersion()?.trim()?.takeIf { it.isNotEmpty() } ?: return CoreUpdateOutcome.CatalogUnavailable
        synchronized(this) {
            cachedLatest = latest
            cachedAt = now()
        }
        if (!isUpdateAvailable(currentVersion, latest)) return CoreUpdateOutcome.UpToDate
        return try {
            CoreUpdateOutcome.Accepted(toDto(store.insertPending(latest, currentVersion, requestedBy), now()))
        } catch (_: CoreUpdateActiveException) {
            CoreUpdateOutcome.InProgress
        }
    }

    companion object {
        val StalePendingAfter: Duration = Duration.ofMinutes(3)
        val CacheRefreshAfter: Duration = Duration.ofSeconds(60)
        val CacheKeepFor: Duration = Duration.ofMinutes(10)
        private val semver = Regex("^[0-9]+\\.[0-9]+\\.[0-9]+$")

        /** Solo se ofrece si la ultima es semver valida y mayor que la actual (nunca un downgrade). */
        fun isUpdateAvailable(current: String, latest: String?): Boolean {
            val candidate = parse(latest) ?: return false
            val installed = parse(current) ?: return false
            return compare(candidate, installed) > 0
        }

        private fun parse(version: String?): List<Long>? {
            val value = version?.trim() ?: return null
            if (!semver.matches(value)) return null
            val parts = value.split('.').map { it.toLongOrNull() }
            return if (parts.any { it == null }) null else parts.filterNotNull()
        }

        private fun compare(left: List<Long>, right: List<Long>): Int {
            for (index in left.indices) {
                val difference = left[index].compareTo(right[index])
                if (difference != 0) return difference
            }
            return 0
        }

        /** pending por mas de 3 minutos: el ayudante root no esta instalado. */
        fun isStalePending(status: String, requestedAt: Instant, now: Instant): Boolean =
            status == "pending" && Duration.between(requestedAt, now) > StalePendingAfter

        fun toDto(row: CoreUpdateRow, now: Instant): CoreUpdateRequestDto = CoreUpdateRequestDto(
            id = row.id,
            targetVersion = row.targetVersion,
            fromVersion = row.fromVersion,
            status = row.status,
            requestedAt = row.requestedAt.toString(),
            startedAt = row.startedAt?.toString(),
            finishedAt = row.finishedAt?.toString(),
            message = row.message,
            logTail = row.logTail,
            stalePending = isStalePending(row.status, row.requestedAt, now),
        )
    }
}

/** Implementacion JDBC sobre la base central. */
class JdbcCoreUpdateStore(private val central: DataSource) : CoreUpdateStore {
    override fun latest(): CoreUpdateRow? = central.connection.use { connection ->
        connection.prepareStatement("$Select ORDER BY id DESC LIMIT 1").use { query ->
            query.executeQuery().use { rows -> if (rows.next()) rows.toRow() else null }
        }
    }

    override fun insertPending(targetVersion: String, fromVersion: String, requestedBy: String): CoreUpdateRow =
        central.connection.use { connection ->
            val previousAutoCommit = connection.autoCommit
            connection.autoCommit = false
            try {
                val row = connection.prepareStatement(
                    """
                    INSERT INTO core_update_requests (target_version, from_version, requested_by)
                    VALUES (?, ?, ?)
                    RETURNING id, target_version, from_version, status, requested_by, requested_at,
                              started_at, finished_at, message, log_tail
                    """.trimIndent()
                ).use { insert ->
                    insert.setString(1, targetVersion)
                    insert.setString(2, fromVersion)
                    insert.setString(3, requestedBy)
                    insert.executeQuery().use { rows ->
                        rows.next()
                        rows.toRow()
                    }
                }
                audit(connection, requestedBy, row)
                connection.commit()
                row
            } catch (failure: SQLException) {
                runCatching { connection.rollback() }
                // Indice unico: ya hay una solicitud pending o running.
                if (failure.sqlState == UniqueViolation) throw CoreUpdateActiveException()
                throw failure
            } catch (failure: Throwable) {
                runCatching { connection.rollback() }
                throw failure
            } finally {
                runCatching { connection.autoCommit = previousAutoCommit }
            }
        }

    private fun audit(connection: Connection, actorRole: String, row: CoreUpdateRow) {
        connection.prepareStatement(
            """
            INSERT INTO provisioning_audit_log (id, actor_role, action, company_code, module_id, details, actor_name, actor_email)
            SELECT ?, ?, 'server.core_update_requested', NULL, NULL, CAST(? AS jsonb), users.display_name, users.email
            FROM (SELECT 1) AS one
            LEFT JOIN application_users users ON users.postgres_role = ?
            """.trimIndent()
        ).use { insert ->
            insert.setObject(1, UUID.randomUUID())
            insert.setString(2, actorRole)
            // Las versiones ya pasaron la validacion semver, no necesitan escape.
            insert.setString(3, "{\"from\":\"${row.fromVersion}\",\"to\":\"${row.targetVersion}\",\"requestId\":${row.id}}")
            insert.setString(4, actorRole)
            insert.executeUpdate()
        }
    }

    private fun ResultSet.toRow() = CoreUpdateRow(
        id = getLong("id"),
        targetVersion = getString("target_version"),
        fromVersion = getString("from_version"),
        status = getString("status"),
        requestedBy = getString("requested_by"),
        requestedAt = getTimestamp("requested_at").toInstant(),
        startedAt = getTimestamp("started_at")?.toInstant(),
        finishedAt = getTimestamp("finished_at")?.toInstant(),
        message = getString("message"),
        logTail = getString("log_tail"),
    )

    private companion object {
        const val UniqueViolation = "23505"
        const val Select = "SELECT id, target_version, from_version, status, requested_by, requested_at, " +
            "started_at, finished_at, message, log_tail FROM core_update_requests"
    }
}
