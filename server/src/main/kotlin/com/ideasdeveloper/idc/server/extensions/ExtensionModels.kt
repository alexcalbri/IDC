package com.ideasdeveloper.idc.server.extensions

import kotlinx.serialization.Serializable

/*
 * DTOs of the extension API (PLAN_CAMBIOS_2.md 11.3). They never carry a secret: a stored secret
 * is shown as SecretBox.Mask and an empty value means "not set".
 */

@Serializable
data class ExtensionErrorResponse(val code: String, val message: String)

@Serializable
data class ExtensionSettingView(
    val key: String,
    val label: String,
    val secret: Boolean,
    val required: Boolean,
    val help: String,
    val scope: String,
    /** Plain value, or the mask for a stored secret; "" when not set. */
    val value: String,
    val isSet: Boolean,
)

@Serializable
data class ExtensionSummary(
    val id: String,
    val module: String,
    val displayName: String,
    val description: String,
    val version: String,
    val minModuleVersion: String?,
    val sourceType: String,
    val permissions: List<String>,
    val uninstallOptions: List<ExtensionUninstallOption>,
    /** tenant_extensions.status in this company, or null when it was never installed or was removed. */
    val status: String?,
    val moduleInstalled: Boolean,
    val moduleEnabled: Boolean,
    /** False when the loaded module is older than minModuleVersion. */
    val compatible: Boolean,
    val settings: List<ExtensionSettingView> = emptyList(),
    val missingRequired: List<String> = emptyList(),
)

@Serializable
data class ExtensionListResponse(val companyCode: String, val extensions: List<ExtensionSummary>)

@Serializable
data class ConfigureExtensionRequest(
    /** Settings to change. A secret sent as the mask is left as it is; "" clears the setting. */
    val settings: Map<String, String> = emptyMap(),
)

@Serializable
data class SetExtensionEnabledRequest(val enabled: Boolean)

@Serializable
data class UninstallOptionView(val id: String, val label: String, val default: String)

@Serializable
data class UninstallPreviewResponse(
    val extensionId: String,
    val companyCode: String,
    val items: List<UninstallItem>,
    val options: List<UninstallOptionView>,
)

@Serializable
data class UninstallExtensionRequest(
    /** Answer per declared option id: "keep" or "delete". Options with default "ask" need one. */
    val choices: Map<String, String> = emptyMap(),
)

/** "apply" copies the source configuration, "uninstall" removes the extension from each target. */
object GroupOperationType {
    const val Apply = "apply"
    const val Uninstall = "uninstall"
}

@Serializable
data class GroupOperationRequest(
    val operation: String = GroupOperationType.Apply,
    val targets: List<String> = emptyList(),
    val choices: Map<String, String> = emptyMap(),
)

@Serializable
data class GroupTargetPreview(
    val companyCode: String,
    val name: String = "",
    /** What would happen, in order; empty when the target is refused. */
    val steps: List<String> = emptyList(),
    /** Why the target cannot receive the operation, or null when it can. */
    val problem: String? = null,
)

@Serializable
data class GroupOperationPreview(
    val extensionId: String,
    val operation: String,
    val sourceCompany: String,
    val targets: List<GroupTargetPreview>,
)

@Serializable
data class GroupTargetResult(
    val companyCode: String,
    /** pending, applied, failed or skipped. */
    val status: String,
    val reason: String? = null,
    val attempts: Int = 0,
)

@Serializable
data class GroupOperationResult(
    val operationId: Long,
    val extensionId: String,
    val operation: String,
    val sourceCompany: String,
    /** running, done, partial or failed. */
    val status: String,
    val targets: List<GroupTargetResult>,
)

@Serializable
data class GroupMemberView(
    val code: String,
    val name: String,
    val suspended: Boolean,
    val ownerEmailMatches: Boolean,
)

@Serializable
data class GroupResponse(val companyCode: String, val members: List<GroupMemberView>)

@Serializable
data class LinkGroupRequest(val targetCode: String)
