package com.ideasdeveloper.idc.app.features.modules.data

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
)

@Serializable
data class ModuleActionDefinition(
    val id: String,
    val title: String,
    val route: String,
    val method: String,
    val permission: String,
)
