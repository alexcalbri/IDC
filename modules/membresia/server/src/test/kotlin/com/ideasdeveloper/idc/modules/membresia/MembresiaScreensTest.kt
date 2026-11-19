package com.ideasdeveloper.idc.modules.membresia

import com.ideasdeveloper.idc.ui.UiBarChart
import com.ideasdeveloper.idc.ui.UiJson
import com.ideasdeveloper.idc.ui.UiNode
import com.ideasdeveloper.idc.ui.UiNotice
import com.ideasdeveloper.idc.ui.UiScreen
import com.ideasdeveloper.idc.ui.UiTone
import kotlinx.serialization.builtins.ListSerializer
import java.math.BigDecimal
import java.time.LocalDate
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MembresiaScreensTest {
    private val today = LocalDate.of(2026, 11, 18)
    private val viewOnly = MembresiaAccess(view = true, visits = false, manage = false, plans = false)
    private val everything = MembresiaAccess(view = true, visits = true, manage = true, plans = true)
    private val customerId = UUID.randomUUID()

    private fun membership(status: String = StoredStatus.Activa, includedVisits: Int? = null, visitsUsed: Int = 0) = Membership(
        id = UUID.randomUUID(), customerId = customerId, customerName = "Ana Pérez", planId = UUID.randomUUID(), planName = "Mensual",
        startsOn = today.minusDays(10), endsOn = today.plusDays(20), amount = BigDecimal("50.00"), currency = "USD", status = status,
        source = "", hostUserId = null, hostName = "", unit = "A-1", notes = "", includedVisits = includedVisits, visitsUsed = visitsUsed,
        guestPassesTotal = 2, guestPassesUsed = 0, renewedFrom = null, cancelledAt = null, cancelReason = "", expiringNotifiedOn = null, createdBy = "",
    )

    private fun detail(current: Membership? = membership()) = MemberDetail(
        MemberSummary(customerId, "Ana Pérez", "ana@example.com", "555-0100", current),
        listOfNotNull(current),
        emptyList(),
    )

    private fun bodyJson(screen: UiScreen): String = UiJson.encodeToString(ListSerializer(UiNode.serializer()), screen.body)

    private fun json(screen: UiScreen): String = UiJson.encodeToString(UiScreen.serializer(), screen)

    @Test
    fun navigationShowsOnlyAllowedSections() {
        val onlyView = MembresiaScreens.navigation(viewOnly, "recepcion").items.map { it.id }
        assertEquals(listOf("recepcion", "miembros", "niveles"), onlyView)
        val all = MembresiaScreens.navigation(everything, "recepcion").items.map { it.id }
        assertEquals(listOf("recepcion", "miembros", "niveles", "nueva", "planes", "ingresos", "ajustes"), all)
        val plansOnly = MembresiaScreens.navigation(MembresiaAccess(view = false, visits = false, manage = false, plans = true), "planes").items.map { it.id }
        assertEquals(listOf("planes", "ajustes"), plansOnly)
    }

    @Test
    fun screensAreNotServedWithoutThePermission() {
        assertFalse(MembresiaScreens.allowed(viewOnly, "nueva"))
        assertFalse(MembresiaScreens.allowed(viewOnly, "ingresos"))
        assertFalse(MembresiaScreens.allowed(viewOnly, "planes"))
        assertFalse(MembresiaScreens.allowed(viewOnly, "renovar"))
        assertTrue(MembresiaScreens.allowed(viewOnly, "miembro"))
        assertTrue(MembresiaScreens.allowed(everything, "ajustes"))
        assertFalse(MembresiaScreens.isScreen("desconocida"))
        assertNull(MembresiaScreens.home(MembresiaAccess(view = false, visits = false, manage = false, plans = false)))
    }

    @Test
    fun memberScreenHasNoChangeButtonsForViewOnly() {
        val text = bodyJson(MembresiaScreens.miembro(viewOnly, detail(), today, 7))
        listOf("Registrar visita", "Registrar invitado", "Renovar", "Editar", "Cancelar membresía", "Nueva membresía").forEach {
            assertFalse(text.contains(it), "No debe mostrar: $it")
        }
        assertTrue(text.contains("Abrir ficha del cliente"))
        assertTrue(text.contains("\"clientes\""))
    }

    @Test
    fun memberScreenOffersActionsAccordingToPermissions() {
        val text = bodyJson(MembresiaScreens.miembro(everything, detail(), today, 7))
        listOf("Registrar visita", "Registrar invitado", "Renovar", "Editar", "Cancelar membresía").forEach {
            assertTrue(text.contains(it), "Debe mostrar: $it")
        }
        assertFalse(text.contains("Registrar igual"))
    }

    @Test
    fun overrideButtonAppearsOnlyWhenTheCheckInNeedsIt() {
        val reason = "Ya usó todas las visitas incluidas en su plan."
        val screen = MembresiaScreens.miembro(everything, detail(membership(includedVisits = 4, visitsUsed = 4)), today, 7, overrideReason = reason)
        val text = bodyJson(screen)
        assertTrue(text.contains("Registrar igual"))
        assertTrue(text.contains("\"override\":\"true\""))
        assertEquals(UiNotice(reason, UiTone.Warning), screen.notice)
        val withoutPermission = bodyJson(MembresiaScreens.miembro(viewOnly, detail(), today, 7, overrideReason = reason))
        assertFalse(withoutPermission.contains("Registrar igual"))
    }

    @Test
    fun memberWithoutMembershipOffersANewOne() {
        val text = bodyJson(MembresiaScreens.miembro(everything, detail(current = null), today, 7))
        assertTrue(text.contains("Sin membresía"))
        assertTrue(text.contains("Nueva membresía"))
        assertFalse(text.contains("Registrar visita"))
    }

    @Test
    fun noScreenShowsTheCustomerCode() {
        val plan = Plan(UUID.randomUUID(), "Mensual", "", BigDecimal("50.00"), 30, null, 2, true, true, 0)
        val member = MemberSummary(customerId, "Ana Pérez", "ana@example.com", "555-0100", membership())
        val settings = MembresiaSettings.Default
        val months = MembresiaRules.revenueByMonth(emptyList(), today, 12, "USD")
        val screens = listOf(
            MembresiaScreens.recepcion(everything, "ana", listOf(member), mapOf(DisplayStatus.ACTIVA to 3), 5, today, 7),
            MembresiaScreens.miembro(everything, detail(), today, 7, overrideReason = "Aviso"),
            MembresiaScreens.miembros(everything, DisplayStatus.ACTIVA, listOf(membership()), mapOf(DisplayStatus.ACTIVA to 1), today, 7),
            MembresiaScreens.niveles(everything, mapOf("Mensual" to listOf(membership())), today, 7),
            MembresiaScreens.visitas(everything, today, emptyList()),
            MembresiaScreens.nueva(everything, listOf(plan), settings, emptyList(), MembresiaScreens.nuevaValues(today, member)),
            MembresiaScreens.renovar(everything, membership(), listOf(plan), settings, today),
            MembresiaScreens.editar(everything, membership(), settings, emptyList()),
            MembresiaScreens.planes(everything, listOf(plan), "USD", plan, MembresiaScreens.planValues(plan)),
            MembresiaScreens.ingresos(everything, months, emptyList(), "USD"),
            MembresiaScreens.ajustes(everything, MembresiaScreens.ajustesValues(settings)),
        )
        screens.forEach { screen ->
            val text = json(screen).lowercase()
            assertFalse(text.contains("customer_code"), "customer_code en ${screen.title}")
            assertFalse(text.contains("flexible_attributes"), "flexible_attributes en ${screen.title}")
        }
    }

    @Test
    fun suggestionsCarryTheCustomerValues() {
        val member = MemberSummary(customerId, "Ana Pérez", "ana@example.com", "555-0100", membership())
        val withoutMembership = MemberSummary(UUID.randomUUID(), "Luis", "", "", null)
        val suggestions = MembresiaScreens.suggestions(listOf(member, withoutMembership), today, 7)
        assertEquals(2, suggestions.items.size)
        val first = suggestions.items[0]
        assertEquals("Ana Pérez", first.title)
        assertEquals(mapOf("customerId" to customerId.toString(), "name" to "Ana Pérez", "email" to "ana@example.com", "phone" to "555-0100"), first.values)
        assertTrue(first.subtitle.contains("Activa"))
        assertTrue(suggestions.items[1].subtitle.contains("Sin membresía"))
    }

    @Test
    fun revenueScreenHasTwelveMonthPoints() {
        val months = MembresiaRules.revenueByMonth(
            listOf(RevenueRow(today, "Mensual", "USD", BigDecimal("50.00"), status = StoredStatus.Activa)),
            today, 12, "USD",
        )
        val screen = MembresiaScreens.ingresos(everything, months, MembresiaRules.revenueByPlan(emptyList(), today, 12, "USD"), "USD")
        val chart = screen.body.filterIsInstance<UiBarChart>().single()
        assertEquals(12, chart.points.size)
        assertEquals(50.0, chart.points.last().value)
        assertTrue(bodyJson(screen).contains("50.00 USD"))
    }

    @Test
    fun receptionSearchUsesTheMemberSuggestionRoute() {
        val text = bodyJson(MembresiaScreens.recepcion(viewOnly, "", emptyList(), emptyMap(), 0, today, 7))
        assertTrue(text.contains("${MembresiaModule.UiRoute}/suggest/members"))
        assertTrue(text.contains("\"minLength\":2"))
    }
}
