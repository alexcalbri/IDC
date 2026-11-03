package com.ideasdeveloper.idc.modules.crm

import com.ideasdeveloper.idc.server.modules.FileTooLargeException
import com.ideasdeveloper.idc.server.modules.ModuleFiles
import com.ideasdeveloper.idc.server.modules.StoredFileRef
import com.ideasdeveloper.idc.ui.UiFileRef
import org.slf4j.LoggerFactory
import java.io.InputStream
import java.sql.Connection
import java.sql.ResultSet
import java.util.UUID

/**
 * Files attached to leads. The content lives in the server file storage of the company
 * ([ModuleFiles]: the server disk today, other backends later); the table keeps the name, folder,
 * lead and the backend reference. Stored files are only deleted by [cleanup], after the row is
 * marked, so a rolled back save never loses a file that a lead still points to.
 */
object CrmAttachments {
    const val MaxFileBytes = 25L * 1024 * 1024
    // Per lead, so a folder with thousands of files is not attached by mistake.
    const val MaxFilesPerLead = 500
    private val log = LoggerFactory.getLogger(CrmAttachments::class.java)

    class StoredFile(val ref: UiFileRef, val leadId: UUID?, val contentType: String, val open: () -> InputStream?)

    // Name without folders or control characters; the folder is kept apart, normalized to a/b/c.
    fun cleanName(name: String): String = name.substringAfterLast('/').substringAfterLast('\\').filter { it >= ' ' }.trim().take(255)

    fun cleanPath(path: String): String = path.replace('\\', '/').split('/')
        .map { part -> part.filter { it >= ' ' }.trim() }
        .filter { it.isNotEmpty() && it != "." && it != ".." }
        .joinToString("/")
        .take(500)

    /**
     * Stores an upload that waits for its form to be saved and returns the ref the form keeps. The
     * content is written to [files] first; if the row cannot be saved the stored file is removed.
     */
    fun upload(tenant: Connection, files: ModuleFiles, name: String, path: String, contentType: String, content: InputStream, uploadedBy: String): UiFileRef {
        val fileName = cleanName(name)
        if (fileName.isEmpty()) throw CrmValidationException("El archivo no tiene nombre.")
        val type = contentType.take(200).ifBlank { "application/octet-stream" }
        val stored = try {
            files.put(fileName, type, content, MaxFileBytes)
        } catch (_: FileTooLargeException) {
            throw CrmValidationException("$fileName supera el maximo de ${MaxFileBytes / (1024 * 1024)} MB.")
        }
        val ref = UiFileRef(UUID.randomUUID().toString(), fileName, cleanPath(path), stored.size)
        try {
            tenant.prepareStatement(
                "INSERT INTO crm_attachments (id, file_name, folder_path, content_type, size_bytes, storage_backend, storage_ref, uploaded_by) VALUES (?, ?, ?, ?, ?, ?, ?, ?)"
            ).use { insert ->
                insert.setObject(1, UUID.fromString(ref.id))
                insert.setString(2, ref.name)
                insert.setString(3, ref.path)
                insert.setString(4, type)
                insert.setLong(5, ref.size)
                insert.setString(6, stored.backend)
                insert.setString(7, stored.ref)
                insert.setString(8, uploadedBy.take(200))
                insert.executeUpdate()
            }
        } catch (failure: Exception) {
            runCatching { files.delete(stored) }
            throw failure
        }
        return ref
    }

    /**
     * Stores an imported file directly in a lead's field. [sourceRef] (e.g. monday:asset:1) keeps a
     * new import of the same item from attaching it again. The stored file is removed if the row fails.
     */
    fun importLinked(
        tenant: Connection,
        files: ModuleFiles,
        leadId: UUID,
        fieldKey: String,
        name: String,
        path: String,
        contentType: String,
        content: InputStream,
        sourceRef: String,
        uploadedBy: String,
    ) {
        val fileName = cleanName(name).ifEmpty { "archivo" }
        val count = tenant.prepareStatement("SELECT count(*) FROM crm_attachments WHERE lead_id = ? AND deleted_at IS NULL").use { query ->
            query.setObject(1, leadId)
            query.executeQuery().use { rows -> rows.next(); rows.getInt(1) }
        }
        if (count >= MaxFilesPerLead) throw CrmValidationException("el lead ya tiene $MaxFilesPerLead archivos")
        val type = contentType.substringBefore(';').trim().take(200).ifBlank { "application/octet-stream" }
        val stored = try {
            files.put(fileName, type, content, MaxFileBytes)
        } catch (_: FileTooLargeException) {
            throw CrmValidationException("supera ${MaxFileBytes / (1024 * 1024)} MB")
        }
        try {
            tenant.prepareStatement(
                """
                INSERT INTO crm_attachments (id, lead_id, field_key, file_name, folder_path, content_type, size_bytes, storage_backend, storage_ref, uploaded_by, source_ref)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """.trimIndent()
            ).use { insert ->
                insert.setObject(1, UUID.randomUUID())
                insert.setObject(2, leadId)
                insert.setString(3, fieldKey)
                insert.setString(4, fileName)
                insert.setString(5, cleanPath(path))
                insert.setString(6, type)
                insert.setLong(7, stored.size)
                insert.setString(8, stored.backend)
                insert.setString(9, stored.ref)
                insert.setString(10, uploadedBy.take(200))
                insert.setString(11, sourceRef.take(200))
                insert.executeUpdate()
            }
        } catch (failure: Exception) {
            runCatching { files.delete(stored) }
            throw failure
        }
    }

    /** Files of a lead by field key, ordered by folder and name. */
    fun forLead(tenant: Connection, leadId: UUID): Map<String, List<UiFileRef>> = tenant.prepareStatement(
        "SELECT id, field_key, file_name, folder_path, size_bytes FROM crm_attachments WHERE lead_id = ? AND deleted_at IS NULL ORDER BY folder_path, file_name"
    ).use { query ->
        query.setObject(1, leadId)
        query.executeQuery().use { rows ->
            buildList {
                while (rows.next()) add(rows.getString(2) to UiFileRef(rows.getObject(1, UUID::class.java).toString(), rows.getString(3), rows.getString(4), rows.getLong(5)))
            }
        }
    }.groupBy({ it.first }, { it.second })

    data class Change(val added: List<String>, val removed: List<String>)

    /**
     * Makes [ids] the files of the field: pending uploads among them are linked to the lead and the
     * files it had that are not in [ids] are marked for deletion. Ids of other leads are ignored.
     */
    fun link(tenant: Connection, leadId: UUID, fieldKey: String, ids: List<UUID>): Change {
        val current = tenant.prepareStatement("SELECT id, file_name FROM crm_attachments WHERE lead_id = ? AND field_key = ? AND deleted_at IS NULL").use { query ->
            query.setObject(1, leadId)
            query.setString(2, fieldKey)
            query.executeQuery().use { rows -> buildMap { while (rows.next()) put(rows.getObject(1, UUID::class.java), rows.getString(2)) } }
        }
        val removed = current.filterKeys { it !in ids }
        if (removed.isNotEmpty()) {
            tenant.prepareStatement("UPDATE crm_attachments SET deleted_at = CURRENT_TIMESTAMP WHERE lead_id = ? AND id = ANY(?)").use { update ->
                update.setObject(1, leadId)
                update.setArray(2, tenant.createArrayOf("uuid", removed.keys.toTypedArray()))
                update.executeUpdate()
            }
        }
        val pending = ids.filter { it !in current }
        if (pending.isEmpty()) return Change(emptyList(), removed.values.toList())
        val total = tenant.prepareStatement("SELECT count(*) FROM crm_attachments WHERE lead_id = ? AND deleted_at IS NULL").use { query ->
            query.setObject(1, leadId)
            query.executeQuery().use { rows -> rows.next(); rows.getInt(1) }
        }
        if (total + pending.size > MaxFilesPerLead) throw CrmValidationException("Un lead admite hasta $MaxFilesPerLead archivos.")
        val added = tenant.prepareStatement(
            "UPDATE crm_attachments SET lead_id = ?, field_key = ? WHERE lead_id IS NULL AND field_key IS NULL AND deleted_at IS NULL AND id = ANY(?) RETURNING file_name"
        ).use { update ->
            update.setObject(1, leadId)
            update.setString(2, fieldKey)
            update.setArray(3, tenant.createArrayOf("uuid", pending.toTypedArray()))
            update.executeQuery().use { rows -> buildList { while (rows.next()) add(rows.getString(1)) } }
        }
        return Change(added, removed.values.toList())
    }

    /** Marks every file of a deleted custom field for deletion. */
    fun deleteField(tenant: Connection, fieldKey: String) {
        tenant.prepareStatement("UPDATE crm_attachments SET deleted_at = CURRENT_TIMESTAMP WHERE field_key = ? AND deleted_at IS NULL").use {
            it.setString(1, fieldKey)
            it.executeUpdate()
        }
    }

    /** A file that is not deleted: of a lead, or a pending upload. */
    fun file(tenant: Connection, files: ModuleFiles, id: UUID): StoredFile? = tenant.prepareStatement(
        "SELECT id, lead_id, file_name, folder_path, size_bytes, content_type, storage_backend, storage_ref, content IS NOT NULL FROM crm_attachments WHERE id = ? AND deleted_at IS NULL"
    ).use { query ->
        query.setObject(1, id)
        query.executeQuery().use { rows ->
            if (!rows.next()) return null
            val ref = UiFileRef(rows.getObject(1, UUID::class.java).toString(), rows.getString(3), rows.getString(4), rows.getLong(5))
            val leadId = rows.getObject(2, UUID::class.java)
            // Rows from V010 still hold their bytes until cleanup moves them.
            val open: () -> InputStream? = if (rows.getBoolean(9)) {
                { legacyContent(tenant, id)?.inputStream() }
            } else {
                val stored = StoredFileRef(rows.getString(7), rows.getString(8), ref.size)
                val fromStorage: () -> InputStream? = { files.open(stored) }
                fromStorage
            }
            StoredFile(ref, leadId, rows.getString(6), open)
        }
    }

    private fun legacyContent(tenant: Connection, id: UUID): ByteArray? =
        tenant.prepareStatement("SELECT content FROM crm_attachments WHERE id = ?").use { query ->
            query.setObject(1, id)
            query.executeQuery().use { rows -> if (rows.next()) rows.getBytes(1) else null }
        }

    /**
     * Periodic upkeep: moves contents still in the database (V010) to the file storage, and
     * deletes the stored files and rows of removed attachments, of deleted leads and of uploads
     * whose form was never saved within a day.
     */
    fun cleanup(tenant: Connection, files: ModuleFiles, batch: Int = 200) {
        moveLegacy(tenant, files, batch)
        val doomed = tenant.prepareStatement(
            """
            SELECT id, storage_backend, storage_ref, size_bytes FROM crm_attachments
            WHERE deleted_at IS NOT NULL
               OR (lead_id IS NULL AND field_key IS NOT NULL)
               OR (lead_id IS NULL AND created_at < CURRENT_TIMESTAMP - INTERVAL '1 day')
            LIMIT ?
            """.trimIndent()
        ).use { query ->
            query.setInt(1, batch)
            query.executeQuery().use { rows -> buildList { while (rows.next()) add(rows.getObject(1, UUID::class.java) to rows.stored()) } }
        }
        doomed.forEach { (id, stored) ->
            try {
                stored?.let { files.delete(it) }
                tenant.prepareStatement("DELETE FROM crm_attachments WHERE id = ?").use { it.setObject(1, id); it.executeUpdate() }
            } catch (failure: Exception) {
                // The row stays and is retried on the next run.
                log.warn("No se pudo borrar el adjunto {} de {}", id, files.companyCode, failure)
            }
        }
    }

    private fun moveLegacy(tenant: Connection, files: ModuleFiles, batch: Int) {
        val ids = tenant.prepareStatement("SELECT id FROM crm_attachments WHERE content IS NOT NULL AND deleted_at IS NULL LIMIT ?").use { query ->
            query.setInt(1, batch)
            query.executeQuery().use { rows -> buildList { while (rows.next()) add(rows.getObject(1, UUID::class.java)) } }
        }
        ids.forEach { id ->
            val (name, type, bytes) = tenant.prepareStatement("SELECT file_name, content_type, content FROM crm_attachments WHERE id = ?").use { query ->
                query.setObject(1, id)
                query.executeQuery().use { rows -> if (rows.next()) Triple(rows.getString(1), rows.getString(2), rows.getBytes(3)) else null }
            } ?: return@forEach
            if (bytes == null) return@forEach
            val stored = runCatching { files.put(name, type, bytes.inputStream(), Long.MAX_VALUE) }
                .onFailure { log.warn("No se pudo mover el adjunto {} de {}", id, files.companyCode, it) }
                .getOrNull() ?: return@forEach
            val updated = tenant.prepareStatement(
                "UPDATE crm_attachments SET storage_backend = ?, storage_ref = ?, content = NULL WHERE id = ? AND content IS NOT NULL"
            ).use { update ->
                update.setString(1, stored.backend)
                update.setString(2, stored.ref)
                update.setObject(3, id)
                update.executeUpdate()
            }
            if (updated == 0) runCatching { files.delete(stored) }
        }
    }

    private fun ResultSet.stored(): StoredFileRef? {
        val backend = getString(2) ?: return null
        val ref = getString(3) ?: return null
        return StoredFileRef(backend, ref, getLong(4))
    }

    /** "Documentos: +a.pdf, -b.png" for the history, or null when nothing changed. */
    fun describe(label: String, change: Change): String? {
        val parts = change.added.map { "+$it" } + change.removed.map { "-$it" }
        if (parts.isEmpty()) return null
        val shown = parts.take(10).joinToString(", ") + if (parts.size > 10) " y ${parts.size - 10} mas" else ""
        return "$label: $shown"
    }
}
