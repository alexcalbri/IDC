package com.ideasdeveloper.idc.extensions.monday

import com.ideasdeveloper.idc.modules.crm.CrmFieldType
import kotlin.test.Test
import kotlin.test.assertEquals

class MondayImportTest {
    @Test
    fun listsMondayBoardsWithTheirWorkspaceAndSkipsSubitemBoards() {
        val data = kotlinx.serialization.json.Json.parseToJsonElement(
            """{"boards": [
                {"id": "101", "name": "Surf Camps", "type": "board", "items_count": 42, "workspace": {"name": "Magrock"}},
                {"id": "102", "name": "Subelementos de Surf Camps", "type": "sub_items_board", "workspace": {"name": "Magrock"}},
                {"id": "103", "name": "Notas", "type": "document", "workspace": null},
                {"id": "104", "name": "Yoga", "type": "board", "items_count": null, "workspace": null},
                {"id": "x", "name": "Sin id valido", "type": "board"}
            ]}""",
        ) as kotlinx.serialization.json.JsonObject
        val boards = MondayImport.parseMondayBoards(data)
        assertEquals(
            listOf(MondayBoard("101", "Surf Camps", "Magrock", 42), MondayBoard("104", "Yoga", MondayImport.MainWorkspace, null)),
            boards,
        )
    }

    @Test
    fun recognizesTheMondayExportLayout() {
        val records = listOf(
            listOf("Magrock Lead Surf Camps", "", ""),
            listOf("Answered", "", ""),
            listOf("Name", "Leads Status", "Email"),
            listOf("Louise Corbeil", "Answered", "louise@x.com"),
            listOf("Booked/Converted", "", ""),
            listOf("Name", "Leads Status", "Email"),
            listOf("Jason Weiss", "Booked", "jason@x.com"),
        )
        val table = MondayImport.table(records)
        assertEquals(listOf("Name", "Leads Status", "Email", MondayImport.GroupColumn), table.columns)
        assertEquals(listOf("Answered", "Booked/Converted"), table.rows.map { it[MondayImport.GroupColumn] })
        assertEquals("Jason Weiss", table.rows[1]["Name"])
    }

    @Test
    fun guessesColumnsOfMonday() {
        val monday = MondayImport.guess(listOf("Name", "Involved", "Message", "Inquiring", "Notes", "Leads Date", "Follow up", "Booking Date", "Leads Status", "Phone", "Email", "Idioma", "Referrals", MondayImport.GroupColumn))
        assertEquals("name", monday["Name"])
        assertEquals("owner", monday["Involved"])
        assertEquals("interest", monday["Inquiring"])
        assertEquals("details", monday["Notes"])
        assertEquals("createdAt", monday["Leads Date"])
        assertEquals("followUpOn", monday["Follow up"])
        assertEquals("dates", monday["Booking Date"])
        assertEquals("stage", monday["Leads Status"])
        assertEquals("source", monday["Referrals"])
        assertEquals("language", monday["Idioma"])
        // The status column is the stage, so the group is not used.
        assertEquals("", monday[MondayImport.GroupColumn])
        val withoutStatus = MondayImport.guess(listOf("Contact", "Email", MondayImport.GroupColumn))
        assertEquals("stage", withoutStatus[MondayImport.GroupColumn])
    }

    @Test
    fun mondayTypesBecomeCrmTypes() {
        assertEquals(CrmFieldType.Dropdown to false, MondayImport.crmType("status"))
        assertEquals(CrmFieldType.Dropdown to true, MondayImport.crmType("dropdown"))
        assertEquals(CrmFieldType.Boolean to false, MondayImport.crmType("checkbox"))
        assertEquals(CrmFieldType.Text to false, MondayImport.crmType("people"))
        assertEquals(CrmFieldType.Text to false, MondayImport.crmType(null))
    }

    @Test
    fun mondayFileColumnsKeepOnlyTheirAssets() {
        val value = """{"files":[{"fileType":"ASSET","assetId":123,"name":"contrato.pdf"},{"fileType":"GOOGLE_DRIVE","linkToFile":"https://drive","name":"x"}]}"""
        val files = MondayFiles.fromColumnValue("Contratos", value)
        assertEquals(listOf(MondayFile("123", "contrato.pdf", column = "Contratos")), files)
        // Encoded with the row and read back when applying it.
        val update = MondayFile("456", "foto.jpg", update = "2024-05-01")
        assertEquals(files + update, MondayFiles.decode(MondayFiles.encode(files + update)))
        assertEquals(emptyList(), MondayFiles.fromColumnValue("Contratos", "no es json"))
    }

    @Test
    fun theExtensionIsAnImportSourceWithItsOwnManifest() {
        val extension = MondayServerExtension
        assertEquals("monday", extension.manifest.id)
        assertEquals("monday", extension.id)
        assertEquals(listOf("extensions/monday/migrations/V001__settings.sql"), extension.migrationPaths)
        assertEquals(listOf("ext_monday__settings"), extension.runtimeTables)
        assertEquals(listOf("list_monday_boards", "import_monday"), extension.mcpTools.map { it.name })
    }
}
