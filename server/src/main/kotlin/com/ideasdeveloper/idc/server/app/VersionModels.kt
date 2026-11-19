package com.ideasdeveloper.idc.server.app

import com.ideasdeveloper.idc.server.company.application.CoreUpdateService
import kotlinx.serialization.Serializable

const val IdeasCoreVersion = "0.8.0"

// El servidor no conoce la version de la app (cada cliente trae la suya): solo informa la ultima disponible.
@Serializable
data class VersionResponse(
    val coreVersion: String = IdeasCoreVersion,
    val latestCoreVersion: String? = null,
    val latestAppVersion: String? = null,
    val moduleUpdates: List<ModuleVersionUpdate> = emptyList(),
) {
    val coreUpdateAvailable: Boolean
        get() = CoreUpdateService.isUpdateAvailable(coreVersion, latestCoreVersion)
    val modulesUpdateAvailable: Boolean
        get() = moduleUpdates.isNotEmpty()
}

@Serializable
data class RemoteVersionCatalog(
    val latestCoreVersion: String? = null,
    val latestAppVersion: String? = null,
)

@Serializable
data class ModuleVersionUpdate(
    val moduleId: String,
    val displayName: String,
    val currentVersion: String,
    val latestVersion: String,
)
