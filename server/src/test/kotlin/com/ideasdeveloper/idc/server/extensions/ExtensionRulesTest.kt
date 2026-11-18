package com.ideasdeveloper.idc.server.extensions

import io.ktor.http.HttpStatusCode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ExtensionRulesTest {
    @Test
    fun lifecycleAllowsOnlyTheDocumentedTransitions() {
        assertTrue(ExtensionLifecycle.canTransition(null, ExtensionStatus.Installed))
        assertFalse(ExtensionLifecycle.canTransition(null, ExtensionStatus.Enabled))
        assertTrue(ExtensionLifecycle.canTransition(ExtensionStatus.Installed, ExtensionStatus.Enabled))
        assertTrue(ExtensionLifecycle.canTransition(ExtensionStatus.Enabled, ExtensionStatus.Disabled))
        assertTrue(ExtensionLifecycle.canTransition(ExtensionStatus.Disabled, ExtensionStatus.Enabled))
        assertTrue(ExtensionLifecycle.canTransition(ExtensionStatus.Enabled, ExtensionStatus.Incompatible))
        assertTrue(ExtensionLifecycle.canTransition(ExtensionStatus.Incompatible, ExtensionStatus.Enabled))
        assertFalse(ExtensionLifecycle.canTransition(ExtensionStatus.Enabled, ExtensionStatus.Installed))
        // A removed extension can only be installed again.
        assertFalse(ExtensionLifecycle.canTransition(ExtensionStatus.Removed, ExtensionStatus.Enabled))
        assertTrue(ExtensionLifecycle.canTransition(ExtensionStatus.Removed, ExtensionStatus.Installed))
        listOf(ExtensionStatus.Installed, ExtensionStatus.Enabled, ExtensionStatus.Disabled, ExtensionStatus.Incompatible).forEach {
            assertTrue(ExtensionLifecycle.canTransition(it, ExtensionStatus.Removed), it)
        }
    }

    @Test
    fun invalidTransitionIsRefusedWithItsOwnCode() {
        val failure = assertFailsWith<ExtensionException> { ExtensionLifecycle.require(ExtensionStatus.Removed, ExtensionStatus.Enabled) }
        assertEquals("invalid_transition", failure.code)
    }

    @Test
    fun compatibilityComparesVersionsNumerically() {
        assertTrue(ExtensionLifecycle.isCompatible(null, "0.1.0"))
        assertTrue(ExtensionLifecycle.isCompatible("0.23.0", "0.25.0"))
        assertTrue(ExtensionLifecycle.isCompatible("0.23.0", "0.23.0"))
        assertFalse(ExtensionLifecycle.isCompatible("0.23.0", "0.9.0"))
        assertTrue(ExtensionLifecycle.isCompatible("0.9.0", "0.10.0"))
    }

    @Test
    fun gateAnswersFromTheStoredStatus() {
        assertEquals(ExtensionGateDecision.Open, ExtensionGateDecision.forStatus(ExtensionStatus.Enabled))
        val missing = ExtensionGateDecision.forStatus(null) as ExtensionGateDecision.Closed
        assertEquals(HttpStatusCode.NotFound, missing.status)
        assertEquals("extension_not_installed", missing.body.code)
        assertEquals("extension_not_installed", (ExtensionGateDecision.forStatus(ExtensionStatus.Removed) as ExtensionGateDecision.Closed).body.code)
        val disabled = ExtensionGateDecision.forStatus(ExtensionStatus.Disabled) as ExtensionGateDecision.Closed
        assertEquals(HttpStatusCode.Forbidden, disabled.status)
        assertEquals("extension_disabled", disabled.body.code)
        assertEquals("extension_disabled", (ExtensionGateDecision.forStatus(ExtensionStatus.Installed) as ExtensionGateDecision.Closed).body.code)
        val incompatible = ExtensionGateDecision.forStatus(ExtensionStatus.Incompatible) as ExtensionGateDecision.Closed
        assertEquals(HttpStatusCode.Conflict, incompatible.status)
        assertEquals("extension_incompatible", incompatible.body.code)
    }

    @Test
    fun gateOpensServerLevelRoutesOnlyWhenTheModuleIsInstalled() {
        val states = mapOf(
            "acme" to ExtensionTenantState(ExtensionStatus.Enabled, true),
            "beta" to ExtensionTenantState(ExtensionStatus.Disabled, true),
            "delta" to ExtensionTenantState(ExtensionStatus.Enabled, false),
        )
        val gate = ExtensionGate(moduleInstalled = { it == "crm" }, stateOf = { company, id, _ -> if (id == "meta") states[company] else null })

        assertEquals(ExtensionGateDecision.Open, gate.decide("crm", "meta", null))
        assertEquals("module_not_installed", (gate.decide("hotspot", "meta", null) as ExtensionGateDecision.Closed).body.code)
        assertEquals(ExtensionGateDecision.Open, gate.decide("crm", "meta", "acme"))
        assertEquals(ExtensionGateDecision.Open, gate.decide("crm", "meta", null, "acme"))
        assertEquals("extension_disabled", (gate.decide("crm", "meta", "beta") as ExtensionGateDecision.Closed).body.code)
        assertEquals("extension_not_installed", (gate.decide("crm", "meta", "gamma") as ExtensionGateDecision.Closed).body.code)
        // The module must be enabled in the company too.
        assertEquals("module_disabled", (gate.decide("crm", "meta", "delta") as ExtensionGateDecision.Closed).body.code)
        // A malformed company code never reaches the database.
        assertEquals("extension_not_installed", (gate.decide("crm", "meta", "../x") as ExtensionGateDecision.Closed).body.code)
    }

    @Test
    fun gateClosesWhenTheHeaderNamesAnotherCompanyThanThePath() {
        val gate = ExtensionGate(moduleInstalled = { true }, stateOf = { _, _, _ -> ExtensionTenantState(ExtensionStatus.Enabled, true) })

        val mismatch = gate.decide("crm", "meta", "acme", "beta") as ExtensionGateDecision.Closed
        assertEquals(HttpStatusCode.Forbidden, mismatch.status)
        assertEquals("company_mismatch", mismatch.body.code)
        assertEquals(ExtensionGateDecision.Open, gate.decide("crm", "meta", "acme", "acme"))
    }

    private val options = listOf(
        ExtensionUninstallOption("crm_fields", "Campos del CRM", UninstallDefault.Ask),
        ExtensionUninstallOption("history", "Historial", UninstallDefault.Delete),
        ExtensionUninstallOption("log", "Registro", UninstallDefault.Keep),
    )

    @Test
    fun uninstallWithoutAnswerForAskOptionIsRefused() {
        val failure = assertFailsWith<ExtensionException> { UninstallChoices.resolve(options, emptyMap()) }
        assertEquals("uninstall_choice_required", failure.code)
        assertTrue(failure.message.contains("crm_fields"))
        assertEquals(ExtensionException.Kind.BadRequest, failure.kind)
    }

    @Test
    fun uninstallUsesDefaultsForTheRestAndAnswersOverrideThem() {
        val resolved = UninstallChoices.resolve(options, mapOf("crm_fields" to "keep", "history" to "KEEP"))
        assertEquals(mapOf("crm_fields" to "keep", "history" to "keep", "log" to "keep"), resolved)
        assertEquals("delete", UninstallChoices.resolve(options, mapOf("crm_fields" to "delete", "log" to "delete"))["log"])
    }

    @Test
    fun uninstallRefusesUnknownOptionsAndValues() {
        assertEquals("unknown_uninstall_option", assertFailsWith<ExtensionException> { UninstallChoices.resolve(options, mapOf("nope" to "keep")) }.code)
        assertEquals("invalid_uninstall_choice", assertFailsWith<ExtensionException> { UninstallChoices.resolve(options, mapOf("crm_fields" to "ask")) }.code)
        assertEquals(emptyMap(), UninstallChoices.resolve(emptyList(), emptyMap()))
    }

    private val definitions = listOf(
        ExtensionSettingDefinition("app_id", scope = ExtensionSettingScope.App),
        ExtensionSettingDefinition("app_secret", secret = true, scope = ExtensionSettingScope.App),
        ExtensionSettingDefinition("page_token", secret = true, scope = ExtensionSettingScope.Connection),
        ExtensionSettingDefinition("page_id", scope = ExtensionSettingScope.Connection),
    )

    @Test
    fun connectionSettingsAndUnknownKeysAreNeverCopied() {
        val source = mapOf("app_id" to "1", "app_secret" to "s", "page_token" to "t", "page_id" to "p", "stray" to "x")
        assertEquals(mapOf("app_id" to "1", "app_secret" to "s"), SettingsCopy.copyable(definitions, source))
        assertEquals(listOf("app_id", "app_secret"), SettingsCopy.copyableKeys(definitions))
    }

    private fun facts(
        code: String = "beta",
        exists: Boolean = true,
        active: Boolean = true,
        sameGroup: Boolean = true,
        suspended: Boolean = false,
        ownerEmail: String? = "owner@x.com",
        expected: String? = "Owner@X.com",
        moduleEnabled: Boolean = true,
        moduleInstalled: Boolean = true,
        compatible: Boolean = true,
    ) = GroupTargetFacts(code, exists, active, sameGroup, suspended, ownerEmail, expected, moduleEnabled, moduleInstalled, compatible)

    @Test
    fun groupTargetValidationChecksEveryRule() {
        assertNull(GroupTargetValidator.problem(facts(), "acme"))
        assertTrue(GroupTargetValidator.problem(facts(code = "acme"), "acme")!!.contains("origen"))
        assertTrue(GroupTargetValidator.problem(facts(exists = false), "acme")!!.contains("no existe"))
        assertTrue(GroupTargetValidator.problem(facts(active = false), "acme")!!.contains("activa"))
        assertTrue(GroupTargetValidator.problem(facts(sameGroup = false), "acme")!!.contains("grupo"))
        assertTrue(GroupTargetValidator.problem(facts(suspended = true), "acme")!!.contains("suspendida"))
        assertTrue(GroupTargetValidator.problem(facts(ownerEmail = "other@x.com"), "acme")!!.contains("business owner"))
        assertTrue(GroupTargetValidator.problem(facts(ownerEmail = null), "acme")!!.contains("verificar"))
        assertTrue(GroupTargetValidator.problem(facts(expected = null), "acme")!!.contains("verificar"))
        assertTrue(GroupTargetValidator.problem(facts(moduleInstalled = false), "acme")!!.contains("servidor"))
        assertTrue(GroupTargetValidator.problem(facts(moduleEnabled = false), "acme")!!.contains("activo"))
        assertTrue(GroupTargetValidator.problem(facts(compatible = false), "acme")!!.contains("version"))
    }

    @Test
    fun operationStatusSummarisesItsTargets() {
        fun target(status: String) = TargetRecord("c", status, null, 1)
        assertEquals(OperationStatus.Done, OperationStatus.of(listOf(target(TargetStatus.Applied), target(TargetStatus.Skipped))))
        assertEquals(OperationStatus.Partial, OperationStatus.of(listOf(target(TargetStatus.Applied), target(TargetStatus.Failed))))
        assertEquals(OperationStatus.Failed, OperationStatus.of(listOf(target(TargetStatus.Failed), target(TargetStatus.Skipped))))
        assertEquals(OperationStatus.Running, OperationStatus.of(listOf(target(TargetStatus.Pending), target(TargetStatus.Applied))))
    }
}
