package com.ideasdeveloper.idc.app.features.modules.data

import com.ideasdeveloper.idc.ui.ModuleUiEntry
import kotlinx.serialization.Serializable

@Serializable
// Metadata server-driven que permite renderizar un modulo generico en el cliente.
data class ModuleDefinition(
    val id: String,
    val displayName: String,
    val description: String,
    val version: String = "0.1.0",
    val locked: Boolean = false,
    val views: List<ModuleViewDefinition> = emptyList(),
    // Como mostrar el modulo: piezas nativas (UiScreen) o paginas web propias del modulo.
    val ui: ModuleUiEntry? = null,
)

@Serializable
// Describe una vista disponible dentro de un modulo entregado por el servidor.
data class ModuleViewDefinition(
    val id: String,
    val title: String,
    val route: String,
    val kind: String,
    val fields: List<ModuleFieldDefinition> = emptyList(),
    val actions: List<ModuleActionDefinition> = emptyList(),
)

@Serializable
data class ModuleFieldDefinition(
    val key: String,
    val label: String,
    val type: String,
    val required: Boolean = false,
    val dynamic: Boolean = false,
    val options: List<String> = emptyList(),
    val readOnly: Boolean = false,
    val help: String = "",
    // Los campos consecutivos con la misma seccion se agrupan bajo un titulo.
    val section: String = "",
)

@Serializable
data class ModuleActionDefinition(
    val id: String,
    val title: String,
    val route: String,
    val method: String,
    val permission: String,
)
