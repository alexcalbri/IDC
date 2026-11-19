package com.ideasdeveloper.idc.modules.propietarios

object PropietariosModule {
    const val id: String = "propietarios"
    const val displayName: String = "Propietarios"

    /** Ver desarrollos, lotes, titulares, historial y adjuntos. */
    const val View: String = "propietarios.view"

    /** Crear y editar lotes, registrar cambios de titularidad y adjuntar archivos. */
    const val Manage: String = "propietarios.manage"

    /** Crear, editar y archivar desarrollos; archivar lotes. */
    const val Admin: String = "propietarios.admin"

    /** Importar datos de otros sistemas (Monday...) con las extensiones de importacion. */
    const val Import: String = "propietarios.import"

    const val UiRoute: String = "/modules/propietarios/ui"
}
