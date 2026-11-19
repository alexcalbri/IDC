package com.ideasdeveloper.idc.modules.eventos

object EventosModule {
    const val id: String = "eventos"
    const val displayName: String = "Eventos"

    /** See events: upcoming, past, the card of each one, registrations and capacity. */
    const val View: String = "eventos.view"

    /** Register customers, cancel registrations, move people from the waiting list, mark payment and attendance. */
    const val Register: String = "eventos.register"

    /** Create, edit, duplicate, publish and cancel events; prices, private events, payments (abonos) and settings. */
    const val Manage: String = "eventos.manage"

    /** Import events from other systems (Monday...) through the import extensions. */
    const val Import: String = "eventos.import"

    const val UiRoute: String = "/modules/eventos/ui"
}
