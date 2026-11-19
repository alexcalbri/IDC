package com.ideasdeveloper.idc.modules.propietarios

import com.ideasdeveloper.idc.ui.UiFieldValues
import com.ideasdeveloper.idc.ui.UiFileRef
import com.ideasdeveloper.idc.ui.UiJson
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import java.math.BigDecimal
import java.time.LocalDate
import java.time.format.DateTimeParseException
import java.util.UUID

/** Resultado de comparar los archivos de un formulario con los ya unidos al lote. */
data class FileChanges(val added: List<UiFileRef>, val removed: List<UUID>)

/**
 * Lectura pura de lo que envian las pantallas (etiquetas elegidas, fechas, numeros, valores JSON) a los tipos del modulo.
 * Nada aqui toca la base de datos: el servicio aplica las reglas despues.
 */
object PropietariosActions {
    const val AllOption = "Todos"
    const val InvalidForm = "Formulario demasiado grande o no válido."
    private const val MaxBodyChars = 1_000_000

    /** Valores de un formulario: cadenas tal cual y un arreglo JSON (lista desplegable multiple) como su texto codificado. */
    fun bodyValues(body: String): Map<String, String> {
        if (body.length > MaxBodyChars) return emptyMap()
        return runCatching {
            UiJson.parseToJsonElement(body).jsonObject.mapValues { (_, value) ->
                when (value) {
                    is JsonPrimitive -> value.contentOrNull.orEmpty()
                    is JsonArray -> UiFieldValues.options(value.map { (it as? JsonPrimitive)?.contentOrNull.orEmpty() })
                    else -> ""
                }
            }
        }.getOrDefault(emptyMap())
    }

    /** Como [bodyValues], pero un cuerpo demasiado grande o que no es un objeto JSON es un error para el usuario. */
    fun formValues(body: String): Map<String, String> {
        if (body.length > MaxBodyChars) throw PropietariosValidationException(InvalidForm)
        val parsed = runCatching { UiJson.parseToJsonElement(body).jsonObject }.getOrNull() ?: throw PropietariosValidationException(InvalidForm)
        return bodyValues(parsed.toString())
    }

    fun uuid(text: String?): UUID? = text?.trim()?.takeIf { it.isNotEmpty() }?.let { runCatching { UUID.fromString(it) }.getOrNull() }

    /** Una fecha ISO (aaaa-mm-dd); vacio es "sin fecha". */
    fun date(text: String?): LocalDate? {
        val clean = text?.trim().orEmpty()
        if (clean.isEmpty()) return null
        return try {
            LocalDate.parse(clean)
        } catch (_: DateTimeParseException) {
            throw PropietariosValidationException("La fecha debe tener el formato AAAA-MM-DD.")
        }
    }

    /** Un numero decimal (acepta coma); vacio es "sin valor". */
    fun decimal(text: String?, label: String): BigDecimal? {
        val clean = text?.trim().orEmpty().replace(',', '.')
        if (clean.isEmpty()) return null
        if (clean.length > 30) throw PropietariosValidationException("$label no es un número válido.")
        return clean.toBigDecimalOrNull() ?: throw PropietariosValidationException("$label no es un número válido.")
    }

    fun status(text: String?): LotStatus =
        LotStatus.fromLabel(text) ?: throw PropietariosValidationException("Estado no válido: ${text.orEmpty()}.")

    /** El filtro de estado de una pantalla: "Todos" y las etiquetas desconocidas significan sin filtro. */
    fun statusFilter(text: String?): LotStatus? = text?.takeIf { !it.equals(AllOption, ignoreCase = true) }?.let { LotStatus.fromLabel(it) }

    fun developmentInput(values: Map<String, String>): DevelopmentInput {
        val input = DevelopmentInput(
            name = values["name"].orEmpty().trim(),
            location = values["location"].orEmpty().trim(),
            mapUrl = values["map_url"].orEmpty().trim(),
            latitude = decimal(values["latitude"], "La latitud"),
            longitude = decimal(values["longitude"], "La longitud"),
            notes = values["notes"].orEmpty().trim(),
        )
        val problems = input.validate()
        if (problems.isNotEmpty()) throw PropietariosValidationException(problems.joinToString(" "))
        return input
    }

    fun lotInput(developmentId: UUID, values: Map<String, String>): LotInput {
        val input = LotInput(
            developmentId = developmentId,
            lotNumber = values["lot_number"].orEmpty().trim(),
            registerNumber = values["register_number"].orEmpty().trim(),
            propertyName = values["property_name"].orEmpty().trim(),
            propertyType = values["property_type"].orEmpty().trim(),
            status = values["status"]?.takeIf { it.isNotBlank() }?.let { status(it) } ?: LotStatus.Disponible,
            areaM2 = decimal(values["area_m2"], "El área"),
            latitude = decimal(values["latitude"], "La latitud"),
            longitude = decimal(values["longitude"], "La longitud"),
            mapUrl = values["map_url"].orEmpty().trim(),
            notes = values["notes"].orEmpty().trim(),
        )
        val problems = input.validate()
        if (problems.isNotEmpty()) throw PropietariosValidationException(problems.joinToString(" "))
        return input
    }

    /**
     * Los titulares nuevos del formulario de traspaso (campos owner1..owner4, owner1_id.. y share1..share4). Un nombre sin cliente
     * elegido de la lista de sugerencias es un error; los renglones vacios se ignoran.
     */
    fun newOwners(values: Map<String, String>): List<NewOwner> = (1..PropietariosRules.MaxFormOwners).mapNotNull { index ->
        val name = values["owner$index"]?.trim().orEmpty()
        if (name.isEmpty()) return@mapNotNull null
        val id = uuid(values["owner${index}_id"]) ?: throw PropietariosValidationException("Elige el cliente $index de la lista de sugerencias.")
        NewOwner(id, decimal(values["share$index"], "El porcentaje $index"))
    }

    /** El cotitular del formulario de un lote (campos owner, owner_id y share). */
    fun coOwner(values: Map<String, String>): NewOwner {
        val name = values["owner"]?.trim().orEmpty()
        if (name.isEmpty()) throw PropietariosValidationException("Elige el cliente.")
        val id = uuid(values["owner_id"]) ?: throw PropietariosValidationException("Elige el cliente de la lista de sugerencias.")
        return NewOwner(id, decimal(values["share"], "El porcentaje"))
    }

    /**
     * Archivos del formulario frente a los unidos ahora: los nuevos se unen, los que faltan se quitan. Un formulario sin la clave
     * "files" (de solo lectura) no cambia nada.
     */
    fun fileChanges(values: Map<String, String>, current: List<LotAttachment>): FileChanges {
        val raw = values["files"] ?: return FileChanges(emptyList(), emptyList())
        val sent = UiFieldValues.files(raw)
        val currentIds = current.map { it.id.toString() }.toSet()
        val sentIds = sent.map { it.id }.toSet()
        return FileChanges(
            added = sent.filter { it.id !in currentIds },
            removed = current.filter { it.id.toString() !in sentIds }.map { it.id },
        )
    }

    /**
     * Permiso minimo que la ruta pide antes de abrir la conexion (el servicio aplica las reglas finas): ver para leer; gestionar para
     * lotes, titularidad y adjuntos; administrar para desarrollos y archivar lotes; importar para la pantalla de importacion.
     */
    fun requiredPermission(screen: String, op: String?): String = when (screen) {
        "desarrollos" -> if (op == "dev-save") PropietariosModule.Admin else PropietariosModule.View
        "desarrollo" -> when (op) {
            "dev-save", "dev-archive" -> PropietariosModule.Admin
            else -> PropietariosModule.View
        }
        "lote" -> when (op) {
            "archive" -> PropietariosModule.Admin
            "save", "files", "add-owner" -> PropietariosModule.Manage
            else -> PropietariosModule.View
        }
        "traspaso", "titularidad" -> PropietariosModule.Manage
        "importar" -> PropietariosModule.Import
        else -> PropietariosModule.View
    }
}
