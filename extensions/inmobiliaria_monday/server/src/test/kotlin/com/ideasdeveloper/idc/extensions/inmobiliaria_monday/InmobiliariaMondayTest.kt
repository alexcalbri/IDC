package com.ideasdeveloper.idc.extensions.inmobiliaria_monday

import com.ideasdeveloper.idc.modules.crm.CrmImportField
import com.ideasdeveloper.idc.modules.crm.CrmImportKind
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Reglas puras de la importacion de monday: mapeo sugerido, conversion de columnas, respuestas de la API y claves. */
class InmobiliariaMondayTest {
    private fun parse(text: String) = Json.parseToJsonElement(text) as JsonObject

    private fun columns(vararg titles: String) = titles.mapIndexed { index, title -> MondayColumn("c$index", title, "text") }

    private val propertyKeys = setOf(
        "title", "code", "kind", "zone", "development", "status", "agent", "owner_name", "owner_email", "owner_phone", "lot_area", "built_area",
        "bedrooms", "bathrooms", "price", "previous_price", "currency", "description", "notes", "link_website", "link_website_es", "link_craigslist",
        "link_encuentra24", "link_drive", "link_other", "intake",
    )
    private val dealKeys = setOf(
        "property", "buyer_name", "buyer_email", "stage", "agreed_price", "commission_pct", "commission_amount", "estimated_close_on", "closed_on",
        "comments", "seller", "seller_agent", "seller_lawyer", "seller_referral", "buyer", "buyer_agent", "buyer_lawyer", "agents", "brokers",
    )

    private fun kind(id: String, keys: Set<String>, required: Set<String>) =
        CrmImportKind(id, id, keys.map { CrmImportField(it, it.replace('_', ' '), it in required) })

    private val propertyKind = kind("property", propertyKeys, setOf("title"))
    private val dealKind = kind("deal", dealKeys, setOf("property"))

    // ---- Sugerencia de mapeo ----

    @Test
    fun suggestsPropertyFieldsFromEnglishAndSpanishTitles() {
        val cols = columns(
            "Nombre", "Neighborhood", "Price", "Old price", "Lot size", "Construction", "Bedrooms", "Baths", "Status", "Website",
            "Spanish website", "Craigslist", "Encuentra24", "Google Drive", "Agent", "Seller", "Comments", "Algo raro",
        )
        val mapping = MondayMapping.suggest("property", cols, propertyKeys)
        assertEquals("title", mapping["Nombre"])
        assertEquals("zone", mapping["Neighborhood"])
        assertEquals("price", mapping["Price"])
        assertEquals("previous_price", mapping["Old price"])
        assertEquals("lot_area", mapping["Lot size"])
        assertEquals("built_area", mapping["Construction"])
        assertEquals("bedrooms", mapping["Bedrooms"])
        assertEquals("bathrooms", mapping["Baths"])
        assertEquals("status", mapping["Status"])
        assertEquals("link_website", mapping["Website"])
        assertEquals("link_website_es", mapping["Spanish website"])
        assertEquals("link_craigslist", mapping["Craigslist"])
        assertEquals("link_encuentra24", mapping["Encuentra24"])
        assertEquals("link_drive", mapping["Google Drive"])
        assertEquals("agent", mapping["Agent"])
        // En propiedades el vendedor es el propietario.
        assertEquals("owner_name", mapping["Seller"])
        assertEquals("notes", mapping["Comments"])
        assertNull(mapping["Algo raro"])
    }

    @Test
    fun suggestsSpanishTitlesIgnoringAccentsAndCase() {
        val mapping = MondayMapping.suggest("property", columns("BARRIO", "Habitaciones", "Baños", "Construcción", "Precio Anterior"), propertyKeys)
        assertEquals("zone", mapping["BARRIO"])
        assertEquals("bedrooms", mapping["Habitaciones"])
        assertEquals("bathrooms", mapping["Baños"])
        assertEquals("built_area", mapping["Construcción"])
        assertEquals("previous_price", mapping["Precio Anterior"])
    }

    @Test
    fun suggestsDealFieldsAndTheSellerIsAPartyThere() {
        val cols = columns("Nombre", "Seller", "Seller's Lawyer", "Buyer", "Estimated Closing", "Commission %", "Comments", "Price")
        val mapping = MondayMapping.suggest("deal", cols, dealKeys)
        assertEquals("property", mapping["Nombre"])
        assertEquals("seller", mapping["Seller"])
        assertEquals("seller_lawyer", mapping["Seller's Lawyer"])
        assertEquals("buyer", mapping["Buyer"])
        assertEquals("estimated_close_on", mapping["Estimated Closing"])
        assertEquals("commission_pct", mapping["Commission %"])
        assertEquals("comments", mapping["Comments"])
        assertEquals("agreed_price", mapping["Price"])
    }

    @Test
    fun aFieldReceivesOnlyOneColumnAndOnlyKnownFieldsAreSuggested() {
        val twice = MondayMapping.suggest("property", columns("Price", "New price"), propertyKeys)
        assertEquals(mapOf("Price" to "price"), twice)
        // Un campo que la extension destino no tiene no se propone.
        assertTrue(MondayMapping.suggest("property", columns("Price"), setOf("title")).isEmpty())
    }

    @Test
    fun normalizesTitles() {
        assertEquals("sellers lawyer", MondayMapping.normalize("Seller's  Lawyer"))
        assertEquals("commission pct", MondayMapping.normalize("Commission %"))
        assertEquals("banos", MondayMapping.normalize("Baños"))
    }

    // ---- Mapeo y registros ----

    @Test
    fun checksAMappingAgainstColumnsAndFields() {
        val cols = columns("Nombre", "Price")
        assertEquals(mapOf("Nombre" to "title"), MondayMapping.checkMapping(propertyKind, cols, mapOf("Nombre" to "title", "Price" to "")))
        assertFailsWith<InmobiliariaMondayException> { MondayMapping.checkMapping(propertyKind, cols, mapOf("Otra" to "title")) }
        assertFailsWith<InmobiliariaMondayException> { MondayMapping.checkMapping(propertyKind, cols, mapOf("Nombre" to "no_existe")) }
        assertFailsWith<InmobiliariaMondayException> { MondayMapping.checkMapping(propertyKind, cols, mapOf("Nombre" to "title", "Price" to "title")) }
    }

    @Test
    fun reportsMissingRequiredFields() {
        assertEquals(listOf("title"), MondayMapping.missingRequired(propertyKind, mapOf("Price" to "price")))
        assertTrue(MondayMapping.missingRequired(propertyKind, mapOf("Nombre" to "title")).isEmpty())
    }

    @Test
    fun buildsRecordsWithTheImportKeyAndTheIntakeFlag() {
        val row = MondayRow(1, "123456", MondayMapping.importKey("123456"), mapOf("Nombre" to " Casa Azul ", "Price" to "", "Other" to "x"), MondayRowState.Pending, "")
        val mapping = mapOf("Nombre" to "title", "Price" to "price")
        val plain = MondayMapping.record(row, mapping, propertyKind, newListings = false)
        assertEquals("monday:123456", plain.importKey)
        assertEquals(mapOf("title" to "Casa Azul"), plain.values)
        assertEquals("true", MondayMapping.record(row, mapping, propertyKind, newListings = true).values["intake"])
        // La casilla de nuevos listados no aplica a las transacciones.
        assertNull(MondayMapping.record(row, mapOf("Nombre" to "property"), dealKind, newListings = true).values["intake"])
    }

    @Test
    fun importKeyIsStablePerItem() {
        assertEquals("monday:42", MondayMapping.importKey("42"))
        assertEquals(MondayMapping.importKey("42"), MondayMapping.importKey("42"))
    }

    @Test
    fun findsTheProblemOfARecordAndCapsTheStoredOnes() {
        val problems = listOf("monday:12: precio no valido", "monday:123: falta el titulo")
        assertEquals("precio no valido", MondayMapping.problemOf("monday:12", problems))
        assertEquals("falta el titulo", MondayMapping.problemOf("monday:123", problems))
        assertNull(MondayMapping.problemOf("monday:9", problems))
        val many = MondayMapping.capProblems(List(190) { "a$it" }, List(50) { "b$it" })
        assertEquals(MondayMapping.MaxProblems, many.size)
        assertEquals("a0", many.first())
    }

    // ---- Respuestas de monday ----

    @Test
    fun convertsLinkColumnsToTheirUrlAndTheRestToText() {
        assertEquals("https://example.com/a", InmobiliariaMondayClient.columnText("link", "Sitio - https://example.com/a", """{"url":"https://example.com/a","text":"Sitio"}"""))
        // Sin JSON valido se queda con el texto.
        assertEquals("Sitio - x", InmobiliariaMondayClient.columnText("link", " Sitio - x ", "no es json"))
        assertEquals("125000", InmobiliariaMondayClient.columnText("numbers", "125000", "\"125000\""))
        assertEquals("FOR SALE", InmobiliariaMondayClient.columnText("status", "FOR SALE", "{\"index\":1}"))
    }

    @Test
    fun parsesTheFirstPageOfABoard() {
        val data = parse(
            """{"boards": [{
              "name": "All Real Estate Listings",
              "columns": [
                {"id": "name", "title": "Name", "type": "name"},
                {"id": "text1", "title": "Neighborhood", "type": "text"},
                {"id": "link1", "title": "Website", "type": "link"},
                {"id": "files", "title": "Photos", "type": "file"},
                {"id": "text2", "title": "Neighborhood", "type": "text"}
              ],
              "items_page": {"cursor": "abc", "items": [
                {"id": "1001", "name": "Casa Azul", "group": {"title": "For sale"}, "column_values": [
                  {"id": "text1", "type": "text", "text": "Dominical", "value": "\"Dominical\""},
                  {"id": "link1", "type": "link", "text": "Web - https://x.test/1", "value": "{\"url\":\"https://x.test/1\",\"text\":\"Web\"}"},
                  {"id": "text2", "type": "text", "text": null, "value": null},
                  {"id": "files", "type": "file", "text": "", "value": null}
                ]},
                {"id": "mala", "name": "Sin id valido", "column_values": []}
              ]}
            }]}""",
        )
        val page = InmobiliariaMondayClient.parseFirstPage(data, "55")
        assertEquals("All Real Estate Listings", page.boardName)
        assertEquals("abc", page.cursor)
        // Nombre y Grupo van como columnas sinteticas; la columna name de monday y la de archivos no se importan; el titulo repetido se distingue.
        assertEquals(listOf("Nombre", "Grupo", "Neighborhood", "Website", "Neighborhood (2)"), page.columns.map { it.title })
        assertEquals(1, page.items.size)
        val item = page.items.first()
        assertEquals("1001", item.id)
        assertEquals("Casa Azul", item.values[MondayMapping.NameColumn])
        assertEquals("For sale", item.values[MondayMapping.GroupColumn])
        assertEquals("Dominical", item.values["Neighborhood"])
        assertEquals("https://x.test/1", item.values["Website"])
        assertEquals("", item.values["Neighborhood (2)"])
    }

    @Test
    fun parsesTheNextPageAndTheEndOfTheBoard() {
        val cols = columns("Price")
        val next = InmobiliariaMondayClient.parseNextPage(
            parse("""{"next_items_page": {"cursor": null, "items": [{"id": "7", "name": "Lote", "column_values": [{"id": "c0", "type": "numbers", "text": "90000", "value": null}]}]}}"""),
            cols,
        )
        assertEquals("", next.cursor)
        assertEquals("90000", next.items.single().values["Price"])
    }

    @Test
    fun parsesBoardsAndSkipsSubitemBoardsAndDocs() {
        val boards = InmobiliariaMondayClient.parseBoards(
            parse(
                """{"boards": [
                  {"id": "101", "name": "Deals", "type": "board", "items_count": 42, "workspace": {"name": "Real Estate"}},
                  {"id": "102", "name": "Subelementos de Deals", "type": "sub_items_board"},
                  {"id": "103", "name": "Notas", "type": "document"},
                  {"id": "104", "name": "Sin espacio", "type": "board", "workspace": null},
                  {"id": "x", "name": "Sin id valido", "type": "board"}
                ]}""",
            ),
        )
        assertEquals(
            listOf(MondayBoard("101", "Deals", "Real Estate", 42), MondayBoard("104", "Sin espacio", InmobiliariaMondayClient.MainWorkspace, null)),
            boards,
        )
    }

    @Test
    fun turnsMondayErrorsIntoSafeMessages() {
        assertFailsWith<InmobiliariaMondayException> { InmobiliariaMondayClient.parseResponse(401, "{}") }
        val graphql = assertFailsWith<InmobiliariaMondayException> { InmobiliariaMondayClient.parseResponse(200, """{"errors":[{"message":"Complexity budget exhausted"}]}""") }
        assertTrue(graphql.message.orEmpty().contains("Complexity budget exhausted"))
        assertFailsWith<InmobiliariaMondayException> { InmobiliariaMondayClient.parseResponse(502, "<html>") }
        assertEquals(JsonObject(emptyMap()), InmobiliariaMondayClient.parseResponse(200, """{"data": {}}"""))
    }

    @Test
    fun validatesBoardIds() {
        assertTrue(InmobiliariaMondayClient.validBoardId("1234567890"))
        assertTrue(!InmobiliariaMondayClient.validBoardId("12; drop"))
        assertTrue(!InmobiliariaMondayClient.validBoardId(""))
    }

    // ---- Almacen (solo conversion de JSON, sin base de datos) ----

    @Test
    fun storesMappingColumnsAndProblemsAsJson() {
        val mapping = mapOf("Seller's \"Lawyer\"" to "seller_lawyer", "Nombre" to "title")
        assertEquals(mapping, InmobiliariaMondayStore.stringMap(InmobiliariaMondayStore.mappingJson(mapping)))
        val cols = listOf(MondayColumn("a", "Price", "numbers"), MondayColumn("b", "Web", "link"))
        assertEquals(cols, InmobiliariaMondayStore.columns(InmobiliariaMondayStore.columnsJson(cols)))
        assertEquals(listOf("monday:1: mal", "otro"), InmobiliariaMondayStore.problems(InmobiliariaMondayStore.problemsJson(listOf("monday:1: mal", "otro"))))
        assertEquals(emptyMap(), InmobiliariaMondayStore.stringMap("no es json"))
    }

    // ---- Pasos nuevos: columnas sinteticas, filas, errores pasajeros, topes ----

    private val boardJson = """{"boards": [{
          "name": "Listings",
          "columns": [
            {"id": "name", "title": "Name", "type": "name"},
            {"id": "n1", "title": "Price", "type": "numbers"},
            {"id": "n2", "title": "Neighborhood", "type": "text"}
          ],
          "items_page": {"cursor": null, "items": [
            {"id": "1", "name": "Casa Azul", "group": {"title": "For sale"}, "column_values": [
              {"id": "n1", "type": "numbers", "text": "90000", "value": null}
            ]}
          ]}
        }]}"""

    @Test
    fun theSyntheticNameColumnMakesTheSuggestedMappingCoverTheRequiredFields() {
        val page = InmobiliariaMondayClient.parseFirstPage(parse(boardJson), "55")
        assertEquals(listOf("Nombre", "Grupo", "Price", "Neighborhood"), page.columns.map { it.title })
        val property = MondayMapping.suggest("property", page.columns, propertyKeys)
        assertEquals("title", property["Nombre"])
        assertTrue(MondayMapping.missingRequired(propertyKind, property).isEmpty())
        // El mapeo sugerido pasa la validacion contra las columnas reales.
        assertEquals(property, MondayMapping.checkMapping(propertyKind, page.columns, property))
        val deal = MondayMapping.suggest("deal", page.columns, dealKeys)
        assertEquals("property", deal["Nombre"])
        assertTrue(MondayMapping.missingRequired(dealKind, deal).isEmpty())
        // Los valores de nombre y grupo del elemento son los de esas columnas.
        assertEquals("Casa Azul", page.items.single().values["Nombre"])
    }

    private fun row(id: Long, item: String) = MondayRow(id, item, MondayMapping.importKey(item), emptyMap(), MondayRowState.Pending, "")

    @Test
    fun assignsRowStatesByExactKeyFromTheFailedKeys() {
        val rows = listOf(row(1, "1"), row(2, "12"), row(3, "123"))
        val verdicts = MondayMapping.verdicts(
            rows,
            failedKeys = listOf("monday:12"),
            problems = listOf("monday:12: precio no valido", "monday:123: no es de esta fila fallida"),
            warnings = listOf("monday:1: agente no encontrado", "monday:123: aviso largo"),
        )
        assertEquals(listOf(MondayRowState.Applied, MondayRowState.Failed, MondayRowState.Applied), verdicts.map { it.state })
        // monday:1 no casa con monday:12 ni con monday:123.
        assertEquals("agente no encontrado", verdicts[0].text)
        assertEquals("precio no valido", verdicts[1].text)
        assertEquals("aviso largo", verdicts[2].text)
        // Fallida sin mensaje: texto por omision.
        assertEquals("No se pudo guardar el registro.", MondayMapping.verdicts(listOf(row(1, "9")), listOf("monday:9"), emptyList(), emptyList()).single().text)
        // Sin registros fallidos todo queda aplicado.
        assertTrue(MondayMapping.verdicts(rows, emptyList(), emptyList(), emptyList()).all { it.state == MondayRowState.Applied })
    }

    @Test
    fun fallsBackToTheSuggestedMappingWhenTheSentOneDoesNotMatchTheColumns() {
        val cols = columns("Nombre", "Price")
        val (sent, noNotice) = MondayMapping.mappingAfterRead(propertyKind, cols, mapOf("Price" to "price"))
        assertEquals(mapOf("Price" to "price"), sent)
        assertEquals("", noNotice)
        val (fallback, notice) = MondayMapping.mappingAfterRead(propertyKind, cols, mapOf("Columna que no existe" to "price"))
        assertEquals(mapOf("Nombre" to "title", "Price" to "price"), fallback)
        assertTrue(notice.isNotEmpty())
        assertEquals(fallback, MondayMapping.mappingAfterRead(propertyKind, cols, emptyMap()).first)
    }

    @Test
    fun decidesToFinishPauseOrKeepReading() {
        assertEquals(ReadStep.Finish, MondayMapping.readStep("", 0))
        assertEquals(ReadStep.Pause, MondayMapping.readStep("abc", MondayMapping.MinQueryMillis - 1))
        assertEquals(ReadStep.Continue, MondayMapping.readStep("abc", MondayMapping.MinQueryMillis))
    }

    @Test
    fun givesUpReadingAfterTheMaximumAttempts() {
        assertTrue(!MondayMapping.giveUpReading(MondayMapping.MaxReadAttempts - 1))
        assertTrue(MondayMapping.giveUpReading(MondayMapping.MaxReadAttempts))
    }

    @Test
    fun separatesTransientMondayErrorsFromRejectedTokens() {
        assertFailsWith<InmobiliariaMondayTransientException> { InmobiliariaMondayClient.parseResponse(429, "{}") }
        assertFailsWith<InmobiliariaMondayTransientException> { InmobiliariaMondayClient.parseResponse(503, "<html>") }
        assertFailsWith<InmobiliariaMondayTransientException> { InmobiliariaMondayClient.parseResponse(200, """{"errors":[{"message":"Rate limit exceeded"}]}""") }
        val rejected = assertFailsWith<InmobiliariaMondayException> { InmobiliariaMondayClient.parseResponse(401, "{}") }
        assertTrue(rejected !is InmobiliariaMondayTransientException)
        val query = assertFailsWith<InmobiliariaMondayException> { InmobiliariaMondayClient.parseResponse(200, """{"errors":[{"message":"Field x not found"}]}""") }
        assertTrue(query !is InmobiliariaMondayTransientException)
    }

    @Test
    fun httpTimeoutsNeverExceedTheRemainingBudget() {
        assertEquals(InmobiliariaMondayClient.Timeouts(30_000, 10_000, 60_000), InmobiliariaMondayClient.timeoutsFor(120_000))
        assertEquals(InmobiliariaMondayClient.Timeouts(8_000, 8_000, 8_000), InmobiliariaMondayClient.timeoutsFor(8_000))
        assertEquals(1_000L, InmobiliariaMondayClient.timeoutsFor(-5).totalMillis)
    }

    @Test
    fun capsTheLengthOfNamesAndColumnValuesWhenReading() {
        val cols = columns("Notas")
        val items = InmobiliariaMondayClient.parseItems(
            listOf(parse("""{"id": "5", "name": "${"n".repeat(400)}", "column_values": [{"id": "c0", "type": "text", "text": "${"x".repeat(6000)}", "value": null}]}""")),
            cols,
        )
        val values = items.single().values
        assertEquals(MondayMapping.MaxNameChars, values[MondayMapping.NameColumn]!!.length)
        assertEquals(MondayMapping.MaxValueChars, values["Notas"]!!.length)
        assertEquals("abc", MondayMapping.clip("abc", 10))
    }

    // ---- Pantalla ----

    @Test
    fun readsTheMappingFromTheDropdownsOfTheForm() {
        val job = MondayJob(
            UUID.randomUUID(), "1", "Tablero", "property", false, emptyMap(), columns("Nombre", "Price", "Notas"), MondayJobState.Ready,
            true, "", 3, 0, 0, 0, 0, emptyList(), "", null, "",
        )
        val mapping = InmobiliariaMondayScreens.mappingFrom(
            job, mapOf("c0" to "title * [title]", "c1" to "price [price]", "c2" to "(no importar)"),
        )
        assertEquals(mapOf("Nombre" to "title", "Price" to "price"), mapping)
    }
}
