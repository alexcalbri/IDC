package com.ideasdeveloper.idc.modules.empleados

object EmpleadosModule {
    const val id: String = "empleados"
    const val displayName: String = "Empleados"

    /** Mi horario, mis ausencias (pedir y cancelar pendientes) y el directorio basico de empleados activos. */
    const val View: String = "empleados.view"

    /** Vista semanal, horario habitual y turnos de cualquiera, decidir ausencias y ver telefono, correo y disponibilidad. */
    const val Shifts: String = "empleados.shifts"

    /** Crear, editar y dar de baja fichas, enlazar usuarios, ver todo y administrar sedes y departamentos. Incluye Shifts. */
    const val Manage: String = "empleados.manage"

    /** Importar datos de otros sistemas (Monday...) con las extensiones de importacion. */
    const val Import: String = "empleados.import"

    const val UiRoute: String = "/modules/empleados/ui"
}
