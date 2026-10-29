package com.ideasdeveloper.idc.modules.crm

/** Normalization of the contact data that arrives from channels, forms and imports. */
object CrmContacts {
    // Country code used for local numbers of [LocalDigits] digits (Nicaragua: 8 digits, +505).
    private const val LocalCountry = "505"
    private const val LocalDigits = 8
    private val e164 = Regex("^[1-9][0-9]{7,14}$")
    // 10 digits shaped like a USA/Canada number (area and exchange codes start with 2-9).
    private val nanp = Regex("^[2-9][0-9]{2}[2-9][0-9]{6}$")
    private val emailPattern = Regex("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$")

    /**
     * International form "+<digits>" or "" when it is not a phone number. Local 8-digit numbers get
     * +505, "00" prefixes become "+", and 10 digits without "+" shaped like a USA/Canada number get +1
     * (otherwise "2025550123" would read as a +20 number). Prefixes such as "whatsapp:" are removed.
     */
    fun normalizePhone(raw: String): String {
        val phone = raw.trim().removePrefix("whatsapp:").removePrefix("tel:").trim()
        if (phone.isEmpty() || phone.any { !it.isDigit() && it !in "+ ().-/" }) return ""
        val international = phone.startsWith("+")
        var digits = phone.filter { it.isDigit() }
        digits = when {
            digits.startsWith("00") -> digits.drop(2)
            !international && digits.length == LocalDigits -> LocalCountry + digits
            !international && nanp.matches(digits) -> "1$digits"
            else -> digits
        }
        return if (e164.matches(digits)) "+$digits" else ""
    }

    fun isEmail(value: String): Boolean = emailPattern.matches(value)

    private val providerTypos = mapOf(
        "gmail" to setOf("gmial", "gmai", "gamil", "gmal", "gmaill", "gnail", "gmil", "gmaul", "gimail", "gmali", "gmeil", "gemail", "gmsil", "gmaik"),
        "hotmail" to setOf("hotmal", "hotmial", "hotmai", "homail", "hotmil", "hotamil", "htomail", "hotmaill", "hotnail", "hormail", "hotmsil"),
        "yahoo" to setOf("yaho", "yahooo", "yhoo", "yahho", "yaoo", "yajoo"),
        "outlook" to setOf("outlok", "outllok", "outloo", "oulook", "outlookk", "otlook", "outook"),
        "icloud" to setOf("iclod", "iclould", "icoud", "icluod"),
    )

    /** Lowercases the domain and fixes common typos (gmial.com, hotmail.con, gmail.co...). */
    fun fixEmail(raw: String): String {
        val email = raw.trim()
        val at = email.lastIndexOf('@')
        if (at <= 0) return email
        val local = email.substring(0, at)
        var domain = email.substring(at + 1).trimEnd('.').lowercase()
        if (domain.isEmpty() || domain.endsWith(".invalid")) return email
        // Endings that almost always meant .com.
        domain = domain.replace(Regex("\\.(?:comm+|con|cmo|ocm|cpm|vom|xom|coom|comn|cim|cpom|ccom|c0m)$"), ".com")
        // Provider without the dot: gmailcom, hotmailcom.
        domain = domain.replace(Regex("^(gmail|hotmail|yahoo|outlook|icloud)com$"), "$1.com")
        Regex("^([a-z0-9-]+)\\.(.+)$").matchEntire(domain)?.let { parts ->
            providerTypos.entries.firstOrNull { parts.groupValues[1] in it.value }?.let { domain = "${it.key}.${parts.groupValues[2]}" }
        }
        // gmail only uses .com: gmail.co, gmail.cm, gmail.om -> gmail.com.
        domain = domain.replace(Regex("^gmail\\.(?:co|cm|om|c|cpm|ccom)$"), "gmail.com")
        domain = domain.replace(Regex("^(hotmail|outlook|icloud)\\.(?:cm|om|c)$"), "$1.com")
        return "$local@$domain"
    }

    /** First and last name for systems that need them apart (Cloudbeds). */
    fun splitName(name: String): Pair<String, String> {
        val parts = name.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
        return when (parts.size) {
            0 -> "" to ""
            1 -> parts[0] to parts[0]
            else -> parts.dropLast(1).joinToString(" ") to parts.last()
        }
    }
}
