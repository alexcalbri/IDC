package com.ideasdeveloper.idc.modules.clientes

import com.ideasdeveloper.idc.server.modules.CompanyLinkException
import com.ideasdeveloper.idc.server.modules.GroupCompany
import com.ideasdeveloper.idc.server.modules.ModuleAccessService
import com.ideasdeveloper.idc.server.modules.ModuleJob
import org.slf4j.LoggerFactory
import java.sql.Connection
import java.sql.SQLException
import java.sql.SQLNonTransientConnectionException
import java.sql.SQLTimeoutException
import java.sql.SQLTransientConnectionException
import java.time.Duration
import java.time.OffsetDateTime
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock

data class LinkJob(
    val from: GroupCompany,
    val to: GroupCompany,
    val status: String,
    val copied: Int,
    val merged: Int,
    val review: Int,
    val total: Int,
    val error: String,
)

data class LinkStatus(
    val linked: List<GroupCompany>,
    val candidates: List<GroupCompany>,
    val jobs: List<LinkJob>,
    val reviewCount: Int,
)

data class MergeItem(
    val id: UUID,
    // Company whose database holds the decision (where the merged customer was created).
    val company: GroupCompany,
    val keptId: UUID,
    val keptName: String,
    val keptCompany: String,
    val mergedId: UUID,
    val mergedName: String,
    val email: String,
    val status: String,
    val reason: String,
    val at: OffsetDateTime,
)

/**
 * Linked companies share every customer (Core company_links, purpose [LinkPurpose]). Each company
 * copies its own customers to the others in the background ([job]), in batches of 200 (an own
 * customer with shared = FALSE is one not copied yet), so thousands of customers never block a request
 * and the work survives a restart. A customer
 * whose email is already used by an active customer of another linked company is merged with it
 * (ClientesMerge); what cannot be copied or merged waits in "Por revisar".
 * Safety: a company only reads or writes the databases of the companies linked with it at that moment.
 */
object ClientesLinking {
    const val LinkPurpose: String = "clientes"

    private val log = LoggerFactory.getLogger(ClientesLinking::class.java)
    private const val BatchSize = 200
    private val RunBudgetNanos = TimeUnit.SECONDS.toNanos(20)
    // Author of what the background job does.
    private val SystemAuthor: Pair<UUID?, String> = null to "Sistema"

    /** What happened to one customer when it was copied to one company. */
    enum class ShareOutcome { Copied, Merged, Review, Skipped }

    // One lock per owner (named by the smallest code of its whole owner group, so it never changes when companies link or unlink),
    // shared by the job, shareNew, separate, retry and propagate. Each holder keeps it for one customer or one merge only.
    private val locks = ConcurrentHashMap<String, ReentrantLock>()

    // The key of an owner is cached: if the central database fails for a moment the lock must not fall back to another key.
    private val lockKeys = ConcurrentHashMap<String, String>()

    private fun lockFor(access: ModuleAccessService, code: String): ReentrantLock {
        val key = try {
            ((access.groupCompanies(code).map { it.code }) + code).minOrNull()!!.also { lockKeys[code] = it }
        } catch (failure: Exception) {
            lockKeys[code] ?: throw failure
        }
        return locks.computeIfAbsent(key) { ReentrantLock() }
    }

    private inline fun <T> locked(lock: ReentrantLock, block: () -> T): T {
        lock.lock()
        try {
            return block()
        } finally {
            lock.unlock()
        }
    }

    /** Runs [block] holding the group lock when it is free within [millis]; otherwise runs it anyway (never waits for minutes). */
    fun <T> withShortLock(access: ModuleAccessService, code: String, millis: Long = 1_500, block: () -> T): T {
        val lock = lockFor(access, code)
        val acquired = lock.tryLock(millis, TimeUnit.MILLISECONDS)
        try {
            return block()
        } finally {
            if (acquired) lock.unlock()
        }
    }

    // ---- Pure rules (tested without a database) ---------------------------------------------------

    /** Own customers not shared yet, oldest id first: what other modules inserted directly, or what a failed attempt left. Merged losers and open reviews are left out. */
    fun pendingSql(withCursor: Boolean, limit: Int = BatchSize): String =
        "SELECT ${ClientesSharing.RowColumns} FROM customers WHERE origin_company_code = '' AND NOT shared" +
            " AND NOT EXISTS (SELECT 1 FROM customer_merges m WHERE m.merged_id = customers.id AND m.status IN ('merged', 'review'))" +
            (if (withCursor) " AND id > ?" else "") + " ORDER BY id LIMIT $limit"

    /** A customer is marked shared only when it reached every other linked company (copied, merged or deliberately skipped). */
    fun fullyShared(outcomes: Collection<ShareOutcome>, expected: Int): Boolean =
        expected > 0 && outcomes.size == expected && outcomes.none { it == ShareOutcome.Review }

    /** Stable id of the "could not copy to [targetCode]" review of a customer: one row per target, never overwritten by another target. */
    fun copyReviewKey(customerId: UUID, targetCode: String): UUID =
        UUID.nameUUIDFromBytes("$customerId:$targetCode".toByteArray(Charsets.UTF_8))

    /** Whether the review row is a "could not copy" one rather than a pair of customers. */
    fun isCopyReview(mergedId: UUID, keptId: UUID, keptCompany: String): Boolean = keptId == copyReviewKey(mergedId, keptCompany)

    /** Whether this company's database has the V008 tables (its Clientes is up to date). */
    fun supportsLinking(tenant: Connection): Boolean =
        tenant.createStatement().use { statement ->
            statement.executeQuery("SELECT to_regclass('customer_merges') IS NOT NULL AND to_regclass('customer_link_jobs') IS NOT NULL").use { rows -> rows.next() && rows.getBoolean(1) }
        }

    /** Writes a row of customer_activity; [customerId] null is an event of the company. user_id is only valid in the author's own company. */
    fun activity(db: Connection, customerId: UUID?, kind: String, body: String, author: Pair<UUID?, String>, withUser: Boolean) {
        db.prepareStatement(
            "INSERT INTO customer_activity (id, customer_id, kind, body, user_id, author) VALUES (?, ?, ?, ?, ?, ?)"
        ).use { insert ->
            insert.setObject(1, UUID.randomUUID())
            insert.setObject(2, customerId)
            insert.setString(3, kind)
            insert.setString(4, body.take(5_000))
            insert.setObject(5, if (withUser) author.first else null)
            insert.setString(6, author.second)
            insert.executeUpdate()
        }
    }

    // ---- Status -----------------------------------------------------------------------------------

    fun status(access: ModuleAccessService, currentCode: String): LinkStatus {
        val linked = access.linkedCompanies(currentCode, LinkPurpose)
        val candidates = access.groupCompanies(currentCode).filter { company -> company.code != currentCode && linked.none { it.code == company.code } }
        val names = linked.associateBy { it.code }
        val jobs = mutableListOf<LinkJob>()
        var reviewCount = 0
        if (linked.size > 1) {
            linked.forEach { company ->
                runCatching {
                    access.tenantConnectionForCompany(company.code)?.use { db ->
                        if (!supportsLinking(db)) return@use
                        db.prepareStatement(
                            "SELECT target_company_code, status, copied, merged, review, total, error FROM customer_link_jobs ORDER BY target_company_code"
                        ).use { query ->
                            query.executeQuery().use { rows ->
                                while (rows.next()) {
                                    val target = names[rows.getString("target_company_code")]
                                    if (target != null) {
                                        jobs += LinkJob(company, target, rows.getString("status"), rows.getInt("copied"), rows.getInt("merged"), rows.getInt("review"), rows.getInt("total"), rows.getString("error"))
                                    }
                                }
                            }
                        }
                        reviewCount += count(db, "SELECT count(*) FROM customer_merges WHERE status = 'review'")
                    }
                }.onFailure { log.warn("Could not read the link status of company {}", company.code, it) }
            }
        }
        return LinkStatus(linked, candidates, jobs, reviewCount)
    }

    private fun count(db: Connection, sql: String): Int =
        db.createStatement().use { statement -> statement.executeQuery(sql).use { rows -> if (rows.next()) rows.getInt(1) else 0 } }

    // ---- Link and unlink --------------------------------------------------------------------------

    /**
     * Links [targetCode] with [currentCode] (the Core checks both belong to the same owner and are
     * active). Nothing is copied here: the job does it. Records the event and creates the pending jobs.
     */
    fun link(access: ModuleAccessService, tenant: Connection, currentCode: String, targetCode: String, author: Pair<UUID?, String>): String {
        val group = try {
            access.linkCompany(currentCode, targetCode, LinkPurpose, author.first?.toString() ?: author.second)
        } catch (failure: CompanyLinkException) {
            throw ClientesSharingException(failure.message ?: "No se pudo vincular la empresa.")
        }
        val names = group.associate { it.code to it.name }
        val targetName = names[targetCode] ?: targetCode
        locked(lockFor(access, currentCode)) {
            group.forEach { company ->
                runCatching {
                    ClientesSharing.onCompany(access, tenant, currentCode, company.code) { db ->
                        if (!supportsLinking(db)) return@onCompany
                        createJobs(db, group.filter { it.code != company.code })
                        when (company.code) {
                            currentCode -> activity(db, null, "linked", "Se vinculo con $targetName: los clientes se comparten entre las empresas vinculadas.", author, withUser = true)
                            targetCode -> activity(db, null, "linked", "Se vinculo con ${names[currentCode] ?: currentCode}: los clientes se comparten entre las empresas vinculadas.", author, withUser = false)
                            else -> Unit
                        }
                    }
                }.onFailure { log.warn("Could not prepare the link in company {} (the job fixes it)", company.code, it) }
            }
        }
        return "Se vinculo $targetName. Todos los clientes se estan compartiendo en segundo plano; puede tardar unos minutos y lo que no se pueda unir queda en Por revisar."
    }

    /**
     * Unlinks [targetCode] (it may be [currentCode], or a company that was deactivated) from the group;
     * the Core validates it. The copies of the unlinked company stop being visible on both sides (the
     * others hide what came from it even when its database does not open); merges already done are
     * kept. A database that cannot be opened is not an error: the job of that company hides what must
     * be hidden (the safety sweep).
     */
    fun unlink(access: ModuleAccessService, tenant: Connection, currentCode: String, targetCode: String, author: Pair<UUID?, String>): String {
        val before = access.linkedCompanies(currentCode, LinkPurpose)
        val targetName = before.firstOrNull { it.code == targetCode }?.name
            ?: runCatching { access.groupCompanies(currentCode).firstOrNull { it.code == targetCode }?.name }.getOrNull()
            ?: targetCode
        locked(lockFor(access, currentCode)) {
            try {
                access.unlinkCompany(currentCode, targetCode, LinkPurpose)
            } catch (failure: CompanyLinkException) {
                throw ClientesSharingException(failure.message ?: "No se pudo desvincular la empresa.")
            }
            val remaining = before.filter { it.code != targetCode }
            val currentName = before.firstOrNull { it.code == currentCode }?.name ?: currentCode
            // The unlinked company: everything it shared stops being visible and its jobs are removed.
            runCatching {
                ClientesSharing.onCompany(access, tenant, currentCode, targetCode) { db ->
                    if (!supportsLinking(db)) return@onCompany
                    db.prepareStatement("UPDATE customers SET shared = FALSE, updated_at = CURRENT_TIMESTAMP WHERE shared").use { it.executeUpdate() }
                    db.prepareStatement("DELETE FROM customer_link_jobs").use { it.executeUpdate() }
                    activity(db, null, "unlinked", "Se desvinculo de ${if (targetCode == currentCode) remaining.joinToString { it.name } else currentName}: los clientes de las otras empresas dejan de verse.", author, withUser = targetCode == currentCode)
                }
            }.onFailure { log.warn("Could not hide the copies in company {} (the job fixes it)", targetCode, it) }
            // The companies that stay: hide what came from the unlinked one (everything when one is left alone).
            remaining.forEach { company ->
                runCatching {
                    ClientesSharing.onCompany(access, tenant, currentCode, company.code) { db ->
                        if (!supportsLinking(db)) return@onCompany
                        if (remaining.size == 1) {
                            db.prepareStatement("UPDATE customers SET shared = FALSE, updated_at = CURRENT_TIMESTAMP WHERE shared").use { it.executeUpdate() }
                            db.prepareStatement("DELETE FROM customer_link_jobs").use { it.executeUpdate() }
                        } else {
                            db.prepareStatement("UPDATE customers SET shared = FALSE, updated_at = CURRENT_TIMESTAMP WHERE origin_company_code = ?").use { update ->
                                update.setString(1, targetCode)
                                update.executeUpdate()
                            }
                            db.prepareStatement("DELETE FROM customer_link_jobs WHERE target_company_code = ?").use { delete ->
                                delete.setString(1, targetCode)
                                delete.executeUpdate()
                            }
                        }
                        if (company.code == currentCode || targetCode == currentCode) {
                            activity(db, null, "unlinked", "Se desvinculo $targetName.", author, withUser = company.code == currentCode)
                        }
                    }
                }.onFailure { log.warn("Could not hide the copies in company {} (the job fixes it)", company.code, it) }
            }
        }
        return "Se desvinculo $targetName. Sus clientes dejan de verse entre las dos empresas; las fusiones ya hechas se conservan."
    }

    /** Targets that have no job row yet. */
    fun missingTargets(existing: Set<String>, wanted: List<String>): List<String> = wanted.filter { it !in existing }

    /** Own customers go back to "not copied yet" so the pending pass copies them to a destination that was just linked. */
    const val ResetSharedSql = "UPDATE customers SET shared = FALSE WHERE origin_company_code = '' AND shared"

    // Creates the job rows of new destinations; for them every own customer is marked to be copied again.
    private fun createJobs(db: Connection, targets: List<GroupCompany>) {
        val existing = db.createStatement().use { statement ->
            statement.executeQuery("SELECT target_company_code FROM customer_link_jobs").use { rows -> buildSet { while (rows.next()) add(rows.getString(1)) } }
        }
        val missing = missingTargets(existing, targets.map { it.code })
        if (missing.isEmpty()) return
        db.prepareStatement("INSERT INTO customer_link_jobs (target_company_code) VALUES (?) ON CONFLICT (target_company_code) DO NOTHING").use { insert ->
            missing.forEach { code ->
                insert.setString(1, code)
                insert.addBatch()
            }
            insert.executeBatch()
        }
        db.createStatement().use { it.executeUpdate(ResetSharedSql) }
    }

    // ---- Share a new customer ---------------------------------------------------------------------

    /**
     * Right after a customer is created in [currentCode], tries to copy it to the linked companies. If
     * the lock is busy (about 2 seconds), a company does not open or anything fails it does nothing
     * more: the pending pass of the job copies every own customer that is not shared yet.
     */
    fun shareNew(access: ModuleAccessService, tenant: Connection, currentCode: String, customerId: UUID) {
        try {
            if (!supportsLinking(tenant)) return
            val row = ClientesSharing.row(tenant, customerId) ?: return
            shareBatch(access, lockFor(access, currentCode), currentCode, tenant, listOf(row), Long.MAX_VALUE, HashMap(), lockWaitMillis = 2_000)
        } catch (failure: InterruptedException) {
            Thread.currentThread().interrupt()
        } catch (failure: Exception) {
            log.warn("Could not share the new customer {} right now; the job will", customerId, failure)
        }
    }

    // ---- Background job ---------------------------------------------------------------------------

    val job: ModuleJob = ModuleJob("link-sync", Duration.ofMinutes(1)) { context ->
        sync(context.access, context.tenant, context.companyCode)
    }

    /**
     * One bounded run (about 20 seconds) for company [code]: safety sweep, job rows, then the pending
     * pass. There is no cursor: an own customer with shared = FALSE is a customer not yet copied to the
     * current linked group (shared = TRUE on an own customer means exactly that; visibility only reads
     * shared on copies). A new destination, or a separated customer, sets shared = FALSE again so the
     * pass copies it. A customer left in review (customer_merges with a copyReviewKey, or the pair) is
     * excluded from the pass until the owner resolves it with Reintentar or Separar.
     */
    fun sync(access: ModuleAccessService, tenant: Connection, code: String) {
        if (!supportsLinking(tenant)) return
        val group = access.linkedCompanies(code, LinkPurpose)
        val others = group.filter { it.code != code }
        sweep(tenant, group.map { it.code }, hasOthers = others.isNotEmpty())
        syncJobRows(tenant, others)
        if (others.isEmpty()) return
        val deadline = System.nanoTime() + RunBudgetNanos
        try {
            sharePending(access, lockFor(access, code), code, tenant, deadline, HashMap())
        } catch (failure: ClientesSharingException) {
            log.warn("The pending pass of company {} stopped: {}", code, failure.message)
        }
    }

    // What no longer applies is hidden, whatever happened to the unlink request: copies of companies
    // that are not linked, merged losers, and own customers when there is nobody to share with.
    private fun sweep(tenant: Connection, linkedCodes: List<String>, hasOthers: Boolean) {
        val linked = tenant.createArrayOf("varchar", linkedCodes.toTypedArray())
        val hasStrangers = tenant.prepareStatement(
            "SELECT EXISTS (SELECT 1 FROM customers WHERE origin_company_code <> '' AND shared AND NOT (origin_company_code = ANY (?)))"
        ).use { query ->
            query.setArray(1, linked)
            query.executeQuery().use { it.next() && it.getBoolean(1) }
        }
        if (hasStrangers) {
            tenant.prepareStatement(
                "UPDATE customers SET shared = FALSE, updated_at = CURRENT_TIMESTAMP WHERE origin_company_code <> '' AND shared AND NOT (origin_company_code = ANY (?))"
            ).use { update ->
                update.setArray(1, linked)
                update.executeUpdate()
            }
        }
        if (!hasOthers) {
            tenant.createStatement().use { it.executeUpdate("UPDATE customers SET shared = FALSE WHERE origin_company_code = '' AND shared") }
        } else {
            // A customer merged into another is not copied again.
            tenant.createStatement().use {
                it.executeUpdate("UPDATE customers SET shared = FALSE WHERE id IN (SELECT merged_id FROM customer_merges WHERE status = 'merged') AND shared AND origin_company_code = ''")
            }
        }
    }

    // Jobs towards companies that are no longer linked disappear; missing ones are created (and every own customer is marked to be copied to them).
    private fun syncJobRows(tenant: Connection, others: List<GroupCompany>) {
        tenant.prepareStatement("DELETE FROM customer_link_jobs WHERE NOT (target_company_code = ANY (?))").use { delete ->
            delete.setArray(1, tenant.createArrayOf("varchar", others.map { it.code }.toTypedArray()))
            delete.executeUpdate()
        }
        createJobs(tenant, others)
    }

    // Counters of one batch per target: copied, merged, review.
    private class Tally {
        val counts = HashMap<String, IntArray>()
        fun add(target: String, outcome: ShareOutcome) {
            val c = counts.getOrPut(target) { IntArray(3) }
            when (outcome) {
                ShareOutcome.Copied -> c[0]++
                ShareOutcome.Merged -> c[1]++
                ShareOutcome.Review -> c[2]++
                ShareOutcome.Skipped -> Unit
            }
        }
    }

    // Writes the counters (and the error of the companies that did not open) every ~20 rows and at the end of a batch.
    private fun flush(tenant: Connection, tally: Tally, unavailable: Map<String, String>) {
        tally.counts.forEach { (target, c) ->
            tenant.prepareStatement(
                "UPDATE customer_link_jobs SET copied = copied + ?, merged = merged + ?, review = review + ?, updated_at = CURRENT_TIMESTAMP WHERE target_company_code = ?"
            ).use { update ->
                update.setInt(1, c[0])
                update.setInt(2, c[1])
                update.setInt(3, c[2])
                update.setString(4, target)
                update.executeUpdate()
            }
        }
        tally.counts.clear()
        unavailable.forEach { (target, message) ->
            tenant.prepareStatement("UPDATE customer_link_jobs SET status = 'error', error = ?, updated_at = CURRENT_TIMESTAMP WHERE target_company_code = ?").use { update ->
                update.setString(1, message.take(1_900))
                update.setString(2, target)
                update.executeUpdate()
            }
        }
    }

    private fun pendingRows(tenant: Connection, after: UUID?): List<ClientesSharing.Row> =
        tenant.prepareStatement(pendingSql(withCursor = after != null)).use { query ->
            if (after != null) query.setObject(1, after)
            query.executeQuery().use { result -> buildList { while (result.next()) add(ClientesSharing.readRow(result)) } }
        }

    // Batches of own customers not copied yet; each batch holds the lock and its connections only while it runs.
    private fun sharePending(
        access: ModuleAccessService,
        lock: ReentrantLock,
        code: String,
        tenant: Connection,
        deadline: Long,
        keyCache: MutableMap<String, List<ClientesMerge.ForeignKey>>,
    ) {
        var after: UUID? = null
        var started = false
        while (System.nanoTime() < deadline && !Thread.currentThread().isInterrupted) {
            val rows = pendingRows(tenant, after)
            if (rows.isEmpty()) {
                markDone(tenant)
                return
            }
            if (!started) {
                started = true
                tenant.prepareStatement(
                    """
                    UPDATE customer_link_jobs SET status = 'running', error = '',
                        total = (SELECT count(*) FROM customers WHERE origin_company_code = ''), updated_at = CURRENT_TIMESTAMP
                    WHERE status <> 'running'
                    """.trimIndent()
                ).use { it.executeUpdate() }
            }
            if (!shareBatch(access, lock, code, tenant, rows, deadline, keyCache, lockWaitMillis = 500)) return
            after = rows.last().id
            if (rows.size < BatchSize) {
                if (pendingRows(tenant, null).isEmpty()) markDone(tenant)
                return
            }
        }
    }

    // Nothing is pending: every destination is up to date.
    private fun markDone(tenant: Connection) {
        tenant.prepareStatement("UPDATE customer_link_jobs SET status = 'done', error = '', updated_at = CURRENT_TIMESTAMP WHERE status <> 'done'").use { it.executeUpdate() }
    }

    /**
     * Copies [rows] (own customers) to every other linked company. Order: wait for the group lock (short;
     * busy means stop), read the linked group once, open each destination once, process the rows
     * (checking the deadline per row), close everything, release the lock. A destination that does not
     * open (pool timeout, connection error) is skipped for this batch and the others go on; its
     * customers stay not shared and are retried later. Returns false when the pass should stop (lock busy,
     * deadline, nobody linked).
     */
    private fun shareBatch(
        access: ModuleAccessService,
        lock: ReentrantLock,
        code: String,
        tenant: Connection,
        rows: List<ClientesSharing.Row>,
        deadline: Long,
        keyCache: MutableMap<String, List<ClientesMerge.ForeignKey>>,
        lockWaitMillis: Long,
    ): Boolean {
        if (!lock.tryLock(lockWaitMillis, TimeUnit.MILLISECONDS)) return false
        try {
            val group = access.linkedCompanies(code, LinkPurpose)
            val others = group.filter { it.code != code }
            if (others.isEmpty()) return false
            val connections = LinkedHashMap<String, Connection>()
            val unavailable = LinkedHashMap<String, String>()
            try {
                others.forEach { company ->
                    try {
                        val db = connect(access, company)
                        if (supportsLinking(db)) {
                            connections[company.code] = db
                        } else {
                            runCatching { db.close() }
                            unavailable[company.code] = "Actualiza el modulo Clientes de ${company.name}."
                        }
                    } catch (failure: ClientesMerge.CompanyUnavailableException) {
                        unavailable[company.code] = failure.message.orEmpty()
                    }
                }
                val all = LinkedHashMap<String, Connection>().apply { put(code, tenant); putAll(connections) }
                val skips = connections.keys.associateWith { target -> skipSet(tenant, rows.map { it.id }, target) }
                val tally = Tally()
                var sinceFlush = 0
                for (row in rows) {
                    if (System.nanoTime() >= deadline || Thread.currentThread().isInterrupted) {
                        flush(tenant, tally, unavailable)
                        return false
                    }
                    shareRow(access, group, code, tenant, row, all, skips, unavailable, keyCache, tally)
                    if (++sinceFlush >= 20) {
                        flush(tenant, tally, unavailable)
                        sinceFlush = 0
                    }
                }
                flush(tenant, tally, unavailable)
                return true
            } finally {
                connections.values.forEach { runCatching { it.close() } }
            }
        } finally {
            lock.unlock()
        }
    }

    // One customer to every available destination; marks it shared only when all the linked companies have it.
    private fun shareRow(
        access: ModuleAccessService,
        group: List<GroupCompany>,
        code: String,
        tenant: Connection,
        row: ClientesSharing.Row,
        connections: MutableMap<String, Connection>,
        skips: Map<String, Set<UUID>>,
        unavailable: MutableMap<String, String>,
        keyCache: MutableMap<String, List<ClientesMerge.ForeignKey>>,
        tally: Tally,
    ) {
        val others = group.filter { it.code != code }
        val outcomes = mutableListOf<ShareOutcome>()
        for (company in others) {
            if (!connections.containsKey(company.code)) continue
            try {
                val outcome = copyGuarded(access, group, code, tenant, company, connections, row, skips[company.code].orEmpty(), keyCache)
                outcomes += outcome
                tally.add(company.code, outcome)
            } catch (failure: ClientesMerge.CompanyUnavailableException) {
                // That company (the destination or one involved in a merge) is skipped for the rest of the batch.
                val broken = failure.company ?: company.code
                unavailable[broken] = failure.message.orEmpty()
                connections.remove(broken)?.let { runCatching { it.close() } }
            }
        }
        if (fullyShared(outcomes, others.size)) {
            tenant.prepareStatement("UPDATE customers SET shared = TRUE WHERE id = ? AND origin_company_code = '' AND NOT shared").use { update ->
                update.setObject(1, row.id)
                update.executeUpdate()
            }
        }
    }

    /**
     * Opens the database of [company]. A pool timeout or a connection error is [ClientesMerge.CompanyUnavailableException]
     * (not a review): the work is retried later.
     */
    fun connect(access: ModuleAccessService, company: GroupCompany): Connection {
        val connection = try {
            access.tenantConnectionForCompany(company.code)
        } catch (failure: Exception) {
            if (isConnectionFailure(failure)) {
                log.warn("Company {} did not give a connection", company.code, failure)
                throw ClientesMerge.CompanyUnavailableException("No se pudo abrir la empresa ${company.name}; se reintentara mas tarde.", company.code)
            }
            throw failure
        }
        return connection ?: throw ClientesMerge.CompanyUnavailableException("No se pudo abrir la empresa ${company.name}; se reintentara mas tarde.", company.code)
    }

    /** Whether [failure] (or its cause) says the database could not be reached: pool timeout, SQLState 08xxx, refused or broken connection. */
    fun isConnectionFailure(failure: Throwable?): Boolean {
        var current = failure
        var depth = 0
        while (current != null && depth++ < 8) {
            if (current is SQLException) {
                if (current.sqlState?.startsWith("08") == true) return true
                if (current is SQLTransientConnectionException || current is SQLNonTransientConnectionException || current is SQLTimeoutException) return true
            }
            val message = current.message.orEmpty()
            if (message.contains("request timed out", ignoreCase = true) || message.contains("Connection is not available", ignoreCase = true) ||
                message.contains("Connection refused", ignoreCase = true) || message.contains("has been closed", ignoreCase = true)
            ) {
                return true
            }
            current = current.cause
        }
        return false
    }

    // One customer, one company: a failure becomes a review and the pass goes on; a company that cannot be reached is not a review.
    private fun copyGuarded(
        access: ModuleAccessService,
        group: List<GroupCompany>,
        code: String,
        tenant: Connection,
        target: GroupCompany,
        connections: Map<String, Connection>,
        row: ClientesSharing.Row,
        skip: Set<UUID>,
        keyCache: MutableMap<String, List<ClientesMerge.ForeignKey>>,
    ): ShareOutcome = try {
        copyOne(access, group, code, tenant, target, connections, row, skip, keyCache)
    } catch (failure: ClientesMerge.CompanyUnavailableException) {
        throw failure
    } catch (failure: InterruptedException) {
        throw failure
    } catch (failure: Exception) {
        if (isConnectionFailure(failure)) {
            log.warn("Lost the connection while copying customer {} to company {}", row.id, target.code, failure)
            throw ClientesMerge.CompanyUnavailableException("Se perdio la conexion con ${target.name}; se reintentara mas tarde.", target.code)
        }
        log.warn("Could not copy customer {} to company {}", row.id, target.code, failure)
        recordCopyReview(tenant, row, target.code, "", "No se pudo copiar a ${target.name}. El detalle esta en el registro del servidor; puedes reintentar.")
        ShareOutcome.Review
    }

    // Customers that must not be copied to [targetCode]: merged into another one, or whose copy the owner refused.
    private fun skipSet(tenant: Connection, ids: List<UUID>, targetCode: String): Set<UUID> =
        tenant.prepareStatement(
            """
            SELECT merged_id FROM customer_merges WHERE merged_id = ANY (?) AND status = 'merged'
            UNION
            SELECT merged_id FROM customer_merges WHERE kept_id = ANY (?) AND kept_company_code = ? AND status = 'separated'
            """.trimIndent()
        ).use { query ->
            query.setArray(1, tenant.createArrayOf("uuid", ids.toTypedArray()))
            query.setArray(2, tenant.createArrayOf("uuid", ids.map { copyReviewKey(it, targetCode) }.toTypedArray()))
            query.setString(3, targetCode)
            query.executeQuery().use { rows -> buildSet { while (rows.next()) add(rows.getObject(1, UUID::class.java)) } }
        }

    /**
     * Copies own customer [row] of [code] to [target] (its connection is in [connections], with the own one
     * under [code] and every other open company): merging with a customer that has the same email, or
     * leaving a review. A review leaves a mark in the own database (copyReviewKey) so the pending pass does
     * not process the customer again every minute until the owner resolves it.
     */
    private fun copyOne(
        access: ModuleAccessService,
        group: List<GroupCompany>,
        code: String,
        tenant: Connection,
        target: GroupCompany,
        connections: Map<String, Connection>,
        row: ClientesSharing.Row,
        skip: Set<UUID>,
        keyCache: MutableMap<String, List<ClientesMerge.ForeignKey>>,
    ): ShareOutcome {
        if (row.id in skip) return ShareOutcome.Skipped
        val targetDb = connections.getValue(target.code)
        val incoming = ClientesMerge.Candidate(row, code)
        if (row.status != "archived" && row.email.isNotBlank()) {
            val existing = duplicateOf(targetDb, target.code, row)
            if (existing != null) {
                val separated = existing.origin != code && separatedPair(access, group, tenant, connections, target.code, row.id, existing)
                when (val decision = ClientesMerge.decide(incoming, existing, group.map { it.code }.toSet(), separated)) {
                    is ClientesMerge.Decision.Skip -> return ShareOutcome.Skipped
                    is ClientesMerge.Decision.Merge -> {
                        val result = ClientesMerge.merge(
                            access, group, connections,
                            decision.loser, decision.winner, row.email, SystemAuthor, userCompany = null, keyCache = keyCache,
                        )
                        if (result is ClientesMerge.Result.Merged) return ShareOutcome.Merged
                        // The pair's row lives in the loser's company; when that is not this one, mark the own customer too.
                        if (decision.loser.origin != code) {
                            recordCopyReview(tenant, row, target.code, existing.row.displayName, "No se pudo fusionar con ${existing.row.displayName} de ${target.name}. Revisa la decision en Por revisar.")
                        }
                        return ShareOutcome.Review
                    }
                    is ClientesMerge.Decision.Copy -> Unit
                }
            }
        }
        val sameCode = targetDb.prepareStatement("SELECT display_name FROM customers WHERE customer_code = ? AND id <> ? LIMIT 1").use { query ->
            query.setString(1, row.code)
            query.setObject(2, row.id)
            query.executeQuery().use { rows -> if (rows.next()) rows.getString(1) else null }
        }
        if (sameCode != null) {
            recordCopyReview(tenant, row, target.code, sameCode, "Codigo repetido: el cliente $sameCode de ${target.name} ya usa el codigo ${row.code}. No se copio.")
            return ShareOutcome.Review
        }
        return try {
            ClientesSharing.upsertCopy(targetDb, row, origin = code, shared = true)
            ShareOutcome.Copied
        } catch (failure: SQLException) {
            if (isConnectionFailure(failure)) throw failure
            log.warn("Could not copy customer {} to company {}", row.id, target.code, failure)
            recordCopyReview(tenant, row, target.code, "", "No se pudo copiar a ${target.name}. El detalle esta en el registro del servidor; puedes reintentar.")
            ShareOutcome.Review
        }
    }

    // Active customer of [target] with the same email as [row] (own or a copy), the oldest first.
    private fun duplicateOf(target: Connection, targetCode: String, row: ClientesSharing.Row): ClientesMerge.Candidate? =
        target.prepareStatement(
            """
            SELECT ${ClientesSharing.RowColumns}, origin_company_code FROM customers
            WHERE lower(primary_email) = lower(?) AND id <> ? AND status <> 'archived' AND ${ClientesSharing.Visible}
            ORDER BY created_at, id LIMIT 1
            """.trimIndent()
        ).use { query ->
            query.setString(1, row.email)
            query.setObject(2, row.id)
            query.executeQuery().use { rows ->
                if (rows.next()) ClientesMerge.Candidate(ClientesSharing.readRow(rows), rows.getString("origin_company_code").ifBlank { targetCode }) else null
            }
        }

    // Whether the owner separated this pair, in the database of either customer's origin company (an open connection is reused).
    private fun separatedPair(
        access: ModuleAccessService,
        group: List<GroupCompany>,
        tenant: Connection,
        connections: Map<String, Connection>,
        targetCode: String,
        incomingId: UUID,
        existing: ClientesMerge.Candidate,
    ): Boolean {
        if (isSeparated(tenant, incomingId, existing.row.id)) return true
        connections[existing.origin]?.let { return supportsLinking(it) && isSeparated(it, incomingId, existing.row.id) }
        val company = group.firstOrNull { it.code == existing.origin } ?: return false
        return connect(access, company).use { origin -> supportsLinking(origin) && isSeparated(origin, incomingId, existing.row.id) }
    }

    private fun isSeparated(db: Connection, a: UUID, b: UUID): Boolean =
        db.prepareStatement(
            "SELECT 1 FROM customer_merges WHERE status = 'separated' AND ((merged_id = ? AND kept_id = ?) OR (merged_id = ? AND kept_id = ?))"
        ).use { query ->
            query.setObject(1, a)
            query.setObject(2, b)
            query.setObject(3, b)
            query.setObject(4, a)
            query.executeQuery().use { it.next() }
        }

    // A customer that cannot be copied to [targetCode]: kept_id is a stable id per customer and target (copyReviewKey), kept_company_code is the target.
    private fun recordCopyReview(db: Connection, row: ClientesSharing.Row, targetCode: String, otherName: String, reason: String) {
        db.prepareStatement(
            """
            INSERT INTO customer_merges (id, kept_id, kept_company_code, kept_name, merged_id, merged_name, email, status, reason)
            VALUES (?, ?, ?, ?, ?, ?, ?, 'review', ?)
            ON CONFLICT (merged_id, kept_id) DO UPDATE SET reason = EXCLUDED.reason, kept_name = EXCLUDED.kept_name
            WHERE customer_merges.status = 'review'
            """.trimIndent()
        ).use { insert ->
            insert.setObject(1, UUID.randomUUID())
            insert.setObject(2, copyReviewKey(row.id, targetCode))
            insert.setString(3, targetCode)
            insert.setString(4, otherName)
            insert.setObject(5, row.id)
            insert.setString(6, row.displayName)
            insert.setString(7, row.email)
            insert.setString(8, reason.take(1_900))
            insert.executeUpdate()
        }
    }

    // ---- Reviews, separate and retry --------------------------------------------------------------

    /** Decisions of every linked company: the ones to review first, then the merges done (they can be separated). */
    fun reviews(access: ModuleAccessService, currentCode: String, limit: Int = 100): List<MergeItem> {
        val group = access.linkedCompanies(currentCode, LinkPurpose)
        val names = group.associate { it.code to it.name }
        val cap = limit.coerceIn(1, 500)
        return group.flatMap { company ->
            runCatching {
                access.tenantConnectionForCompany(company.code)?.use { db ->
                    if (!supportsLinking(db)) return@use emptyList()
                    db.prepareStatement(
                        """
                        SELECT id, kept_id, kept_company_code, kept_name, merged_id, merged_name, email, status, reason, created_at
                        FROM customer_merges WHERE status IN ('review', 'merged')
                        ORDER BY CASE status WHEN 'review' THEN 0 ELSE 1 END, created_at DESC LIMIT ?
                        """.trimIndent()
                    ).use { query ->
                        query.setInt(1, cap)
                        query.executeQuery().use { rows ->
                            buildList {
                                while (rows.next()) {
                                    val keptCompany = rows.getString("kept_company_code")
                                    add(
                                        MergeItem(
                                            id = rows.getObject("id", UUID::class.java),
                                            company = company,
                                            keptId = rows.getObject("kept_id", UUID::class.java),
                                            keptName = rows.getString("kept_name"),
                                            keptCompany = names[keptCompany] ?: keptCompany,
                                            mergedId = rows.getObject("merged_id", UUID::class.java),
                                            mergedName = rows.getString("merged_name"),
                                            email = rows.getString("email"),
                                            status = rows.getString("status"),
                                            reason = rows.getString("reason"),
                                            at = rows.getObject("created_at", OffsetDateTime::class.java),
                                        )
                                    )
                                }
                            }
                        }
                    }
                }
            }.onFailure { log.warn("Could not read the reviews of company {}", company.code, it) }.getOrNull().orEmpty()
        }.sortedWith(compareBy<MergeItem> { if (it.status == "review") 0 else 1 }.thenByDescending { it.at }).take(cap)
    }

    private class MergeRow(
        val id: UUID,
        val keptId: UUID,
        val keptCompany: String,
        val mergedId: UUID,
        val email: String,
        val status: String,
        val moved: List<ClientesMerge.Moved>,
    ) {
        // A customer that could not be copied (see copyReviewKey) instead of two customers to merge.
        val copyProblem: Boolean get() = isCopyReview(mergedId, keptId, keptCompany)
    }

    private fun loadMerge(db: Connection, mergeId: UUID): MergeRow? =
        db.prepareStatement("SELECT kept_id, kept_company_code, merged_id, email, status, moved::text AS moved FROM customer_merges WHERE id = ?").use { query ->
            query.setObject(1, mergeId)
            query.executeQuery().use { rows ->
                if (!rows.next()) return@use null
                MergeRow(
                    mergeId,
                    rows.getObject("kept_id", UUID::class.java),
                    rows.getString("kept_company_code"),
                    rows.getObject("merged_id", UUID::class.java),
                    rows.getString("email"),
                    rows.getString("status"),
                    ClientesMerge.parseMoved(rows.getString("moved")),
                )
            }
        }

    // Linked companies read inside the lock; [companyCode] must be one of them.
    private fun requireLinked(access: ModuleAccessService, currentCode: String, companyCode: String): List<GroupCompany> {
        val group = access.linkedCompanies(currentCode, LinkPurpose)
        if (group.none { it.code == companyCode }) throw ClientesSharingException("Esa empresa no esta vinculada con la actual.")
        return group
    }

    /** The owner decides that two customers are not the same: merges are undone, reviews are closed and never merged again. */
    fun separate(access: ModuleAccessService, currentCode: String, companyCode: String, mergeId: UUID, author: Pair<UUID?, String>): String =
        locked(lockFor(access, currentCode)) {
            val group = requireLinked(access, currentCode, companyCode)
            val names = group.associate { it.code to it.name }
            val opened = access.tenantConnectionForCompany(companyCode) ?: throw ClientesSharingException("No se pudo abrir la empresa.")
            opened.use { db ->
                val row = loadMerge(db, mergeId) ?: throw ClientesSharingException("No se encontro esa decision.")
                when (row.status) {
                    "separated" -> throw ClientesSharingException("Ya estaban separados.")
                    "review" -> {
                        db.prepareStatement("UPDATE customer_merges SET status = 'separated', resolved_by = ?, resolved_at = CURRENT_TIMESTAMP WHERE id = ? AND status = 'review'").use { update ->
                            update.setString(1, author.second)
                            update.setObject(2, mergeId)
                            update.executeUpdate()
                        }
                        if (row.copyProblem) "Listo: ese cliente no se copiara a esa empresa." else "Listo: los dos clientes quedan separados y no se fusionaran automaticamente."
                    }
                    else -> separateMerged(access, group, names, currentCode, companyCode, db, row, author)
                }
            }
        }

    private fun separateMerged(
        access: ModuleAccessService,
        group: List<GroupCompany>,
        names: Map<String, String>,
        currentCode: String,
        companyCode: String,
        db: Connection,
        row: MergeRow,
        author: Pair<UUID?, String>,
    ): String {
        // Every linked company is involved (the winner's copies may have completed fields), and every company named in the log must still be linked.
        val named = (row.moved.map { it.company } + companyCode + row.keptCompany).toSet()
        named.firstOrNull { it !in names }?.let {
            throw ClientesSharingException("No se puede separar: la empresa $it ya no esta vinculada. Vincula de nuevo las empresas y vuelve a intentarlo.")
        }
        val opened = mutableListOf<Connection>()
        val previous = LinkedHashMap<Connection, Boolean>()
        try {
            val connections = LinkedHashMap<String, Connection>()
            names.keys.forEach { code ->
                val connection = if (code == companyCode) db else (access.tenantConnectionForCompany(code)?.also { opened += it }
                    ?: throw ClientesSharingException("No se pudo abrir la empresa ${names[code]}; intenta de nuevo."))
                if (!supportsLinking(connection)) throw ClientesSharingException("Actualiza el modulo Clientes de ${names[code]}.")
                connections[code] = connection
            }
            connections.values.forEach { previous[it] = it.autoCommit; it.autoCommit = false }
            try {
                val loserRow = ClientesSharing.row(db, row.mergedId) ?: throw ClientesSharingException("El cliente fusionado ya no existe.")
                val winnerRow = ClientesSharing.row(connections.getValue(row.keptCompany), row.keptId) ?: throw ClientesSharingException("El cliente que se conserva ya no existe.")
                val report = ClientesMerge.separate(
                    connections, names, row.id,
                    ClientesMerge.Candidate(loserRow, companyCode), ClientesMerge.Candidate(winnerRow, row.keptCompany),
                    row.moved, author, userCompany = currentCode,
                )
                // The loser's database goes last, like in a merge.
                connections.values.filter { it !== db }.forEach { it.commit() }
                db.commit()
                val hadFilled = row.moved.any { it.kind == "filled" }
                return "Separados: ${loserRow.displayName} y ${winnerRow.displayName} vuelven a ser dos clientes. Se devolvieron ${report.returned} registros." +
                    (if (report.irreversible > 0) " ${report.irreversible} registros no se pudieron devolver y siguen con el cliente conservado." else "") +
                    (if (report.notesGone > 0) " ${report.notesGone} notas ya no existian y no se restauraron." else "") +
                    (if (hadFilled && report.filledReverted) " Se devolvieron los datos que se habian completado." else if (hadFilled) " Los datos completados al fusionar se conservan porque se editaron despues." else "")
            } catch (failure: Throwable) {
                connections.values.forEach { runCatching { it.rollback() } }
                throw failure
            }
        } finally {
            previous.forEach { (connection, auto) -> runCatching { connection.autoCommit = auto } }
            opened.forEach { runCatching { it.close() } }
        }
    }

    /** Tries again a decision left in review (a customer not copied, or a merge that failed). */
    fun retry(access: ModuleAccessService, currentCode: String, companyCode: String, mergeId: UUID, author: Pair<UUID?, String>): String =
        locked(lockFor(access, currentCode)) {
            val group = requireLinked(access, currentCode, companyCode)
            val opened = access.tenantConnectionForCompany(companyCode) ?: throw ClientesSharingException("No se pudo abrir la empresa.")
            opened.use { db ->
                val row = loadMerge(db, mergeId) ?: throw ClientesSharingException("No se encontro esa decision.")
                if (row.status != "review") throw ClientesSharingException("Esa decision ya no esta por revisar.")
                val loserRow = ClientesSharing.row(db, row.mergedId) ?: throw ClientesSharingException("El cliente ya no existe.")
                // The other company must still be linked before it is opened.
                val other = group.firstOrNull { it.code == row.keptCompany && it.code != companyCode }
                    ?: throw ClientesSharingException("La empresa del otro cliente ya no esta vinculada.")
                val otherDb = access.tenantConnectionForCompany(other.code) ?: throw ClientesSharingException("No se pudo abrir ${other.name}.")
                otherDb.use { destination ->
                    if (!supportsLinking(destination)) throw ClientesSharingException("Actualiza el modulo Clientes de ${other.name}.")
                    if (row.copyProblem) {
                        when (copyOne(access, group, companyCode, db, other, mapOf(companyCode to db, other.code to destination), loserRow, emptySet(), HashMap())) {
                            ShareOutcome.Review -> {
                                val reason = db.prepareStatement("SELECT reason FROM customer_merges WHERE id = ?").use { q ->
                                    q.setObject(1, mergeId)
                                    q.executeQuery().use { it.next(); it.getString(1) }
                                }
                                throw ClientesSharingException("Sigue por revisar. $reason")
                            }
                            ShareOutcome.Skipped ->
                                throw ClientesSharingException("Ese cliente se omitio porque ya esta separado o fusionado; la decision no cambia.")
                            ShareOutcome.Copied, ShareOutcome.Merged -> {
                                db.prepareStatement("DELETE FROM customer_merges WHERE id = ? AND status = 'review'").use { delete ->
                                    delete.setObject(1, mergeId)
                                    delete.executeUpdate()
                                }
                                "Listo: ${loserRow.displayName} se copio a ${other.name}."
                            }
                        }
                    } else {
                        val winnerRow = ClientesSharing.row(destination, row.keptId) ?: throw ClientesSharingException("El otro cliente ya no existe.")
                        // Both must still be active with the same email, or the merge no longer makes sense.
                        if (loserRow.status == "archived" || winnerRow.status == "archived" || loserRow.email.isBlank() || !ClientesMerge.sameEmail(loserRow.email, winnerRow.email)) {
                            throw ClientesSharingException("Los clientes ya no coinciden (correo distinto o alguno archivado). Usa Separar para cerrar esta decision.")
                        }
                        val result = ClientesMerge.merge(
                            access, group, mapOf(companyCode to db, other.code to destination),
                            ClientesMerge.Candidate(loserRow, companyCode), ClientesMerge.Candidate(winnerRow, row.keptCompany),
                            row.email, author, userCompany = currentCode,
                        )
                        when (result) {
                            is ClientesMerge.Result.Merged -> "Listo: ${loserRow.displayName} se fusiono con ${winnerRow.displayName}."
                            is ClientesMerge.Result.Review -> throw ClientesSharingException("Sigue por revisar. ${result.reason}")
                        }
                    }
                }
            }
        }
}
