package com.ideasdeveloper.idc.extensions.inmobiliaria

import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeParseException
import java.util.UUID

/** Error de negocio de la extension: su mensaje esta escrito para el usuario (pantallas y herramientas MCP lo muestran tal cual). */
class InmobiliariaException(message: String) : Exception(message)

/** Valor con id de base de datos y etiqueta en espanol. */
interface Coded {
    val id: String
    val label: String
}

private fun <E : Coded> Iterable<E>.byIdOrLabel(text: String?): E? {
    val wanted = text?.trim().orEmpty()
    if (wanted.isEmpty()) return null
    return firstOrNull { it.id.equals(wanted, ignoreCase = true) || it.label.equals(wanted, ignoreCase = true) }
}

enum class PropertyKind(override val id: String, override val label: String) : Coded {
    Land("land", "Terreno"),
    House("house", "Casa"),
    Condo("condo", "Condominio"),
    DevelopmentLot("development_lot", "Lote en desarrollo"),
    Commercial("commercial", "Comercial"),
    Other("other", "Otro");

    companion object {
        fun fromId(id: String?): PropertyKind? = entries.firstOrNull { it.id == id }

        /** Acepta el id o la etiqueta (sin distinguir mayusculas). */
        fun parse(text: String?): PropertyKind? = entries.byIdOrLabel(text)
    }
}

enum class PropertyStatus(override val id: String, override val label: String) : Coded {
    Intake("intake", "Captacion"),
    ForSale("for_sale", "En venta"),
    Negotiation("negotiation", "En negociacion"),
    Sold("sold", "Vendida"),
    Withdrawn("withdrawn", "Retirada");

    companion object {
        fun fromId(id: String?): PropertyStatus? = entries.firstOrNull { it.id == id }

        fun parse(text: String?): PropertyStatus? = entries.byIdOrLabel(text)
    }
}

enum class DealOutcome(override val id: String, override val label: String) : Coded {
    Open("open", "Abierta"),
    Won("won", "Ganada"),
    Lost("lost", "Perdida");

    companion object {
        fun fromId(id: String?): DealOutcome? = entries.firstOrNull { it.id == id }

        fun parse(text: String?): DealOutcome? = entries.byIdOrLabel(text)
    }
}

enum class PartyRole(override val id: String, override val label: String) : Coded {
    Seller("seller", "Vendedor"),
    Buyer("buyer", "Comprador"),
    ListingAgent("listing_agent", "Agente del listado"),
    BuyerAgent("buyer_agent", "Agente del comprador"),
    Broker("broker", "Corredor"),
    SellerLawyer("seller_lawyer", "Abogado del vendedor"),
    BuyerLawyer("buyer_lawyer", "Abogado del comprador"),
    Referral("referral", "Referido"),
    Other("other", "Otro");

    companion object {
        fun fromId(id: String?): PartyRole? = entries.firstOrNull { it.id == id }

        fun parse(text: String?): PartyRole? = entries.byIdOrLabel(text)
    }
}

/** Ajustes de la extension (fila unica de ext_inmobiliaria__settings). */
data class Settings(val currency: String = "USD", val defaultCommissionPct: BigDecimal = BigDecimal("5"))

data class Property(
    val id: UUID,
    val code: String,
    val title: String,
    val kind: PropertyKind,
    val zone: String,
    val development: String,
    val status: PropertyStatus,
    val listingAgentId: UUID?,
    val ownerCustomerId: UUID?,
    val lotAreaM2: BigDecimal?,
    val builtAreaM2: BigDecimal?,
    val bedrooms: Int?,
    val bathrooms: BigDecimal?,
    val price: BigDecimal?,
    val previousPrice: BigDecimal?,
    val currency: String,
    val description: String,
    val notes: String,
    val wordpressListingId: Long?,
    val publishedOn: LocalDate?,
    val soldOn: LocalDate?,
    val createdBy: UUID?,
    val createdAt: Instant,
    val updatedAt: Instant,
    // Clave de la fuente de una importacion (nula si se creo a mano).
    val importKey: String? = null,
) {
    /** "Rebajada": el precio actual es menor que el anterior (no es un estado, sale del historial de precios). */
    val reduced: Boolean get() = InmobiliariaMath.reduced(price, previousPrice)
    val pricePerLotM2: BigDecimal? get() = InmobiliariaMath.pricePerM2(price, lotAreaM2)
    val pricePerBuiltM2: BigDecimal? get() = InmobiliariaMath.pricePerM2(price, builtAreaM2)
}

data class PropertyLink(val id: UUID, val propertyId: UUID, val label: String, val url: String, val position: Int)

data class PriceChange(val id: UUID, val propertyId: UUID, val oldPrice: BigDecimal?, val newPrice: BigDecimal?, val changedBy: UUID?, val changedAt: Instant, val note: String)

data class DealStage(val id: UUID, val name: String, val position: Int, val outcome: DealOutcome)

data class DealParty(
    val id: UUID,
    val dealId: UUID,
    val role: PartyRole,
    val userId: UUID?,
    val customerId: UUID?,
    val name: String,
    // Porcentaje de la comision total de la transaccion.
    val sharePct: BigDecimal,
    val notes: String,
)

data class Deal(
    val id: UUID,
    val propertyId: UUID,
    val buyerLeadId: UUID?,
    val buyerCustomerId: UUID?,
    val stageId: UUID,
    val agreedPrice: BigDecimal?,
    val commissionPct: BigDecimal,
    // Monto fijado a mano; nulo = se calcula con el porcentaje.
    val commissionAmount: BigDecimal?,
    val currency: String,
    val estimatedCloseOn: LocalDate?,
    val closedOn: LocalDate?,
    val comments: String,
    val createdBy: UUID?,
    val createdAt: Instant,
    val updatedAt: Instant,
    // Datos de las tablas unidas (los llena el almacen).
    val stageName: String = "",
    val stageOutcome: DealOutcome = DealOutcome.Open,
    val propertyCode: String = "",
    val propertyTitle: String = "",
    val propertyListingAgentId: UUID? = null,
    val parties: List<DealParty> = emptyList(),
    // Clave de la fuente de una importacion (nula si se creo a mano).
    val importKey: String? = null,
) {
    /** Comision total: el monto fijado a mano o precio acordado x porcentaje. */
    val commissionTotal: BigDecimal? get() = InmobiliariaMath.commissionTotal(agreedPrice, commissionPct, commissionAmount)

    /** Aviso si los porcentajes de las partes no suman 100; nulo si no hay partes o suman 100. */
    val shareWarning: String? get() = InmobiliariaMath.shareWarning(parties.map { it.sharePct })

    /** Monto de una parte, redondeado por parte: los montos de las partes pueden no sumar exactamente [commissionTotal]. */
    fun partyAmount(party: DealParty): BigDecimal? = InmobiliariaMath.partyAmount(commissionTotal, party.sharePct)
}

/** Meses de cierre estimado de las transacciones abiertas, con sus totales. */
data class ClosingMonth(val month: YearMonth, val currency: String, val count: Int, val totalPrice: BigDecimal, val totalCommission: BigDecimal)

/** Calculos puros (sin base de datos). Dinero siempre en BigDecimal, con redondeo HALF_UP a 2 decimales. */
object InmobiliariaMath {
    private val Hundred = BigDecimal(100)

    /** Precio por metro cuadrado; nulo si falta el precio o el area es nula o cero. */
    fun pricePerM2(price: BigDecimal?, areaM2: BigDecimal?): BigDecimal? {
        if (price == null || areaM2 == null || areaM2.signum() <= 0) return null
        return price.divide(areaM2, 2, RoundingMode.HALF_UP)
    }

    /** True si el precio anterior es mayor que el actual. */
    fun reduced(price: BigDecimal?, previousPrice: BigDecimal?): Boolean =
        price != null && previousPrice != null && previousPrice > price

    /** Comision total: el monto manual si lo hay; si no, precio acordado x porcentaje / 100; nulo si no hay con que calcular. */
    fun commissionTotal(agreedPrice: BigDecimal?, commissionPct: BigDecimal?, manualAmount: BigDecimal?): BigDecimal? {
        if (manualAmount != null) return manualAmount.setScale(2, RoundingMode.HALF_UP)
        if (agreedPrice == null || commissionPct == null) return null
        return agreedPrice.multiply(commissionPct).divide(Hundred, 2, RoundingMode.HALF_UP)
    }

    /**
     * Monto de una parte: comision total x porcentaje de la parte / 100, redondeado a 2 decimales por parte: la suma de los montos
     * de las partes puede diferir en centavos de la comision total.
     */
    fun partyAmount(commissionTotal: BigDecimal?, sharePct: BigDecimal): BigDecimal? =
        commissionTotal?.multiply(sharePct)?.divide(Hundred, 2, RoundingMode.HALF_UP)

    fun shareSum(shares: Collection<BigDecimal>): BigDecimal = shares.fold(BigDecimal.ZERO) { total, share -> total + share }

    /** Aviso cuando hay partes y sus porcentajes no suman 100; nulo en otro caso. */
    fun shareWarning(shares: Collection<BigDecimal>): String? {
        if (shares.isEmpty()) return null
        val sum = shareSum(shares)
        if (sum.compareTo(Hundred) == 0) return null
        return "Los porcentajes de las partes suman ${plain(sum)} % y deberian sumar 100 %."
    }

    /** Numero sin ceros sobrantes ni notacion cientifica ("5.000" -> "5", "12.50" -> "12.5"). */
    fun plain(value: BigDecimal): String = if (value.signum() == 0) "0" else value.stripTrailingZeros().toPlainString()
}

/** Reglas puras de la maquina de estados de la venta (sin base de datos). */
object InmobiliariaRules {
    /**
     * Estado al que debe pasar la propiedad cuando una transaccion entra en una etapa con resultado [nextOutcome]; nulo = no cambiarlo.
     * [previousOutcome] es el resultado de la etapa de la que sale (nulo al abrir la transaccion); [otherOpen] y [otherWon] cuentan
     * las OTRAS transacciones de la propiedad abiertas y ganadas.
     * - Entrar en Ganada: Vendida.
     * - Salir de Ganada (a Abierta o Perdida): si otra transaccion sigue ganada, sigue Vendida; si no, vuelve a En negociacion (hay una
     *   abierta o la transaccion pasa a Abierta) o a En venta. Solo si la propiedad estaba Vendida.
     * - Entrar en Perdida (sin venir de Ganada): En negociacion pasa a En venta si no queda otra abierta.
     * - Entrar en Abierta (sin venir de Ganada): En venta pasa a En negociacion.
     * Retirada y Captacion solo cambian al entrar en Ganada.
     */
    fun propertyStatusAfter(previousOutcome: DealOutcome?, nextOutcome: DealOutcome, current: PropertyStatus, otherOpen: Int, otherWon: Int): PropertyStatus? {
        if (nextOutcome == DealOutcome.Won) return PropertyStatus.Sold.takeIf { current != PropertyStatus.Sold }
        if (previousOutcome == DealOutcome.Won) {
            if (otherWon > 0 || current != PropertyStatus.Sold) return null
            return if (nextOutcome == DealOutcome.Open || otherOpen > 0) PropertyStatus.Negotiation else PropertyStatus.ForSale
        }
        return when (nextOutcome) {
            DealOutcome.Lost -> PropertyStatus.ForSale.takeIf { current == PropertyStatus.Negotiation && otherOpen == 0 }
            else -> PropertyStatus.Negotiation.takeIf { current == PropertyStatus.ForSale }
        }
    }
}

/** Validacion y lectura de entradas (formularios y herramientas). Los mensajes llegan tal cual al usuario. */
object InmobiliariaInput {
    const val MaxUrl = 2000
    private const val MaxMoney = "1000000000000"
    private val currencyPattern = Regex("^[A-Z]{3}$")

    /** True si [code] es un codigo de moneda de 3 letras mayusculas. */
    fun isCurrency(code: String): Boolean = currencyPattern.matches(code)

    val CodePattern = Regex("^[A-Za-z0-9][A-Za-z0-9._-]{0,39}$")
    private val moneyPattern = Regex("^[0-9]{1,12}(\\.[0-9]{1,2})?$")
    private val areaPattern = Regex("^[0-9]{1,10}(\\.[0-9]{1,2})?$")
    private val percentPattern = Regex("^[0-9]{1,3}(\\.[0-9]{1,3})?$")
    private val decimalOnePattern = Regex("^[0-9]{1,3}(\\.[0-9])?$")

    /** True solo para http/https con host, sin espacios ni caracteres de control y de hasta [MaxUrl] caracteres. */
    fun isValidUrl(raw: String?): Boolean {
        val url = raw ?: return false
        if (url.isEmpty() || url.length > MaxUrl) return false
        if (url.any { it.isWhitespace() || it.isISOControl() }) return false
        val rest = when {
            url.startsWith("https://", ignoreCase = true) -> url.substring(8)
            url.startsWith("http://", ignoreCase = true) -> url.substring(7)
            else -> return false
        }
        val authority = rest.takeWhile { it != '/' && it != '?' && it != '#' }
        val hostAndPort = authority.substringAfterLast('@')
        val host = if (hostAndPort.startsWith("[")) hostAndPort.substringBefore(']').removePrefix("[") else hostAndPort.substringBefore(':')
        return host.isNotEmpty() && !host.startsWith(".") && '\\' !in host
    }

    /** La URL sin espacios en los bordes; lanza [InmobiliariaException] si no es valida. */
    fun requireUrl(raw: String?, label: String = "El enlace"): String {
        val url = raw.orEmpty().trim()
        if (!isValidUrl(url)) throw InmobiliariaException("$label debe ser una direccion http:// o https:// valida (sin espacios, hasta $MaxUrl caracteres).")
        return url
    }

    /**
     * Dinero desde texto de formulario: acepta "1,250,000.50", "$ 95000". Vacio = nulo. Positivo, hasta 2 decimales
     * y menor que un billon (numeric(14,2)).
     */
    fun parseMoney(raw: String?, label: String = "El monto"): BigDecimal? {
        val digits = raw.orEmpty().filterNot { it == '$' || it == ',' || it.isWhitespace() }
        if (digits.isEmpty()) return null
        if (!moneyPattern.matches(digits)) throw InmobiliariaException("$label debe ser un monto positivo con hasta 2 decimales (por ejemplo 125000.50).")
        return BigDecimal(digits).setScale(2)
    }

    /** Valida un monto ya numerico con las mismas reglas que [parseMoney]. */
    fun requireMoney(value: BigDecimal, label: String = "El monto"): BigDecimal {
        if (value.signum() < 0 || value.stripTrailingZeros().scale() > 2 || value >= BigDecimal(MaxMoney)) {
            throw InmobiliariaException("$label debe ser un monto positivo con hasta 2 decimales.")
        }
        return value.setScale(2, RoundingMode.UNNECESSARY)
    }

    /** Porcentaje desde texto ("5%", "12,5", "50"): de 0 a 100 con hasta 3 decimales. Vacio = nulo. */
    fun parsePercent(raw: String?, label: String = "El porcentaje"): BigDecimal? {
        val text = raw.orEmpty().filterNot { it == '%' || it.isWhitespace() }.replace(',', '.')
        if (text.isEmpty()) return null
        if (!percentPattern.matches(text)) throw InmobiliariaException("$label debe ser un numero entre 0 y 100 con hasta 3 decimales.")
        val value = BigDecimal(text)
        if (value > BigDecimal(100)) throw InmobiliariaException("$label debe estar entre 0 y 100.")
        return value.setScale(3)
    }

    fun requirePercent(value: BigDecimal, label: String = "El porcentaje"): BigDecimal {
        if (value.signum() < 0 || value > BigDecimal(100) || value.stripTrailingZeros().scale() > 3) {
            throw InmobiliariaException("$label debe estar entre 0 y 100 con hasta 3 decimales.")
        }
        return value.setScale(3, RoundingMode.UNNECESSARY)
    }

    /** Area en metros cuadrados desde texto ("1,250.5"); positiva o cero, hasta 2 decimales. Vacio = nulo. */
    fun parseArea(raw: String?, label: String = "El area"): BigDecimal? {
        val digits = raw.orEmpty().filterNot { it == ',' || it.isWhitespace() }
        if (digits.isEmpty()) return null
        if (!areaPattern.matches(digits)) throw InmobiliariaException("$label debe ser un numero positivo con hasta 2 decimales.")
        return BigDecimal(digits).setScale(2)
    }

    /** Numero de banos (de 0 a 100 con un decimal, por ejemplo 2.5). Vacio = nulo. */
    fun parseBathrooms(raw: String?): BigDecimal? {
        val text = raw.orEmpty().trim().replace(',', '.')
        if (text.isEmpty()) return null
        if (!decimalOnePattern.matches(text) || BigDecimal(text) > BigDecimal(100)) throw InmobiliariaException("Los banos deben ser un numero entre 0 y 100 (por ejemplo 2.5).")
        return BigDecimal(text).setScale(1)
    }

    fun parseBedrooms(raw: String?): Int? {
        val text = raw.orEmpty().trim()
        if (text.isEmpty()) return null
        return text.toIntOrNull()?.takeIf { it in 0..100 } ?: throw InmobiliariaException("Las habitaciones deben ser un numero entero entre 0 y 100.")
    }

    fun parseDate(raw: String?, label: String = "La fecha"): LocalDate? {
        val text = raw.orEmpty().trim()
        if (text.isEmpty()) return null
        return try {
            LocalDate.parse(text)
        } catch (failure: DateTimeParseException) {
            throw InmobiliariaException("$label debe tener el formato AAAA-MM-DD.")
        }
    }

    fun parseUuid(raw: String?, label: String): UUID? {
        val text = raw.orEmpty().trim()
        if (text.isEmpty()) return null
        return try {
            UUID.fromString(text)
        } catch (failure: IllegalArgumentException) {
            throw InmobiliariaException("$label no es un identificador valido.")
        }
    }

    /** Revisa los textos de una propiedad (largos y formato del codigo). */
    fun checkFields(fields: PropertyFields) {
        fields.code?.takeIf { it.isNotEmpty() }?.let {
            if (!CodePattern.matches(it)) throw InmobiliariaException("El codigo lleva letras, numeros, punto, guion o guion bajo (hasta 40 caracteres, empezando por letra o numero).")
        }
        if (fields.title.isEmpty() || fields.title.length > 300) throw InmobiliariaException("El titulo debe tener entre 1 y 300 caracteres.")
        if (fields.zone.length > 120) throw InmobiliariaException("La zona admite hasta 120 caracteres.")
        if (fields.development.length > 120) throw InmobiliariaException("El desarrollo admite hasta 120 caracteres.")
        if (fields.description.length > 10_000) throw InmobiliariaException("La descripcion admite hasta 10000 caracteres.")
        if (fields.notes.length > 10_000) throw InmobiliariaException("Las notas admiten hasta 10000 caracteres.")
        fields.currency?.let { if (!isCurrency(it)) throw InmobiliariaException("La moneda es un codigo de 3 letras mayusculas (USD).") }
    }
}

/** Campos descriptivos de una propiedad (el precio y el estado se cambian aparte para dejar historial). */
data class PropertyFields(
    // Vacio o nulo: se genera uno al crear y se conserva el actual al editar.
    val code: String?,
    val title: String,
    val kind: PropertyKind = PropertyKind.Other,
    val zone: String = "",
    val development: String = "",
    val listingAgentId: UUID? = null,
    val ownerCustomerId: UUID? = null,
    val lotAreaM2: BigDecimal? = null,
    val builtAreaM2: BigDecimal? = null,
    val bedrooms: Int? = null,
    val bathrooms: BigDecimal? = null,
    // Nulo: la moneda de los ajustes al crear, la actual al editar.
    val currency: String? = null,
    val description: String = "",
    val notes: String = "",
    val wordpressListingId: Long? = null,
    val publishedOn: LocalDate? = null,
)

/** Lectura de formularios de la propiedad (los nombres de campo los fija la pantalla). */
object InmobiliariaForms {
    /**
     * Lee el formulario de una propiedad. [agentFor] convierte el nombre elegido en un id de usuario (nulo si no hay agente).
     * Campos: code, title, kind, zone, development, agent, ownerCustomer, lotArea, builtArea, bedrooms, bathrooms, currency,
     * description, notes, wordpressListing, publishedOn.
     */
    fun parseProperty(values: Map<String, String>, agentFor: (String) -> UUID?): PropertyFields {
        fun text(key: String) = values[key].orEmpty().trim()
        val kindText = text("kind")
        val kind = if (kindText.isEmpty()) PropertyKind.Other else PropertyKind.parse(kindText) ?: throw InmobiliariaException("Tipo de propiedad desconocido.")
        val agentName = text("agent")
        val wordpress = text("wordpressListing").takeIf { it.isNotEmpty() }?.let {
            it.toLongOrNull()?.takeIf { id -> id > 0 } ?: throw InmobiliariaException("El id del listado del sitio debe ser un numero entero positivo.")
        }
        val fields = PropertyFields(
            code = text("code").ifEmpty { null },
            title = text("title"),
            kind = kind,
            zone = text("zone"),
            development = text("development"),
            listingAgentId = if (agentName.isEmpty()) null else agentFor(agentName),
            ownerCustomerId = InmobiliariaInput.parseUuid(text("ownerCustomer"), "El vendedor"),
            lotAreaM2 = InmobiliariaInput.parseArea(text("lotArea"), "El area del lote"),
            builtAreaM2 = InmobiliariaInput.parseArea(text("builtArea"), "El area construida"),
            bedrooms = InmobiliariaInput.parseBedrooms(text("bedrooms")),
            bathrooms = InmobiliariaInput.parseBathrooms(text("bathrooms")),
            currency = text("currency").uppercase().ifEmpty { null },
            description = text("description"),
            notes = text("notes"),
            wordpressListingId = wordpress,
            publishedOn = InmobiliariaInput.parseDate(text("publishedOn"), "La fecha de publicacion"),
        )
        InmobiliariaInput.checkFields(fields)
        return fields
    }
}
