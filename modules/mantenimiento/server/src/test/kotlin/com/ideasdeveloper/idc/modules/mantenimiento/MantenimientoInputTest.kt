package com.ideasdeveloper.idc.modules.mantenimiento

import java.math.BigDecimal
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Validación de longitudes y rangos de las entradas (`OrderInput`, `PlanInput`, `AssetInput`) y del folio. */
class MantenimientoInputTest {
    private val order = OrderInput(title = "Cambiar filtro")
    private val plan = PlanInput(title = "Revisión", nextDue = LocalDate.of(2026, 12, 1))

    @Test
    fun validOrderHasNoMessages() {
        assertTrue(order.validate().isEmpty())
        assertTrue(order.copy(title = "x".repeat(OrderInput.MaxTitle), description = "d".repeat(OrderInput.MaxDescription)).validate().isEmpty())
    }

    @Test
    fun orderTitleIsRequiredAndLimited() {
        assertEquals(1, order.copy(title = "   ").validate().size)
        assertEquals(1, order.copy(title = "x".repeat(OrderInput.MaxTitle + 1)).validate().size)
    }

    @Test
    fun orderTextsAreLimited() {
        assertFalse(order.copy(description = "d".repeat(OrderInput.MaxDescription + 1)).validate().isEmpty())
        assertFalse(order.copy(department = "d".repeat(OrderInput.MaxDepartment + 1)).validate().isEmpty())
        assertFalse(order.copy(quotedBy = "q".repeat(OrderInput.MaxPerson + 1)).validate().isEmpty())
        assertFalse(order.copy(resolution = "r".repeat(OrderInput.MaxResolution + 1)).validate().isEmpty())
    }

    @Test
    fun orderCurrenciesNeedThreeCapitalLetters() {
        assertFalse(order.copy(quoteCurrency = "usd").validate().isEmpty())
        assertFalse(order.copy(costCurrency = "US").validate().isEmpty())
        assertTrue(order.copy(quoteCurrency = "EUR", costCurrency = "MXN").validate().isEmpty())
    }

    @Test
    fun orderAmountsMustBeNonNegativeWithTwoDecimalsAtMost() {
        assertTrue(order.copy(quoteAmount = BigDecimal("1250.50"), costAmount = BigDecimal.ZERO).validate().isEmpty())
        assertFalse(order.copy(quoteAmount = BigDecimal("-1")).validate().isEmpty())
        assertFalse(order.copy(costAmount = BigDecimal("1.234")).validate().isEmpty())
        assertFalse(order.copy(costAmount = BigDecimal("1000000000000")).validate().isEmpty())
        // Ceros a la derecha no cuentan como decimales.
        assertTrue(order.copy(costAmount = BigDecimal("10.500")).validate().isEmpty())
    }

    @Test
    fun orderDueDateMustBeInRange() {
        assertFalse(order.copy(dueDate = LocalDate.of(1999, 12, 31)).validate().isEmpty())
        assertFalse(order.copy(dueDate = LocalDate.of(2101, 1, 1)).validate().isEmpty())
        assertTrue(order.copy(dueDate = LocalDate.of(2026, 11, 30)).validate().isEmpty())
    }

    @Test
    fun validPlanHasNoMessages() {
        assertTrue(plan.validate().isEmpty())
        assertTrue(plan.copy(frequency = PlanFrequency.EveryNDays, intervalDays = 15, checklist = listOf("Apagar", "Limpiar")).validate().isEmpty())
    }

    @Test
    fun planNeedsAnIntervalOnlyForEveryNDays() {
        assertFalse(plan.copy(frequency = PlanFrequency.EveryNDays, intervalDays = null).validate().isEmpty())
        assertFalse(plan.copy(frequency = PlanFrequency.EveryNDays, intervalDays = 0).validate().isEmpty())
        assertFalse(plan.copy(frequency = PlanFrequency.EveryNDays, intervalDays = PlanInput.MaxIntervalDays + 1).validate().isEmpty())
        assertFalse(plan.copy(frequency = PlanFrequency.Monthly, intervalDays = 10).validate().isEmpty())
    }

    @Test
    fun planLeadDaysAndDateRules() {
        assertFalse(plan.copy(leadDays = -1).validate().isEmpty())
        assertFalse(plan.copy(leadDays = PlanInput.MaxLeadDays + 1).validate().isEmpty())
        assertTrue(plan.copy(leadDays = 0).validate().isEmpty())
        assertFalse(plan.copy(nextDue = null).validate().isEmpty())
        // Un plan "desde el último cierre" que espera su fecha puede editarse sin ella.
        assertTrue(plan.copy(nextDue = null).validate(requireNextDue = false).isEmpty())
    }

    @Test
    fun planChecklistIsLimited() {
        assertFalse(plan.copy(checklist = List(PlanInput.MaxChecklistItems + 1) { "paso $it" }).validate().isEmpty())
        assertFalse(plan.copy(checklist = listOf("ok", "  ")).validate().isEmpty())
        assertFalse(plan.copy(checklist = listOf("x".repeat(PlanInput.MaxChecklistText + 1))).validate().isEmpty())
        assertFalse(plan.copy(title = "t".repeat(PlanInput.MaxTitle + 1)).validate().isEmpty())
        assertFalse(plan.copy(description = "d".repeat(PlanInput.MaxDescription + 1)).validate().isEmpty())
    }

    @Test
    fun assetFieldsAreLimited() {
        val asset = AssetInput(name = "Caldera")
        assertTrue(asset.validate().isEmpty())
        assertFalse(asset.copy(name = " ").validate().isEmpty())
        assertFalse(asset.copy(name = "n".repeat(AssetInput.MaxName + 1)).validate().isEmpty())
        assertFalse(asset.copy(category = "c".repeat(AssetInput.MaxCategory + 1)).validate().isEmpty())
        assertFalse(asset.copy(location = "l".repeat(AssetInput.MaxLocation + 1)).validate().isEmpty())
        assertFalse(asset.copy(notes = "n".repeat(AssetInput.MaxNotes + 1)).validate().isEmpty())
    }

    @Test
    fun folioIsFormattedAndParsed() {
        assertEquals("OT-0007", MantenimientoFolio.format(7))
        assertEquals("OT-12345", MantenimientoFolio.format(12345))
        assertEquals(7L, MantenimientoFolio.parse("OT-0007"))
        assertEquals(7L, MantenimientoFolio.parse("ot-7"))
        assertEquals(12L, MantenimientoFolio.parse(" 12 "))
        assertEquals(null, MantenimientoFolio.parse("OT-"))
        assertEquals(null, MantenimientoFolio.parse("abc"))
        assertEquals(null, MantenimientoFolio.parse("0"))
        assertEquals("OT-0007", MantenimientoFixtures.order(number = 7).displayNumber)
    }

    @Test
    fun enumsAcceptIdsAndSpanishLabels() {
        assertEquals(OrderStatus.OnHold, OrderStatus.fromLabel("en espera"))
        assertEquals(OrderStatus.Requested, OrderStatus.fromLabel("Pendiente de aprobación"))
        assertEquals(OrderStatus.Done, OrderStatus.fromId("DONE"))
        assertEquals(PlanFrequency.EveryNDays, PlanFrequency.fromId("every_n_days"))
        assertEquals(ScheduleMode.Floating, ScheduleMode.fromLabel("floating"))
        assertEquals(OrderPriority.Urgent, OrderPriority.fromLabel("Urgente"))
        assertEquals(OrderKind.Project, OrderKind.fromLabel("proyecto"))
        assertEquals(null, OrderKind.fromId("otro"))
        assertTrue(OrderStatus.Open.isActive && OrderStatus.Open.isWorking)
        assertFalse(OrderStatus.Requested.isWorking)
        assertFalse(OrderStatus.Rejected.isActive)
    }

    @Test
    fun overdueOnlyWhileTheOrderIsStillActive() {
        val today = LocalDate.of(2026, 11, 18)
        assertTrue(MantenimientoFixtures.order(dueDate = today.minusDays(1)).isOverdue(today))
        assertFalse(MantenimientoFixtures.order(dueDate = today).isOverdue(today))
        assertFalse(MantenimientoFixtures.order(status = OrderStatus.Done, dueDate = today.minusDays(5)).isOverdue(today))
        assertFalse(MantenimientoFixtures.order(dueDate = null).isOverdue(today))
    }
}
