package com.ideasdeveloper.idc.modules.crm

import java.sql.Connection

/** Campo que un tipo de importacion acepta: [key] es la clave en [CrmImportRecord.values]; [hint] es una ayuda corta para el mapeo. */
data class CrmImportField(val key: String, val label: String, val required: Boolean = false, val hint: String = "")

/** Tipo de registro que una extension sabe importar (por ejemplo "property" o "deal") con los campos que acepta. */
data class CrmImportKind(val id: String, val label: String, val fields: List<CrmImportField>)

/** Un registro a importar. [importKey] es la clave estable de la fuente (p. ej. "monday:<itemId>"): reimportar actualiza, nunca duplica. */
data class CrmImportRecord(val importKey: String, val values: Map<String, String>)

/**
 * Resultado de un lote: registros creados, actualizados y omitidos.
 * - [problems]: solo errores ("<importKey>: <mensaje>" de cada registro no guardado, o un mensaje del lote entero).
 * - [failedKeys]: la [CrmImportRecord.importKey] de cada registro que NO se guardo.
 * - [warnings]: "<importKey>: <aviso>" de registros que si se guardaron pero con algo que revisar.
 */
data class CrmImportOutcome(
    val created: Int,
    val updated: Int,
    val skipped: Int,
    val problems: List<String>,
    val failedKeys: List<String> = emptyList(),
    val warnings: List<String> = emptyList(),
)

/**
 * Destino de importacion que una extension del CRM ofrece a otras extensiones (por ejemplo las que migran datos desde otra
 * herramienta). Cada extension carga en su propio classloader, asi que se hablan por esta interfaz del CRM.
 *
 * Quien llama DEBE comprobar antes que el usuario tiene `crm.settings`: el destino aplica el lote con permisos de importacion
 * (sin filtrar por la visibilidad del usuario) y solo conoce su id y nombre.
 */
interface CrmImportTarget {
    val extensionId: String

    val importKinds: List<CrmImportKind>

    /** Aplica un lote en una transaccion; valida cada registro y reporta problemas sin abortar el lote. */
    fun importRecords(tenant: Connection, actor: CrmActor, kind: String, records: List<CrmImportRecord>): CrmImportOutcome
}
