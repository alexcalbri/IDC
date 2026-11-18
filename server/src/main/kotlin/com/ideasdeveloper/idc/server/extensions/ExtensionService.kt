package com.ideasdeveloper.idc.server.extensions

import com.ideasdeveloper.idc.server.modules.ModuleAccessService
import com.ideasdeveloper.idc.server.modules.ModuleRegistry
import com.ideasdeveloper.idc.server.security.SecretBox
import com.ideasdeveloper.idc.server.security.SecretsUnavailableException
import java.sql.Connection
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.slf4j.LoggerFactory

/** Writes provisioning_audit_log (CompanyProvisioningService.insertAudit); details is a JSON object. */
typealias ExtensionAuditor = (actorRole: String, action: String, companyCode: String?, moduleId: String?, details: String) -> Unit

/** Tells a user about something in a company through the Core's notification center. */
fun interface ExtensionNotifier {
    fun notify(tenant: Connection, moduleId: String, userId: UUID, title: String, text: String)

    companion object {
        fun from(access: ModuleAccessService): ExtensionNotifier =
            ExtensionNotifier { tenant, moduleId, userId, title, text -> access.notificationsFor(moduleId).notify(tenant, userId, title, text) }
    }
}

/**
 * Runs the pending tenant migrations of an extension in a company (CompanyProvisioningService,
 * with the provisioning role: the runtime connection cannot create tables); idempotent.
 */
fun interface ExtensionMigrationRunner {
    fun run(companyCode: String, extension: ServerExtension)
}

/** Who acts. [role] is the PostgreSQL role written to the audit log; [companyCode] is the company of a business owner. */
data class ExtensionActor(val role: String, val serverOwner: Boolean, val companyCode: String? = null)

/**
 * Extensions per company (PLAN_CAMBIOS_2.md 11.3): list, install, configure, enable, disable,
 * uninstall, mark incompatible and apply to the companies of a linked group. Methods use blocking
 * JDBC; call them from Dispatchers.IO. Secrets are sealed on write and never returned.
 */
class ExtensionService(
    private val connections: ExtensionConnections,
    private val storage: ExtensionStorage,
    private val registry: ExtensionRegistry,
    private val modules: ModuleRegistry,
    private val notifier: ExtensionNotifier,
    private val auditor: ExtensionAuditor,
    private val clock: () -> Instant = Instant::now,
    private val migrationRunner: ExtensionMigrationRunner = ExtensionMigrationRunner { _, _ -> },
    private val operations: ExtensionOperationStore = JdbcExtensionOperationStore(connections),
) {
    private val log = LoggerFactory.getLogger(ExtensionService::class.java)
    private val secretBox: SecretBox get() = storage.secrets
    private val runner = OperationRunner(operations)

    // ---- Company extensions ----

    fun list(companyCode: String, actor: ExtensionActor): ExtensionListResponse {
        authorize(actor, companyCode)
        return withTenant(companyCode) { tenant ->
            val rows = storage.rows(tenant)
            val enabledModules = enabledModules(tenant)
            val extensions = registry.all.filter { modules.isInstalled(it.manifest.module) }.map { extension ->
                val manifest = extension.manifest
                val row = rows[manifest.id]?.takeIf { it.status != ExtensionStatus.Removed }
                val views = if (row != null) settingViews(tenant, manifest) else emptyList()
                ExtensionSummary(
                    id = manifest.id,
                    module = manifest.module,
                    displayName = manifest.displayName,
                    description = manifest.description,
                    version = manifest.version,
                    minModuleVersion = manifest.minModuleVersion,
                    sourceType = manifest.source.type,
                    permissions = manifest.permissions,
                    uninstallOptions = manifest.uninstall.options,
                    status = row?.status,
                    moduleInstalled = true,
                    moduleEnabled = manifest.module in enabledModules,
                    compatible = compatible(manifest),
                    settings = views,
                    missingRequired = if (row != null) missingRequired(manifest, views) else emptyList(),
                )
            }
            ExtensionListResponse(companyCode, extensions)
        }
    }

    fun install(companyCode: String, extensionId: String, actor: ExtensionActor): ExtensionSummary {
        authorize(actor, companyCode)
        val extension = requireExtension(extensionId)
        installOne(companyCode, extension, actor)
        audit(actor.role, "extension.install", companyCode, extension.manifest.module, details("extension" to extensionId))
        return summary(companyCode, extension)
    }

    fun configure(companyCode: String, extensionId: String, values: Map<String, String>, actor: ExtensionActor): ExtensionSummary {
        authorize(actor, companyCode)
        val extension = requireExtension(extensionId)
        val manifest = extension.manifest
        val definitions = manifest.settings.associateBy { it.key }
        values.keys.firstOrNull { it !in definitions }?.let {
            throw ExtensionException("unknown_setting", "El ajuste '$it' no existe en esta extension.", ExtensionException.Kind.BadRequest)
        }
        // A secret sent back as the mask means "unchanged".
        val changes = values.filterNot { (key, value) -> definitions.getValue(key).secret && value == SecretBox.Mask }
        if (changes.any { (key, value) -> definitions.getValue(key).secret && value.isNotEmpty() } && !secretBox.available) {
            throw ExtensionException("SECRETS_UNAVAILABLE", "El servidor no tiene IDC_MASTER_KEY configurada: no se pueden guardar secretos.", ExtensionException.Kind.Unavailable)
        }
        withTenant(companyCode) { tenant ->
            try {
                tenant.inTransaction {
                    // The row stays locked until the commit, so a concurrent uninstall or enable waits.
                    val row = requireLocked(tenant, extensionId)
                    val current = storage.settings(tenant, extensionId).all(includeSecrets = true)
                    val merged = current.toMutableMap().also { map ->
                        changes.forEach { (key, value) -> if (value.isEmpty()) map.remove(key) else map[key] = value }
                    }
                    extension.validateSettings(merged)?.let { throw ExtensionException("invalid_settings", it, ExtensionException.Kind.BadRequest) }
                    if (row.status == ExtensionStatus.Enabled) {
                        manifest.settings.firstOrNull { it.required && merged[it.key].isNullOrBlank() }?.let {
                            throw ExtensionException("missing_required_settings", "El ajuste '${it.key}' es obligatorio mientras la extension esta activa.", ExtensionException.Kind.BadRequest)
                        }
                    }
                    changes.forEach { (key, value) ->
                        if (value.isEmpty()) storage.deleteSetting(tenant, extensionId, key)
                        else storage.putSetting(tenant, extensionId, key, value, definitions.getValue(key).secret)
                    }
                }
            } catch (_: SecretsUnavailableException) {
                throw ExtensionException("SECRETS_UNAVAILABLE", "El servidor no tiene IDC_MASTER_KEY configurada: no se pueden guardar secretos.", ExtensionException.Kind.Unavailable)
            }
        }
        // Only the names of the changed settings are recorded, never their values.
        audit(actor.role, "extension.configure", companyCode, manifest.module, details("extension" to extensionId, keys = changes.keys.sorted()))
        return summary(companyCode, extension)
    }

    fun enable(companyCode: String, extensionId: String, actor: ExtensionActor): ExtensionSummary {
        authorize(actor, companyCode)
        val extension = requireExtension(extensionId)
        enableOne(companyCode, extension)
        audit(actor.role, "extension.enable", companyCode, extension.manifest.module, details("extension" to extensionId))
        return summary(companyCode, extension)
    }

    fun disable(companyCode: String, extensionId: String, actor: ExtensionActor): ExtensionSummary {
        authorize(actor, companyCode)
        val extension = requireExtension(extensionId)
        withTenant(companyCode) { tenant ->
            tenant.inTransaction {
                val row = requireLocked(tenant, extensionId)
                if (row.status != ExtensionStatus.Disabled) {
                    ExtensionLifecycle.require(row.status, ExtensionStatus.Disabled)
                    setStatus(tenant, extensionId, ExtensionStatus.Disabled)
                }
            }
        }
        audit(actor.role, "extension.disable", companyCode, extension.manifest.module, details("extension" to extensionId))
        return summary(companyCode, extension)
    }

    /** What an uninstall would do in the company; reads only. */
    fun uninstallPreview(companyCode: String, extensionId: String, actor: ExtensionActor): UninstallPreviewResponse {
        authorize(actor, companyCode)
        val extension = requireExtension(extensionId)
        return withTenant(companyCode) { tenant ->
            requireInstalled(tenant, extensionId)
            tenant.isReadOnly = true
            UninstallPreviewResponse(extensionId, companyCode, previewItems(tenant, companyCode, extension), UninstallChoices.views(extension.manifest.uninstall.options))
        }
    }

    fun uninstall(companyCode: String, extensionId: String, answers: Map<String, String>, actor: ExtensionActor) {
        authorize(actor, companyCode)
        val extension = requireExtension(extensionId)
        val choices = UninstallChoices.resolve(extension.manifest.uninstall.options, answers)
        uninstallOne(companyCode, extension, choices)
        audit(actor.role, "extension.uninstall", companyCode, extension.manifest.module, details("extension" to extensionId, choices = choices))
    }

    /**
     * After a module update: every extension of [moduleId] whose minimum is above [newVersion]
     * becomes incompatible in each company and the business owner is told. Returns how many
     * company/extension pairs changed. A company that fails is logged and skipped.
     */
    fun markIncompatible(moduleId: String, newVersion: String): Int {
        val affected = registry.forModule(moduleId).filterNot { ExtensionLifecycle.isCompatible(it.manifest.minModuleVersion, newVersion) }
        if (affected.isEmpty()) return 0
        var changed = 0
        for (company in activeCompanyCodes()) {
            try {
                val tenant = connections.tenant(company) ?: continue
                tenant.use {
                    val rows = storage.rows(it)
                    for (extension in affected) {
                        val row = rows[extension.manifest.id] ?: continue
                        if (row.status !in setOf(ExtensionStatus.Installed, ExtensionStatus.Enabled, ExtensionStatus.Disabled)) continue
                        setStatus(it, row.extensionId, ExtensionStatus.Incompatible)
                        changed++
                        notifyBusinessOwner(
                            it, moduleId,
                            "Extension incompatible: ${extension.manifest.displayName}",
                            "El modulo ahora es la version $newVersion y la extension necesita ${extension.manifest.minModuleVersion}. Esta desactivada hasta que se instale una version compatible.",
                        )
                        audit(SystemActor, "extension.incompatible", company, moduleId, details("extension" to row.extensionId, "moduleVersion" to newVersion))
                    }
                }
            } catch (failure: Exception) {
                log.warn("Extensions of company {} not checked after updating module {}: {}", company, moduleId, failure.message)
            }
        }
        return changed
    }

    // ---- Linked groups ----

    fun group(companyCode: String, actor: ExtensionActor): GroupResponse {
        authorize(actor, companyCode)
        val members = groupMembers(companyCode)
        return GroupResponse(companyCode, members.map { member ->
            GroupMemberView(member.code, member.name, member.suspended, ownerEmailMatches = !evaluateGroupOwner(member.code, persist = false).suspendedNow)
        })
    }

    /** Server owner: puts [companyCode] in the group of [targetCode]; both need the same business owner email. */
    fun linkGroup(companyCode: String, targetCode: String, actor: ExtensionActor): GroupResponse {
        requireServerOwner(actor)
        if (companyCode == targetCode) throw ExtensionException("invalid_group", "Elige otra empresa para vincular.", ExtensionException.Kind.BadRequest)
        val (company, target) = connections.central().use { central -> companyRow(central, companyCode) to companyRow(central, targetCode) }
        company?.takeUnless { it.serverWorkspace } ?: throw ExtensionException("company_not_found", "Empresa no encontrada.", ExtensionException.Kind.NotFound)
        target?.takeUnless { it.serverWorkspace } ?: throw ExtensionException("company_not_found", "La empresa a vincular no existe.", ExtensionException.Kind.NotFound)
        val email = ownerEmailOf(companyCode)
        if (email.isNullOrBlank() || !email.equals(ownerEmailOf(targetCode), ignoreCase = true)) {
            throw ExtensionException("different_business_owner", "Las empresas deben tener el mismo correo de business owner para vincularse.")
        }
        connections.central().use { central ->
            central.inTransaction {
                central.prepareStatement(
                    "UPDATE companies SET owner_group = (SELECT owner_group FROM companies WHERE code = ?), group_owner_email = ?, group_suspended = FALSE WHERE code = ?"
                ).use { update ->
                    update.setString(1, targetCode)
                    update.setString(2, email.lowercase())
                    update.setString(3, companyCode)
                    update.executeUpdate()
                }
                // The company it joined may predate group_owner_email: record the baseline there too.
                central.prepareStatement("UPDATE companies SET group_owner_email = ? WHERE code = ? AND group_owner_email IS NULL").use { update ->
                    update.setString(1, email.lowercase())
                    update.setString(2, targetCode)
                    update.executeUpdate()
                }
            }
        }
        audit(actor.role, "extension.group.linked", companyCode, null, details("linkedTo" to targetCode))
        return group(companyCode, actor)
    }

    fun unlinkGroup(companyCode: String, actor: ExtensionActor): GroupResponse {
        requireServerOwner(actor)
        connections.central().use { central ->
            companyRow(central, companyCode)?.takeUnless { it.serverWorkspace } ?: throw ExtensionException("company_not_found", "Empresa no encontrada.", ExtensionException.Kind.NotFound)
            central.inTransaction {
                central.prepareStatement("UPDATE companies SET owner_group = id, group_owner_email = NULL, group_suspended = FALSE WHERE code = ?").use { update ->
                    update.setString(1, companyCode)
                    update.executeUpdate()
                }
            }
        }
        audit(actor.role, "extension.group.unlinked", companyCode, null, details())
        return group(companyCode, actor)
    }

    /** Server owner: accepts the current business owner email of a suspended company and lifts the suspension. */
    fun reviewSuspension(companyCode: String, actor: ExtensionActor): GroupResponse {
        requireServerOwner(actor)
        val email = ownerEmailOf(companyCode)?.takeIf { it.isNotBlank() }
            ?: throw ExtensionException("owner_email_missing", "El business owner de la empresa no tiene correo.")
        connections.central().use { central ->
            companyRow(central, companyCode)?.takeUnless { it.serverWorkspace } ?: throw ExtensionException("company_not_found", "Empresa no encontrada.", ExtensionException.Kind.NotFound)
            central.prepareStatement("UPDATE companies SET group_owner_email = ?, group_suspended = FALSE WHERE code = ?").use { update ->
                update.setString(1, email.lowercase())
                update.setString(2, companyCode)
                update.executeUpdate()
            }
        }
        audit(actor.role, "extension.group.reviewed", companyCode, null, details())
        return group(companyCode, actor)
    }

    /**
     * Records the business owner email of a company as its group baseline (call it when a company is
     * created in a group or linked). A company with no baseline yet gets one on its first check.
     */
    fun recordGroupOwner(companyCode: String) {
        val email = ownerEmailOf(companyCode)?.takeIf { it.isNotBlank() } ?: return
        connections.central().use { central ->
            central.prepareStatement("UPDATE companies SET group_owner_email = ? WHERE code = ? AND group_owner_email IS NULL").use { update ->
                update.setString(1, email.lowercase())
                update.setString(2, companyCode)
                update.executeUpdate()
            }
        }
    }

    /**
     * Compares the business owner email of the company now with the one recorded for its group and
     * suspends it in its group when they differ. True when the company may take part in group
     * operations. Call it before every apply.
     */
    fun checkGroupOwner(companyCode: String): Boolean = !evaluateGroupOwner(companyCode, persist = true).blocked

    fun previewGroupOperation(sourceCompany: String, extensionId: String, request: GroupOperationRequest, actor: ExtensionActor): GroupOperationPreview {
        authorize(actor, sourceCompany)
        val extension = requireExtension(extensionId)
        val operation = requireOperation(request.operation)
        val plan = planGroupOperation(sourceCompany, extension, operation, request, persist = false)
        val sourceSettings = if (operation == GroupOperationType.Apply) copyableSourceSettings(sourceCompany, extension) else emptyMap()
        val targets = plan.targets.map { code ->
            val problem = plan.problems[code]
            val name = plan.names[code].orEmpty()
            GroupTargetPreview(code, name, if (problem != null) emptyList() else steps(code, extension, operation, sourceSettings.size), problem)
        }
        return GroupOperationPreview(extensionId, operation, sourceCompany, targets)
    }

    /** Runs the operation on each target in its own transaction; a failed target does not stop the others. */
    fun applyToGroup(sourceCompany: String, extensionId: String, request: GroupOperationRequest, actor: ExtensionActor): GroupOperationResult {
        authorize(actor, sourceCompany)
        val extension = requireExtension(extensionId)
        val operation = requireOperation(request.operation)
        val plan = planGroupOperation(sourceCompany, extension, operation, request, persist = true)
        val execute = executorFor(sourceCompany, extension, operation, request, actor)
        val operationId = runner.start(
            actor.role, operation, extensionId, sourceCompany, plan.targets,
            validate = { code -> validateTarget(sourceCompany, extension, code) },
            execute = execute,
        )
        val result = result(operationId)
        audit(actor.role, "extension.$operation.group", sourceCompany, extension.manifest.module, details("extension" to extensionId, "operationId" to operationId.toString(), "status" to result.status))
        return result
    }

    /** Retries only the failed targets of an operation, inside that same operation. */
    fun retryFailed(sourceCompany: String, extensionId: String, operationId: Long, request: GroupOperationRequest, actor: ExtensionActor): GroupOperationResult {
        authorize(actor, sourceCompany)
        val extension = requireExtension(extensionId)
        val header = operations.header(operationId)
        if (header == null || header.extensionId != extensionId || header.sourceCompany != sourceCompany) {
            throw ExtensionException("operation_not_found", "La operacion no existe.", ExtensionException.Kind.NotFound)
        }
        if (header.operation !in setOf(GroupOperationType.Apply, GroupOperationType.Uninstall)) {
            throw ExtensionException("operation_not_retryable", "Solo se reintentan operaciones sobre un grupo.")
        }
        if (!checkGroupOwner(sourceCompany)) {
            throw ExtensionException("group_suspended", "La empresa de origen esta suspendida en su grupo.")
        }
        val execute = executorFor(sourceCompany, extension, header.operation, request, actor)
        runner.retryFailed(operationId, validate = { code -> validateTarget(sourceCompany, extension, code) }, execute = execute)
        val result = result(operationId)
        audit(actor.role, "extension.${header.operation}.retry", sourceCompany, extension.manifest.module, details("extension" to extensionId, "operationId" to operationId.toString(), "status" to result.status))
        return result
    }

    /**
     * Call it at startup: operations a restart left running end as failed, and their pending targets
     * as failed ("interrumpida"), so the actor can retry them. Returns how many operations it closed.
     */
    fun recoverInterrupted(): Int = operations.failInterrupted()

    fun result(operationId: Long): GroupOperationResult {
        val header = operations.header(operationId) ?: throw ExtensionException("operation_not_found", "La operacion no existe.", ExtensionException.Kind.NotFound)
        val targets = operations.targets(operationId)
        return GroupOperationResult(
            operationId, header.extensionId, header.operation, header.sourceCompany.orEmpty(), OperationStatus.of(targets),
            targets.map { GroupTargetResult(it.companyCode, it.status, it.reason, it.attempts) },
        )
    }

    // ---- Steps shared by single-company and group operations ----

    private fun installOne(companyCode: String, extension: ServerExtension, actor: ExtensionActor?) {
        val manifest = extension.manifest
        if (!modules.isInstalled(manifest.module)) {
            throw ExtensionException("module_not_installed", "El modulo ${manifest.module} no esta instalado en el servidor.")
        }
        if (!compatible(manifest)) {
            throw ExtensionException("extension_incompatible", "La extension necesita el modulo ${manifest.module} ${manifest.minModuleVersion} o superior.")
        }
        withTenant(companyCode) { tenant ->
            if (manifest.module !in enabledModules(tenant)) {
                throw ExtensionException("module_disabled", "El modulo ${manifest.module} no esta activo en la empresa.")
            }
            // Installing again is harmless: pending migrations still run, the state is kept.
            migrationRunner.run(companyCode, extension)
            tenant.inTransaction {
                val row = storage.rowForUpdate(tenant, manifest.id)
                if (row != null && row.status != ExtensionStatus.Removed) return@inTransaction
                ExtensionLifecycle.require(row?.status, ExtensionStatus.Installed)
                // Only a removed row is overwritten: an enabled extension is never taken back to "installed".
                tenant.prepareStatement(
                    """
                    INSERT INTO tenant_extensions (extension_id, module_id, version, status, installed_by, installed_at, updated_at)
                    VALUES (?, ?, ?, 'installed', ?, ?, ?)
                    ON CONFLICT (extension_id) DO UPDATE
                    SET module_id = EXCLUDED.module_id, version = EXCLUDED.version, status = 'installed', installed_by = EXCLUDED.installed_by,
                        installed_at = EXCLUDED.installed_at, updated_at = EXCLUDED.updated_at, enabled_at = NULL
                    WHERE tenant_extensions.status = 'removed'
                    """.trimIndent()
                ).use { statement ->
                    val now = Timestamp.from(clock())
                    statement.setString(1, manifest.id)
                    statement.setString(2, manifest.module)
                    statement.setString(3, manifest.version)
                    statement.setString(4, actor?.role)
                    statement.setTimestamp(5, now)
                    statement.setTimestamp(6, now)
                    statement.executeUpdate()
                }
            }
        }
    }

    private fun enableOne(companyCode: String, extension: ServerExtension) {
        val manifest = extension.manifest
        withTenant(companyCode) { tenant ->
            tenant.inTransaction {
                val row = requireLocked(tenant, manifest.id)
                if (row.status == ExtensionStatus.Enabled) return@inTransaction
                ExtensionLifecycle.require(row.status, ExtensionStatus.Enabled)
                if (!compatible(manifest)) {
                    throw ExtensionException("extension_incompatible", "La extension necesita el modulo ${manifest.module} ${manifest.minModuleVersion} o superior.")
                }
                if (manifest.module !in enabledModules(tenant)) {
                    throw ExtensionException("module_disabled", "El modulo ${manifest.module} no esta activo en la empresa.")
                }
                val missing = missingRequired(manifest, settingViews(tenant, manifest))
                if (missing.isNotEmpty()) {
                    throw ExtensionException("missing_required_settings", "Faltan ajustes obligatorios: ${missing.joinToString(", ")}.")
                }
                extension.onEnable(ExtensionContext(companyCode, tenant, manifest.id, storage.settings(tenant, manifest.id)))
                setStatus(tenant, manifest.id, ExtensionStatus.Enabled)
            }
        }
    }

    private fun uninstallOne(companyCode: String, extension: ServerExtension, choices: Map<String, String>) {
        val manifest = extension.manifest
        withTenant(companyCode) { tenant ->
            // One transaction: an extension that throws leaves settings, objects and status as they were.
            tenant.inTransaction {
                requireLocked(tenant, manifest.id)
                extension.uninstall(ExtensionContext(companyCode, tenant, manifest.id, storage.settings(tenant, manifest.id)), choices)
                tenant.prepareStatement("DELETE FROM tenant_extension_settings WHERE extension_id = ?").use {
                    it.setString(1, manifest.id)
                    it.executeUpdate()
                }
                // Objects of an option that was kept stay registered; the rest are gone with the extension.
                val kept = choices.filterValues { it == UninstallDefault.Keep }.keys.toList()
                val keepClause = if (kept.isEmpty()) "" else " AND (option_id IS NULL OR option_id NOT IN (${kept.joinToString(",") { "?" }}))"
                tenant.prepareStatement("DELETE FROM tenant_extension_objects WHERE extension_id = ?$keepClause").use {
                    it.setString(1, manifest.id)
                    kept.forEachIndexed { index, option -> it.setString(index + 2, option) }
                    it.executeUpdate()
                }
                setStatus(tenant, manifest.id, ExtensionStatus.Removed)
            }
        }
        // After the commit; what lives outside the company's database is released here, best effort.
        try {
            extension.afterUninstall(companyCode)
        } catch (failure: Exception) {
            log.error("Cleanup after uninstalling extension {} from company {} failed: {}", manifest.id, companyCode, failure.javaClass.simpleName)
        }
    }

    private fun previewItems(tenant: Connection, companyCode: String, extension: ServerExtension): List<UninstallItem> {
        val manifest = extension.manifest
        val settings = storage.storedSettings(tenant, manifest.id).size
        val own = extension.uninstallPreview(ExtensionContext(companyCode, tenant, manifest.id, storage.settings(tenant, manifest.id)))
        return listOf(UninstallItem(null, "Ajustes de la extension", settings, UninstallDefault.Delete)) + own
    }

    // ---- Group planning ----

    private class GroupPlan(val targets: List<String>, val problems: Map<String, String>, val names: Map<String, String>)

    private fun planGroupOperation(sourceCompany: String, extension: ServerExtension, operation: String, request: GroupOperationRequest, persist: Boolean): GroupPlan {
        val manifest = extension.manifest
        val source = evaluateGroupOwner(sourceCompany, persist)
        if (source.blocked) throw ExtensionException("group_suspended", "La empresa de origen esta suspendida en su grupo o no se pudo verificar su business owner.")
        withTenant(sourceCompany) { tenant ->
            val row = requireInstalled(tenant, manifest.id)
            if (operation == GroupOperationType.Apply && row.status in setOf(ExtensionStatus.Incompatible)) {
                throw ExtensionException("extension_incompatible", "La extension de la empresa de origen es incompatible.")
            }
        }
        if (operation == GroupOperationType.Uninstall) UninstallChoices.resolve(manifest.uninstall.options, request.choices)
        val targets = request.targets.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
        if (targets.size > MaxGroupTargets) {
            throw ExtensionException("too_many_targets", "Elige como maximo $MaxGroupTargets empresas por operacion.", ExtensionException.Kind.BadRequest)
        }
        if (targets.isEmpty()) throw ExtensionException("no_targets", "Elige al menos una empresa de destino.", ExtensionException.Kind.BadRequest)
        val members = groupMembers(sourceCompany).associateBy { it.code }
        val problems = HashMap<String, String>()
        for (code in targets) {
            val problem = targetProblem(sourceCompany, extension, code, members[code], persist)
            if (problem != null) problems[code] = problem
        }
        return GroupPlan(targets, problems, members.mapValues { it.value.name })
    }

    // Validation of a target while the operation runs (the same checks again, persisting a suspension).
    private fun validateTarget(sourceCompany: String, extension: ServerExtension, code: String): String? =
        targetProblem(sourceCompany, extension, code, groupMembers(sourceCompany).firstOrNull { it.code == code }, persist = true)

    private fun targetProblem(sourceCompany: String, extension: ServerExtension, code: String, @Suppress("UNUSED_PARAMETER") member: GroupMember?, persist: Boolean): String? {
        val manifest = extension.manifest
        val (source, target) = connections.central().use { central -> companyRow(central, sourceCompany) to companyRow(central, code) }
        val usable = target != null && !target.serverWorkspace
        val inGroup = usable && target!!.active && source != null && target.ownerGroup == source.ownerGroup
        val targetOwner = if (inGroup) evaluateGroupOwner(code, persist) else null
        val moduleEnabled = inGroup && runCatching { withTenant(code) { manifest.module in enabledModules(it) } }.getOrDefault(false)
        val facts = GroupTargetFacts(
            companyCode = code,
            exists = usable,
            active = target?.active == true,
            sameGroup = inGroup || (usable && source != null && target!!.ownerGroup == source.ownerGroup),
            suspended = target?.suspended == true || targetOwner?.suspendedNow == true,
            ownerEmail = targetOwner?.currentEmail,
            expectedOwnerEmail = if (inGroup) ownerEmailOf(sourceCompany) else null,
            moduleEnabled = moduleEnabled,
            moduleInstalled = modules.isInstalled(manifest.module),
            compatible = compatible(manifest),
        )
        return GroupTargetValidator.problem(facts, sourceCompany)
    }

    private fun steps(code: String, extension: ServerExtension, operation: String, copyableSettings: Int): List<String> {
        val manifest = extension.manifest
        val row = runCatching { withTenant(code) { storage.row(it, manifest.id) } }.getOrNull()
        if (operation == GroupOperationType.Uninstall) {
            return if (row == null || row.status == ExtensionStatus.Removed) listOf("La extension no esta instalada: no se hace nada")
            else listOf("Desinstalar la extension (se borran sus ajustes)")
        }
        return buildList {
            if (row == null || row.status == ExtensionStatus.Removed) add("Instalar la extension")
            if (manifest.migrations.isNotEmpty() || extension.migrationPaths.isNotEmpty()) add("Ejecutar las migraciones pendientes")
            add("Copiar $copyableSettings ajustes de la aplicacion, cifrados de nuevo para la empresa; los ajustes de conexion no se copian")
            if (row?.status != ExtensionStatus.Enabled) add("Activar la extension")
        }
    }

    private fun executorFor(sourceCompany: String, extension: ServerExtension, operation: String, request: GroupOperationRequest, actor: ExtensionActor): (String) -> Unit {
        val manifest = extension.manifest
        if (operation == GroupOperationType.Uninstall) {
            val choices = UninstallChoices.resolve(manifest.uninstall.options, request.choices)
            return { code ->
                val installed = withTenant(code) { storage.row(it, manifest.id)?.status }
                if (installed != null && installed != ExtensionStatus.Removed) uninstallOne(code, extension, choices)
                audit(actor.role, "extension.uninstall", code, manifest.module, details("extension" to manifest.id, "from" to sourceCompany, choices = choices))
            }
        }
        // Read once for all targets: plain values live only in this closure while the operation runs.
        val values = copyableSourceSettings(sourceCompany, extension)
        val secretKeys = manifest.settings.filter { it.secret }.map { it.key }.toSet()
        return { code ->
            installOne(code, extension, actor)
            withTenant(code) { tenant ->
                tenant.inTransaction {
                    values.forEach { (key, value) -> storage.putSetting(tenant, manifest.id, key, value, secret = key in secretKeys) }
                }
            }
            enableOne(code, extension)
            audit(actor.role, "extension.apply", code, manifest.module, details("extension" to manifest.id, "from" to sourceCompany, keys = values.keys.sorted()))
        }
    }

    /** Settings of the source that may be copied (scope other than "connection"), opened with the source's key. */
    private fun copyableSourceSettings(sourceCompany: String, extension: ServerExtension): Map<String, String> =
        withTenant(sourceCompany) { tenant ->
            SettingsCopy.copyable(extension.manifest.settings, storage.settings(tenant, extension.manifest.id).all(includeSecrets = true))
        }

    // ---- Group ownership ----

    private data class GroupOwnerState(val blocked: Boolean, val suspendedNow: Boolean, val currentEmail: String?)

    private fun evaluateGroupOwner(companyCode: String, persist: Boolean): GroupOwnerState {
        val row = connections.central().use { companyRow(it, companyCode) } ?: return GroupOwnerState(blocked = true, suspendedNow = false, currentEmail = null)
        val current = ownerEmailOf(companyCode)?.lowercase()?.takeIf { it.isNotBlank() }
        if (current == null) return GroupOwnerState(blocked = true, suspendedNow = row.suspended, currentEmail = null)
        if (row.suspended) return GroupOwnerState(blocked = true, suspendedNow = true, currentEmail = current)
        val recorded = row.ownerEmail?.lowercase()
        if (recorded == null) {
            // Linked before the baseline existed: trust the current owner and remember it.
            if (persist) recordGroupOwner(companyCode)
            return GroupOwnerState(blocked = false, suspendedNow = false, currentEmail = current)
        }
        if (recorded == current) return GroupOwnerState(blocked = false, suspendedNow = false, currentEmail = current)
        if (persist) {
            connections.central().use { central ->
                central.prepareStatement("UPDATE companies SET group_suspended = TRUE WHERE code = ?").use { update ->
                    update.setString(1, companyCode)
                    update.executeUpdate()
                }
            }
            audit(SystemActor, "extension.group.suspended", companyCode, null, details())
        }
        return GroupOwnerState(blocked = true, suspendedNow = true, currentEmail = current)
    }

    private class GroupMember(val code: String, val name: String, val suspended: Boolean)

    private fun groupMembers(companyCode: String): List<GroupMember> = connections.central().use { central ->
        central.prepareStatement(
            """
            SELECT member.code, member.name, member.group_suspended
            FROM companies origin
            JOIN companies member ON member.owner_group = origin.owner_group AND member.code <> origin.code
            WHERE origin.code = ? AND member.is_active AND NOT member.is_server_workspace
            ORDER BY lower(member.name)
            """.trimIndent()
        ).use { query ->
            query.setString(1, companyCode)
            query.executeQuery().use { rows -> buildList { while (rows.next()) add(GroupMember(rows.getString(1), rows.getString(2), rows.getBoolean(3))) } }
        }
    }

    private class CompanyGroupRow(
        val code: String,
        val active: Boolean,
        val serverWorkspace: Boolean,
        val ownerGroup: String?,
        val suspended: Boolean,
        val ownerEmail: String?,
    )

    private fun companyRow(central: Connection, code: String): CompanyGroupRow? =
        central.prepareStatement(
            "SELECT code, is_active, is_server_workspace, owner_group::text, group_suspended, group_owner_email FROM companies WHERE code = ?"
        ).use { query ->
            query.setString(1, code)
            query.executeQuery().use { rows ->
                if (rows.next()) CompanyGroupRow(rows.getString(1), rows.getBoolean(2), rows.getBoolean(3), rows.getString(4), rows.getBoolean(5), rows.getString(6)) else null
            }
        }

    private fun ownerEmailOf(companyCode: String): String? = connections.tenant(companyCode)?.use { tenant ->
        tenant.prepareStatement(
            "SELECT users.email FROM business_owner owner JOIN application_users users ON users.id = owner.user_id ORDER BY users.email LIMIT 1"
        ).use { query -> query.executeQuery().use { rows -> if (rows.next()) rows.getString(1)?.trim()?.lowercase() else null } }
    }

    private fun notifyBusinessOwner(tenant: Connection, moduleId: String, title: String, text: String) {
        val owners = tenant.prepareStatement("SELECT user_id FROM business_owner").use { query ->
            query.executeQuery().use { rows -> buildList { while (rows.next()) add(rows.getObject(1, UUID::class.java)) } }
        }
        owners.forEach { notifier.notify(tenant, moduleId, it, title, text) }
    }

    // ---- Helpers ----

    private fun authorize(actor: ExtensionActor, companyCode: String) {
        if (actor.serverOwner) return
        if (actor.companyCode == null || actor.companyCode != companyCode) {
            throw ExtensionException("forbidden", "No puedes administrar las extensiones de esta empresa.", ExtensionException.Kind.Forbidden)
        }
    }

    private fun requireServerOwner(actor: ExtensionActor) {
        if (!actor.serverOwner) throw ExtensionException("server_owner_required", "Solo el dueno del servidor puede hacerlo.", ExtensionException.Kind.Forbidden)
    }

    private fun requireExtension(extensionId: String): ServerExtension =
        registry.get(extensionId) ?: throw ExtensionException("extension_not_found", "La extension no existe en este servidor.", ExtensionException.Kind.NotFound)

    private fun requireOperation(operation: String): String {
        if (operation != GroupOperationType.Apply && operation != GroupOperationType.Uninstall) {
            throw ExtensionException("invalid_operation", "La operacion debe ser apply o uninstall.", ExtensionException.Kind.BadRequest)
        }
        return operation
    }

    // Inside a transaction: the row of the extension, locked until commit.
    private fun requireLocked(tenant: Connection, extensionId: String): TenantExtensionRow =
        storage.rowForUpdate(tenant, extensionId)?.takeIf { it.status != ExtensionStatus.Removed }
            ?: throw ExtensionException("extension_not_installed", "La extension no esta instalada en esta empresa.", ExtensionException.Kind.NotFound)

    // The audit never changes the outcome of what already happened: a failure is logged, not thrown.
    private fun audit(actorRole: String, action: String, companyCode: String?, moduleId: String?, details: String) {
        try {
            auditor(actorRole, action, companyCode, moduleId, details)
        } catch (failure: Exception) {
            log.error("Audit entry {} could not be written: {}", action, failure.javaClass.simpleName)
        }
    }

    private fun requireInstalled(tenant: Connection, extensionId: String): TenantExtensionRow =
        storage.row(tenant, extensionId)?.takeIf { it.status != ExtensionStatus.Removed }
            ?: throw ExtensionException("extension_not_installed", "La extension no esta instalada en esta empresa.", ExtensionException.Kind.NotFound)

    private fun compatible(manifest: ExtensionManifest) = ExtensionLifecycle.isCompatible(manifest.minModuleVersion, modules.loadedVersion(manifest.module))

    private fun missingRequired(manifest: ExtensionManifest, views: List<ExtensionSettingView>): List<String> {
        val set = views.filter { it.isSet }.map { it.key }.toSet()
        return manifest.settings.filter { it.required && it.key !in set }.map { it.key }
    }

    private fun settingViews(tenant: Connection, manifest: ExtensionManifest): List<ExtensionSettingView> {
        val stored = storage.storedSettings(tenant, manifest.id).associateBy { it.key }
        return manifest.settings.map { definition ->
            val value = stored[definition.key]
            val shown = when {
                value == null -> ""
                definition.secret || value.secret -> SecretBox.mask(value.value)
                else -> value.value
            }
            ExtensionSettingView(definition.key, definition.label, definition.secret, definition.required, definition.help, definition.scope, shown, value != null && value.value.isNotEmpty())
        }
    }

    private fun summary(companyCode: String, extension: ServerExtension): ExtensionSummary =
        withTenant(companyCode) { tenant ->
            val manifest = extension.manifest
            val row = storage.row(tenant, manifest.id)?.takeIf { it.status != ExtensionStatus.Removed }
            val views = if (row != null) settingViews(tenant, manifest) else emptyList()
            ExtensionSummary(
                manifest.id, manifest.module, manifest.displayName, manifest.description, manifest.version, manifest.minModuleVersion,
                manifest.source.type, manifest.permissions, manifest.uninstall.options, row?.status, modules.isInstalled(manifest.module),
                manifest.module in enabledModules(tenant), compatible(manifest), views, if (row != null) missingRequired(manifest, views) else emptyList(),
            )
        }

    private fun enabledModules(tenant: Connection): Set<String> =
        tenant.prepareStatement("SELECT module_id FROM tenant_modules WHERE status = 'enabled'").use { query ->
            query.executeQuery().use { rows -> buildSet { while (rows.next()) add(rows.getString(1)) } }
        }

    private fun setStatus(tenant: Connection, extensionId: String, status: String) {
        tenant.prepareStatement(
            "UPDATE tenant_extensions SET status = ?, updated_at = ?, enabled_at = CASE WHEN ? = 'enabled' THEN ? ELSE enabled_at END WHERE extension_id = ?"
        ).use { statement ->
            val now = Timestamp.from(clock())
            statement.setString(1, status)
            statement.setTimestamp(2, now)
            statement.setString(3, status)
            statement.setTimestamp(4, now)
            statement.setString(5, extensionId)
            statement.executeUpdate()
        }
    }

    private fun activeCompanyCodes(): List<String> = connections.central().use { central ->
        central.prepareStatement("SELECT code FROM companies WHERE is_active = TRUE AND NOT is_server_workspace ORDER BY code").use { query ->
            query.executeQuery().use { rows -> buildList { while (rows.next()) add(rows.getString(1)) } }
        }
    }

    private fun <T> withTenant(companyCode: String, block: (Connection) -> T): T {
        val tenant = connections.tenant(companyCode)
            ?: throw ExtensionException("company_not_found", "La empresa no esta activa.", ExtensionException.Kind.NotFound)
        return tenant.use(block)
    }

    private fun details(vararg pairs: Pair<String, String>, keys: List<String>? = null, choices: Map<String, String>? = null): String =
        buildJsonObject {
            pairs.forEach { (key, value) -> put(key, value) }
            keys?.let { put("keys", JsonArray(it.map { name -> JsonPrimitive(name) })) }
            choices?.let { map -> put("choices", buildJsonObject { map.forEach { (key, value) -> put(key, value) } }) }
        }.toString()

    private fun <T> Connection.inTransaction(block: () -> T): T {
        val previous = autoCommit
        autoCommit = false
        try {
            val result = block()
            commit()
            return result
        } catch (failure: Throwable) {
            runCatching { rollback() }
            throw failure
        } finally {
            autoCommit = previous
        }
    }

    companion object {
        const val SystemActor = "system"
        const val MaxGroupTargets = 50
    }
}
