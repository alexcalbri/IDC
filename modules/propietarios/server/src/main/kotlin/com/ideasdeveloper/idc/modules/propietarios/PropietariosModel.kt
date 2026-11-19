package com.ideasdeveloper.idc.modules.propietarios

import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/** Estados fijos de un lote: los filtros, los totales y los agentes dependen de estos codigos. */
enum class LotStatus(val code: String, val label: String) {
    Disponible("disponible", "Disponible"),
    Reservado("reservado", "Reservado"),
    Vendido("vendido", "Vendido"),
    EnConstruccion("en_construccion", "En construcción"),
    Construido("construido", "Construido");

    companion object {
        fun fromCode(code: String?): LotStatus? = entries.firstOrNull { it.code == code?.trim()?.lowercase() }

        /** Acepta la etiqueta visible (sin distinguir mayusculas) y, por comodidad, el codigo. */
        fun fromLabel(label: String?): LotStatus? {
            val text = label?.trim().orEmpty()
            return entries.firstOrNull { it.label.equals(text, ignoreCase = true) } ?: fromCode(text)
        }
    }
}

/** Se lanza por una regla del modulo (limites, referencias que no existen); el mensaje se muestra al usuario. */
class PropietariosValidationException(message: String) : Exception(message)

data class Development(
    val id: UUID,
    val name: String,
    val location: String,
    val mapUrl: String,
    val latitude: BigDecimal?,
    val longitude: BigDecimal?,
    val notes: String,
    val archived: Boolean,
)

/** Un desarrollo con sus metricas: lotes (sin archivar), area total en m2 y titulares vigentes distintos. */
data class DevelopmentSummary(val development: Development, val lots: Int, val totalAreaM2: BigDecimal, val owners: Int)

data class Lot(
    val id: UUID,
    val developmentId: UUID,
    val developmentName: String,
    val lotNumber: String,
    val registerNumber: String,
    val propertyName: String,
    val propertyType: String,
    val status: LotStatus,
    val areaM2: BigDecimal?,
    val latitude: BigDecimal?,
    val longitude: BigDecimal?,
    val mapUrl: String,
    val notes: String,
    val archived: Boolean,
)

data class Ownership(
    val id: UUID,
    val lotId: UUID,
    val customerId: UUID,
    val customerName: String,
    val customerEmail: String,
    val sharePercent: BigDecimal?,
    val startsOn: LocalDate?,
    val endsOn: LocalDate?,
    val notes: String,
    val createdAt: Instant,
    val endedAt: Instant?,
    /** Vigente hoy: sin fecha de fin o con fecha de fin posterior a hoy. */
    val current: Boolean,
)

/** Una titularidad con el lote al que pertenece (pantalla de un propietario). */
data class OwnedLot(val ownership: Ownership, val lot: Lot)

/** Un cliente con titularidades vigentes: cuantos lotes tiene y su area total. */
data class OwnerSummary(val customerId: UUID, val name: String, val email: String, val lots: Int, val totalAreaM2: BigDecimal)

data class LotHistoryEntry(val id: UUID, val kind: String, val happenedOn: LocalDate, val detail: String, val author: String, val createdAt: Instant)

data class LotAttachment(
    val id: UUID,
    val lotId: UUID?,
    val name: String,
    val path: String,
    val contentType: String,
    val size: Long,
    val uploadedBy: UUID?,
    val createdAt: Instant,
)

/** Un cliente visible: lo minimo que el modulo muestra de el (el resto vive en Clientes). */
data class CustomerRef(val id: UUID, val name: String, val email: String)

/** Lotes y area de un estado en un desarrollo. */
data class StatusTotals(val lots: Int, val areaM2: BigDecimal)

data class LotFilter(
    val developmentId: UUID? = null,
    val status: LotStatus? = null,
    val query: String? = null,
    val limit: Int = 200,
)

data class DevelopmentInput(
    val name: String,
    val location: String = "",
    val mapUrl: String = "",
    val latitude: BigDecimal? = null,
    val longitude: BigDecimal? = null,
    val notes: String = "",
) {
    fun validate(): List<String> = buildList {
        if (name.isBlank()) add("El nombre del desarrollo es obligatorio.")
        if (name.trim().length > PropietariosRules.MaxName) add("El nombre del desarrollo admite hasta ${PropietariosRules.MaxName} caracteres.")
        if (location.trim().length > PropietariosRules.MaxLocation) add("La ubicación admite hasta ${PropietariosRules.MaxLocation} caracteres.")
        addAll(PropietariosRules.validateMap(mapUrl, latitude, longitude))
        if (notes.length > PropietariosRules.MaxNotes) add("Las notas admiten hasta ${PropietariosRules.MaxNotes} caracteres.")
    }
}

data class LotInput(
    val developmentId: UUID,
    val lotNumber: String,
    val registerNumber: String = "",
    val propertyName: String = "",
    val propertyType: String = "",
    val status: LotStatus = LotStatus.Disponible,
    val areaM2: BigDecimal? = null,
    val latitude: BigDecimal? = null,
    val longitude: BigDecimal? = null,
    val mapUrl: String = "",
    val notes: String = "",
) {
    fun validate(): List<String> = buildList {
        if (lotNumber.isBlank()) add("El número de lote es obligatorio.")
        if (lotNumber.trim().length > PropietariosRules.MaxLotNumber) add("El número de lote admite hasta ${PropietariosRules.MaxLotNumber} caracteres.")
        if (registerNumber.trim().length > PropietariosRules.MaxRegister) add("El número de registro admite hasta ${PropietariosRules.MaxRegister} caracteres.")
        if (propertyName.trim().length > PropietariosRules.MaxName) add("El nombre de la propiedad admite hasta ${PropietariosRules.MaxName} caracteres.")
        if (propertyType.trim().length > PropietariosRules.MaxPropertyType) add("El tipo de propiedad admite hasta ${PropietariosRules.MaxPropertyType} caracteres.")
        if (areaM2 != null && (areaM2.signum() < 0 || areaM2.compareTo(PropietariosRules.MaxArea) > 0)) add("El área debe estar entre 0 y ${PropietariosRules.MaxArea.toPlainString()} m².")
        addAll(PropietariosRules.validateMap(mapUrl, latitude, longitude))
        if (notes.length > PropietariosRules.MaxNotes) add("Las notas admiten hasta ${PropietariosRules.MaxNotes} caracteres.")
    }
}

/** Un titular nuevo de un cambio de titularidad: el cliente y su porcentaje (opcional). */
data class NewOwner(val customerId: UUID, val sharePercent: BigDecimal? = null)

/** Lo que decide el cambio de titularidad antes de escribir: que titularidades se cierran y el estado del lote despues. */
data class TransferPlan(val closeIds: List<UUID>, val newStatus: LotStatus, val kind: String)

/**
 * Reglas puras (sin base de datos) del modulo: limites, porcentajes, enlaces de mapa y el plan de un cambio de titularidad.
 * Los servicios las aplican y las pruebas las ejercitan directamente.
 */
object PropietariosRules {
    const val MaxName = 200
    const val MaxLocation = 300
    const val MaxLotNumber = 60
    const val MaxRegister = 80
    const val MaxPropertyType = 80
    const val MaxNotes = 5000
    const val MaxMapUrl = 1000
    const val MaxNewOwners = 10
    const val MaxFormOwners = 4
    const val MaxDetail = 5000
    val MaxArea: BigDecimal = BigDecimal("999999999999.99")
    private val Hundred = BigDecimal(100)

    /** Corta [text] a [max] caracteres terminando en "…" en lugar de fallar. */
    fun clip(text: String, max: Int): String = if (text.length <= max) text else text.take(max - 1).trimEnd() + "…"

    /** Un porcentaje valido: mayor que 0 y hasta 100, con a lo mas dos decimales. */
    fun validShare(share: BigDecimal): Boolean =
        share.signum() > 0 && share.compareTo(Hundred) <= 0 && share.stripTrailingZeros().scale() <= 2

    /**
     * Comprueba los porcentajes de los titulares que quedan vigentes: cada uno valido y la suma, junto con [alreadyAssigned]
     * (lo que ya tienen otros titulares vigentes del lote), de 100 como maximo. Los vacios no cuentan.
     */
    fun checkShares(shares: List<BigDecimal?>, alreadyAssigned: BigDecimal = BigDecimal.ZERO) {
        val given = shares.filterNotNull()
        given.forEach { share ->
            if (!validShare(share)) throw PropietariosValidationException("Cada porcentaje debe ser mayor que 0 y de 100 como máximo, con hasta dos decimales.")
        }
        val total = given.fold(alreadyAssigned) { sum, share -> sum + share }
        if (total.compareTo(Hundred) > 0) throw PropietariosValidationException("Los porcentajes de los titulares suman más de 100 % (${total.stripTrailingZeros().toPlainString()} %).")
    }

    /** Problemas del enlace de mapa y de las coordenadas (lista vacia si todo esta bien). */
    fun validateMap(mapUrl: String, latitude: BigDecimal?, longitude: BigDecimal?): List<String> = buildList {
        val url = mapUrl.trim()
        if (url.length > MaxMapUrl) add("El enlace del mapa admite hasta $MaxMapUrl caracteres.")
        if (url.isNotEmpty() && !isHttps(url)) add("El enlace del mapa debe empezar por https://.")
        if (latitude != null && (latitude.abs().compareTo(BigDecimal(90)) > 0)) add("La latitud debe estar entre -90 y 90.")
        if (longitude != null && (longitude.abs().compareTo(BigDecimal(180)) > 0)) add("La longitud debe estar entre -180 y 180.")
        if ((latitude == null) != (longitude == null)) add("Escribe la latitud y la longitud juntas, o ninguna.")
    }

    private fun isHttps(url: String): Boolean = url.length > 8 && url.startsWith("https://", ignoreCase = true) && url.none { it.isWhitespace() }

    /** A donde lleva "Ver en mapa": el enlace propio si es https; si no, Google Maps con las coordenadas; si no hay nada, null. */
    fun mapLink(mapUrl: String, latitude: BigDecimal?, longitude: BigDecimal?): String? {
        val url = mapUrl.trim()
        if (isHttps(url)) return url
        if (latitude == null || longitude == null) return null
        return "https://www.google.com/maps?q=${latitude.toPlainString()},${longitude.toPlainString()}"
    }

    /** Un lote comprado deja de estar disponible o reservado; los demas estados no cambian solos. */
    fun statusAfterOwnership(current: LotStatus): LotStatus =
        if (current == LotStatus.Disponible || current == LotStatus.Reservado) LotStatus.Vendido else current

    /** "Nombre (50 %)" o solo el nombre si no hay porcentaje. */
    fun ownerLabel(name: String, share: BigDecimal?): String =
        if (share == null) name else "$name (${share.stripTrailingZeros().toPlainString()} %)"

    /**
     * Decide un cambio de titularidad sin tocar la base de datos. [open] son las titularidades del lote que siguen abiertas en [date]
     * (sin fecha de fin, o con una posterior); [visibleCustomers] los clientes que el usuario puede ver. Una lista vacia de [newOwners] es una baja.
     */
    fun planTransfer(
        date: LocalDate,
        open: List<Ownership>,
        newOwners: List<NewOwner>,
        visibleCustomers: Set<UUID>,
        currentStatus: LotStatus,
    ): TransferPlan {
        if (newOwners.size > MaxNewOwners) throw PropietariosValidationException("Un cambio admite hasta $MaxNewOwners titulares nuevos.")
        if (newOwners.map { it.customerId }.toSet().size != newOwners.size) throw PropietariosValidationException("Un titular no puede repetirse en el mismo cambio.")
        newOwners.forEach { owner ->
            if (owner.customerId !in visibleCustomers) throw PropietariosValidationException("Uno de los titulares ya no está disponible en Clientes.")
        }
        checkShares(newOwners.map { it.sharePercent })
        if (newOwners.isEmpty() && open.isEmpty()) throw PropietariosValidationException("El lote no tiene titulares vigentes que dar de baja.")
        open.firstOrNull { it.startsOn != null && it.startsOn.isAfter(date) }?.let { blocked ->
            throw PropietariosValidationException("La fecha del cambio es anterior al inicio de la titularidad de ${blocked.customerName} (${blocked.startsOn}).")
        }
        return if (newOwners.isEmpty()) {
            TransferPlan(open.map { it.id }, currentStatus, "baja")
        } else {
            TransferPlan(open.map { it.id }, statusAfterOwnership(currentStatus), "traspaso")
        }
    }

    /** El texto del historial: "De: A, B → A: C" (traspaso), "Baja de: A" o "Alta de: C", con las notas si las hay. */
    fun historyDetail(kind: String, from: List<String>, to: List<String>, notes: String): String {
        val head = when (kind) {
            "baja" -> "Baja de: ${from.joinToString(", ").ifEmpty { "sin titulares" }}"
            "alta" -> "Alta de: ${to.joinToString(", ")}"
            else -> "De: ${from.joinToString(", ").ifEmpty { "sin titulares" }} → A: ${to.joinToString(", ")}"
        }
        val clean = notes.trim()
        return clip(if (clean.isEmpty()) head else "$head\nNotas: $clean", MaxDetail)
    }
}
