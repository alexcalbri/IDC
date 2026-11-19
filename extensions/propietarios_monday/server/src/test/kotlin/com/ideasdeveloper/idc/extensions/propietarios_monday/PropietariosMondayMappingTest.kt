package com.ideasdeveloper.idc.extensions.propietarios_monday

import com.ideasdeveloper.idc.modules.propietarios.LotStatus
import java.math.BigDecimal
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PropietariosMondayMappingTest {
    private fun cell(id: String, title: String, type: String, text: String, value: String = "") = MondayCell(id, title, type, text, value)

    private val board = listOf(
        MondayColumn("name", "Name", "name"),
        MondayColumn("property", "Property", "text"),
        MondayColumn("lot", "Lot Number", "text"),
        MondayColumn("area", "Area M2", "numbers"),
        MondayColumn("reg", "Register Number", "text"),
        MondayColumn("ptype", "Property Type", "text"),
        MondayColumn("mail", "Correo", "text"),
        MondayColumn("budget", "Budget", "numbers"),
    )

    @Test
    fun suggestsColumnsByTitleInEnglishAndOwnerFromGroup() {
        val mapping = PropietariosMondayMapping.suggest(board).columns
        assertEquals("property", mapping[MondayTarget.Property])
        assertEquals("lot", mapping[MondayTarget.Lot])
        assertEquals("area", mapping[MondayTarget.Area])
        assertEquals("reg", mapping[MondayTarget.Register])
        assertEquals("ptype", mapping[MondayTarget.Type])
        assertEquals("mail", mapping[MondayTarget.Email])
        assertEquals(PropietariosMondayMapping.GroupSource, mapping[MondayTarget.Owner])
        // Una columna sirve para un solo dato.
        val columnIds = mapping.filterValues { it != PropietariosMondayMapping.GroupSource }.values
        assertEquals(columnIds.size, columnIds.toSet().size)
    }

    @Test
    fun suggestsColumnsByTitleAndTypeInSpanish() {
        val columns = listOf(
            MondayColumn("a", "Propietario", "text"),
            MondayColumn("b", "No. Lote", "text"),
            MondayColumn("c", "Área", "numbers"),
            MondayColumn("d", "Matrícula", "text"),
            MondayColumn("e", "Tipo", "text"),
            MondayColumn("f", "Teléfono", "phone"),
            MondayColumn("g", "Estado", "status"),
            MondayColumn("h", "Ubicación", "location"),
            MondayColumn("i", "Fecha de compra", "date"),
            MondayColumn("j", "% Participación", "numbers"),
            MondayColumn("k", "Email", "email"),
        )
        val mapping = PropietariosMondayMapping.suggest(columns).columns
        assertEquals("a", mapping[MondayTarget.Owner])
        assertEquals("b", mapping[MondayTarget.Lot])
        assertEquals("c", mapping[MondayTarget.Area])
        assertEquals("d", mapping[MondayTarget.Register])
        assertEquals("e", mapping[MondayTarget.Type])
        assertEquals("f", mapping[MondayTarget.Phone])
        assertEquals("g", mapping[MondayTarget.Status])
        assertEquals("h", mapping[MondayTarget.Location])
        assertEquals("i", mapping[MondayTarget.Start])
        assertEquals("j", mapping[MondayTarget.Share])
        assertEquals("k", mapping[MondayTarget.Email])
    }

    @Test
    fun statusWordsInSpanishAndEnglishIgnoreCaseAndAccents() {
        val expected = mapOf(
            "Sold" to LotStatus.Vendido, "VENDIDO" to LotStatus.Vendido,
            "Available" to LotStatus.Disponible, "Disponible" to LotStatus.Disponible,
            "Reserved" to LotStatus.Reservado, "Reservado" to LotStatus.Reservado,
            "Under construction" to LotStatus.EnConstruccion, "En construcción" to LotStatus.EnConstruccion,
            "Built" to LotStatus.Construido, "Construido" to LotStatus.Construido,
        )
        expected.forEach { (label, status) -> assertEquals(status, PropietariosMondayMapping.statusOf(label), "estado de '$label'") }
        assertNull(PropietariosMondayMapping.statusOf(""))
        assertNull(PropietariosMondayMapping.statusOf(null))
        assertNull(PropietariosMondayMapping.statusOf("Algo raro"))
    }

    @Test
    fun emptyStatusIsSoldWithOwnerAndAvailableWithout() {
        val mapping = MondayMapping(mapOf(MondayTarget.Lot to "lot", MondayTarget.Status to "st"))
        val withOwner = MondayItem("1", "Casa", "Juan Perez", listOf(cell("lot", "Lot", "text", "12"), cell("st", "Estado", "status", "")))
        val without = MondayItem("2", "Casa", "", listOf(cell("lot", "Lot", "text", "13")))
        assertEquals(LotStatus.Vendido, PropietariosMondayMapping.toLot(withOwner, mapping).lot.status)
        assertEquals(LotStatus.Disponible, PropietariosMondayMapping.toLot(without, mapping).lot.status)
        val explicit = MondayItem("3", "Casa", "Ana", listOf(cell("lot", "Lot", "text", "14"), cell("st", "Estado", "status", "Reservado")))
        assertEquals(LotStatus.Reservado, PropietariosMondayMapping.toLot(explicit, mapping).lot.status)
    }

    @Test
    fun severalEmailsMakeOneOwnerEach() {
        val mapping = MondayMapping(mapOf(MondayTarget.Lot to "lot", MondayTarget.Email to "mail", MondayTarget.Phone to "tel", MondayTarget.Share to "pct"))
        val item = MondayItem(
            "10", "Casa 1", "Familia Lopez",
            listOf(
                cell("lot", "Lot", "text", " A-5 "),
                cell("mail", "Correo", "text", "Ana@Lopez.com; luis@lopez.com, ana@lopez.com  nota"),
                cell("tel", "Tel", "phone", "555-1234"),
                cell("pct", "%", "numbers", "50"),
            ),
        )
        val lot = PropietariosMondayMapping.toLot(item, mapping).lot
        assertEquals("monday:10", lot.key)
        assertEquals("A-5", lot.lotNumber)
        assertEquals(2, lot.owners.size)
        assertEquals("Familia Lopez", lot.owners[0].name)
        assertEquals("ana@lopez.com", lot.owners[0].email)
        assertEquals("555-1234", lot.owners[0].phone)
        assertEquals("luis@lopez.com", lot.owners[1].name)
        assertEquals("luis@lopez.com", lot.owners[1].email)
        assertEquals("", lot.owners[1].phone)
        // Con varios titulares el porcentaje no se reparte solo: queda en las notas.
        assertTrue(lot.owners.all { it.sharePercent == null })
        assertTrue(lot.notes.contains("Porcentaje: 50.00"))
        assertTrue(lot.notes.contains("Correo (no valido): nota"))
    }

    @Test
    fun singleOwnerKeepsShareStartDateAndNameFromColumnOrItem() {
        val mapping = MondayMapping(mapOf(MondayTarget.Owner to PropietariosMondayMapping.ItemSource, MondayTarget.Share to "pct", MondayTarget.Start to "d"))
        val item = MondayItem(
            "7", "Maria Gomez", "Grupo X",
            listOf(cell("pct", "Share", "numbers", "100%"), cell("d", "Fecha", "date", "2024-03-01", """{"date":"2024-03-01"}""")),
        )
        val lot = PropietariosMondayMapping.toLot(item, mapping).lot
        // Sin columna de lote, el lote es el nombre del elemento.
        assertEquals("Maria Gomez", lot.lotNumber)
        assertEquals(1, lot.owners.size)
        assertEquals("Maria Gomez", lot.owners[0].name)
        assertEquals(BigDecimal("100.00"), lot.owners[0].sharePercent)
        assertEquals(LocalDate.of(2024, 3, 1), lot.owners[0].startsOn)
    }

    @Test
    fun ownerFromGroupByDefaultAndWithoutOwnerHasNoOwners() {
        val item = MondayItem("5", "Lote 9", "Garcia", listOf(cell("lot", "Lot Number", "text", "9")))
        val lot = PropietariosMondayMapping.toLot(item, MondayMapping(mapOf(MondayTarget.Lot to "lot"))).lot
        assertEquals("Garcia", lot.owners.single().name)
        val noGroup = PropietariosMondayMapping.toLot(item.copy(group = ""), MondayMapping(mapOf(MondayTarget.Lot to "lot"))).lot
        assertTrue(noGroup.owners.isEmpty())
    }

    @Test
    fun locationGivesLatitudeAndLongitudeFromRawValue() {
        val value = """{"lat":"19.432608","lng":"-99.133209","address":"Centro"}"""
        val coordinates = PropietariosMondayMapping.coordinatesOf(cell("loc", "Ubicación", "location", "Centro", value))
        assertEquals(BigDecimal("19.432608"), coordinates?.first)
        assertEquals(BigDecimal("-99.133209"), coordinates?.second)
        assertNull(PropietariosMondayMapping.coordinatesOf(cell("loc", "Ubicación", "location", "", """{"lat":"123","lng":"-99"}""")))
        assertNull(PropietariosMondayMapping.coordinatesOf(cell("loc", "Ubicación", "location", "", "")))
        val item = MondayItem("8", "L", "G", listOf(cell("loc", "Ubicación", "location", "Centro", value)))
        val lot = PropietariosMondayMapping.toLot(item, MondayMapping(mapOf(MondayTarget.Location to "loc"))).lot
        assertEquals(BigDecimal("19.432608"), lot.latitude)
        assertEquals(BigDecimal("-99.133209"), lot.longitude)
        assertTrue(lot.notes.contains("Ubicacion: Centro"))
    }

    @Test
    fun timelineStartsOnItsFirstDate() {
        val timeline = cell("t", "Plazo", "timeline", "2024-01-05 - 2024-02-01", """{"from":"2024-01-05","to":"2024-02-01"}""")
        assertEquals(LocalDate.of(2024, 1, 5), PropietariosMondayMapping.startOf(timeline))
        assertNull(PropietariosMondayMapping.startOf(null))
    }

    @Test
    fun areaAndShareParseDecimalCommaAndDot() {
        val expected = mapOf(
            "1.234,5" to "1234.50", "1234.5" to "1234.50", "1,234.5" to "1234.50", "1234,5" to "1234.50",
            "250" to "250.00", "250 m2" to "250.00", "1,234" to "1234.00", "1.234.567,89" to "1234567.89", "0,5" to "0.50",
        )
        expected.forEach { (text, number) -> assertEquals(BigDecimal(number), PropietariosMondayMapping.parseDecimal(text), "numero de '$text'") }
        assertNull(PropietariosMondayMapping.parseDecimal(""))
        assertNull(PropietariosMondayMapping.parseDecimal("abc"))
        assertNull(PropietariosMondayMapping.parseDecimal("-5"))
        assertEquals(BigDecimal("33.33"), PropietariosMondayMapping.shareOf("33,33 %"))
        assertNull(PropietariosMondayMapping.shareOf("0"))
        assertNull(PropietariosMondayMapping.shareOf("150"))
    }

    @Test
    fun leftoverColumnsGoToNotesAsColumnValue() {
        val mapping = MondayMapping(mapOf(MondayTarget.Lot to "lot"))
        val item = MondayItem(
            "3", "Maria", "Grupo",
            listOf(
                cell("lot", "Lot Number", "text", "4"),
                cell("budget", "Budget", "numbers", "1500"),
                cell("obs", "Observaciones", "long_text", "Pendiente de escritura"),
                cell("empty", "Vacia", "text", ""),
                cell("files", "Documentos", "file", "a.pdf", """{"files":[{"fileType":"ASSET","assetId":99,"name":"a.pdf"},{"fileType":"GOOGLE_DRIVE","assetId":5,"name":"g"}]}"""),
            ),
        )
        val converted = PropietariosMondayMapping.toLot(item, mapping)
        assertEquals("Budget: 1500\nObservaciones: Pendiente de escritura\nElemento: Maria", converted.lot.notes)
        // Solo los archivos de monday (ASSET) se importan, y no van a las notas.
        assertEquals(listOf("99"), converted.files.map { it.asset })
        assertEquals(converted.files, PropietariosMondayMapping.decodeFiles(PropietariosMondayMapping.encodeFiles(converted.files)))
    }

    @Test
    fun rowDtoRoundTripsALot() {
        val mapping = PropietariosMondayMapping.suggest(board)
        val item = MondayItem(
            "42", "Casa", "Familia Ruiz",
            listOf(
                cell("lot", "Lot Number", "text", "L-1"), cell("area", "Area M2", "numbers", "1.234,5"), cell("reg", "Register Number", "text", "R-9"),
                cell("mail", "Correo", "text", "ruiz@example.com"),
            ),
        )
        val lot = PropietariosMondayMapping.toLot(item, mapping).lot
        val back = MondayRowDto.decode(MondayRowDto.encode(lot))
        assertEquals(lot, back)
        assertEquals(BigDecimal("1234.50"), back.areaM2)
        assertEquals("R-9", back.registerNumber)
        assertEquals(LotStatus.Vendido, back.status)
    }

    @Test
    fun onlyMondayHostsAreAllowedForFiles() {
        assertTrue(PropietariosMondayClient.isAllowedFileHost("files-monday-com.s3.amazonaws.com"))
        assertTrue(PropietariosMondayClient.isAllowedFileHost("cdn.monday.com"))
        assertEquals(false, PropietariosMondayClient.isAllowedFileHost("evil.example.com"))
        assertEquals(false, PropietariosMondayClient.isAllowedFileHost(null))
    }
}
