package com.ideasdeveloper.idc.modules.tareas

object TareasModule {
    const val id: String = "tareas"
    const val displayName: String = "Tareas"

    /** See tasks: the module, the boards the user sees and "Mis tareas". */
    const val View: String = "tareas.view"

    /** Create, edit, assign and archive tasks in the boards the user sees. */
    const val Manage: String = "tareas.manage"

    /** Create, edit and archive boards, groups and members; sees every board (private ones too). */
    const val Boards: String = "tareas.boards"

    const val UiRoute: String = "/modules/tareas/ui"
}
