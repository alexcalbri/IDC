package com.ideasdeveloper.idc.modules.empleados

import java.text.Normalizer
import java.time.LocalTime

/**
 * Entiende los textos libres de horario que la gente escribe ("Lunes a Viernes", "8:00 a.m. a 5:00 p.m.") en espanol e
 * ingles. Es pura y publica: la usa la extension de Monday para las columnas de dias y de horario. Cuando no entiende
 * el texto devuelve null (nunca adivina).
 */
object EmpleadosScheduleText {
    private val days: Map<String, Int> = buildMap {
        fun add(number: Int, vararg names: String) = names.forEach { put(it, number) }
        add(1, "lunes", "lun", "lu", "l", "monday", "mon", "mo")
        add(2, "martes", "mar", "ma", "m", "tuesday", "tue", "tues", "tu")
        add(3, "miercoles", "mie", "mier", "mi", "x", "wednesday", "wed", "weds", "we")
        add(4, "jueves", "jue", "ju", "j", "thursday", "thu", "thur", "thurs", "th")
        add(5, "viernes", "vie", "vi", "v", "friday", "fri", "fr")
        add(6, "sabado", "sab", "sa", "s", "saturday", "sat")
        add(7, "domingo", "dom", "do", "d", "sunday", "sun", "su")
    }

    private val connectors = setOf("a", "al", "hasta", "to", "thru", "through", "till", "until", "-")
    private val fillers = setOf("y", "e", "o", "and", "or", "de", "del", "los", "las", "el", "la", "dias", "dia", "days", "day", "solo", "only")
    private val allDays = setOf("todos los dias", "todos", "diario", "diaria", "a diario", "cada dia", "everyday", "every day", "daily", "all days", "all week")
    private val weekdays = setOf(
        "entre semana", "dias laborales", "dias laborables", "laborales", "laborables", "weekdays", "weekday", "business days", "work days", "workdays",
    )
    private val weekend = setOf("fines de semana", "fin de semana", "fines de semanas", "fin de semanas", "weekend", "weekends", "finde", "findes")

    /** Minusculas, sin acentos, sin guiones largos y con los espacios colapsados. */
    private fun normalize(text: String): String =
        Normalizer.normalize(text.lowercase(), Normalizer.Form.NFD)
            .replace(Regex("\\p{M}+"), "")
            .replace('–', '-')
            .replace('—', '-')
            .replace(Regex("\\s+"), " ")
            .trim()

    /** Dias ISO (1 lunes ... 7 domingo) de un texto como "Lun-Vie", "L, X, V", "Mon-Fri" o "Fines de semana"; null si no lo entiende. */
    fun parseDays(text: String): Set<Int>? {
        val norm = normalize(text)
        if (norm.isEmpty() || norm.length > 120) return null
        val phrase = norm.replace(Regex("[^a-z0-9 ]+"), " ").replace(Regex("\\s+"), " ").trim()
        when {
            phrase in allDays -> return (1..7).toSet()
            phrase in weekdays -> return (1..5).toSet()
            phrase in weekend -> return setOf(6, 7)
        }
        // Un numero suele ser una hora ("Lunes 8-5"): este texto no es solo de dias.
        if (norm.any { it.isDigit() }) return null
        val result = sortedSetOf<Int>()
        var previous: Int? = null
        var rangeFrom: Int? = null
        for (match in Regex("[a-z]+|-").findAll(norm)) {
            val token = match.value
            // Un plural ("sabados", "mondays") solo cuenta si lo que queda sin la s es un nombre o abreviatura de 3+ letras: "mas", "dos" y "sus" no son dias.
            val day = days[token] ?: token.removeSuffix("s").takeIf { it != token && it.length >= 3 }?.let { days[it] }
            when {
                token in connectors && day == null -> {
                    if (previous == null || rangeFrom != null) return null
                    rangeFrom = previous
                }
                day != null -> {
                    val from = rangeFrom
                    if (from != null) {
                        result += range(from, day)
                        rangeFrom = null
                    } else {
                        result += day
                    }
                    previous = day
                }
                token in fillers -> Unit
                else -> return null
            }
        }
        if (rangeFrom != null) return null
        return result.takeIf { it.isNotEmpty() }?.toSet()
    }

    /** De [from] a [to] incluidos; "viernes a lunes" da viernes, sabado, domingo y lunes. */
    private fun range(from: Int, to: Int): List<Int> = if (to >= from) (from..to).toList() else (from..7).toList() + (1..to).toList()

    private const val Time = "(\\d{1,2})(?:[:.h](\\d{2}))?\\s*(am|pm)?"
    private const val HourWord = "(?:h|hs|hrs|horas)?"
    private val HoursPattern = Regex("^(?:de |desde |from |entre )?$Time\\s*$HourWord\\s*(?:-|a|al|to|until|till|hasta|y)\\s*$Time\\s*$HourWord$")

    private fun hour24(hour: Int, suffix: String?): Int? = when (suffix) {
        null -> hour.takeIf { it in 0..23 }
        "am" -> hour.takeIf { it in 1..12 }?.rem(12)
        else -> hour.takeIf { it in 1..12 }?.rem(12)?.plus(12)
    }

    /**
     * Hora de inicio y de fin de un texto como "8:00-17:00", "8am - 5pm", "8:00 a.m. a 5:00 p.m." o "08:00 – 16:00"; null si
     * no lo entiende. Sin am/pm y con las dos horas de 1 a 12 y el fin menor que el inicio, el fin se toma como tarde
     * ("8-5" es 08:00-17:00); un horario nocturno se escribe con 24 horas ("22:00-06:00") o con am/pm.
     */
    fun parseHours(text: String): Pair<LocalTime, LocalTime>? {
        var norm = normalize(text)
        if (norm.isEmpty() || norm.length > 60) return null
        norm = norm.replace(Regex("([ap])\\s*\\.\\s*m\\s*\\.?"), "$1m").replace(Regex("\\.+$"), "").trim()
        val match = HoursPattern.matchEntire(norm) ?: return null
        val g = match.groupValues
        val startHour = g[1].toInt()
        val endHour = g[4].toInt()
        val startMinute = g[2].takeIf { it.isNotEmpty() }?.toInt() ?: 0
        val endMinute = g[5].takeIf { it.isNotEmpty() }?.toInt() ?: 0
        if (startMinute > 59 || endMinute > 59) return null
        var startSuffix = g[3].takeIf { it.isNotEmpty() }
        var endSuffix = g[6].takeIf { it.isNotEmpty() }
        var endPlus = 0
        when {
            startSuffix == null && endSuffix != null -> {
                startSuffix = endSuffix
                val start = hour24(startHour, startSuffix)?.times(60)?.plus(startMinute)
                val end = hour24(endHour, endSuffix)?.times(60)?.plus(endMinute)
                // "9-5pm": las 9 de la noche pasan de las 5 de la tarde, asi que el inicio es de manana.
                if (start != null && end != null && start >= end) startSuffix = if (endSuffix == "pm") "am" else "pm"
            }
            startSuffix != null && endSuffix == null -> {
                endSuffix = startSuffix
                val start = hour24(startHour, startSuffix)?.times(60)?.plus(startMinute)
                val end = hour24(endHour, endSuffix)?.times(60)?.plus(endMinute)
                // "10am-2": las 2 de la madrugada son antes de las 10, asi que el fin es de tarde.
                if (start != null && end != null && end <= start) endSuffix = if (startSuffix == "am") "pm" else "am"
            }
            startSuffix == null && endSuffix == null -> {
                val start = startHour * 60 + startMinute
                val end = endHour * 60 + endMinute
                if (end < start && startHour in 1..12 && endHour in 1..12) endPlus = 12
            }
        }
        val startH = hour24(startHour, startSuffix) ?: return null
        val endH = (hour24(endHour, endSuffix) ?: return null) + endPlus
        if (startH > 23 || endH > 23) return null
        val start = LocalTime.of(startH, startMinute)
        val end = LocalTime.of(endH, endMinute)
        return (start to end).takeIf { start != end }
    }
}
