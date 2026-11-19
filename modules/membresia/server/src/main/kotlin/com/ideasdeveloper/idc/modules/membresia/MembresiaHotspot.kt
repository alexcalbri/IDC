package com.ideasdeveloper.idc.modules.membresia

import org.slf4j.LoggerFactory
import java.sql.Connection
import java.sql.Savepoint
import java.sql.SQLException
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneId
import java.util.UUID

/**
 * WiFi mientras la membresia esta vigente, con el contrato de pases del hotspot (hotspot_pass_sources y
 * hotspot_access_passes, migracion V004 del hotspot). Solo actua si el hotspot esta instalado en la empresa.
 * Cada funcion usa un savepoint cuando la conexion esta en transaccion, registra el fallo y nunca lo propaga:
 * la operacion de la membresia no depende del hotspot.
 */
object MembresiaHotspot {
    private val log = LoggerFactory.getLogger(MembresiaHotspot::class.java)

    fun sourceRef(membershipId: UUID): String = "membership:$membershipId"

    /** El hotspot de la empresa ya tiene el contrato de pases. */
    fun available(tenant: Connection): Boolean = try {
        tenant.prepareStatement(
            "SELECT to_regclass('public.hotspot_pass_sources') IS NOT NULL AND to_regclass('public.hotspot_access_passes') IS NOT NULL"
        ).use { query -> query.executeQuery().use { rows -> rows.next() && rows.getBoolean(1) } }
    } catch (failure: SQLException) {
        log.warn("No se pudo comprobar si el hotspot está disponible: {}", failure.message)
        false
    }

    /** Registra la fuente "membresia" (idempotente); el dueño la activa en Hotspot > Integraciones. */
    fun registerSource(tenant: Connection) {
        if (!available(tenant)) return
        guarded(tenant, "registrar la fuente de pases") { insertSource(tenant) }
    }

    /** Crea o actualiza el pase de la membresia; si no esta programada/activa o el plan no tiene WiFi, lo borra. */
    fun syncPass(tenant: Connection, membership: Membership, wifiAccess: Boolean) {
        if (!available(tenant)) return
        guarded(tenant, "sincronizar el pase de la membresía ${membership.id}") { upsert(tenant, membership, wifiAccess) }
    }

    fun removePass(tenant: Connection, membershipId: UUID) {
        if (!available(tenant)) return
        guarded(tenant, "quitar el pase de la membresía $membershipId") { delete(tenant, membershipId) }
    }

    /**
     * Sincroniza todas las membresias programadas o activas, paginando por keyset (ends_on, id) en lotes de [limit]
     * (tope de 20 lotes por corrida), y borra los pases huerfanos de la fuente (cancelada, vencida, sin WiFi, cliente archivado):
     * un borrado que fallo antes se repara en la corrida siguiente. Devuelve cuantas membresias proceso.
     */
    fun syncAll(tenant: Connection, today: LocalDate, limit: Int = 500): Int {
        if (!available(tenant)) return 0
        guarded(tenant, "registrar la fuente de pases") { insertSource(tenant) }
        var processed = 0
        var after: Pair<LocalDate, UUID>? = null
        repeat(MaxBatches) {
            val batch = try {
                MembresiaRepository.syncCandidates(tenant, limit, after)
            } catch (failure: SQLException) {
                log.warn("No se pudieron leer las membresías para sincronizar el hotspot: {}", failure.message)
                return processed
            }
            for ((membership, wifi) in batch) {
                // Una activa aun no marcada como vencida ya expiro: su pase deja de servir solo por valid_until.
                if (membership.endsOn.isBefore(today)) continue
                guarded(tenant, "sincronizar el pase de la membresía ${membership.id}") { upsert(tenant, membership, wifi) }
                processed++
            }
            if (batch.size < limit) {
                removeOrphans(tenant, limit)
                return processed
            }
            after = batch.last().first.let { it.endsOn to it.id }
        }
        removeOrphans(tenant, limit)
        return processed
    }

    private const val MaxBatches = 20

    private fun removeOrphans(tenant: Connection, limit: Int) {
        repeat(MaxBatches) {
            var deleted = 0
            guarded(tenant, "borrar los pases huérfanos") { deleted = MembresiaRepository.deleteOrphanPasses(tenant, limit) }
            if (deleted < limit) return
        }
    }

    private fun insertSource(tenant: Connection) {
        tenant.prepareStatement(
            "INSERT INTO hotspot_pass_sources (id, name, description) VALUES (?, ?, ?) ON CONFLICT (id) DO NOTHING"
        ).use { insert ->
            insert.setString(1, MembresiaModule.HotspotSourceId)
            insert.setString(2, MembresiaModule.DisplayName)
            insert.setString(3, "WiFi mientras la membresía está vigente")
            insert.executeUpdate()
        }
    }

    private fun upsert(tenant: Connection, membership: Membership, wifiAccess: Boolean) {
        // A prueba de fallos: solo una membresia programada o activa con WiFi tiene pase; cualquier otro caso lo borra.
        if (!wifiAccess || (membership.status != StoredStatus.Programada && membership.status != StoredStatus.Activa)) {
            delete(tenant, membership.id)
            return
        }
        insertSource(tenant)
        val zone = ZoneId.systemDefault()
        val from = OffsetDateTime.ofInstant(membership.startsOn.atStartOfDay(zone).toInstant(), zone)
        // El ultimo dia es inclusive: el pase vale hasta el inicio del dia siguiente.
        val until = OffsetDateTime.ofInstant(membership.endsOn.plusDays(1).atStartOfDay(zone).toInstant(), zone)
        tenant.prepareStatement(
            """
            INSERT INTO hotspot_access_passes (source_id, source_ref, customer_id, rule, valid_from, valid_until)
            VALUES (?, ?, ?, 'periodo', ?, ?)
            ON CONFLICT (source_id, source_ref) DO UPDATE SET customer_id = EXCLUDED.customer_id, rule = 'periodo',
                valid_from = EXCLUDED.valid_from, valid_until = EXCLUDED.valid_until, minutes_balance = NULL, updated_at = CURRENT_TIMESTAMP
            """.trimIndent()
        ).use { statement ->
            statement.setString(1, MembresiaModule.HotspotSourceId)
            statement.setString(2, sourceRef(membership.id))
            statement.setObject(3, membership.customerId)
            statement.setObject(4, from)
            statement.setObject(5, until)
            statement.executeUpdate()
        }
    }

    private fun delete(tenant: Connection, membershipId: UUID) {
        tenant.prepareStatement("DELETE FROM hotspot_access_passes WHERE source_id = ? AND source_ref = ?").use { statement ->
            statement.setString(1, MembresiaModule.HotspotSourceId)
            statement.setString(2, sourceRef(membershipId))
            statement.executeUpdate()
        }
    }

    // Savepoint si la conexion esta en transaccion, para que un fallo del hotspot no la deje abortada.
    private fun guarded(tenant: Connection, description: String, action: () -> Unit) {
        var savepoint: Savepoint? = null
        try {
            if (!tenant.autoCommit) savepoint = tenant.setSavepoint()
            action()
            savepoint?.let { tenant.releaseSavepoint(it) }
        } catch (failure: Exception) {
            log.warn("Hotspot: no se pudo {}: {}", description, failure.message)
            savepoint?.let {
                try {
                    tenant.rollback(it)
                } catch (_: SQLException) {
                    // La conexion ya no sirve; el llamador decide.
                }
            }
        }
    }
}
