package com.ideasdeveloper.idc.modules.mantenimiento

object MantenimientoModule {
    const val id: String = "mantenimiento"
    const val displayName: String = "Mantenimiento"

    /** Ver el módulo, reportar averías, solicitar proyectos y ver las órdenes propias, activos y planes. */
    const val View: String = "mantenimiento.view"

    /** Ver todas las órdenes y trabajar las asignadas a uno o sin responsable. */
    const val Execute: String = "mantenimiento.execute"

    /** Gestionar órdenes, proyectos, planes, activos, propiedades y ajustes. */
    const val Manage: String = "mantenimiento.manage"

    /** Importar datos de otros sistemas (Monday...) con las extensiones de importación. */
    const val Import: String = "mantenimiento.import"

    /** Permiso que las extensiones de importación declaran para escribir en el módulo. */
    const val ImportWrite: String = "mantenimiento.import.write"

    const val UiRoute: String = "/modules/mantenimiento/ui"
}
