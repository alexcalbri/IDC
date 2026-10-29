package com.ideasdeveloper.idc.modules.crm

import java.io.ByteArrayOutputStream
import java.time.LocalDate
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CrmImportTest {
    @Test
    fun readsCsvWithQuotesSemicolonsAndBom() {
        val table = CrmImportParse.csv("﻿Nombre;Correo;Notas\r\nAna;ana@gmial.com;\"linea 1\nlinea \"\"2\"\"\"\r\n;;\r\nLuis;;sin correo\r\n")
        assertEquals(listOf("Nombre", "Correo", "Notas"), table.columns)
        assertEquals(2, table.rows.size)
        assertEquals("linea 1\nlinea \"2\"", table.rows[0]["Notas"])
    }

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
        val boards = CrmImport.parseMondayBoards(data)
        assertEquals(
            listOf(CrmMondayBoard("101", "Surf Camps", "Magrock", 42), CrmMondayBoard("104", "Yoga", CrmImport.MainWorkspace, null)),
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
        val table = CrmImportParse.table(records)
        assertEquals(listOf("Name", "Leads Status", "Email", CrmImportParse.GroupColumn), table.columns)
        assertEquals(listOf("Answered", "Booked/Converted"), table.rows.map { it[CrmImportParse.GroupColumn] })
        assertEquals("Jason Weiss", table.rows[1]["Name"])
    }

    @Test
    fun guessesColumnsOfMondayAndLeadsPro() {
        val monday = CrmImportParse.guess(listOf("Name", "Involved", "Message", "Inquiring", "Notes", "Leads Date", "Follow up", "Booking Date", "Leads Status", "Phone", "Email", "Idioma", "Referrals", CrmImportParse.GroupColumn))
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
        assertEquals("", monday[CrmImportParse.GroupColumn])
        val withoutStatus = CrmImportParse.guess(listOf("Contact", "Email", CrmImportParse.GroupColumn))
        assertEquals("stage", withoutStatus[CrmImportParse.GroupColumn])
    }

    @Test
    fun parsesDatesAndRanges() {
        assertEquals(LocalDate.of(2026, 10, 27), CrmImportParse.date("2026-10-27 14:05:00"))
        assertEquals(LocalDate.of(2026, 3, 22), CrmImportParse.date("22/03/2026"))
        assertEquals(LocalDate.of(2026, 3, 22), CrmImportParse.date("Mar 22, 2026"))
        assertEquals(LocalDate.of(2024, 1, 1), CrmImportParse.date("45292"))
        assertNull(CrmImportParse.date("pronto"))
        assertEquals(LocalDate.of(2026, 10, 25) to LocalDate.of(2026, 11, 3), CrmImportParse.range("Oct 25 - Nov 3", 2026))
        assertEquals(LocalDate.of(2026, 12, 28) to LocalDate.of(2027, 1, 4), CrmImportParse.range("2026-12-28 - 2027-01-04", 2026))
    }

    @Test
    fun readsXlsxSharedStrings() {
        fun zip(vararg entries: Pair<String, String>) = ByteArrayOutputStream().also { output ->
            ZipOutputStream(output).use { zip -> entries.forEach { (name, text) -> zip.putNextEntry(ZipEntry(name)); zip.write(text.toByteArray()); zip.closeEntry() } }
        }.toByteArray()
        val bytes = zip(
            "xl/workbook.xml" to """<workbook xmlns:r="r"><sheets><sheet name="Leads" sheetId="1" r:id="rId1"/></sheets></workbook>""",
            "xl/_rels/workbook.xml.rels" to """<Relationships><Relationship Id="rId1" Target="worksheets/sheet1.xml"/></Relationships>""",
            "xl/sharedStrings.xml" to """<sst><si><t>Nombre</t></si><si><t>Correo</t></si><si><t>Ana</t></si></sst>""",
            "xl/worksheets/sheet1.xml" to """<worksheet><sheetData><row r="1"><c r="A1" t="s"><v>0</v></c><c r="B1" t="s"><v>1</v></c></row><row r="2"><c r="A2" t="s"><v>2</v></c><c r="C2"><v>5</v></c></row></sheetData></worksheet>""",
        )
        val table = CrmImportParse.xlsx(bytes)
        assertEquals(listOf("Nombre", "Correo"), table.columns)
        assertEquals("Ana", table.rows.single()["Nombre"])
        // A DTD is refused (XML entity expansion).
        val evil = zip("xl/worksheets/sheet1.xml" to """<!DOCTYPE x [<!ENTITY a "aaaa">]><worksheet><sheetData/></worksheet>""")
        assertFailsWith<CrmImportException> { CrmImportParse.xlsx(evil) }
    }

    @Test
    fun mapsRowValuesWithValueMaps() {
        val stages = listOf(CrmStage(UUID.randomUUID(), "Nuevo", StageKind.Open, StageTone.Info, 0), CrmStage(UUID.randomUUID(), "Reservado", StageKind.Won, StageTone.Success, 1))
        val surf = CrmInterest(UUID.randomUUID(), "Surf", 0)
        val ana = CrmUser(UUID.randomUUID(), "ana")
        val context = CrmImport.Context(stages, listOf(surf), listOf(ana), LocalDate.of(2026, 10, 28), "file")
        val mapping = CrmImportMapping(
            columns = mapOf("Name" to "name", "Email" to "email", "Phone" to "phone", "Leads Status" to "stage", "Involved" to "owner", "Tablero" to "interest", "Booking Date" to "dates", "Notes" to "details"),
            stages = mapOf("Booked" to "Reservado"),
            owners = mapOf("BF" to "ana"),
            interests = mapOf("Magrock Lead Surf Camps" to "Surf"),
        )
        val row = mapOf(
            "Name" to "Jason Weiss", "Email" to "JASON@gmail.co", "Phone" to "(202) 555-0123", "Leads Status" to "Booked", "Involved" to "BF",
            "Tablero" to "Magrock Lead Surf Camps", "Booking Date" to "Dec 25 - Jan 3", "Notes" to "2 ppl, LIBERIA",
        )
        val values = CrmImport.values(row, mapping, context)
        assertEquals("JASON@gmail.com", values.email)
        assertEquals("+12025550123", values.phone)
        assertEquals("Reservado", values.stage?.name)
        assertEquals(ana.id, values.ownerId)
        assertEquals(surf.id, values.interestId)
        assertEquals("2026-12-25", values.fields["targetStart"])
        assertEquals("2027-01-03", values.fields["targetEnd"])
        assertTrue(values.key.startsWith("file:"))
        // The same person gets the same key, so a second import updates the lead.
        assertEquals(values.key, CrmImport.values(row + ("Notes" to "otro"), mapping, context).key)
        assertEquals("monday:42", CrmImport.values(row + (CrmImport.KeyColumn to "monday:42"), mapping, context).key)
    }
}
