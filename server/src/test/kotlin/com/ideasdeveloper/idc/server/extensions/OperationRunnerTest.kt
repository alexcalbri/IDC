package com.ideasdeveloper.idc.server.extensions

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class OperationRunnerTest {
    private class MemoryStore : ExtensionOperationStore {
        val targets = LinkedHashMap<String, TargetRecord>()
        var finalStatus: String? = null

        override fun create(actor: String, operation: String, extensionId: String, sourceCompany: String?, targets: List<String>): Long {
            targets.forEach { this.targets[it] = TargetRecord(it, TargetStatus.Pending, null, 0) }
            return 1L
        }

        override fun failInterrupted(): Int {
            val pending = targets.values.filter { it.status == TargetStatus.Pending }
            pending.forEach { targets[it.companyCode] = it.copy(status = TargetStatus.Failed, reason = InterruptedReason) }
            return if (finalStatus == null) 1 else 0
        }

        override fun targets(operationId: Long): List<TargetRecord> = targets.values.toList()

        override fun updateTarget(operationId: Long, companyCode: String, status: String, reason: String?, attempts: Int) {
            targets[companyCode] = TargetRecord(companyCode, status, reason, attempts)
        }

        override fun finish(operationId: Long, status: String) {
            finalStatus = status
        }

        override fun header(operationId: Long) = OperationHeader(operationId, "actor", "apply", "meta", "acme", finalStatus ?: "running")
    }

    @Test
    fun everyTargetEndsAppliedFailedOrSkippedAndAFailureDoesNotStopTheOthers() {
        val store = MemoryStore()
        val executed = mutableListOf<String>()
        OperationRunner(store).start(
            "actor", "apply", "meta", "acme", listOf("one", "two", "three", "four"),
            validate = { if (it == "three") "Suspendida" else null },
            execute = {
                executed += it
                if (it == "two") throw IllegalStateException("boom")
            },
        )

        assertEquals(listOf("one", "two", "four"), executed)
        assertEquals(TargetStatus.Applied, store.targets.getValue("one").status)
        assertEquals(TargetStatus.Failed, store.targets.getValue("two").status)
        // The raw message of an unexpected error is never stored.
        assertEquals("Error inesperado al procesar esta empresa.", store.targets.getValue("two").reason)
        assertEquals(TargetStatus.Skipped, store.targets.getValue("three").status)
        assertEquals("Suspendida", store.targets.getValue("three").reason)
        assertEquals(TargetStatus.Applied, store.targets.getValue("four").status)
        assertEquals(1, store.targets.getValue("two").attempts)
        assertEquals(OperationStatus.Partial, store.finalStatus)
    }

    @Test
    fun retryRunsOnlyFailedTargetsInTheSameOperation() {
        val store = MemoryStore()
        val runner = OperationRunner(store)
        var twoShouldFail = true
        val executed = mutableListOf<String>()
        val execute: (String) -> Unit = {
            executed += it
            if (it == "two" && twoShouldFail) error("boom")
        }
        val validate: (String) -> String? = { if (it == "three") "Suspendida" else null }
        runner.start("actor", "apply", "meta", "acme", listOf("one", "two", "three"), validate, execute)
        executed.clear()
        twoShouldFail = false

        runner.retryFailed(1L, validate, execute)

        assertEquals(listOf("two"), executed)
        assertEquals(TargetStatus.Applied, store.targets.getValue("two").status)
        assertEquals(2, store.targets.getValue("two").attempts)
        assertEquals(1, store.targets.getValue("one").attempts)
        assertEquals(TargetStatus.Skipped, store.targets.getValue("three").status)
        assertEquals(OperationStatus.Done, store.finalStatus)
    }

    @Test
    fun retryWithoutFailedTargetsIsRefused() {
        val store = MemoryStore()
        val runner = OperationRunner(store)
        runner.start("actor", "apply", "meta", "acme", listOf("one"), { null }, { })

        assertEquals("nothing_to_retry", assertFailsWith<ExtensionException> { runner.retryFailed(1L, { null }, { }) }.code)
    }

    @Test
    fun aTargetThatFailsAgainStaysFailedWithMoreAttempts() {
        val store = MemoryStore()
        val runner = OperationRunner(store)
        runner.start("actor", "apply", "meta", "acme", listOf("one"), { null }, { error("down") })
        runner.retryFailed(1L, { null }, { error("still down") })

        assertEquals(TargetStatus.Failed, store.targets.getValue("one").status)
        assertEquals(2, store.targets.getValue("one").attempts)
        assertEquals("Error inesperado al procesar esta empresa.", store.targets.getValue("one").reason)
        assertEquals(OperationStatus.Failed, store.finalStatus)
    }

    @Test
    fun refusalsKeepTheirOwnMessageAndSqlErrorsStayGeneric() {
        val store = MemoryStore()
        OperationRunner(store).start(
            "actor", "apply", "meta", "acme", listOf("one", "two"), { null },
            { if (it == "one") throw ExtensionException("missing_required_settings", "Faltan ajustes obligatorios: app_id.") else throw java.sql.SQLException("duplicate key value (secret)", "23505") },
        )

        assertEquals("Faltan ajustes obligatorios: app_id.", store.targets.getValue("one").reason)
        assertEquals("Error de base de datos (23505).", store.targets.getValue("two").reason)
    }

    @Test
    fun interruptedPendingTargetsEndAsFailedAndCanBeRetried() {
        val store = MemoryStore()
        store.create("actor", "apply", "meta", "acme", listOf("one", "two"))
        store.updateTarget(1L, "one", TargetStatus.Applied, null, 1)

        store.failInterrupted()

        assertEquals(TargetStatus.Applied, store.targets.getValue("one").status)
        assertEquals(TargetStatus.Failed, store.targets.getValue("two").status)
        assertEquals(InterruptedReason, store.targets.getValue("two").reason)
        val retried = mutableListOf<String>()
        OperationRunner(store).retryFailed(1L, { null }, { retried += it })
        assertEquals(listOf("two"), retried)
    }
}
