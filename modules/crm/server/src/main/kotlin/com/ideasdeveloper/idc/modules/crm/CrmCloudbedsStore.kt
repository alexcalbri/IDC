package com.ideasdeveloper.idc.modules.crm

/**
 * Constantes compartidas de Cloudbeds que usan el CRM y la extension. Los ajustes ya no viven aqui: los guarda la
 * extension en su propia tabla (ext_cloudbeds__settings) y el CRM los pide por [CrmHost].
 */
object CrmCloudbedsStore {
    /** Origen (source) de los items de lead que vienen de Cloudbeds. */
    const val Source = "cloudbeds"

    val paymentLabels = linkedMapOf("cash" to "Efectivo", "credit" to "Tarjeta", "ebanking" to "Transferencia", "pay_pal" to "PayPal")

    /** A guest country as Cloudbeds takes it: ISO 3166-1 alpha-2. */
    val CountryCode = Regex("^[A-Z]{2}$")
}
