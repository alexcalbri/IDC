package com.ideasdeveloper.idc.modules.membresia

/** Identificadores del modulo Membresias. */
object MembresiaModule {
    const val Id = "membresia"
    const val DisplayName = "Membresías"
    const val Version = "0.1.0"
    const val UiRoute = "/modules/membresia/ui"

    // Permisos (ModulePermissionDefinition).
    const val View = "membresia.view"
    const val Visits = "membresia.visits"
    const val Manage = "membresia.manage"
    const val Plans = "membresia.plans"

    // Fuente registrada en el contrato de pases del hotspot.
    const val HotspotSourceId = "membresia"
}
