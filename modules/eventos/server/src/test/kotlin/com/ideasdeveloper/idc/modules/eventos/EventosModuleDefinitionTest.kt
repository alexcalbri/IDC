package com.ideasdeveloper.idc.modules.eventos

import com.ideasdeveloper.idc.server.modules.McpToolRules
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The module definition, its agent tools and the notices: declarations and pure helpers (no database). */
class EventosModuleDefinitionTest {
    private val definition = EventosServerModule.definition
    private val tools = EventosMcpTools.mcp.tools

    private fun tool(name: String) = tools.first { it.name == name }

    private fun propertyNames(schema: JsonObject): Set<String> = schema["properties"]?.jsonObject?.keys.orEmpty()

    // ---- definition ----

    @Test
    fun declaresTheFourPermissions() {
        assertEquals(
            listOf("eventos.view", "eventos.register", "eventos.manage", "eventos.import"),
            definition.permissions.map { it.id },
        )
        definition.permissions.forEach { assertTrue(it.title.isNotBlank() && it.description.isNotBlank(), it.id) }
    }

    @Test
    fun identityAndVersion() {
        assertEquals("eventos", definition.id)
        assertEquals("0.1.0", definition.version)
        assertEquals("eventos", EventosModule.id)
        assertTrue(definition.supportsExtensions)
        assertEquals(listOf("eventos.import.write"), definition.extensionPermissions)
    }

    @Test
    fun usesNativeScreensStartingAtUpcoming() {
        val ui = definition.ui
        assertEquals("native", ui?.type)
        assertEquals("/modules/eventos/ui", ui?.entry)
        assertEquals("proximos", ui?.home)
    }

    @Test
    fun declaresMigrationTablesJobsAndSettings() {
        assertEquals(listOf("modules/eventos/migrations/V001__create_eventos.sql"), EventosServerModule.migrationPaths)
        assertEquals(
            setOf("eventos_settings", "eventos_events", "eventos_registrations", "eventos_payments"),
            EventosServerModule.runtimeTables.toSet(),
        )
        assertEquals(listOf("cierre", "import-runner"), EventosServerModule.jobs.map { it.id })
        assertEquals(1, EventosServerModule.duplicatedSettings.size)
    }

    @Test
    fun providerCreatesTheModule() {
        assertEquals(EventosServerModule, EventosServerModuleProvider().create())
    }

    // ---- MCP ----

    @Test
    fun everyToolPassesTheRegistryRules() {
        tools.forEach { assertNull(McpToolRules.problem(definition, it), "tool ${it.name}") }
    }

    @Test
    fun toolNamesAreUniqueAndQualifiedNamesFit() {
        assertEquals(tools.size, tools.map { it.name }.toSet().size)
        tools.forEach { assertTrue("${definition.id}_${it.name}".length <= McpToolRules.MaxQualifiedNameLength) }
    }

    @Test
    fun expectedToolsExist() {
        val expected = setOf(
            "list_events", "get_event", "get_capacity", "list_registrations",
            "register_customer", "cancel_registration", "record_attendance", "set_registration_paid",
        )
        assertEquals(expected, tools.map { it.name }.toSet())
    }

    @Test
    fun readingToolsAreReadOnlyAndWritingToolsAreNot() {
        listOf("list_events", "get_event", "get_capacity", "list_registrations").forEach { assertTrue(tool(it).readOnly, it) }
        listOf("register_customer", "cancel_registration", "record_attendance", "set_registration_paid").forEach { assertFalse(tool(it).readOnly, it) }
        assertTrue(tools.none { it.destructive })
    }

    @Test
    fun permissionsMatchTheDesign() {
        listOf("list_events", "get_event", "get_capacity", "list_registrations").forEach { assertEquals(EventosModule.View, tool(it).permission, it) }
        listOf("register_customer", "cancel_registration", "record_attendance", "set_registration_paid").forEach { assertEquals(EventosModule.Register, tool(it).permission, it) }
        assertTrue(tools.none { it.permission == EventosModule.Manage })
    }

    @Test
    fun noArgumentIsACredential() {
        val forbidden = listOf("password", "token", "secret", "key", "credential")
        tools.forEach { tool ->
            propertyNames(tool.inputSchema).forEach { name ->
                forbidden.forEach { assertFalse(name.contains(it, ignoreCase = true), "${tool.name}.$name") }
            }
        }
    }

    @Test
    fun requiredArgumentsAreDeclaredProperties() {
        tools.forEach { tool ->
            val required = tool.inputSchema["required"]?.let { element -> (element as kotlinx.serialization.json.JsonArray).map { (it as JsonPrimitive).content } }.orEmpty()
            required.forEach { assertTrue(it in propertyNames(tool.inputSchema), "${tool.name}.$it") }
        }
    }

    @Test
    fun instructionsAreInSpanishAndMentionTheWaitingList() {
        assertTrue(EventosMcpTools.Instructions.contains("lista de espera"))
        assertTrue(EventosMcpTools.Instructions.contains("cupo"))
    }

    @Test
    fun registrationJsonNeverCarriesContactData() {
        val registration = Registration(
            id = UUID.randomUUID(), eventId = UUID.randomUUID(), customerId = UUID.randomUUID(), customerName = "Ana",
            status = RegistrationStatus.Registered, seats = 2, priceKind = PriceKind.Member, amount = BigDecimal("30.00"),
            paid = true, paidAt = null, paymentNote = "", attendedAt = Instant.EPOCH, attendedBy = "", waitlistedAt = null,
            note = "", cancelledAt = null, createdBy = "", createdAt = Instant.EPOCH, eventTitle = "Taller",
            eventStartsAt = Instant.EPOCH, eventCurrency = "USD",
        )
        val json = EventosMcpTools.registrationJson(registration)
        assertEquals(setOf("registration_id", "event_id", "customer_id", "customer", "status", "seats", "price_kind", "amount", "currency", "paid", "attended"), json.keys)
        assertEquals("inscrito", (json["status"] as JsonPrimitive).content)
        assertEquals("true", (json["attended"] as JsonPrimitive).content)
    }

    // ---- notices ----

    @Test
    fun noticesNeverGoToTheAuthor() {
        val author = UUID.randomUUID()
        val other = UUID.randomUUID()
        assertEquals(listOf(other), EventosNotify.recipients(null, false, listOf(author, other, other), author))
        assertEquals(emptyList<UUID>(), EventosNotify.recipients(author, true, listOf(other), author))
    }

    @Test
    fun responsibleIsToldInsteadOfTheManagersWhenActive() {
        val responsible = UUID.randomUUID()
        val manager = UUID.randomUUID()
        assertEquals(listOf(responsible), EventosNotify.recipients(responsible, true, listOf(manager), null))
        // An inactive or missing responsible: the managers are told.
        assertEquals(listOf(manager), EventosNotify.recipients(responsible, false, listOf(manager), null))
        assertEquals(listOf(manager), EventosNotify.recipients(null, false, listOf(manager), null))
    }

    @Test
    fun noticeTexts() {
        assertEquals("Ana · Taller · 2 plazas", EventosNotify.newRegistrationText("Ana", "Taller", 2))
        assertEquals("Taller · 20 plazas", EventosNotify.fullText("Taller", 20))
        assertEquals("Taller: 3 en espera", EventosNotify.seatFreedText("Taller", 3))
        assertEquals("Nueva inscripción", EventosNotify.NewRegistrationTitle)
        assertEquals("Evento lleno", EventosNotify.FullTitle)
        assertEquals("Se liberó una plaza", EventosNotify.SeatFreedTitle)
    }
}
