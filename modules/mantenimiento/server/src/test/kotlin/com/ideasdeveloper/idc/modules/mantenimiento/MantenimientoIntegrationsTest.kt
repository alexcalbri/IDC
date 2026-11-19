package com.ideasdeveloper.idc.modules.mantenimiento

import com.ideasdeveloper.idc.modules.mantenimiento.MantenimientoFixtures.order
import com.ideasdeveloper.idc.ui.UiOpenModule
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.math.BigDecimal
import java.time.LocalDate
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Avisos, publicación en Tareas y solicitud de compra: las partes puras (sin base de datos ni módulos vecinos). */
class MantenimientoIntegrationsTest {
    private val me = UUID.randomUUID()
    private val other = UUID.randomUUID()

    // ---- avisos ----

    @Test
    fun assignmentNeverNotifiesTheAuthor() {
        assertEquals(other, MantenimientoNotify.assignedRecipient(other, me))
        assertNull(MantenimientoNotify.assignedRecipient(me, me))
        assertNull(MantenimientoNotify.assignedRecipient(null, me))
        assertEquals(other, MantenimientoNotify.assignedRecipient(other, null))
    }

    @Test
    fun managersAreNotifiedOnceAndWithoutTheAuthor() {
        assertEquals(listOf(other), MantenimientoNotify.managerRecipients(listOf(me, other, other), me))
        assertTrue(MantenimientoNotify.managerRecipients(emptyList(), me).isEmpty())
    }

    @Test
    fun requesterIsNotifiedUnlessHeMadeTheChange() {
        assertEquals(other, MantenimientoNotify.requesterRecipient(order(requestedBy = other), me))
        assertNull(MantenimientoNotify.requesterRecipient(order(requestedBy = me), me))
        assertNull(MantenimientoNotify.requesterRecipient(order(requestedBy = null), me))
    }

    @Test
    fun noticeTextAndTargetUseTheFolio() {
        val sample = order(number = 7, title = "Cambiar filtro")
        assertEquals("OT-0007 · Cambiar filtro", MantenimientoNotify.text(sample))
        val target = MantenimientoNotify.target(sample.id)
        assertEquals("order", target.screen)
        assertEquals(mapOf("id" to sample.id.toString()), target.params)
    }

    // ---- Tareas ----

    @Test
    fun taskStatusFollowsTheOrderStatus() {
        assertEquals("pending", MantenimientoTareasSync.taskStatus(OrderStatus.Open))
        assertEquals("pending", MantenimientoTareasSync.taskStatus(OrderStatus.OnHold))
        assertEquals("in_progress", MantenimientoTareasSync.taskStatus(OrderStatus.InProgress))
        assertEquals("done", MantenimientoTareasSync.taskStatus(OrderStatus.Done))
        assertEquals("cancelled", MantenimientoTareasSync.taskStatus(OrderStatus.Cancelled))
        assertEquals("cancelled", MantenimientoTareasSync.taskStatus(OrderStatus.Rejected))
    }

    @Test
    fun createArgumentsAreIdempotentByExternalRef() {
        val sample = order(number = 7, title = "Cambiar filtro", dueDate = LocalDate.of(2026, 12, 1)).copy(description = "Con cuidado", propertyName = "Sede", assetName = "Caldera")
        val board = UUID.randomUUID()
        val args = MantenimientoTareasSync.createArguments(sample, board, "ana@empresa.com")
        assertEquals(board.toString(), args["board_id"]?.jsonPrimitive?.content)
        assertEquals("OT-0007 · Cambiar filtro", args["title"]?.jsonPrimitive?.content)
        assertEquals("medium", args["priority"]?.jsonPrimitive?.content)
        assertEquals("2026-12-01", args["due_date"]?.jsonPrimitive?.content)
        assertEquals("mantenimiento:order:${sample.id}", args["external_ref"]?.jsonPrimitive?.content)
        assertEquals("ana@empresa.com", args["assignees"]?.jsonArray?.single()?.jsonPrimitive?.content)
        assertEquals("Sede · Caldera\nCon cuidado", args["detail"]?.jsonPrimitive?.content)
    }

    @Test
    fun createArgumentsOmitWhatTheOrderDoesNotHave() {
        val args = MantenimientoTareasSync.createArguments(order(), UUID.randomUUID(), null)
        assertFalse(args.containsKey("assignees"))
        assertFalse(args.containsKey("due_date"))
    }

    @Test
    fun updateArgumentsClearTheDateAndCarryTheStatus() {
        val taskId = UUID.randomUUID()
        val args = MantenimientoTareasSync.updateArguments(order(status = OrderStatus.Done), taskId, null)
        assertEquals(taskId.toString(), args["id"]?.jsonPrimitive?.content)
        assertEquals("", args["due_date"]?.jsonPrimitive?.content)
        assertEquals("done", args["status"]?.jsonPrimitive?.content)
        assertFalse(args.containsKey("assignees"))
    }

    @Test
    fun taskIdIsReadFromTheCreateResponse() {
        val id = UUID.randomUUID()
        val json = buildJsonObject { put("created", true); put("task", buildJsonObject { put("id", id.toString()) }) }
        assertEquals(id, MantenimientoTareasSync.taskIdOf(json))
        assertNull(MantenimientoTareasSync.taskIdOf(buildJsonObject { put("created", true) }))
        assertNull(MantenimientoTareasSync.taskIdOf(buildJsonObject { put("task", buildJsonObject { put("id", "no-es-uuid") }) }))
        assertNotNull(json.jsonObject["task"])
    }

    // ---- Compras ----

    @Test
    fun purchaseTitleAndAmountPreferTheQuote() {
        val sample = order(number = 7, title = "Compresor").copy(quoteAmount = BigDecimal("1250.50"), quoteCurrency = "EUR", costAmount = BigDecimal("99"), costCurrency = "USD")
        assertEquals("Mantenimiento OT-0007: Compresor", MantenimientoPurchases.requestTitle(sample))
        assertEquals("1250.50" to "EUR", MantenimientoPurchases.amountOf(sample))
        assertEquals("99" to "USD", MantenimientoPurchases.amountOf(sample.copy(quoteAmount = null)))
        assertNull(MantenimientoPurchases.amountOf(sample.copy(quoteAmount = null, costAmount = null)))
    }

    @Test
    fun purchaseRequestIsADraftWithMappedPriority() {
        val sample = order(title = "Compresor").copy(priority = OrderPriority.Urgent, propertyName = "Sede", assetName = "Caldera", description = "Se averió")
        val args = MantenimientoPurchases.requestArguments(sample)
        assertEquals("critical", args["priority"]?.jsonPrimitive?.content)
        assertEquals("false", args["submit"]?.jsonPrimitive?.content)
        assertEquals("Propiedad: Sede\nActivo: Caldera\nSe averió", args["description"]?.jsonPrimitive?.content)
        assertFalse(args.containsKey("estimated_amount"))
        assertEquals("high", MantenimientoPurchases.purchasePriority(OrderPriority.High))
        assertEquals("low", MantenimientoPurchases.purchasePriority(OrderPriority.Low))
    }

    @Test
    fun purchaseTitleIsClippedToTwoHundredCharacters() {
        assertEquals(200, MantenimientoPurchases.requestTitle(order(title = "t".repeat(300))).length)
    }

    @Test
    fun linkedRequestOpensComprasAndIdAndFolioAreParsed() {
        val requestId = UUID.randomUUID()
        val linked = order().copy(purchaseRequestId = requestId, purchaseFolio = "C-00012")
        assertEquals(UiOpenModule("compras", "request", mapOf("id" to requestId.toString())), MantenimientoPurchases.openRequestAction(linked))
        assertNull(MantenimientoPurchases.openRequestAction(order()))
        val json = buildJsonObject { put("id", requestId.toString()); put("folio", "C-00012"); put("status", "draft") }
        assertEquals(requestId to "C-00012", MantenimientoPurchases.idAndFolioOf(json))
        assertNull(MantenimientoPurchases.idAndFolioOf(buildJsonObject { put("folio", "C-1") }))
    }
}
