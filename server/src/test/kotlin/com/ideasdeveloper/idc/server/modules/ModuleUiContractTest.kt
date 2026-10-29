package com.ideasdeveloper.idc.server.modules

import com.ideasdeveloper.idc.ui.UiBadge
import com.ideasdeveloper.idc.ui.UiBoard
import com.ideasdeveloper.idc.ui.UiBoardCard
import com.ideasdeveloper.idc.ui.UiBoardColumn
import com.ideasdeveloper.idc.ui.UiButton
import com.ideasdeveloper.idc.ui.UiCard
import com.ideasdeveloper.idc.ui.UiJson
import com.ideasdeveloper.idc.ui.UiList
import com.ideasdeveloper.idc.ui.UiListItem
import com.ideasdeveloper.idc.ui.UiMetric
import com.ideasdeveloper.idc.ui.UiNavigate
import com.ideasdeveloper.idc.ui.UiRequest
import com.ideasdeveloper.idc.ui.UiScreen
import com.ideasdeveloper.idc.ui.UiTimeline
import com.ideasdeveloper.idc.ui.UiTimelineEntry
import com.ideasdeveloper.idc.ui.UiTone
import com.ideasdeveloper.idc.ui.UiUnknownAction
import com.ideasdeveloper.idc.ui.UiUnknownNode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ModuleUiContractTest {
    @Test
    fun screensRoundTripWithTypeDiscriminators() {
        val screen = UiScreen(
            title = "Panel",
            body = listOf(UiCard(listOf(UiMetric("Hoy", "3")), actions = listOf(UiButton("Ver", UiNavigate("visitors"))))),
        )
        val json = UiJson.encodeToString(UiScreen.serializer(), screen)
        assertTrue(json.contains("\"type\":\"card\""))
        assertTrue(json.contains("\"type\":\"metric\""))
        assertTrue(json.contains("\"type\":\"navigate\""))
        assertEquals(screen, UiJson.decodeFromString(UiScreen.serializer(), json))
    }

    @Test
    fun boardListAndTimelineRoundTrip() {
        val open = UiNavigate("lead", mapOf("id" to "1"))
        val screen = UiScreen(
            title = "Pipeline",
            body = listOf(
                UiBoard(
                    columns = listOf(
                        UiBoardColumn("new", "Nuevo", UiTone.Info, cards = listOf(UiBoardCard("1", "Ana", badges = listOf(UiBadge("Surf")), action = open))),
                        UiBoardColumn("won", "Ganado", UiTone.Success, footer = UiButton("Ver todos", UiNavigate("leads"))),
                    ),
                    move = UiRequest("/modules/crm/ui/pipeline", params = mapOf("op" to "move")),
                ),
                UiList(listOf(UiListItem("Ana", "Surf", "2026-10-28", action = open))),
                UiTimeline(listOf(UiTimelineEntry("Etapa cambiada", "Nuevo -> Ganado", "ana", UiTone.Success))),
            ),
        )
        val json = UiJson.encodeToString(UiScreen.serializer(), screen)
        assertTrue(json.contains("\"type\":\"board\""))
        assertTrue(json.contains("\"type\":\"list\""))
        assertTrue(json.contains("\"type\":\"timeline\""))
        assertEquals(screen, UiJson.decodeFromString(UiScreen.serializer(), json))
    }

    @Test
    fun unknownPiecesAndActionsDoNotBreakOlderClients() {
        val json = """
            {"title":"Nuevo","futureField":1,"body":[
              {"type":"kanban","columns":[]},
              {"type":"button","label":"X","action":{"type":"share","target":"a"}},
              {"type":"text","text":"ok"}
            ]}
        """.trimIndent()
        val screen = UiJson.decodeFromString(UiScreen.serializer(), json)
        assertIs<UiUnknownNode>(screen.body[0])
        assertIs<UiUnknownAction>((screen.body[1] as UiButton).action)
        assertEquals(3, screen.body.size)
    }

    @Test
    fun webTicketsAreSingleUseAndModuleScoped() {
        val ticket = ModuleWebTickets.issue("crm", "acme", "token")
        assertNull(ModuleWebTickets.consume(ticket, "hotspot"))
        assertNull(ModuleWebTickets.consume(ticket, "crm"))

        val second = ModuleWebTickets.issue("crm", "acme", "token")
        assertNotNull(ModuleWebTickets.consume(second, "crm"))
        assertNull(ModuleWebTickets.consume(second, "crm"))
    }
}
