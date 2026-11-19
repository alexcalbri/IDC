package com.ideasdeveloper.idc.modules.membresia

import java.math.BigDecimal
import java.time.LocalDate
import java.time.YearMonth
import java.time.temporal.ChronoUnit

/** Resultado de intentar registrar la visita de un miembro. */
sealed interface CheckInDecision {
    /** Se puede registrar; [overridden] indica que recepcion la registro igual pese al aviso. */
    data class Allowed(val overridden: Boolean = false) : CheckInDecision

    /** Hay un aviso (vencida, programada, visitas agotadas): solo se registra con "registrar igual". */
    data class NeedsOverride(val reason: String) : CheckInDecision

    /** Nunca se registra, ni con "registrar igual". */
    data class Refused(val reason: String) : CheckInDecision
}

/** Reglas del modulo como funciones puras (sin base de datos ni reloj), probadas aparte. */
object MembresiaRules {
    const val MaxNameLength = 80
    const val MaxNotesLength = 4000
    const val MaxUnitLength = 80
    const val MaxSourceLength = 40
    const val MaxHostNameLength = 160
    const val MaxDescriptionLength = 2000
    const val MaxDurationDays = 3660
    const val MaxGuestPasses = 1000
    const val MaxExpiringDays = 60
    const val MaxSources = 20
    val MaxAmount: BigDecimal = BigDecimal("99999999.99")

    private val amountPattern = Regex("^\\d{1,8}([.]\\d{1,2})?$")
    private val currencyPattern = Regex("^[A-Z]{3}$")

    /** Ultimo dia (inclusive) de una membresia que empieza en [start] y dura [durationDays]. */
    fun endsOn(start: LocalDate, durationDays: Int): LocalDate = start.plusDays(durationDays.toLong() - 1)

    /** Inicio de la renovacion: el dia siguiente al fin actual, o hoy si ya vencio. */
    fun renewalStart(currentEndsOn: LocalDate, today: LocalDate): LocalDate {
        val next = currentEndsOn.plusDays(1)
        return if (next.isAfter(today)) next else today
    }

    /** Estado guardado al crear: permite altas retroactivas (nacen vencidas). */
    fun initialStatus(startsOn: LocalDate, endsOn: LocalDate, today: LocalDate): String = when {
        startsOn.isAfter(today) -> StoredStatus.Programada
        endsOn.isBefore(today) -> StoredStatus.Vencida
        else -> StoredStatus.Activa
    }

    /** Estado que ve el equipo; correcto aunque el trabajo diario no haya corrido. */
    fun displayStatus(stored: String, startsOn: LocalDate, endsOn: LocalDate, today: LocalDate, expiringDays: Int): DisplayStatus = when {
        stored == StoredStatus.Cancelada -> DisplayStatus.CANCELADA
        endsOn.isBefore(today) -> DisplayStatus.VENCIDA
        startsOn.isAfter(today) -> DisplayStatus.PROGRAMADA
        ChronoUnit.DAYS.between(today, endsOn) <= expiringDays -> DisplayStatus.POR_VENCER
        else -> DisplayStatus.ACTIVA
    }

    fun displayStatus(membership: Membership, today: LocalDate, expiringDays: Int): DisplayStatus =
        displayStatus(membership.status, membership.startsOn, membership.endsOn, today, expiringDays)

    /** Dias que faltan para el ultimo dia (0 = vence hoy, negativo = ya vencio). */
    fun daysLeft(endsOn: LocalDate, today: LocalDate): Long = ChronoUnit.DAYS.between(today, endsOn)

    /** Visitas que quedan; null = ilimitadas. Nunca negativo. */
    fun visitsLeft(membership: Membership): Int? =
        membership.includedVisits?.let { (it - membership.visitsUsed).coerceAtLeast(0) }

    fun guestPassesLeft(membership: Membership): Int =
        (membership.guestPassesTotal - membership.guestPassesUsed).coerceAtLeast(0)

    /**
     * La membresia que representa al miembro: la vigente de fin mas lejano; si no hay, la programada mas proxima;
     * si no, la mas reciente (por fin). Vacio = null.
     */
    fun currentMembership(memberships: List<Membership>, today: LocalDate, expiringDays: Int): Membership? {
        val byStatus = memberships.groupBy { displayStatus(it, today, expiringDays) }
        return byStatus.entries.filter { it.key.current }.flatMap { it.value }.maxByOrNull { it.endsOn }
            ?: byStatus[DisplayStatus.PROGRAMADA]?.minByOrNull { it.startsOn }
            ?: memberships.filter { it.status != StoredStatus.Cancelada }.maxByOrNull { it.endsOn }
            ?: memberships.maxByOrNull { it.endsOn }
    }

    /** Decide si se puede registrar la visita de hoy del miembro. */
    fun checkIn(membership: Membership, today: LocalDate, expiringDays: Int, override: Boolean): CheckInDecision {
        val status = displayStatus(membership, today, expiringDays)
        if (status == DisplayStatus.CANCELADA) return CheckInDecision.Refused("La membresía está cancelada.")
        val warning = when {
            status == DisplayStatus.VENCIDA -> "La membresía venció el ${membership.endsOn}."
            status == DisplayStatus.PROGRAMADA -> "La membresía empieza el ${membership.startsOn}."
            visitsLeft(membership) == 0 -> "Ya usó todas las visitas incluidas en su plan."
            else -> null
        }
        return when {
            warning == null -> CheckInDecision.Allowed()
            override -> CheckInDecision.Allowed(overridden = true)
            else -> CheckInDecision.NeedsOverride(warning)
        }
    }

    /** Un pase de invitado exige membresia vigente y pases disponibles; no admite "registrar igual". */
    fun guestPassAllowed(membership: Membership, today: LocalDate, expiringDays: Int): CheckInDecision {
        val status = displayStatus(membership, today, expiringDays)
        return when {
            status == DisplayStatus.CANCELADA -> CheckInDecision.Refused("La membresía está cancelada.")
            !status.current -> CheckInDecision.Refused("La membresía no está vigente.")
            guestPassesLeft(membership) == 0 -> CheckInDecision.Refused("No quedan pases de invitado.")
            else -> CheckInDecision.Allowed()
        }
    }

    /** Monto con hasta 2 decimales entre 0 y 99 999 999.99; admite coma decimal. Null si no es valido. */
    fun parseAmount(text: String?): BigDecimal? {
        val clean = text?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val normalized = if ('.' !in clean) clean.replace(',', '.') else clean
        if (!amountPattern.matches(normalized)) return null
        return normalized.toBigDecimalOrNull()?.takeIf { it <= MaxAmount }?.setScale(2)
    }

    fun validAmount(amount: BigDecimal): Boolean =
        amount.signum() >= 0 && amount <= MaxAmount && amount.stripTrailingZeros().scale() <= 2

    /** Escapa % _ y \ para usar el texto dentro de un patron LIKE/ILIKE. */
    fun escapeLike(text: String): String =
        text.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")

    fun validatePlan(input: PlanInput): List<String> = buildList {
        if (input.name.isBlank()) add("Escribe el nombre del plan.")
        if (input.name.trim().length > MaxNameLength) add("El nombre del plan admite hasta $MaxNameLength caracteres.")
        if (input.description.length > MaxDescriptionLength) add("La descripción admite hasta $MaxDescriptionLength caracteres.")
        if (!validAmount(input.price)) add("El precio debe estar entre 0 y 99 999 999.99 con hasta 2 decimales.")
        if (input.durationDays !in 1..MaxDurationDays) add("La duración debe estar entre 1 y $MaxDurationDays días.")
        if (input.includedVisits != null && input.includedVisits < 1) add("Las visitas incluidas deben ser 1 o más (vacío = ilimitadas).")
        if (input.guestPasses !in 0..MaxGuestPasses) add("Los pases de invitado deben estar entre 0 y $MaxGuestPasses.")
    }

    /** Valida un alta o renovacion contra los ajustes (fuente permitida) con el monto ya resuelto. */
    fun validateMembership(input: MembershipInput, amount: BigDecimal, settings: MembresiaSettings): List<String> =
        validateDetails(amount, input.source, input.hostName, input.unit, input.notes, settings)

    fun validateEdit(edit: MembershipEdit, startsOn: LocalDate, settings: MembresiaSettings, today: LocalDate): List<String> = buildList {
        addAll(validateDates(startsOn, edit.endsOn, today, checkStart = false))
        addAll(validateDetails(edit.amount, edit.source, edit.hostName, edit.unit, edit.notes, settings))
    }

    /** Fechas razonables: inicio entre hoy - 5 años y hoy + 2 años, y una duracion de hasta 3660 dias. */
    fun validateDates(startsOn: LocalDate, endsOn: LocalDate, today: LocalDate, checkStart: Boolean = true): List<String> = buildList {
        if (checkStart && (startsOn.isBefore(today.minusYears(5)) || startsOn.isAfter(today.plusYears(2)))) add("El inicio debe estar entre 5 años atrás y 2 años adelante.")
        if (endsOn.isBefore(startsOn)) add("El fin no puede ser anterior al inicio.")
        else if (ChronoUnit.DAYS.between(startsOn, endsOn) > MaxDurationDays) add("La membresía no puede durar más de $MaxDurationDays días.")
    }

    private fun validateDetails(amount: BigDecimal, source: String, hostName: String, unit: String, notes: String, settings: MembresiaSettings): List<String> = buildList {
        if (!validAmount(amount)) add("El monto debe estar entre 0 y 99 999 999.99 con hasta 2 decimales.")
        if (source.isNotEmpty() && source !in settings.sources) add("Elige una fuente de la lista.")
        if (hostName.length > MaxHostNameLength) add("El nombre del anfitrión admite hasta $MaxHostNameLength caracteres.")
        if (unit.length > MaxUnitLength) add("La unidad admite hasta $MaxUnitLength caracteres.")
        if (notes.length > MaxNotesLength) add("Las notas admiten hasta $MaxNotesLength caracteres.")
    }

    fun validateSettings(settings: MembresiaSettings): List<String> = buildList {
        if (!currencyPattern.matches(settings.currency)) add("La moneda es un código de 3 letras mayúsculas, por ejemplo USD.")
        if (settings.expiringDays !in 1..MaxExpiringDays) add("Los días de aviso deben estar entre 1 y $MaxExpiringDays.")
        if (settings.sources.size > MaxSources) add("Se admiten hasta $MaxSources fuentes.")
        if (settings.sources.any { it.isBlank() || it.length > MaxSourceLength }) add("Cada fuente admite hasta $MaxSourceLength caracteres.")
        if (settings.sources.map { it.lowercase() }.toSet().size != settings.sources.size) add("Las fuentes no pueden repetirse.")
    }

    /** Fuentes escritas una por linea: recorta, quita vacias y repetidas. */
    fun parseSources(text: String): List<String> =
        text.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.distinctBy { it.lowercase() }.toList()

    /** Ingresos por mes de inicio de la [currency] dada (no canceladas), de mas antiguo a mas reciente, con ceros en los meses vacios. */
    fun revenueByMonth(rows: List<RevenueRow>, today: LocalDate, months: Int, currency: String): List<RevenueMonth> {
        val last = YearMonth.from(today)
        val first = last.minusMonths(months.toLong() - 1)
        val counted = rows.filter { it.status != StoredStatus.Cancelada && it.currency == currency }.groupBy { YearMonth.from(it.startsOn) }
        return (0 until months).map { offset ->
            val month = first.plusMonths(offset.toLong())
            val inMonth = counted[month].orEmpty()
            RevenueMonth(month, inMonth.fold(BigDecimal.ZERO.setScale(2)) { total, row -> total + row.amount }, inMonth.sumOf { it.count })
        }
    }

    /** Ingresos por plan de la [currency] dada en la misma ventana de meses (no canceladas), de mayor a menor. */
    fun revenueByPlan(rows: List<RevenueRow>, today: LocalDate, months: Int, currency: String): List<RevenueByPlan> {
        val first = YearMonth.from(today).minusMonths(months.toLong() - 1).atDay(1)
        val last = YearMonth.from(today).atEndOfMonth()
        return rows.filter { it.status != StoredStatus.Cancelada && it.currency == currency && !it.startsOn.isBefore(first) && !it.startsOn.isAfter(last) }
            .groupBy { it.planName }
            .map { (plan, list) -> RevenueByPlan(plan, list.fold(BigDecimal.ZERO.setScale(2)) { total, row -> total + row.amount }, list.sumOf { it.count }) }
            .sortedWith(compareByDescending<RevenueByPlan> { it.amount }.thenBy { it.planName })
    }
}
