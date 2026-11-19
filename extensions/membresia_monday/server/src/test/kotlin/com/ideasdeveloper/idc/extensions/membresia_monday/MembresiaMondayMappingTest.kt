package com.ideasdeveloper.idc.extensions.membresia_monday

import com.ideasdeveloper.idc.modules.membresia.ImportedMember
import com.ideasdeveloper.idc.modules.membresia.ImportedVisit
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import java.math.BigDecimal
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MembresiaMondayMappingTest {
    private fun column(id: String, title: String, type: String) = MondayColumn(id, title, type)

    // Columnas del tablero real de Esc (en ingles).
    private val englishBoard = listOf(
        column("name", "Name", "name"),
        column("type", "Membership Type", "status"),
        column("period", "Membership Period", "timeline"),
        column("amount", "Amount USD", "numbers"),
        column("guests", "Guest Passes", "numbers"),
        column("source", "Source", "dropdown"),
        column("email", "Email", "email"),
        column("phone", "Phone", "phone"),
        column("country", "Country", "text"),
        column("host", "Host", "people"),
        column("notes", "Notes", "long_text"),
        column("room", "Room/Unit", "text"),
        column("passport", "Passport/Document", "text"),
        column("birth", "Date of Birth", "date"),
        column("emergency", "Emergency Contact", "text"),
        column("subitems", "Subitems", "subitems"),
    )

    @Test
    fun suggestsTheMappingForTheEnglishBoardOfEsc() {
        val mapping = MembresiaMondayMapping.suggest(englishBoard).columns
        assertEquals(
            mapOf(
                MondayTarget.Plan to "type", MondayTarget.Period to "period", MondayTarget.Amount to "amount",
                MondayTarget.GuestPasses to "guests", MondayTarget.Source to "source", MondayTarget.Email to "email",
                MondayTarget.Phone to "phone", MondayTarget.Country to "country", MondayTarget.Host to "host",
                MondayTarget.Notes to "notes", MondayTarget.Room to "room", MondayTarget.Document to "passport",
                MondayTarget.Birth to "birth", MondayTarget.Emergency to "emergency",
            ),
            mapping,
        )
    }

    @Test
    fun suggestsTheMappingForASpanishBoardWithAccents() {
        val spanish = listOf(
            column("tipo", "Tipo de membresía", "status"),
            column("periodo", "Período", "timeline"),
            column("monto", "Monto", "numbers"),
            column("pases", "Pases de invitado", "numbers"),
            column("fuente", "Fuente", "dropdown"),
            column("correo", "Correo", "email"),
            column("tel", "Teléfono", "phone"),
            column("pais", "País", "text"),
            column("anfitrion", "Anfitrión", "people"),
            column("notas", "Notas", "long_text"),
            column("hab", "Habitación", "text"),
            column("doc", "Pasaporte", "text"),
            column("nac", "Fecha de nacimiento", "date"),
            column("emerg", "Contacto de emergencia", "text"),
        )
        val mapping = MembresiaMondayMapping.suggest(spanish).columns
        assertEquals("tipo", mapping[MondayTarget.Plan])
        assertEquals("periodo", mapping[MondayTarget.Period])
        assertEquals("monto", mapping[MondayTarget.Amount])
        assertEquals("pases", mapping[MondayTarget.GuestPasses])
        assertEquals("correo", mapping[MondayTarget.Email])
        assertEquals("tel", mapping[MondayTarget.Phone])
        assertEquals("anfitrion", mapping[MondayTarget.Host])
        assertEquals("hab", mapping[MondayTarget.Room])
        assertEquals("nac", mapping[MondayTarget.Birth])
        assertEquals("emerg", mapping[MondayTarget.Emergency])
        // Cada columna sirve para un solo dato.
        assertEquals(mapping.size, mapping.values.toSet().size)
    }

    @Test
    fun separateStartAndEndDatesAreSuggestedOnlyWithoutAPeriod() {
        val columns = listOf(column("s", "Start date", "date"), column("e", "End date", "date"), column("t", "Plan", "status"))
        val mapping = MembresiaMondayMapping.suggest(columns).columns
        assertEquals("s", mapping[MondayTarget.Start])
        assertEquals("e", mapping[MondayTarget.End])
        assertNull(mapping[MondayTarget.Period])
        assertNull(MembresiaMondayFlow.mappingProblem(MondayMapping(mapping), columns))
        // Sin plan o sin fechas no se puede importar.
        assertNotNull(MembresiaMondayFlow.mappingProblem(MondayMapping(mapOf(MondayTarget.Plan to "t")), columns))
        assertNotNull(MembresiaMondayFlow.mappingProblem(MondayMapping(mapOf(MondayTarget.Period to "p")), columns))
    }

    private fun cell(id: String, title: String, type: String, text: String, value: String = "") = MondayCell(id, title, type, text, value)

    private val fullMapping = MembresiaMondayMapping.suggest(englishBoard)

    private fun item(group: String = "Active", subitems: List<MondaySubitem> = emptyList()) = MondayItem(
        id = "9001",
        name = "  Ana Perez ",
        group = group,
        cells = listOf(
            cell("type", "Membership Type", "status", "Hot Desk"),
            cell("period", "Membership Period", "timeline", "2025-01-01 - 2025-01-31", """{"from":"2025-01-01","to":"2025-01-31","changed_at":"2025-01-02T00:00:00Z"}"""),
            cell("amount", "Amount USD", "numbers", "1,200.50"),
            cell("guests", "Guest Passes", "numbers", "3"),
            cell("source", "Source", "dropdown", "Instagram"),
            cell("email", "Email", "email", "ANA@Example.com"),
            cell("phone", "Phone", "phone", "+507 6000 0000"),
            cell("country", "Country", "text", "Panama"),
            cell("host", "Host", "people", "Luis, Marta"),
            cell("notes", "Notes", "long_text", "Prefiere la ventana"),
            cell("room", "Room/Unit", "text", "A-12"),
            cell("passport", "Passport/Document", "text", "PA123"),
            cell("birth", "Date of Birth", "date", "1990-05-17", """{"date":"1990-05-17","time":null}"""),
            cell("emergency", "Emergency Contact", "text", "Rosa 555-1234"),
        ),
        subitems = subitems,
    )

    @Test
    fun convertsAnItemWithTimelineDatesNumbersAndPeople() {
        val member = MembresiaMondayMapping.toMember(item(), fullMapping)
        assertEquals("monday:9001", member.key)
        assertEquals("Ana Perez", member.name)
        assertEquals("ana@example.com", member.email)
        assertEquals("+507 6000 0000", member.phone)
        assertEquals("Hot Desk", member.planName)
        assertEquals(LocalDate.of(2025, 1, 1), member.startsOn)
        assertEquals(LocalDate.of(2025, 1, 31), member.endsOn)
        assertEquals(BigDecimal("1200.50"), member.amount)
        assertEquals(3, member.guestPasses)
        assertEquals("Instagram", member.source)
        assertEquals("Luis, Marta", member.hostName)
        assertEquals("A-12", member.unit)
        assertEquals("Prefiere la ventana", member.notes)
        assertEquals(
            mapOf(
                MondayTarget.CustomerCountry to "Panama", MondayTarget.CustomerDocument to "PA123",
                MondayTarget.CustomerBirth to "1990-05-17", MondayTarget.CustomerEmergency to "Rosa 555-1234",
            ),
            member.customerFields,
        )
        assertEquals(false, member.cancelled)
    }

    @Test
    fun readsTimelineAndDatesFromTextWhenTheRawValueIsMissing() {
        assertEquals(LocalDate.of(2025, 2, 1) to LocalDate.of(2025, 3, 1), MembresiaMondayMapping.periodOf(cell("p", "P", "timeline", "2025-02-01 - 2025-03-01")))
        assertEquals(null to null, MembresiaMondayMapping.periodOf(cell("p", "P", "timeline", "")))
        assertEquals(LocalDate.of(2025, 4, 9), MembresiaMondayMapping.dateOf(cell("d", "D", "date", "2025-04-09")))
        assertEquals(LocalDate.of(2025, 4, 9), MembresiaMondayMapping.dateOf(cell("d", "D", "date", "", """{"date":"2025-04-09"}""")))
        assertNull(MembresiaMondayMapping.dateOf(cell("d", "D", "date", "")))
    }

    @Test
    fun separateStartAndEndColumnsFillTheDatesWhenThereIsNoPeriod() {
        val mapping = MondayMapping(mapOf(MondayTarget.Plan to "t", MondayTarget.Start to "s", MondayTarget.End to "e"))
        val member = MembresiaMondayMapping.toMember(
            MondayItem(
                "1", "Bea", "Active",
                listOf(cell("t", "Plan", "status", "Flex"), cell("s", "Start", "date", "2025-06-01"), cell("e", "End", "date", "2025-06-30")),
            ),
            mapping,
        )
        assertEquals(LocalDate.of(2025, 6, 1), member.startsOn)
        assertEquals(LocalDate.of(2025, 6, 30), member.endsOn)
        assertNull(member.amount)
        assertNull(member.guestPasses)
    }

    @Test
    fun parsesAmountsInSeveralFormats() {
        assertEquals(BigDecimal("1234.50"), MembresiaMondayMapping.parseAmount("$1,234.50"))
        assertEquals(BigDecimal("1234.50"), MembresiaMondayMapping.parseAmount("1.234,50"))
        assertEquals(BigDecimal("12.5"), MembresiaMondayMapping.parseAmount("12,5"))
        assertEquals(BigDecimal("1200"), MembresiaMondayMapping.parseAmount("1,200"))
        assertEquals(BigDecimal("300"), MembresiaMondayMapping.parseAmount("USD 300"))
        assertNull(MembresiaMondayMapping.parseAmount(""))
        assertNull(MembresiaMondayMapping.parseAmount("n/a"))
        assertNull(MembresiaMondayMapping.parseAmount(null))
    }

    @Test
    fun onlyAGroupWithCancelCancelsTheMembership() {
        assertTrue(MembresiaMondayMapping.toMember(item("Cancelled"), fullMapping).cancelled)
        assertTrue(MembresiaMondayMapping.toMember(item("Canceladas 2024"), fullMapping).cancelled)
        // Expired / Vencidas no son canceladas: las fechas deciden el estado.
        assertEquals(false, MembresiaMondayMapping.toMember(item("Expired"), fullMapping).cancelled)
        assertEquals(false, MembresiaMondayMapping.toMember(item("Vencidas"), fullMapping).cancelled)
        assertEquals(false, MembresiaMondayMapping.toMember(item(""), fullMapping).cancelled)
    }

    @Test
    fun convertsSubitemsToVisitsAndGuestVisits() {
        val subitems = listOf(
            MondaySubitem("11", "Visita", listOf(cell("d", "Fecha", "date", "2025-01-05", """{"date":"2025-01-05"}"""))),
            MondaySubitem("12", "Guest: John Smith", listOf(cell("d", "Fecha", "date", "2025-01-06"))),
            MondaySubitem("13", "Invitada - Maria", listOf(cell("d", "Fecha", "date", "2025-01-07"))),
            MondaySubitem("14", "Carlos", listOf(cell("d", "Fecha", "date", "2025-01-08"), cell("g", "Invitado", "text", "Pedro Gomez"))),
            MondaySubitem("15", "Marcado", listOf(cell("d", "Fecha", "date", "2025-01-09"), cell("g", "Guest", "checkbox", "v"))),
            MondaySubitem("16", "Guest", listOf(cell("d", "Fecha", "date", "2025-01-10"))),
            MondaySubitem("17", "Sin fecha", listOf(cell("d", "Fecha", "date", ""))),
        )
        val visits = MembresiaMondayMapping.toMember(item(subitems = subitems), fullMapping).visits
        assertEquals(
            listOf(
                ImportedVisit("monday:11", LocalDate.of(2025, 1, 5), null),
                ImportedVisit("monday:12", LocalDate.of(2025, 1, 6), "John Smith"),
                ImportedVisit("monday:13", LocalDate.of(2025, 1, 7), "Maria"),
                ImportedVisit("monday:14", LocalDate.of(2025, 1, 8), "Pedro Gomez"),
                ImportedVisit("monday:15", LocalDate.of(2025, 1, 9), "Marcado"),
                ImportedVisit("monday:16", LocalDate.of(2025, 1, 10), "Invitado"),
            ),
            visits,
        )
    }

    @Test
    fun keysAreStableAcrossConversions() {
        val first = MembresiaMondayMapping.toMember(item(), fullMapping)
        val second = MembresiaMondayMapping.toMember(item(), fullMapping)
        assertEquals(first, second)
        assertEquals("monday:9001", first.key)
    }

    @Test
    fun theRowDtoRoundTripKeepsEverything() {
        val subitems = listOf(
            MondaySubitem("11", "Visita", listOf(cell("d", "Fecha", "date", "2025-01-05"))),
            MondaySubitem("12", "Guest: John Smith", listOf(cell("d", "Fecha", "date", "2025-01-06"))),
        )
        val member = MembresiaMondayMapping.toMember(item(subitems = subitems), fullMapping)
        assertEquals(member, MondayRowDto.decode(MondayRowDto.encode(member)))

        val sparse = ImportedMember(key = "monday:2", name = "Solo nombre", planName = "", startsOn = null, endsOn = null, amount = null)
        assertEquals(sparse, MondayRowDto.decode(MondayRowDto.encode(sparse)))

        val cancelled = member.copy(cancelled = true, amount = BigDecimal("0.10"), guestPasses = 0)
        assertEquals(cancelled, MondayRowDto.decode(MondayRowDto.encode(cancelled)))
    }

    @Test
    fun columnOptionsKeepTheIdEvenWithRepeatedTitles() {
        val option = MembresiaMondayFlow.columnOption(column("date_2", "Fecha · inicio", "date"))
        assertEquals("date_2", MembresiaMondayFlow.columnIdOf(option))
        assertNull(MembresiaMondayFlow.columnIdOf(MembresiaMondayFlow.NoColumn))
        assertNull(MembresiaMondayFlow.columnIdOf(""))
    }

    @Test
    fun parsesBoardsColumnsAndItemsFromTheApiResponse() {
        val boards = MembresiaMondayClient.parseBoards(
            Json.parseToJsonElement(
                """{"boards": [
                    {"id": "101", "name": "Membresias", "type": "board", "items_count": 42, "workspace": {"name": "Esc"}},
                    {"id": "102", "name": "Subelementos de Membresias", "type": "sub_items_board", "workspace": {"name": "Esc"}},
                    {"id": "x", "name": "Sin id valido", "type": "board"},
                    {"id": "104", "name": "Otro", "type": "board", "items_count": null, "workspace": null}
                ]}""",
            ) as JsonObject,
        )
        assertEquals(
            listOf(MondayBoard("101", "Membresias", "Esc", 42), MondayBoard("104", "Otro", MembresiaMondayClient.MainWorkspace, null)),
            boards,
        )
        val item = MembresiaMondayClient.parseItem(
            Json.parseToJsonElement(
                """{"id": "7", "name": "Ana", "group": {"title": "Expired"},
                    "column_values": [{"id": "period", "type": "timeline", "text": "2025-01-01 - 2025-01-31", "value": "{\"from\":\"2025-01-01\",\"to\":\"2025-01-31\"}", "column": {"title": "Period"}}],
                    "subitems": [{"id": "70", "name": "Guest Bob", "column_values": [{"id": "d", "type": "date", "text": "2025-01-05", "value": null, "column": {"title": "Date"}}]}]}""",
            ) as JsonObject,
        )
        assertEquals("Expired", item.group)
        assertEquals("period", item.cells.single().columnId)
        assertEquals(LocalDate.of(2025, 1, 31), MembresiaMondayMapping.periodOf(item.cells.single()).second)
        assertEquals("monday:70", MembresiaMondayMapping.visitOf(item.subitems.single())?.key)
    }
}
