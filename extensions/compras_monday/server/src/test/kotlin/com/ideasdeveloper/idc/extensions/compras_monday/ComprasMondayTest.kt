package com.ideasdeveloper.idc.extensions.compras_monday

import com.ideasdeveloper.idc.modules.compras.Priority
import com.ideasdeveloper.idc.modules.compras.RequestStatus
import com.ideasdeveloper.idc.server.extensions.UninstallDefault
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ComprasMondayTest {
    private fun obj(text: String) = ComprasMondayParse.parseJson(text)!!

    private val itemJson = """
        {"id": "5551", "name": "Bomba de agua", "created_at": "2025-02-10T15:30:00Z",
         "group": {"id": "group_magrock", "title": "Magrock"},
         "column_values": [
           {"id": "status", "type": "status", "text": "Aprobado-en progreso", "value": null},
           {"id": "prio", "type": "status", "text": "Alta", "value": null},
           {"id": "price", "type": "numbers", "text": "1,234.5", "value": "\"1234.5\""},
           {"id": "quoted", "type": "people", "text": "Carlos Perez, Otro", "value": null},
           {"id": "supplier", "type": "text", "text": "Ferreteria Sol", "value": null},
           {"id": "date", "type": "date", "text": "2025-03-04", "value": null},
           {"id": "req", "type": "people", "text": "Ana Lopez", "value": null},
           {"id": "desc", "type": "long_text", "text": "Bomba para la cisterna", "value": null},
           {"id": "files", "type": "file", "text": "factura.pdf, foto.jpg",
            "value": "{\"files\":[{\"fileType\":\"ASSET\",\"assetId\":901,\"name\":\"factura.pdf\"},{\"fileType\":\"GOOGLE_DRIVE\",\"name\":\"x\"},{\"fileType\":\"ASSET\",\"assetId\":902,\"name\":\"foto.jpg\"}]}"}
         ]}
    """.trimIndent()

    private val mapping = mapOf(
        MondayFields.Status to "status", MondayFields.Priority to "prio", MondayFields.Amount to "price",
        MondayFields.QuotedBy to "quoted", MondayFields.Supplier to "supplier", MondayFields.RequiredDate to "date",
        MondayFields.Requester to "req", MondayFields.Description to "desc", MondayFields.Files to "files",
        MondayFields.Currency to "usd",
    )

    private fun item() = ComprasMondayParse.parseItem(obj(itemJson))!!

    // ---- Estados y prioridades ----

    @Test
    fun mapsMondayStatusesIgnoringCaseAndAccents() {
        assertEquals(RequestStatus.PendingApproval to null, ComprasMondayMapping.mapStatus("Pending"))
        assertEquals(RequestStatus.PendingApproval to null, ComprasMondayMapping.mapStatus("Pendiente de Aprobación"))
        assertEquals(RequestStatus.Approved to null, ComprasMondayMapping.mapStatus("APPROVED"))
        assertEquals(RequestStatus.InProgress to null, ComprasMondayMapping.mapStatus("Aprobado-en progreso"))
        assertEquals(RequestStatus.InProgress to null, ComprasMondayMapping.mapStatus("Working on it"))
        assertEquals(RequestStatus.Completed to null, ComprasMondayMapping.mapStatus("Done"))
        assertEquals(RequestStatus.Completed to null, ComprasMondayMapping.mapStatus("Completado"))
        assertEquals(RequestStatus.Rejected to null, ComprasMondayMapping.mapStatus("Rechazado"))
        assertEquals(RequestStatus.Quoting to null, ComprasMondayMapping.mapStatus("En cotización"))
        assertEquals(RequestStatus.Draft to null, ComprasMondayMapping.mapStatus("  "))
    }

    @Test
    fun unknownAndStuckStatusesComeWithAProblem() {
        val unknown = ComprasMondayMapping.mapStatus("Esperando al jefe")
        assertEquals(RequestStatus.Draft, unknown.first)
        assertEquals("Estado desconocido: Esperando al jefe", unknown.second)
        val stuck = ComprasMondayMapping.mapStatus("Stuck")
        assertEquals(RequestStatus.PendingApproval, stuck.first)
        assertNotNull(stuck.second)
    }

    @Test
    fun mapsPriorities() {
        assertEquals(Priority.Low to null, ComprasMondayMapping.mapPriority("Baja"))
        assertEquals(Priority.Medium to null, ComprasMondayMapping.mapPriority("medium"))
        assertEquals(Priority.High to null, ComprasMondayMapping.mapPriority("ALTA"))
        assertEquals(Priority.Critical to null, ComprasMondayMapping.mapPriority("Crítica"))
        assertEquals(Priority.Medium to null, ComprasMondayMapping.mapPriority(""))
        assertNotNull(ComprasMondayMapping.mapPriority("rarisima").second)
    }

    // ---- Columnas ----

    @Test
    fun suggestsColumnsByTitleAndType() {
        val columns = listOf(
            MondayColumn("name", "Nombre", "name"),
            MondayColumn("c_status", "Estado", "status"),
            MondayColumn("c_prio", "Prioridad", "status"),
            MondayColumn("c_dept", "Departamento", "dropdown"),
            MondayColumn("c_desc", "Descripción", "long_text"),
            MondayColumn("c_price", "Price", "numbers"),
            MondayColumn("c_date", "Fecha requerida", "date"),
            MondayColumn("c_req", "Solicitante", "people"),
            MondayColumn("c_files", "Cotización adjunta", "file"),
            MondayColumn("c_by", "Cotizado por", "people"),
            MondayColumn("c_sup", "Proveedor", "text"),
        )
        val suggestion = ComprasMondayMapping.suggest(columns)
        assertEquals("c_status", suggestion[MondayFields.Status])
        assertEquals("c_prio", suggestion[MondayFields.Priority])
        assertEquals("c_dept", suggestion[MondayFields.Department])
        assertEquals("c_desc", suggestion[MondayFields.Description])
        assertEquals("c_price", suggestion[MondayFields.Amount])
        assertEquals("c_date", suggestion[MondayFields.RequiredDate])
        assertEquals("c_req", suggestion[MondayFields.Requester])
        assertEquals("c_files", suggestion[MondayFields.Files])
        assertEquals("c_by", suggestion[MondayFields.QuotedBy])
        assertEquals("c_sup", suggestion[MondayFields.Supplier])
        // Una columna no se usa para dos campos.
        assertEquals(suggestion.values.size, suggestion.values.toSet().size)
    }

    @Test
    fun aBoardWithoutMatchingColumnsSuggestsNothing() {
        assertEquals(emptyMap(), ComprasMondayMapping.suggest(listOf(MondayColumn("x", "Cosas", "text"), MondayColumn("y", "Otras", "numbers"))))
    }

    @Test
    fun departmentComesFromTheBoardNameWhenThereIsNoColumn() {
        assertEquals("Hotel", ComprasMondayMapping.departmentFromBoard("Purchase & Project Requests - Hotel"))
        assertEquals("F&B", ComprasMondayMapping.departmentFromBoard("purchase & project requests - F&B"))
        assertEquals("Maintenance", ComprasMondayMapping.departmentFromBoard("Maintenance"))
        assertNull(ComprasMondayMapping.departmentFromBoard("Purchase & Project Requests -  "))
    }

    // ---- Montos ----

    @Test
    fun parsesMondayAmounts() {
        assertEquals(BigDecimal("1234.50") to null, ComprasMondayMapping.parseAmount("1,234.5"))
        assertEquals(BigDecimal("1234.50") to null, ComprasMondayMapping.parseAmount("$1,234.50"))
        assertEquals(BigDecimal("12.00") to null, ComprasMondayMapping.parseAmount("USD 12"))
        assertEquals(BigDecimal("12.00") to null, ComprasMondayMapping.parseAmount("12 usd"))
        assertEquals(BigDecimal("1234.00") to null, ComprasMondayMapping.parseAmount("1,234"))
        // Vacio: sin monto y sin aviso.
        assertEquals(null to null, ComprasMondayMapping.parseAmount(""))
    }

    @Test
    fun amountsThatAreZeroOrHaveForeignTextComeWithAProblem() {
        assertEquals(null to "El monto es 0", ComprasMondayMapping.parseAmount("0"))
        assertEquals(null to "El monto es 0", ComprasMondayMapping.parseAmount("0.00"))
        assertNotNull(ComprasMondayMapping.parseAmount("n/a").second)
        assertNotNull(ComprasMondayMapping.parseAmount("12 pendiente").second)
        assertNotNull(ComprasMondayMapping.parseAmount("-5").second)
        assertNotNull(ComprasMondayMapping.parseAmount("12.345").second)
        assertNull(ComprasMondayMapping.parseAmount("12.345").first)
    }

    @Test
    fun theApprovedAmountIsNotTheEstimateAndExactTitlesWin() {
        val columns = listOf(
            MondayColumn("approved", "Monto aprobado", "numbers"),
            MondayColumn("amount", "Monto", "numbers"),
        )
        assertEquals("amount", ComprasMondayMapping.suggest(columns)[MondayFields.Amount])
        // Solo con "Monto aprobado" no se sugiere nada para el estimado.
        assertNull(ComprasMondayMapping.suggest(columns.take(1))[MondayFields.Amount])
        // Una coincidencia parcial no le quita la columna a otro campo que la nombra exacta.
        val files = listOf(MondayColumn("f", "Cotizacion", "file"), MondayColumn("n", "Cotizacion", "numbers"))
        val suggestion = ComprasMondayMapping.suggest(files)
        assertEquals("n", suggestion[MondayFields.Amount])
        assertEquals("f", suggestion[MondayFields.Files])
    }

    @Test
    fun readFailuresRetryUntilTheLimitOrFailAtOnceWhenPermanent() {
        assertEquals("reading", ComprasMondayRetry.statusAfterFailure(1, retryable = true))
        assertEquals("reading", ComprasMondayRetry.statusAfterFailure(4, retryable = true))
        assertEquals("failed", ComprasMondayRetry.statusAfterFailure(5, retryable = true))
        assertEquals("failed", ComprasMondayRetry.statusAfterFailure(1, retryable = false))
        assertTrue(ComprasMondayRetry.MaxPages >= 200)
    }

    // ---- Elementos ----

    @Test
    fun readsAnItemOfMondayWithItsFiles() {
        val item = item()
        assertEquals("5551", item.id)
        assertEquals("group_magrock", item.groupId)
        assertEquals("1,234.5", item.columns["price"]?.text)
        // El enlace a Drive no es un archivo.
        assertEquals(listOf(MondayItemFile("901", "factura.pdf", "files"), MondayItemFile("902", "foto.jpg", "files")), item.files)
        // Ida y vuelta por la fila preparada.
        assertEquals(item, ComprasMondayParse.decode(ComprasMondayParse.encode(item)))
    }

    @Test
    fun anItemWithoutAValidIdIsSkipped() {
        assertNull(ComprasMondayParse.parseItem(obj("""{"id": "abc", "name": "x"}""")))
        assertNull(ComprasMondayParse.decode("no es json"))
    }

    @Test
    fun buildsTheRequestWithAChosenQuoteWhenQuotedByAndAmountExist() {
        val conversion = ComprasMondayMapping.convert(
            item(), "Purchase & Project Requests - Maintenance", mapping + (MondayFields.Currency to "usd"), "Quien importa",
        ) { null }
        val request = assertNotNull(conversion.request)
        assertEquals("monday:5551", request.key)
        assertEquals("Bomba de agua", request.title)
        assertEquals("Bomba para la cisterna", request.description)
        assertEquals("Maintenance", request.department)
        assertEquals("USD", request.currency)
        assertEquals(RequestStatus.InProgress, request.status)
        assertEquals(Priority.High, request.priority)
        assertEquals(LocalDate.parse("2025-03-04"), request.requiredDate)
        assertEquals("Ana Lopez", request.requesterName)
        assertEquals(Instant.parse("2025-02-10T15:30:00Z"), request.createdAt)
        // Con "cotizado por" el monto es una cotizacion elegida y no el estimado.
        assertNull(request.estimatedAmount)
        val quote = request.quotes.single()
        assertEquals("monday:5551:quote", quote.key)
        assertEquals("Ferreteria Sol", quote.supplier)
        assertEquals(BigDecimal("1234.50"), quote.amount)
        assertTrue(quote.selected)
        assertEquals("Carlos Perez", quote.quotedByName)
        assertEquals(listOf("monday:asset:901", "monday:asset:902"), quote.files.map { it.key })
        assertEquals(setOf("quote"), quote.files.map { it.kind }.toSet())
        assertEquals(listOf("application/pdf", "image/jpeg"), quote.files.map { it.contentType })
        assertTrue(request.files.isEmpty())
        assertTrue(conversion.problems.isEmpty())
    }

    @Test
    fun withoutQuotedByTheAmountIsTheEstimateAndFilesGoToTheRequest() {
        val noQuote = mapping - MondayFields.QuotedBy
        val request = assertNotNull(ComprasMondayMapping.convert(item(), "Hotel", noQuote, "Quien importa") { null }.request)
        assertEquals(BigDecimal("1234.50"), request.estimatedAmount)
        assertTrue(request.quotes.isEmpty())
        assertEquals(listOf("invoice", "support"), request.files.map { it.kind })
        // Sin columna de departamento, sale del nombre del tablero.
        assertEquals("Hotel", request.department)
    }

    @Test
    fun problemsAreReportedWithoutStoppingTheItem() {
        val item = ComprasMondayParse.parseItem(
            obj("""{"id": "7", "name": "Algo", "created_at": "", "group": {"id": "g", "title": "G"}, "column_values": [
                {"id": "status", "type": "status", "text": "Quien sabe"}, {"id": "price", "type": "numbers", "text": "12.345"},
                {"id": "date", "type": "date", "text": "manana"}]}"""),
        )!!
        val conversion = ComprasMondayMapping.convert(item, "Esc", mapping, "Quien importa") { null }
        val request = assertNotNull(conversion.request)
        assertEquals(RequestStatus.Draft, request.status)
        assertNull(request.estimatedAmount)
        assertNull(request.requiredDate)
        assertNull(request.createdAt)
        assertEquals("Quien importa", request.requesterName)
        assertEquals(3, conversion.problems.size)
        assertTrue(conversion.problems.any { it.startsWith("Estado desconocido") })
    }

    @Test
    fun anItemWithoutNameIsNotImported() {
        val item = item().copy(name = "  ")
        val conversion = ComprasMondayMapping.convert(item, "Hotel", mapping, "Quien importa") { null }
        assertNull(conversion.request)
        assertEquals(1, conversion.problems.size)
    }

    @Test
    fun rejectedItemsCarryAReason() {
        val rejected = item().copy(columns = item().columns + ("status" to MondayColumnValue("status", "Rechazado")))
        val request = assertNotNull(ComprasMondayMapping.convert(rejected, "Hotel", mapping, "x") { null }.request)
        assertEquals(RequestStatus.Rejected, request.status)
        assertNotNull(request.decisionReason)
    }

    // ---- Grupos y cursor ----

    @Test
    fun keepsOnlyTheChosenGroups() {
        fun at(id: String, group: String) = item().copy(id = id, groupId = group)
        val items = listOf(at("1", "magrock"), at("2", "jolla"), at("3", "escape"), at("4", "magrock"))
        assertEquals(listOf("1", "4"), ComprasMondayParse.filterByGroups(items, setOf("magrock")).map { it.id })
        assertEquals(listOf("2", "3"), ComprasMondayParse.filterByGroups(items, setOf("jolla", "escape")).map { it.id })
        assertTrue(ComprasMondayParse.filterByGroups(items, emptySet()).isEmpty())
    }

    @Test
    fun validatesTheCursorBeforeItGoesIntoAQuery() {
        assertTrue(ComprasMondayParse.isValidCursor("MSw0NTE2MzY0-_:=+/abc"))
        assertFalse(ComprasMondayParse.isValidCursor(null))
        assertFalse(ComprasMondayParse.isValidCursor(""))
        assertFalse(ComprasMondayParse.isValidCursor("abc\") { boards { id } } #"))
        assertFalse(ComprasMondayParse.isValidCursor("con espacio"))
        assertFalse(ComprasMondayParse.isValidCursor("a".repeat(5000)))
    }

    @Test
    fun validatesIds() {
        assertTrue(ComprasMondayParse.isBoardId("123456"))
        assertFalse(ComprasMondayParse.isBoardId("12a"))
        assertFalse(ComprasMondayParse.isBoardId("1) { x }"))
        assertTrue(ComprasMondayParse.isKeyId("new_group-12"))
        assertFalse(ComprasMondayParse.isKeyId("a b"))
    }

    @Test
    fun readsPagesAndBoards() {
        val page = ComprasMondayParse.parsePage(obj("""{"cursor": "abc123", "items": [$itemJson]}"""))
        assertEquals(1, page.items.size)
        assertEquals("abc123", page.cursor)
        assertNull(ComprasMondayParse.parsePage(obj("""{"cursor": null, "items": []}""")).cursor)
        val boards = ComprasMondayParse.parseBoards(
            obj("""{"boards": [{"id": "10", "name": "A", "type": "board", "items_count": 3, "workspace": {"name": "Magrock"}},
                {"id": "11", "name": "Sub", "type": "sub_items_board"}, {"id": "x", "name": "Sin id", "type": "board"}]}"""),
        )
        assertEquals(listOf(MondayBoard("10", "A", "Magrock", 3)), boards)
        val info = ComprasMondayParse.parseBoardInfo(
            obj("""{"boards": [{"name": "Hotel", "columns": [{"id": "c1", "title": "Estado", "type": "status"}], "groups": [{"id": "g1", "title": "Magrock"}]}]}"""), "10",
        )!!
        assertEquals(listOf(MondayColumn("c1", "Estado", "status")), info.columns)
        assertEquals(listOf(MondayGroup("g1", "Magrock")), info.groups)
    }

    @Test
    fun mappingAndListsSurviveJsonb() {
        assertEquals(mapping, ComprasMondayParse.decodeMap(ComprasMondayParse.encodeMap(mapping)))
        assertEquals(listOf("a", "b"), ComprasMondayParse.decodeList(ComprasMondayParse.encodeList(listOf("a", "b"))))
        assertEquals(emptyMap(), ComprasMondayParse.decodeMap(null))
        assertEquals(emptyList(), ComprasMondayParse.decodeList("no es json"))
    }

    @Test
    fun readsTheColumnOptionIdOfTheForm() {
        assertEquals("c_price", ComprasMondayImport.optionIdOf("Price (c_price)"))
        assertEquals("g1", ComprasMondayImport.optionIdOf("La Jolla (propiedad) (g1)"))
        assertNull(ComprasMondayImport.optionIdOf("(ninguna)"))
        assertNull(ComprasMondayImport.optionIdOf("sin id"))
    }

    // ---- Extension ----

    @Test
    fun theManifestNeedsComprasWithImportsAndDeclaresItsPermissions() {
        val extension = ComprasMondayServerExtension
        val manifest = extension.manifest
        assertEquals("compras_monday", manifest.id)
        assertEquals("compras", manifest.module)
        assertEquals("1.0.0", manifest.version)
        assertEquals("0.2.0", manifest.minModuleVersion)
        assertEquals(listOf("network.outbound:api.monday.com", "secrets.write", "compras.requests.import"), manifest.permissions)
        assertEquals(listOf("import_history" to UninstallDefault.Delete), manifest.uninstall.options.map { it.id to it.default })
        assertEquals("monday", extension.id)
        assertEquals("compras_monday", extension.extensionId)
        assertEquals(listOf("extensions/compras_monday/migrations/V001__staging.sql"), extension.migrationPaths)
        assertEquals(
            listOf("ext_compras_monday__settings", "ext_compras_monday__jobs", "ext_compras_monday__rows"),
            extension.runtimeTables,
        )
        // Toda tabla lleva el prefijo de la extension.
        assertTrue(extension.runtimeTables.all { it.startsWith("ext_compras_monday__") })
    }

    @Test
    fun theAgentToolsNeedTheImportPermissionAndNeverTakeTheToken() {
        val tools = ComprasMondayServerExtension.mcpTools
        assertEquals(listOf("monday_list_boards", "monday_start_import", "monday_import_status", "monday_apply_import"), tools.map { it.name })
        assertTrue(tools.all { it.permission == "compras.import" })
        assertTrue(tools.all { "compras_${it.name}".length <= 64 })
        assertEquals(setOf("monday_list_boards", "monday_import_status"), tools.filter { it.readOnly }.map { it.name }.toSet())
        // Ninguna herramienta recibe credenciales.
        assertTrue(tools.none { tool -> tool.inputSchema.toString().contains("token", ignoreCase = true) })
    }
}
