package com.ideasdeveloper.idc.modules.compras

import com.ideasdeveloper.idc.server.modules.FileTooLargeException
import com.ideasdeveloper.idc.server.modules.ModuleFiles
import com.ideasdeveloper.idc.server.modules.StoredFileRef
import com.ideasdeveloper.idc.ui.UiFileRef
import org.slf4j.LoggerFactory
import java.io.InputStream
import java.sql.Connection
import java.sql.ResultSet
import java.time.Instant
import java.util.UUID

/**
 * Respaldos de una solicitud (facturas, proformas, cotizaciones...). El contenido vive en el almacen de
 * archivos de la empresa ([ModuleFiles]); la tabla guarda nombre, carpeta, solicitud y la referencia.
 * Una subida queda pendiente (sin solicitud) hasta que su formulario se guarda con [link]; solo quien la
 * subio puede vincularla o descargarla mientras esta pendiente. Los archivos solo se borran en [cleanup],
 * despues de marcar la fila, para que un guardado revertido no pierda un archivo que una solicitud aun usa.
 *
 * Bloqueante (JDBC y disco): llamar dentro de withContext(Dispatchers.IO).
 */
object ComprasAttachments {
    const val MaxFileBytes = 25L * 1024 * 1024
    const val MaxFilesPerRequest = 100

    /** Subidas pendientes (sin solicitud) que un usuario puede tener a la vez. */
    const val MaxPendingPerUser = 50
    val Kinds = setOf("support", "quote", "proforma", "invoice", "other")
    private val log = LoggerFactory.getLogger(ComprasAttachments::class.java)

    /** Un adjunto de una solicitud para mostrarlo; [ref] es lo que entiende UiFiles. */
    data class Attachment(val ref: UiFileRef, val kind: String, val quoteId: UUID?, val contentType: String, val uploadedBy: String, val createdAt: Instant)

    /** Archivo listo para descargar: [open] devuelve el contenido (null si el almacen ya no lo tiene). */
    class StoredFile(val ref: UiFileRef, val requestId: UUID?, val contentType: String, val open: () -> InputStream?)

    /** Cambio de adjuntos de una solicitud: nombres anadidos y quitados. */
    data class Change(val added: List<String>, val removed: List<String>)

    /** Nombre sin carpetas ni caracteres de control. */
    fun cleanName(name: String): String = name.substringAfterLast('/').substringAfterLast('\\').filter { it >= ' ' }.trim().take(255)

    /** Carpeta normalizada a a/b/c, sin ".." ni caracteres de control. */
    fun cleanPath(path: String): String = path.replace('\\', '/').split('/')
        .map { part -> part.filter { it >= ' ' }.trim() }
        .filter { it.isNotEmpty() && it != "." && it != ".." }
        .joinToString("/")
        .take(500)

    /**
     * Guarda una subida pendiente de [actor] y devuelve la referencia que conserva el formulario. El contenido
     * se escribe primero en [files]; si la fila no se guarda, el archivo se borra.
     */
    fun upload(tenant: Connection, files: ModuleFiles, actor: Actor, name: String, path: String, contentType: String, content: InputStream): UiFileRef {
        if (!actor.can(ComprasModule.Create) && !actor.can(ComprasModule.Manage)) throw ComprasValidationException("No tienes permiso para subir archivos.")
        val userId = actor.userId ?: throw ComprasValidationException("No se pudo identificar al usuario.")
        val fileName = cleanName(name)
        if (fileName.isEmpty()) throw ComprasValidationException("El archivo no tiene nombre.")
        val pending = tenant.prepareStatement("SELECT count(*) FROM compras_attachments WHERE uploaded_by_id = ? AND request_id IS NULL AND deleted_at IS NULL").use { query ->
            query.setObject(1, userId)
            query.executeQuery().use { rows -> rows.next(); rows.getInt(1) }
        }
        if (pending >= MaxPendingPerUser) throw ComprasValidationException("Tienes demasiados archivos sin guardar; guarda o recarga la solicitud antes de subir mas.")
        val type = contentType.substringBefore(';').trim().take(200).ifBlank { "application/octet-stream" }
        val stored = try {
            files.put(fileName, type, content, MaxFileBytes)
        } catch (_: FileTooLargeException) {
            throw ComprasValidationException("$fileName supera el maximo de ${MaxFileBytes / (1024 * 1024)} MB.")
        }
        val ref = UiFileRef(UUID.randomUUID().toString(), fileName, cleanPath(path), stored.size)
        try {
            tenant.prepareStatement(
                """
                INSERT INTO compras_attachments (id, file_name, folder_path, content_type, size_bytes, storage_backend, storage_ref, uploaded_by, uploaded_by_id)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """.trimIndent()
            ).use { insert ->
                ComprasSql.bind(insert, listOf(UUID.fromString(ref.id), ref.name, ref.path, type, ref.size, stored.backend, stored.ref, actor.name.take(200), userId))
                insert.executeUpdate()
            }
        } catch (failure: Exception) {
            runCatching { files.delete(stored) }
            throw failure
        }
        return ref
    }

    private fun ResultSet.attachment() = Attachment(
        ref = UiFileRef(getObject("id", UUID::class.java).toString(), getString("file_name"), getString("folder_path"), getLong("size_bytes")),
        kind = getString("kind"),
        quoteId = getObject("quote_id", UUID::class.java),
        contentType = getString("content_type"),
        uploadedBy = getString("uploaded_by"),
        createdAt = getTimestamp("created_at").toInstant(),
    )

    /** Adjuntos de la solicitud (vacio si no es visible para [actor]), por carpeta y nombre. */
    fun forRequest(tenant: Connection, actor: Actor, requestId: UUID): List<Attachment> {
        if (ComprasRepository.get(tenant, requestId, actor) == null) return emptyList()
        return tenant.prepareStatement(
            """
            SELECT id, quote_id, kind, file_name, folder_path, content_type, size_bytes, uploaded_by, created_at
            FROM compras_attachments WHERE request_id = ? AND deleted_at IS NULL ORDER BY folder_path, file_name
            """.trimIndent()
        ).use { query ->
            query.setObject(1, requestId)
            query.executeQuery().use { rows -> buildList { while (rows.next()) add(rows.attachment()) } }
        }
    }

    /**
     * Vincula a la solicitud las subidas pendientes [ids] que subio [actor] (los ids ya vinculados o de otros se
     * ignoran). Permitido en cualquier estado menos cancelada ([ComprasFlow.canAddAttachment]). [quoteId] liga
     * los archivos a una cotizacion de la misma solicitud. Registra el evento "attachment".
     */
    fun link(tenant: Connection, actor: Actor, requestId: UUID, ids: List<UUID>, quoteId: UUID? = null, kind: String = "support"): Change {
        if (ids.isEmpty()) return Change(emptyList(), emptyList())
        if (kind !in Kinds) throw ComprasValidationException("Tipo de adjunto no valido.")
        val userId = actor.userId ?: throw ComprasValidationException("No se pudo identificar al usuario.")
        return ComprasSql.transaction(tenant) {
            // Fila bloqueada primero: el estado, las cotizaciones y el tope de archivos no cambian mientras se vincula.
            ComprasRepository.lockRequest(tenant, requestId)
            val request = ComprasRepository.get(tenant, requestId, actor) ?: throw ComprasValidationException("La solicitud no existe o no tienes acceso a ella.")
            val approver = ComprasRepository.approver(tenant, actor)
            if (!ComprasFlow.canAddAttachment(request.status, actor, ComprasFlow.contextOf(request, approver))) {
                throw ComprasValidationException("No puedes agregar archivos a esta solicitud en estado ${request.status.label}.")
            }
            if (quoteId != null) {
                val belongs = tenant.prepareStatement("SELECT 1 FROM compras_quotes WHERE id = ? AND request_id = ?").use { query ->
                    query.setObject(1, quoteId)
                    query.setObject(2, requestId)
                    query.executeQuery().use { it.next() }
                }
                if (!belongs) throw ComprasValidationException("La cotizacion no pertenece a la solicitud.")
            }
            val total = tenant.prepareStatement("SELECT count(*) FROM compras_attachments WHERE request_id = ? AND deleted_at IS NULL").use { query ->
                query.setObject(1, requestId)
                query.executeQuery().use { rows -> rows.next(); rows.getInt(1) }
            }
            if (total + ids.size > MaxFilesPerRequest) throw ComprasValidationException("Una solicitud admite hasta $MaxFilesPerRequest archivos.")
            val added = tenant.prepareStatement(
                """
                UPDATE compras_attachments SET request_id = ?, quote_id = ?, kind = ?
                WHERE request_id IS NULL AND deleted_at IS NULL AND uploaded_by_id = ? AND id = ANY(?)
                RETURNING file_name
                """.trimIndent()
            ).use { update ->
                update.setObject(1, requestId)
                update.setObject(2, quoteId)
                update.setString(3, kind)
                update.setObject(4, userId)
                update.setArray(5, tenant.createArrayOf("uuid", ids.distinct().toTypedArray()))
                update.executeQuery().use { rows -> buildList { while (rows.next()) add(rows.getString(1)) } }
            }
            describe(Change(added, emptyList()))?.let { ComprasRepository.addEvent(tenant, requestId, actor, "attachment", it) }
            Change(added, emptyList())
        }
    }

    /**
     * Quita (marca para borrar) un adjunto: en borrador o cotizacion el solicitante, o con compras.manage en
     * cualquier estado. Devuelve el nombre del archivo quitado, o null si no existia.
     */
    fun remove(tenant: Connection, actor: Actor, requestId: UUID, attachmentId: UUID): String? {
        val request = ComprasRepository.get(tenant, requestId, actor) ?: throw ComprasValidationException("La solicitud no existe o no tienes acceso a ella.")
        if (!ComprasFlow.canRemoveAttachment(request.status, actor, request.requesterId)) {
            throw ComprasValidationException("No puedes quitar archivos de esta solicitud en estado ${request.status.label}.")
        }
        return ComprasSql.transaction(tenant) {
            val name = tenant.prepareStatement(
                "UPDATE compras_attachments SET deleted_at = CURRENT_TIMESTAMP WHERE id = ? AND request_id = ? AND deleted_at IS NULL RETURNING file_name"
            ).use { update ->
                update.setObject(1, attachmentId)
                update.setObject(2, requestId)
                update.executeQuery().use { rows -> if (rows.next()) rows.getString(1) else null }
            }
            name?.let { ComprasRepository.addEvent(tenant, requestId, actor, "attachment", "Adjuntos: -$it") }
            name
        }
    }

    /**
     * Archivo para descargar. Si pertenece a una solicitud, esta debe ser visible para [actor]; si es una subida
     * pendiente, solo la ve quien la subio. Null si no existe, esta borrado o no tiene acceso.
     */
    fun file(tenant: Connection, files: ModuleFiles, actor: Actor, id: UUID): StoredFile? {
        class Row(val ref: UiFileRef, val requestId: UUID?, val contentType: String, val stored: StoredFileRef, val uploaderId: UUID?)
        val row = tenant.prepareStatement(
            """
            SELECT id, request_id, file_name, folder_path, size_bytes, content_type, storage_backend, storage_ref, uploaded_by_id
            FROM compras_attachments WHERE id = ? AND deleted_at IS NULL
            """.trimIndent()
        ).use { query ->
            query.setObject(1, id)
            query.executeQuery().use { rows ->
                if (!rows.next()) return null
                val ref = UiFileRef(rows.getObject(1, UUID::class.java).toString(), rows.getString(3), rows.getString(4), rows.getLong(5))
                Row(ref, rows.getObject(2, UUID::class.java), rows.getString(6), StoredFileRef(rows.getString(7), rows.getString(8), ref.size), rows.getObject(9, UUID::class.java))
            }
        }
        val allowed = if (row.requestId == null) actor.isUser(row.uploaderId) else ComprasRepository.get(tenant, row.requestId, actor) != null
        if (!allowed) return null
        return StoredFile(row.ref, row.requestId, row.contentType) { files.open(row.stored) }
    }

    /**
     * Trabajo periodico (idempotente): borra el archivo y despues la fila de los adjuntos quitados, de
     * solicitudes borradas y de subidas que no se guardaron en un dia. Los errores se registran y la fila
     * se reintenta en la siguiente corrida.
     */
    fun cleanup(tenant: Connection, files: ModuleFiles, batch: Int = 200) {
        val doomed = tenant.prepareStatement(
            """
            SELECT id, storage_backend, storage_ref, size_bytes FROM compras_attachments
            WHERE deleted_at IS NOT NULL OR (request_id IS NULL AND created_at < CURRENT_TIMESTAMP - INTERVAL '1 day')
            LIMIT ?
            """.trimIndent()
        ).use { query ->
            query.setInt(1, batch)
            query.executeQuery().use { rows ->
                buildList { while (rows.next()) add(rows.getObject(1, UUID::class.java) to StoredFileRef(rows.getString(2), rows.getString(3), rows.getLong(4))) }
            }
        }
        doomed.forEach { (id, stored) ->
            try {
                // Reclamar antes de borrar el archivo: si entre la consulta y ahora se vinculo a una solicitud, no coincide
                // y se salta; una vez marcado, link() ya no puede tomarlo (exige deleted_at IS NULL).
                val claimed = tenant.prepareStatement(
                    """
                    UPDATE compras_attachments SET deleted_at = COALESCE(deleted_at, CURRENT_TIMESTAMP)
                    WHERE id = ? AND (deleted_at IS NOT NULL OR (request_id IS NULL AND created_at < CURRENT_TIMESTAMP - INTERVAL '1 day'))
                    RETURNING id
                    """.trimIndent()
                ).use { claim ->
                    claim.setObject(1, id)
                    claim.executeQuery().use { it.next() }
                }
                if (claimed) {
                    files.delete(stored)
                    tenant.prepareStatement("DELETE FROM compras_attachments WHERE id = ? AND deleted_at IS NOT NULL").use { it.setObject(1, id); it.executeUpdate() }
                }
            } catch (failure: Exception) {
                log.warn("No se pudo borrar el adjunto {} de {}", id, files.companyCode, failure)
            }
        }
    }

    /** "Adjuntos: +a.pdf, -b.png" para el historial, o null si no cambio nada. */
    fun describe(change: Change): String? {
        val parts = change.added.map { "+$it" } + change.removed.map { "-$it" }
        if (parts.isEmpty()) return null
        val shown = parts.take(10).joinToString(", ") + if (parts.size > 10) " y ${parts.size - 10} mas" else ""
        return "Adjuntos: $shown"
    }
}
