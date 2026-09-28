package com.ideasdeveloper.idc.server.app

import kotlinx.serialization.Serializable

const val IdeasCoreVersion = "0.1.0"

@Serializable
data class VersionResponse(
    val coreVersion: String = IdeasCoreVersion,
    val appVersion: String = IdeasCoreVersion,
    val latestCoreVersion: String? = null,
    val latestAppVersion: String? = null,
    val moduleUpdates: List<ModuleVersionUpdate> = emptyList(),
) {
    val coreUpdateAvailable: Boolean
        get() = !latestCoreVersion.isNullOrBlank() && latestCoreVersion != coreVersion
    val appUpdateAvailable: Boolean
        get() = !latestAppVersion.isNullOrBlank() && latestAppVersion != appVersion
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
