package com.ideasdeveloper.idc.app.features.modules.data

import kotlinx.serialization.Serializable

@Serializable
data class ModuleDefinition(
    val id: String,
    val displayName: String,
    val description: String,
    val locked: Boolean = false,
    val views: List<ModuleViewDefinition> = emptyList(),
)

@Serializable
data class ModuleViewDefinition(
    val id: String,
    val title: String,
    val route: String,
    val kind: String,
)
