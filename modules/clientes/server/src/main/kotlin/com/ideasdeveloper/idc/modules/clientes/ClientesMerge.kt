package com.ideasdeveloper.idc.modules.clientes

import com.ideasdeveloper.idc.server.modules.GroupCompany
import com.ideasdeveloper.idc.server.modules.ModuleAccessService
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import org.slf4j.LoggerFactory
import java.sql.Connection
import java.sql.SQLException
import java.time.OffsetDateTime
import java.util.UUID

/**
 * Merge of two customers of different linked companies that share an email (V008 customer_merges).
 * The oldest customer stays (the winner); the other one (the loser) is archived and every row that
 * pointed to it, in every company that has a copy, now points to the winner. What was moved is kept
 * in customer_merges.moved so the owner can separate them again. The decisions and texts are pure
 * functions (tested without a database); [merge] and [separate] do the work in the databases.
 */
object ClientesMerge {
    private val log = LoggerFactory.getLogger(ClientesMerge::class.java)

    /** A customer as one company sees it: its row and the company where it was created. */
    class Candidate(val row: ClientesSharing.Row, val origin: String)

    sealed interface Decision {
        /** No duplicate (or not a duplicate that may be merged): copy the customer as usual. */
        object Copy : Decision

        /** The owner separated this pair before: neither merge nor copy to that company. */
        object Skip : Decision

        class Merge(val winner: Candidate, val loser: Candidate) : Decision
    }

    sealed interface Result {
        class Merged(val mergeId: UUID, val winner: Candidate, val loser: Candidate) : Result
        class Review(val reason: String) : Result
    }

    /** A step of the merge failed in one database; everything is rolled back and the pair goes to review. */
    class AbsorbFailure(val reason: String) : Exception(reason)

    /** A company cannot be used right now (it does not open, its Clientes is old, it is no longer linked): the work is retried later. */
    class CompanyUnavailableException(message: String, val company: String? = null) : ClientesSharingException(message)

    /** What [separate] could and could not give back. */
    class SeparateReport(val returned: Int, val irreversible: Int, val notesGone: Int, val filledReverted: Boolean)

    /** Column of another table that points to customers(id) with a single-column foreign key. */
    class ForeignKey(val schema: String, val table: String, val column: String, val pk: String?)

    /**
     * What was moved in one company, to move it back on "Separar". kind "filled": the winner's email, phone
     * and attributes before and after its empty ones were completed ([note]). kind "fk": rows of [table] whose
     * [column] was changed (their [pk] values are in [ids]; without a simple pk only [count] is known
     * and it cannot be reverted). kind "note": one customer note that moved to the winner's company.
     */
    class Moved(
        val company: String,
        val kind: String,
        val schema: String = "",
        val table: String = "",
        val column: String = "",
        val pk: String? = null,
        val ids: List<String> = emptyList(),
        val count: Int = ids.size,
        val note: JsonObject? = null,
    ) {
        val reversible: Boolean get() = kind == "note" || kind == "filled" || (pk != null && ids.size == count)

        fun toJson(): JsonObject = buildJsonObject {
            put("company", company)
            put("kind", kind)
            if (kind == "fk") {
                put("schema", schema)
                put("table", table)
                put("column", column)
                put("pk", pk)
                put("count", count)
                put("ids", JsonArray(ids.map { JsonPrimitive(it) }))
            } else {
                put("note", note ?: JsonObject(emptyMap()))
            }
        }

        companion object {
            fun fromJson(element: JsonElement): Moved {
                val json = element as JsonObject
                fun text(key: String) = (json[key] as? JsonPrimitive)?.contentOrNull.orEmpty()
                val ids = (json["ids"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
                return Moved(
                    company = text("company"),
                    kind = text("kind"),
                    schema = text("schema"),
                    table = text("table"),
                    column = text("column"),
                    pk = (json["pk"] as? JsonPrimitive)?.contentOrNull,
                    ids = ids,
                    count = (json["count"] as? JsonPrimitive)?.contentOrNull?.toIntOrNull() ?: ids.size,
                    note = json["note"] as? JsonObject,
                )
            }
        }
    }

    // Tables never repointed in bulk: the merge log itself, and notes (they move to the winner's company).
    private val NotRepointed = setOf("customer_merges", "customer_notes")

    // ---- Pure rules -------------------------------------------------------------------------------

    /** The oldest customer wins; on a tie the smaller id. Returns winner to loser. */
    fun order(a: Candidate, b: Candidate): Pair<Candidate, Candidate> {
        val byDate = a.row.createdAt.compareTo(b.row.createdAt)
        val aWins = if (byDate != 0) byDate < 0 else a.row.id.toString() < b.row.id.toString()
        return if (aWins) a to b else b to a
    }

    /**
     * What to do with [incoming] (an own customer being copied) when [existing] is the active customer
     * with the same email already in the target company. Only customers of two different companies of
     * the group are merged; [separated] is true when the owner already separated this pair.
     */
    fun decide(incoming: Candidate, existing: Candidate?, group: Set<String>, separated: Boolean): Decision {
        if (existing == null) return Decision.Copy
        if (incoming.row.email.isBlank() || !sameEmail(incoming.row.email, existing.row.email)) return Decision.Copy
        if (incoming.row.id == existing.row.id) return Decision.Copy
        if (incoming.origin == existing.origin) return Decision.Copy
        if (incoming.origin !in group || existing.origin !in group) return Decision.Copy
        if (separated) return Decision.Skip
        val (winner, loser) = order(incoming, existing)
        return Decision.Merge(winner, loser)
    }

    fun sameEmail(a: String, b: String): Boolean = a.trim().lowercase() == b.trim().lowercase()

    fun sameName(a: String, b: String): Boolean = normalizeName(a) == normalizeName(b)

    private fun normalizeName(name: String) = name.trim().lowercase().split(Regex("\\s+")).joinToString(" ")

    /** Winner fields after completing the empty ones with the loser's; [filled] names what was completed. */
    class Filled(val email: String, val phone: String, val attributes: String, val filled: List<String>) {
        val changed: Boolean get() = filled.isNotEmpty()
    }

    fun fillEmpty(winner: ClientesSharing.Row, loser: ClientesSharing.Row): Filled {
        val filled = mutableListOf<String>()
        val email = if (winner.email.isBlank() && loser.email.isNotBlank()) loser.email.also { filled += "correo" } else winner.email
        val phone = if (winner.phone.isBlank() && loser.phone.isNotBlank()) loser.phone.also { filled += "telefono" } else winner.phone
        val own = parseObject(winner.attributes)
        val other = parseObject(loser.attributes)
        val merged = own.toMutableMap()
        other.forEach { (key, value) ->
            if (isEmptyValue(own[key]) && !isEmptyValue(value)) {
                merged[key] = value
                filled += "campo $key"
            }
        }
        val attributes = if (merged.size == own.size && filled.none { it.startsWith("campo ") }) winner.attributes else JsonObject(merged).toString()
        return Filled(email, phone, attributes, filled)
    }

    private fun parseObject(text: String): JsonObject =
        runCatching { Json.parseToJsonElement(text) as? JsonObject }.getOrNull() ?: JsonObject(emptyMap())

    private fun isEmptyValue(value: JsonElement?): Boolean = when (value) {
        null, JsonNull -> true
        is JsonPrimitive -> value.isString && value.content.isBlank()
        is JsonArray -> value.isEmpty()
        is JsonObject -> value.isEmpty()
    }

    fun withFilled(row: ClientesSharing.Row, filled: Filled): ClientesSharing.Row =
        ClientesSharing.Row(row.id, row.displayName, filled.email, filled.phone, row.status, row.customerType, row.code, filled.attributes, row.createdAt)

    /** Text of the activity of both customers: "Fusionado con {nombre} ({empresa}) por el correo {correo}". */
    fun mergedActivity(otherName: String, otherCompany: String, email: String, filled: List<String> = emptyList()): String =
        "Fusionado con $otherName ($otherCompany) por el correo $email" +
            if (filled.isEmpty()) "" else ". Se completaron en el cliente que se conserva: ${filled.joinToString(", ")}"

    /** Reason shown to the owner for a merge that was done; says clearly when only the email matched. */
    fun mergedReason(winnerName: String, loserName: String, email: String): String {
        val kept = "Se conserva \"$winnerName\" (el mas antiguo) y \"$loserName\" queda archivado."
        return if (sameName(winnerName, loserName)) {
            "Mismo correo ($email) y mismo nombre. $kept"
        } else {
            "Mismo correo ($email) pero nombres distintos. $kept Si no es la misma persona, usa Separar."
        }
    }

    fun separatedActivity(otherName: String, otherCompany: String): String =
        "Separado de $otherName ($otherCompany): ya no se fusionan automaticamente. Los datos que se completaron al fusionar se conservan."

    // Short and generic on purpose: the SQL detail goes to the server log, not to the user.
    fun failureReason(company: String, table: String): String =
        "No se pudo fusionar: la tabla $table de $company ya tiene datos que chocan con el cliente que se conserva. Nada se modifico."

    /** The winner's values before and after completing its empty fields, so Separar can put them back if nobody changed them since. */
    fun filledMoved(company: String, before: ClientesSharing.Row, filled: Filled): Moved? {
        if (!filled.changed) return null
        val note = buildJsonObject {
            put("beforeEmail", before.email)
            put("beforePhone", before.phone)
            put("beforeAttributes", before.attributes)
            put("afterEmail", filled.email)
            put("afterPhone", filled.phone)
            put("afterAttributes", filled.attributes)
        }
        return Moved(company, "filled", note = note)
    }

    /** True when the winner still has exactly the values the merge wrote (so they can be reverted without losing later edits). */
    fun filledStillApplies(entry: Moved, email: String, phone: String, attributes: String): Boolean {
        val note = entry.note ?: return false
        fun text(key: String) = (note[key] as? JsonPrimitive)?.contentOrNull.orEmpty()
        return email == text("afterEmail") && phone == text("afterPhone") && parseObject(attributes) == parseObject(text("afterAttributes"))
    }

    /** An entry of moved may only run when it matches a real single-column foreign key of that database (never a name taken from stored JSON). */
    fun allowed(entry: Moved, keys: List<ForeignKey>): Boolean =
        entry.kind == "fk" && entry.pk != null && entry.table !in NotRepointed &&
            keys.any { it.schema == entry.schema && it.table == entry.table && it.column == entry.column && it.pk == entry.pk }

    /** Quotes a name taken from the catalog so it is safe inside SQL: double quotes, inner ones doubled. */
    fun quote(identifier: String): String = "\"" + identifier.replace("\"", "\"\"") + "\""

    fun qualified(schema: String, table: String): String = quote(schema) + "." + quote(table)

    /** Repoints the rows of [table] from the loser (parameter 2) to the winner (parameter 1); with a pk it returns the moved pks. */
    fun repointSql(key: ForeignKey): String {
        val base = "UPDATE ${qualified(key.schema, key.table)} SET ${quote(key.column)} = ? WHERE ${quote(key.column)} = ?"
        return if (key.pk != null) "$base RETURNING ${quote(key.pk)}::text" else base
    }

    /** Moves back the rows listed in parameter 3 (text pks): parameter 1 = the loser, parameter 2 = the winner. */
    fun revertSql(key: ForeignKey): String =
        "UPDATE ${qualified(key.schema, key.table)} SET ${quote(key.column)} = ? WHERE ${quote(key.column)} = ? AND ${quote(key.pk!!)}::text = ANY (?)"

    /** Foreign keys that are repointed in bulk (everything but the merge log and the notes). */
    fun repointable(keys: List<ForeignKey>): List<ForeignKey> = keys.filter { it.table !in NotRepointed }

    fun movedFor(company: String, key: ForeignKey, ids: List<String>, count: Int = ids.size): Moved =
        Moved(company, "fk", key.schema, key.table, key.column, key.pk, ids, count)

    fun movedToJson(moved: List<Moved>): String = JsonArray(moved.map { it.toJson() }).toString()

    fun parseMoved(text: String): List<Moved> =
        runCatching { (Json.parseToJsonElement(text) as JsonArray).map { Moved.fromJson(it) } }.getOrDefault(emptyList())

    // Single-column foreign keys to customers(id), with the single-column primary key of their table.
    const val ForeignKeysSql = """
        SELECT n.nspname AS schema_name, c.relname AS table_name, a.attname AS column_name,
            (SELECT pa.attname FROM pg_index i JOIN pg_attribute pa ON pa.attrelid = i.indrelid AND pa.attnum = i.indkey[0]
             WHERE i.indrelid = con.conrelid AND i.indisprimary AND i.indnatts = 1) AS pk_name
        FROM pg_constraint con
        JOIN pg_class c ON c.oid = con.conrelid
        JOIN pg_namespace n ON n.oid = c.relnamespace
        JOIN pg_attribute a ON a.attrelid = con.conrelid AND a.attnum = con.conkey[1]
        WHERE con.contype = 'f' AND con.confrelid = to_regclass('public.customers') AND array_length(con.conkey, 1) = 1
        ORDER BY n.nspname, c.relname, a.attname
        """

    // ---- Database work ----------------------------------------------------------------------------

    /** Foreign keys of the database of company [code]; [cache] (by company code) keeps the answer for a whole pass of the job. */
    fun foreignKeys(db: Connection, code: String, cache: MutableMap<String, List<ForeignKey>> = HashMap()): List<ForeignKey> =
        cache.getOrPut(code) {
            db.createStatement().use { statement ->
                statement.executeQuery(ForeignKeysSql).use { rows ->
                    buildList {
                        while (rows.next()) add(ForeignKey(rows.getString("schema_name"), rows.getString("table_name"), rows.getString("column_name"), rows.getString("pk_name")))
                    }
                }
            }
        }

    private fun exists(db: Connection, id: UUID): Boolean =
        db.prepareStatement("SELECT 1 FROM customers WHERE id = ?").use { query ->
            query.setObject(1, id)
            query.executeQuery().use { it.next() }
        }

    /**
     * Merges [loser] into [winner] in every company of [group]. Every database runs in its own transaction
     * and a failure of one table rolls everything back and leaves the pair in review with a short reason
     * (the detail goes to the log). This is NOT a distributed (two-phase) transaction: the databases are
     * committed one after another, the loser's last because it holds customer_merges. If a commit fails
     * after others were already committed, a review "fusion incompleta" is written in the loser's database
     * with a new connection; retrying is safe (what already moved is not moved twice).
     * [provided] are connections the caller already has (not closed). A company that cannot be used throws
     * [CompanyUnavailableException] with nothing changed. The caller holds the group lock.
     */
    fun merge(
        access: ModuleAccessService,
        group: List<GroupCompany>,
        provided: Map<String, Connection>,
        loser: Candidate,
        winner: Candidate,
        email: String,
        author: Pair<UUID?, String>,
        userCompany: String?,
        keyCache: MutableMap<String, List<ForeignKey>> = HashMap(),
    ): Result {
        val names = group.associate { it.code to it.name }
        if (loser.origin !in names || winner.origin !in names) throw CompanyUnavailableException("Las empresas del cliente ya no estan vinculadas.")
        val opened = mutableListOf<Connection>()
        val previousAutoCommit = LinkedHashMap<Connection, Boolean>()
        try {
            val connections = LinkedHashMap<String, Connection>()
            for (company in group) {
                val connection = provided[company.code] ?: ClientesLinking.connect(access, company).also { opened += it }
                if (!ClientesLinking.supportsLinking(connection)) throw CompanyUnavailableException("Actualiza el modulo Clientes de ${company.name}.", company.code)
                connections[company.code] = connection
            }
            connections.values.forEach { previousAutoCommit[it] = it.autoCommit; it.autoCommit = false }
            val loserConnection = connections.getValue(loser.origin)
            try {
                val filled = fillEmpty(winner.row, loser.row)
                val winnerRow = withFilled(winner.row, filled)
                val winnerNote = mergedActivity(loser.row.displayName, names.getValue(loser.origin), email, filled.filled)
                val loserNote = mergedActivity(winner.row.displayName, names.getValue(winner.origin), email)
                val moved = mutableListOf<Moved>()
                filledMoved(winner.origin, winner.row, filled)?.let { moved += it }
                connections.forEach { (code, connection) ->
                    moved += absorb(connection, code, names.getValue(code), loser, winner, winnerRow, filled, winnerNote, loserNote, author, withUser = code == userCompany, keyCache)
                }
                try {
                    moved += moveNotes(loserConnection, connections.getValue(winner.origin), loser, winner)
                } catch (failure: SQLException) {
                    log.warn("Could not move the notes of customer {} to company {}", loser.row.id, winner.origin, failure)
                    throw AbsorbFailure(failureReason(names.getValue(loser.origin), "customer_notes"))
                }
                val reason = mergedReason(winner.row.displayName, loser.row.displayName, email)
                val mergeId = writeMergeRow(loserConnection, loser, winner, names.getValue(winner.origin), email, "merged", reason, moved, author.second)
                var committed = 0
                try {
                    connections.values.filter { it !== loserConnection }.forEach { it.commit(); committed++ }
                    loserConnection.commit()
                } catch (failure: SQLException) {
                    connections.values.forEach { runCatching { it.rollback() } }
                    if (committed == 0) throw failure
                    log.error("Merge of {} into {} committed in {} databases and then failed", loser.row.id, winner.row.id, committed, failure)
                    val incomplete = "Fusion incompleta: algunas empresas ya cambiaron y otra no. Reintenta para terminarla o usa Separar."
                    recordIncomplete(access, loser, winner, names.getValue(winner.origin), email, incomplete, author.second)
                    return Result.Review(incomplete)
                }
                return Result.Merged(mergeId, winner, loser)
            } catch (failure: AbsorbFailure) {
                connections.values.forEach { runCatching { it.rollback() } }
                writeMergeRow(loserConnection, loser, winner, names.getValue(winner.origin), email, "review", failure.reason, emptyList(), author.second)
                loserConnection.commit()
                return Result.Review(failure.reason)
            } catch (failure: Throwable) {
                connections.values.forEach { runCatching { it.rollback() } }
                throw failure
            }
        } finally {
            previousAutoCommit.forEach { (connection, auto) -> runCatching { connection.autoCommit = auto } }
            opened.forEach { runCatching { it.close() } }
        }
    }

    // Writes the "incomplete merge" review with a fresh connection of the loser's company.
    private fun recordIncomplete(access: ModuleAccessService, loser: Candidate, winner: Candidate, winnerCompanyName: String, email: String, reason: String, author: String) {
        runCatching {
            access.tenantConnectionForCompany(loser.origin)?.use { db ->
                writeMergeRow(db, loser, winner, winnerCompanyName, email, "review", reason, emptyList(), author)
            }
        }.onFailure { log.error("Could not record the incomplete merge of {}", loser.row.id, it) }
    }

    private fun setWinnerFields(db: Connection, id: UUID, email: String, phone: String, attributes: String) {
        db.prepareStatement(
            "UPDATE customers SET primary_email = ?, primary_phone = ?, flexible_attributes = CAST(? AS jsonb), updated_at = CURRENT_TIMESTAMP WHERE id = ?"
        ).use { update ->
            update.setString(1, email)
            update.setString(2, phone)
            update.setString(3, attributes)
            update.setObject(4, id)
            update.executeUpdate()
        }
    }

    /** Applies the merge in one company: its copy of the winner, the rows of the loser, the loser archived, the activity. */
    private fun absorb(
        db: Connection,
        code: String,
        companyName: String,
        loser: Candidate,
        winner: Candidate,
        winnerRow: ClientesSharing.Row,
        filled: Filled,
        winnerNote: String,
        loserNote: String,
        author: Pair<UUID?, String>,
        withUser: Boolean,
        keyCache: MutableMap<String, List<ForeignKey>>,
    ): List<Moved> {
        val moved = mutableListOf<Moved>()
        // The winner: its own row gets the completed fields; every other company gets or refreshes its copy.
        if (code == winner.origin) {
            if (filled.changed) setWinnerFields(db, winner.row.id, filled.email, filled.phone, filled.attributes)
        } else {
            val savepoint = db.setSavepoint()
            try {
                ClientesSharing.upsertCopy(db, winnerRow, origin = winner.origin, shared = true)
                db.releaseSavepoint(savepoint)
            } catch (failure: SQLException) {
                db.rollback(savepoint)
                log.warn("Merge: could not copy the winner {} to company {}", winner.row.id, code, failure)
                throw AbsorbFailure(failureReason(companyName, "customers"))
            }
        }
        val hasLoser = exists(db, loser.row.id)
        if (hasLoser) {
            repointable(foreignKeys(db, code, keyCache)).forEach { key ->
                val savepoint = db.setSavepoint()
                try {
                    val entry = repoint(db, code, key, loser.row.id, winner.row.id)
                    db.releaseSavepoint(savepoint)
                    if (entry != null) moved += entry
                } catch (failure: SQLException) {
                    db.rollback(savepoint)
                    log.warn("Merge: could not repoint {}.{} in company {}", key.table, key.column, code, failure)
                    throw AbsorbFailure(failureReason(companyName, key.table))
                }
            }
            db.prepareStatement("UPDATE customers SET status = 'archived', shared = FALSE, updated_at = CURRENT_TIMESTAMP WHERE id = ?").use { update ->
                update.setObject(1, loser.row.id)
                update.executeUpdate()
            }
        }
        ClientesLinking.activity(db, winner.row.id, "merged", winnerNote, author, withUser)
        if (hasLoser) ClientesLinking.activity(db, loser.row.id, "merged", loserNote, author, withUser)
        return moved
    }

    private fun repoint(db: Connection, code: String, key: ForeignKey, loserId: UUID, winnerId: UUID): Moved? =
        db.prepareStatement(repointSql(key)).use { update ->
            update.setObject(1, winnerId)
            update.setObject(2, loserId)
            if (key.pk != null) {
                val ids = update.executeQuery().use { rows -> buildList { while (rows.next()) add(rows.getString(1)) } }
                if (ids.isEmpty()) null else movedFor(code, key, ids)
            } else {
                val count = update.executeUpdate()
                if (count == 0) null else movedFor(code, key, emptyList(), count)
            }
        }

    // General notes live only in the origin company: the loser's move to the winner's company.
    private fun moveNotes(from: Connection, to: Connection, loser: Candidate, winner: Candidate): List<Moved> {
        val notes = from.prepareStatement(
            "SELECT id, body, user_id, author, company_code, created_at FROM customer_notes WHERE customer_id = ? ORDER BY created_at"
        ).use { query ->
            query.setObject(1, loser.row.id)
            query.executeQuery().use { rows ->
                buildList {
                    while (rows.next()) {
                        add(
                            buildJsonObject {
                                put("id", rows.getString("id"))
                                put("body", rows.getString("body"))
                                put("user_id", rows.getString("user_id"))
                                put("author", rows.getString("author"))
                                put("company_code", rows.getString("company_code"))
                                put("created_at", rows.getObject("created_at", OffsetDateTime::class.java).toString())
                            }
                        )
                    }
                }
            }
        }
        notes.forEach { note -> insertNote(to, winner.row.id, note); deleteNote(from, note) }
        return notes.map { Moved(loser.origin, "note", note = it) }
    }

    private fun insertNote(db: Connection, customerId: UUID, note: JsonObject) {
        fun text(key: String) = (note[key] as? JsonPrimitive)?.contentOrNull
        db.prepareStatement(
            "INSERT INTO customer_notes (id, customer_id, body, user_id, author, company_code, created_at) VALUES (?, ?, ?, ?, ?, ?, ?) ON CONFLICT (id) DO NOTHING"
        ).use { insert ->
            insert.setObject(1, UUID.fromString(text("id")))
            insert.setObject(2, customerId)
            insert.setString(3, text("body").orEmpty())
            insert.setObject(4, text("user_id")?.let { UUID.fromString(it) })
            insert.setString(5, text("author").orEmpty())
            insert.setString(6, text("company_code").orEmpty())
            insert.setObject(7, OffsetDateTime.parse(text("created_at")))
            insert.executeUpdate()
        }
    }

    // Returns how many rows were deleted (0 when the note no longer exists).
    private fun deleteNote(db: Connection, note: JsonObject): Int =
        db.prepareStatement("DELETE FROM customer_notes WHERE id = ?").use { delete ->
            delete.setObject(1, UUID.fromString((note["id"] as JsonPrimitive).content))
            delete.executeUpdate()
        }

    /** Writes the decision in the loser's company (customer_merges lives where merged_id exists). */
    private fun writeMergeRow(
        db: Connection,
        loser: Candidate,
        winner: Candidate,
        winnerCompanyName: String,
        email: String,
        status: String,
        reason: String,
        moved: List<Moved>,
        author: String,
    ): UUID {
        val id = UUID.randomUUID()
        // A retry updates the open row of the pair; a separated pair is never touched.
        val written = db.prepareStatement(
            """
            INSERT INTO customer_merges (id, kept_id, kept_company_code, kept_name, merged_id, merged_name, email, status, reason, moved, author)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, CAST(? AS jsonb), ?)
            ON CONFLICT (merged_id, kept_id) DO UPDATE SET status = EXCLUDED.status, reason = EXCLUDED.reason, moved = EXCLUDED.moved,
                kept_name = EXCLUDED.kept_name, merged_name = EXCLUDED.merged_name, email = EXCLUDED.email,
                resolved_by = CASE WHEN EXCLUDED.status = 'merged' THEN EXCLUDED.author ELSE customer_merges.resolved_by END,
                resolved_at = CASE WHEN EXCLUDED.status = 'merged' THEN CURRENT_TIMESTAMP ELSE customer_merges.resolved_at END
            WHERE customer_merges.status = 'review'
            """.trimIndent()
        ).use { insert ->
            insert.setObject(1, id)
            insert.setObject(2, winner.row.id)
            insert.setString(3, winner.origin)
            insert.setString(4, winner.row.displayName)
            insert.setObject(5, loser.row.id)
            insert.setString(6, loser.row.displayName)
            insert.setString(7, email)
            insert.setString(8, status)
            insert.setString(9, reason.take(1_900))
            insert.setString(10, movedToJson(moved))
            insert.setString(11, author)
            insert.executeUpdate()
        }
        // A merge must never be reported as done without its row (the pair was already merged or separated).
        if (written == 0 && status == "merged") throw IllegalStateException("The merge of ${loser.row.id} into ${winner.row.id} was already decided")
        return id
    }

    /**
     * Undoes a merge of [loser] into [winner] ([mergeId] is the row in the loser's company): moves back
     * the rows (only those that match a real foreign key of that database) and the notes that still exist,
     * puts back the winner's completed fields if nobody changed them since, restores the loser (active)
     * and marks the pair separated. [connections] holds every company involved, already in transactions
     * (autoCommit false); the caller commits.
     */
    fun separate(
        connections: Map<String, Connection>,
        names: Map<String, String>,
        mergeId: UUID,
        loser: Candidate,
        winner: Candidate,
        moved: List<Moved>,
        author: Pair<UUID?, String>,
        userCompany: String?,
        keyCache: MutableMap<String, List<ForeignKey>> = HashMap(),
    ): SeparateReport {
        val loserConnection = connections.getValue(loser.origin)
        val current = ClientesSharing.row(loserConnection, loser.row.id) ?: loser.row
        // Companies that hold rows to give back need the loser's row: restore it there (hidden in the winner's company).
        val companies = (moved.map { it.company } + loser.origin).toSet()
        companies.forEach { code ->
            val db = connections[code] ?: return@forEach
            val restored = ClientesSharing.Row(current.id, current.displayName, current.email, current.phone, "active", current.customerType, current.code, current.attributes, current.createdAt)
            if (code == loser.origin) {
                db.prepareStatement("UPDATE customers SET status = 'active', shared = FALSE, updated_at = CURRENT_TIMESTAMP WHERE id = ?").use { update ->
                    update.setObject(1, loser.row.id)
                    update.executeUpdate()
                }
            } else {
                ClientesSharing.upsertCopy(db, restored, origin = loser.origin, shared = code != winner.origin)
            }
        }
        var returned = 0
        var irreversible = 0
        var notesGone = 0
        var filledReverted = false
        moved.forEach { entry ->
            val db = connections[entry.company]
            when {
                entry.kind == "filled" -> {
                    connections.values.forEach { connection ->
                        val now = ClientesSharing.row(connection, winner.row.id) ?: return@forEach
                        if (filledStillApplies(entry, now.email, now.phone, now.attributes)) {
                            val note = entry.note!!
                            fun text(key: String) = (note[key] as? JsonPrimitive)?.contentOrNull.orEmpty()
                            setWinnerFields(connection, winner.row.id, text("beforeEmail"), text("beforePhone"), text("beforeAttributes").ifBlank { "{}" })
                            if (connection === connections[winner.origin]) filledReverted = true
                        }
                    }
                }
                db == null -> irreversible += if (entry.kind == "note") 1 else entry.count
                entry.kind == "note" -> {
                    val note = entry.note
                    // Only a note that still exists in the winner's company comes back; a deleted one stays deleted.
                    if (note != null && deleteNote(connections.getValue(winner.origin), note) > 0) {
                        insertNote(db, loser.row.id, note)
                        returned++
                    } else {
                        notesGone++
                    }
                }
                !entry.reversible || !allowed(entry, foreignKeys(db, entry.company, keyCache)) -> irreversible += entry.count
                else -> {
                    val key = ForeignKey(entry.schema, entry.table, entry.column, entry.pk)
                    returned += db.prepareStatement(revertSql(key)).use { update ->
                        update.setObject(1, loser.row.id)
                        update.setObject(2, winner.row.id)
                        update.setArray(3, db.createArrayOf("text", entry.ids.toTypedArray()))
                        update.executeUpdate()
                    }
                }
            }
        }
        loserConnection.prepareStatement(
            "UPDATE customer_merges SET status = 'separated', resolved_by = ?, resolved_at = CURRENT_TIMESTAMP WHERE id = ?"
        ).use { update ->
            update.setString(1, author.second)
            update.setObject(2, mergeId)
            update.executeUpdate()
        }
        // The restored loser is not shared yet (shared = FALSE): the pending pass copies it again to the others (the pair is skipped where the winner is).
        loserConnection.prepareStatement(
            "UPDATE customer_link_jobs SET status = 'pending', copied = 0, merged = 0, review = 0, updated_at = CURRENT_TIMESTAMP"
        ).use { it.executeUpdate() }
        ClientesLinking.activity(loserConnection, loser.row.id, "separated", separatedActivity(winner.row.displayName, names[winner.origin] ?: winner.origin), author, withUser = loser.origin == userCompany)
        if (ClientesSharing.row(loserConnection, winner.row.id) != null) {
            ClientesLinking.activity(loserConnection, winner.row.id, "separated", separatedActivity(loser.row.displayName, names[loser.origin] ?: loser.origin), author, withUser = loser.origin == userCompany)
        }
        connections[winner.origin]?.takeIf { it !== loserConnection }?.let { db ->
            ClientesLinking.activity(db, winner.row.id, "separated", separatedActivity(loser.row.displayName, names[loser.origin] ?: loser.origin), author, withUser = winner.origin == userCompany)
        }
        return SeparateReport(returned, irreversible, notesGone, filledReverted)
    }
}
