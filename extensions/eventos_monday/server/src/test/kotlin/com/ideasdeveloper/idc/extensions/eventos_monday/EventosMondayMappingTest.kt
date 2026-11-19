package com.ideasdeveloper.idc.extensions.eventos_monday

import com.ideasdeveloper.idc.modules.eventos.EventStatus
import com.ideasdeveloper.idc.server.extensions.ExtensionManifest
import kotlinx.serialization.json.Json
import java.io.File
import java.math.BigDecimal
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class EventosMondayMappingTest {
    private val today = LocalDate.of(2026, 11, 18)
    private val future = LocalDateTime.of(2026, 12, 1, 0, 0)
    private val past = LocalDateTime.of(2026, 10, 1, 0, 0)
    private val utc = ZoneId.of("UTC")

    private fun cell(id: String, title: String, type: String, text: String, value: String = "") = MondayCell(id, title, type, text, value)

    // Compara el valor sin importar la escala (12.5 y 12.50 son iguales).
    private fun assertAmount(expected: String, actual: BigDecimal?) {
        assertEquals(0, actual?.compareTo(BigDecimal(expected)), "monto esperado $expected, salio $actual")
    }

    // ---- Estado ----

    @Test
    fun confirmedLabelsArePublishedWhenFutureAndDoneWhenPast() {
        listOf("Booked", "Reservado", "Confirmado", "Confirmed", "RESERVADA").forEach { label ->
            assertEquals(EventStatus.Published, EventosMondayMapping.statusOf(label, future, today), "futuro '$label'")
            assertEquals(EventStatus.Done, EventosMondayMapping.statusOf(label, past, today), "pasado '$label'")
        }
        // El mismo dia todavia es un evento por venir.
        assertEquals(EventStatus.Published, EventosMondayMapping.statusOf("Booked", LocalDateTime.of(2026, 11, 18, 0, 0), today))
    }

    @Test
    fun doneLabelsAreDoneUnlessTheDateIsStillAhead() {
        listOf("Done", "Realizado", "Completado", "Completed").forEach { label ->
            assertEquals(EventStatus.Done, EventosMondayMapping.statusOf(label, past, today), "pasado '$label'")
            assertEquals(EventStatus.Published, EventosMondayMapping.statusOf(label, future, today), "futuro '$label'")
        }
    }

    @Test
    fun cancelledLostAndUnknownLabels() {
        listOf("Cancelled", "Cancelado", "Perdido", "Lost").forEach { label ->
            assertEquals(EventStatus.Cancelled, EventosMondayMapping.statusOf(label, future, today), "'$label'")
        }
        listOf("", "Working on it", "Nuevo lead", "Tentativo").forEach { label ->
            assertEquals(EventStatus.Draft, EventosMondayMapping.statusOf(label, future, today), "'$label'")
        }
        assertEquals(EventStatus.Draft, EventosMondayMapping.statusOf(null, null, today))
    }

    @Test
    fun bookedGroupForcesConfirmedExceptCancelled() {
        assertEquals(EventStatus.Published, EventosMondayMapping.statusOf("", future, today, bookedGroup = true))
        assertEquals(EventStatus.Done, EventosMondayMapping.statusOf("Working on it", past, today, bookedGroup = true))
        assertEquals(EventStatus.Cancelled, EventosMondayMapping.statusOf("Cancelado", future, today, bookedGroup = true))
    }

    // ---- Grupos y espacios ----

    @Test
    fun spaceComesFromTheGroupTitleWithoutEventsWord() {
        assertEquals("La Jolla", EventosMondayMapping.spaceOfGroup("La Jolla Eventos"))
        assertEquals("Magrock", EventosMondayMapping.spaceOfGroup("Magrock Eventos"))
        assertEquals("Magrock", EventosMondayMapping.spaceOfGroup("Magrock Events"))
        assertEquals("", EventosMondayMapping.spaceOfGroup("Upcoming Events"))
        assertEquals("", EventosMondayMapping.spaceOfGroup("Booked"))
        assertEquals("", EventosMondayMapping.spaceOfGroup("Eventos"))
        assertTrue(EventosMondayMapping.isBookedGroup("Booked"))
        assertTrue(EventosMondayMapping.isBookedGroup("Eventos reservados"))
        assertTrue(!EventosMondayMapping.isBookedGroup("La Jolla Eventos"))
    }

    @Test
    fun spaceMappedForAGroupWinsOverTheSuggestion() {
        val mapping = MondayMapping(groups = mapOf("Booked" to "Sala principal", "La Jolla Eventos" to ""))
        assertEquals("Sala principal", EventosMondayMapping.spaceFor("Booked", mapping))
        assertEquals("", EventosMondayMapping.spaceFor("La Jolla Eventos", mapping))
        assertEquals("Magrock", EventosMondayMapping.spaceFor("Magrock Eventos", mapping))
    }

    // ---- Fechas ----

    @Test
    fun dateWithTimeConvertsFromUtcToTheServerZone() {
        val withTime = cell("d", "Fecha", "date", "2026-11-20 18:30", """{"date":"2026-11-20","time":"18:30:00"}""")
        assertEquals(LocalDateTime.of(2026, 11, 20, 18, 30), EventosMondayMapping.dateTimeOf(withTime, utc))
        assertEquals(LocalDateTime.of(2026, 11, 20, 10, 30), EventosMondayMapping.dateTimeOf(withTime, ZoneId.of("America/Los_Angeles")))
    }

    @Test
    fun dateWithoutTimeIsMidnight() {
        val dateOnly = cell("d", "Fecha", "date", "2026-11-20", """{"date":"2026-11-20"}""")
        assertEquals(LocalDateTime.of(2026, 11, 20, 0, 0), EventosMondayMapping.dateTimeOf(dateOnly, utc))
        // Sin JSON se usa el texto: con hora ya es local, sin hora es medianoche.
        assertEquals(LocalDateTime.of(2026, 11, 20, 19, 15), EventosMondayMapping.dateTimeOf(cell("d", "Fecha", "date", "2026-11-20 19:15"), ZoneId.of("America/Los_Angeles")))
        assertEquals(LocalDateTime.of(2026, 11, 20, 0, 0), EventosMondayMapping.dateTimeOf(cell("d", "Fecha", "date", "2026-11-20"), utc))
        assertNull(EventosMondayMapping.dateTimeOf(cell("d", "Fecha", "date", ""), utc))
        assertNull(EventosMondayMapping.dateTimeOf(null, utc))
    }

    @Test
    fun timelineGivesStartAndEnd() {
        val range = cell("t", "Timeline", "timeline", "2026-11-20 - 2026-11-22", """{"from":"2026-11-20","to":"2026-11-22"}""")
        assertEquals(LocalDateTime.of(2026, 11, 20, 0, 0) to LocalDateTime.of(2026, 11, 22, 0, 0), EventosMondayMapping.startEndOf(range, utc))
        // Un solo dia no tiene fin.
        val oneDay = cell("t", "Timeline", "timeline", "2026-11-20", """{"from":"2026-11-20","to":"2026-11-20"}""")
        assertEquals(LocalDateTime.of(2026, 11, 20, 0, 0) to null, EventosMondayMapping.startEndOf(oneDay, utc))
        // Sin JSON se leen las fechas del texto.
        val textOnly = cell("t", "Timeline", "timeline", "2026-11-20 - 2026-11-23")
        assertEquals(LocalDateTime.of(2026, 11, 23, 0, 0), EventosMondayMapping.timelineOf(textOnly).second)
    }

    // ---- Montos e invitados ----

    @Test
    fun amountsAndGuestsAreParsedFromFormattedText() {
        assertAmount("1200.50", EventosMondayMapping.amountOf("1,200.50"))
        assertAmount("1200.50", EventosMondayMapping.amountOf("\$1.200,50"))
        assertAmount("1500", EventosMondayMapping.amountOf("1500"))
        assertAmount("12.50", EventosMondayMapping.amountOf("12,5"))
        assertAmount("1200", EventosMondayMapping.amountOf("1,200"))
        assertNull(EventosMondayMapping.amountOf("abc"))
        assertNull(EventosMondayMapping.amountOf(""))
        assertNull(EventosMondayMapping.amountOf(null))
        assertEquals(80, EventosMondayMapping.guestsOf("80"))
        assertEquals(2, EventosMondayMapping.guestsOf("2.0"))
        assertNull(EventosMondayMapping.guestsOf("0"))
        assertNull(EventosMondayMapping.guestsOf("100001"))
    }

    // ---- Sugerencia de mapeo ----

    @Test
    fun suggestsColumnsByTitleAndTypeInSpanish() {
        val columns = listOf(
            MondayColumn("name", "Nombre", "name"),
            MondayColumn("c", "Contacto", "text"),
            MondayColumn("e", "Correo", "email"),
            MondayColumn("p", "Teléfono", "phone"),
            MondayColumn("r", "Responsable", "people"),
            MondayColumn("d", "Fecha del evento", "date"),
            MondayColumn("b", "Fecha de reserva", "date"),
            MondayColumn("f", "Seguimiento", "date"),
            MondayColumn("s", "Estado", "status"),
            MondayColumn("a", "Monto", "numbers"),
            MondayColumn("g", "Invitados", "numbers"),
            MondayColumn("m", "Mensaje", "long_text"),
            MondayColumn("n", "Notas", "long_text"),
        )
        val mapping = EventosMondayMapping.suggest(columns, listOf("La Jolla Eventos", "Booked")).also {
            assertEquals(mapOf("La Jolla Eventos" to "La Jolla", "Booked" to ""), it.groups)
        }.columns
        assertEquals(
            mapOf(
                MondayTarget.Contact to "c", MondayTarget.Email to "e", MondayTarget.Phone to "p", MondayTarget.Responsible to "r",
                MondayTarget.Date to "d", MondayTarget.Booking to "b", MondayTarget.FollowUp to "f", MondayTarget.Status to "s",
                MondayTarget.Amount to "a", MondayTarget.Guests to "g", MondayTarget.Message to "m", MondayTarget.Notes to "n",
            ),
            mapping,
        )
    }

    @Test
    fun suggestsColumnsByTitleAndTypeInEnglish() {
        val columns = listOf(
            MondayColumn("name", "Name", "name"),
            MondayColumn("c", "Contact", "text"),
            MondayColumn("e", "Email", "email"),
            MondayColumn("p", "Phone", "phone"),
            MondayColumn("r", "Owner", "people"),
            MondayColumn("d", "Event date", "date"),
            MondayColumn("b", "Booking date", "date"),
            MondayColumn("f", "Follow up", "date"),
            MondayColumn("s", "Status", "status"),
            MondayColumn("a", "Amount", "numbers"),
            MondayColumn("g", "Guests", "numbers"),
            MondayColumn("m", "Message", "long_text"),
            MondayColumn("n", "Notes", "long_text"),
            MondayColumn("x", "Created", "creation_log"),
        )
        val mapping = EventosMondayMapping.suggest(columns).columns
        assertEquals("c", mapping[MondayTarget.Contact])
        assertEquals("e", mapping[MondayTarget.Email])
        assertEquals("p", mapping[MondayTarget.Phone])
        assertEquals("r", mapping[MondayTarget.Responsible])
        assertEquals("d", mapping[MondayTarget.Date])
        assertEquals("b", mapping[MondayTarget.Booking])
        assertEquals("f", mapping[MondayTarget.FollowUp])
        assertEquals("s", mapping[MondayTarget.Status])
        assertEquals("a", mapping[MondayTarget.Amount])
        assertEquals("g", mapping[MondayTarget.Guests])
        assertEquals("m", mapping[MondayTarget.Message])
        assertEquals("n", mapping[MondayTarget.Notes])
        assertTrue("x" !in mapping.values)
    }

    @Test
    fun aTimelineColumnIsUsedAsTheEventDateWhenThereIsNoDateColumn() {
        val columns = listOf(MondayColumn("t", "Timeline", "timeline"), MondayColumn("o", "Otra", "text"))
        assertEquals("t", EventosMondayMapping.suggest(columns).columns[MondayTarget.Date])
    }

    // ---- Elemento -> evento ----

    private val fullMapping = MondayMapping(
        mapOf(
            MondayTarget.Contact to "c", MondayTarget.Email to "e", MondayTarget.Phone to "p", MondayTarget.Responsible to "r",
            MondayTarget.Date to "d", MondayTarget.Status to "s", MondayTarget.Amount to "a", MondayTarget.Guests to "g",
            MondayTarget.Message to "m", MondayTarget.Notes to "n", MondayTarget.FollowUp to "f", MondayTarget.Booking to "b",
        ),
    )

    @Test
    fun convertsAnItemWithEveryMappedColumnAndLeftoversGoToNotes() {
        val item = MondayItem(
            "100", "  Boda Perez ", "Booked",
            listOf(
                cell("c", "Contacto", "text", "Ana Perez"),
                cell("e", "Correo", "email", "ana@x.com"),
                cell("p", "Teléfono", "phone", "555-1"),
                cell("r", "Responsable", "people", "Luis", """{"personsAndTeams":[{"id":7,"kind":"person"}]}"""),
                cell("d", "Fecha", "date", "2026-12-05 20:00", """{"date":"2026-12-05","time":"20:00:00"}"""),
                cell("s", "Estado", "status", "Working on it"),
                cell("a", "Monto", "numbers", "12,500.50"),
                cell("g", "Invitados", "numbers", "80"),
                cell("m", "Mensaje", "long_text", "Quiere mariachi"),
                cell("n", "Notas", "long_text", "Pagar anticipo"),
                cell("f", "Seguimiento", "date", "2026-11-25", """{"date":"2026-11-25"}"""),
                cell("b", "Reserva", "date", "2026-10-01", """{"date":"2026-10-01"}"""),
                cell("x", "Fuente", "text", "Instagram"),
                cell("y", "Vacia", "text", ""),
                cell("z", "Archivo", "file", "a.pdf"),
            ),
        )
        val people = mapOf("7" to MondayPerson("7", "Luis", "luis@x.com"))
        val event = EventosMondayMapping.toEvent(item, fullMapping, people, today, utc)
        assertEquals("monday:100", event.key)
        assertEquals("Boda Perez", event.title)
        assertEquals("Ana Perez", event.clientName)
        assertEquals("ana@x.com", event.clientEmail)
        assertEquals("555-1", event.clientPhone)
        assertEquals("luis@x.com", event.responsibleEmail)
        assertNull(event.responsibleName)
        assertEquals(LocalDateTime.of(2026, 12, 5, 20, 0), event.startsAt)
        assertNull(event.endsAt)
        // El grupo Booked fuerza confirmado aunque el estado diga otra cosa, y no da espacio.
        assertEquals(EventStatus.Published, event.status)
        assertEquals("", event.space)
        assertAmount("12500.50", event.agreedAmount)
        assertEquals(80, event.guests)
        assertEquals("Quiere mariachi", event.description)
        assertEquals("Pagar anticipo\nSeguimiento: 2026-11-25\nReservado el: 2026-10-01\nFuente: Instagram", event.notes)
    }

    @Test
    fun withoutAContactTheClientIsTheItemNameAndUnknownResponsibleKeepsItsName() {
        val item = MondayItem(
            "5", "Cumple de Sofia", "La Jolla Eventos",
            listOf(
                cell("r", "Responsable", "people", "Sofia", """{"personsAndTeams":[{"id":9,"kind":"person"}]}"""),
                cell("s", "Estado", "status", "Confirmado"),
                cell("d", "Fecha", "date", "2026-09-01", """{"date":"2026-09-01"}"""),
            ),
        )
        val event = EventosMondayMapping.toEvent(item, fullMapping, emptyMap(), today, utc)
        assertEquals("Cumple de Sofia", event.clientName)
        assertNull(event.responsibleEmail)
        assertEquals("Sofia", event.responsibleName)
        assertEquals("La Jolla", event.space)
        // Confirmado con fecha pasada = realizado.
        assertEquals(EventStatus.Done, event.status)
        assertEquals("", event.notes)
    }

    @Test
    fun aPeopleContactUsesTheirNameAndEmail() {
        val item = MondayItem(
            "6", "Reunion", "Upcoming Events",
            listOf(cell("c", "Contacto", "people", "Carlos", """{"personsAndTeams":[{"id":3,"kind":"person"}]}""")),
        )
        val event = EventosMondayMapping.toEvent(item, fullMapping, mapOf("3" to MondayPerson("3", "Carlos", "c@x.com")), today, utc)
        assertEquals("Carlos", event.clientName)
        assertEquals("c@x.com", event.clientEmail)
        assertEquals("", event.space)
        assertEquals(EventStatus.Draft, event.status)
        assertNull(event.startsAt)
    }

    @Test
    fun spaceMappedForTheGroupIsUsed() {
        val item = MondayItem("7", "Evento", "Booked", emptyList())
        val mapping = MondayMapping(emptyMap(), mapOf("Booked" to "Sala"))
        assertEquals("Sala", EventosMondayMapping.toEvent(item, mapping, emptyMap(), today, utc).space)
    }

    @Test
    fun theRowPayloadSurvivesStorage() {
        val item = MondayItem(
            "100", "Boda", "La Jolla Eventos",
            listOf(
                cell("c", "Contacto", "text", "Ana"),
                cell("t", "Timeline", "timeline", "2026-12-05 - 2026-12-06", """{"from":"2026-12-05","to":"2026-12-06"}"""),
                cell("a", "Monto", "numbers", "1000"),
            ),
        )
        val mapping = MondayMapping(mapOf(MondayTarget.Contact to "c", MondayTarget.Date to "t", MondayTarget.Amount to "a"))
        val event = EventosMondayMapping.toEvent(item, mapping, emptyMap(), today, utc)
        assertEquals(LocalDateTime.of(2026, 12, 6, 0, 0), event.endsAt)
        assertEquals(event, MondayRowDto.decode(MondayRowDto.encode(event)))
        assertEquals(EventStatus.Draft, MondayRowDto(key = "monday:1", title = "x", status = "raro").toEvent().status)
    }

    @Test
    fun summariesMergeAcrossBatches() {
        val merged = MondaySummaryDto.merge(
            listOf(
                MondaySummaryDto(events = 2, newCustomers = 1, bySpace = mapOf("La Jolla" to 2), matchedPeople = listOf("a@x.com"), problems = listOf("p1"), problemCount = 1),
                MondaySummaryDto(events = 3, newCustomers = 2, bySpace = mapOf("La Jolla" to 1, "Magrock" to 2), matchedPeople = listOf("a@x.com", "b@x.com")),
            ),
        )
        assertEquals(5, merged.events)
        assertEquals(3, merged.newCustomers)
        assertEquals(mapOf("La Jolla" to 3, "Magrock" to 2), merged.bySpace)
        assertEquals(listOf("a@x.com", "b@x.com"), merged.matchedPeople)
        assertEquals(1, merged.problemCount)
    }

    // ---- Cliente y extension ----

    @Test
    fun boardIdsMustBeNumeric() {
        assertTrue(EventosMondayClient.isBoardId("1234567890"))
        assertTrue(!EventosMondayClient.isBoardId("12 34"))
        assertTrue(!EventosMondayClient.isBoardId("1; drop"))
        assertTrue(!EventosMondayClient.isBoardId(""))
    }

    @Test
    fun transientFlagOnMondayErrors() {
        assertTrue(EventosMondayException("x", transient = true).transient)
        assertTrue(!EventosMondayException("x").transient)
    }

    @Test
    fun theManifestInCodeMatchesExtensionJson() {
        val text = repoFile("extensions/eventos_monday/extension.json").readText()
        val fromDisk = Json { ignoreUnknownKeys = true }.decodeFromString(ExtensionManifest.serializer(), text)
        assertEquals(fromDisk, EventosMondayServerExtension.manifest)
        assertEquals(EventosMondayId, EventosMondayServerExtension.sourceId)
        assertEquals(listOf("extensions/eventos_monday/migrations/V001__settings.sql"), EventosMondayServerExtension.migrationPaths)
        assertTrue(repoFile("extensions/eventos_monday/migrations/V001__settings.sql").isFile)
        // Solo tablas propias de la extension.
        assertTrue(EventosMondayServerExtension.runtimeTables.all { it.startsWith("ext_eventos_monday__") })
    }

    @Test
    fun mcpToolsNeedTheImportPermissionAndFitTheQualifiedNameLimit() {
        val tools = EventosMondayServerExtension.mcpTools
        assertEquals(listOf("monday_list_boards", "monday_read_board", "monday_import_status", "monday_apply"), tools.map { it.name })
        assertTrue(tools.all { it.permission == EventosImportPermission })
        assertTrue(tools.all { "eventos_${it.name}".length <= 64 })
        assertEquals(listOf(true, false, true, false), tools.map { it.readOnly })
    }

    // El directorio de trabajo de las pruebas es el del proyecto Gradle: se sube hasta encontrar el archivo.
    private fun repoFile(relative: String): File {
        var dir: File? = File(System.getProperty("user.dir")).absoluteFile
        while (dir != null) {
            val candidate = File(dir, relative)
            if (candidate.isFile) return candidate
            dir = dir.parentFile
        }
        error("No se encontro $relative")
    }
}
