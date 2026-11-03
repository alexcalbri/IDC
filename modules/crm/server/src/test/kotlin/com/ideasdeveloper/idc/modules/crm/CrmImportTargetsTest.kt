package com.ideasdeveloper.idc.modules.crm

import com.ideasdeveloper.idc.ui.UiFieldValues
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class CrmImportTargetsTest {
    private val budget = CrmField(UUID.randomUUID(), "cf_presupuesto", "Presupuesto", CrmFieldType.Number)
    private val cedula = CustomerField("cedula", "Cedula", "text", emptyList())

    @Test
    fun columnsWithoutFieldAreProposedByWhatTheyHold() {
        // Same name as an existing field: that field.
        assertEquals("crm:cf_presupuesto", CrmImportTargets.propose("presupuesto", "numbers", true, listOf(budget), emptyList()))
        assertEquals("cliente:cedula", CrmImportTargets.propose("Cedula", "text", true, emptyList(), listOf(cedula)))
        // Personal data goes to the customer, the rest to the CRM, files to the lead's documents, empty columns nowhere.
        assertEquals(CrmImportTargets.NewCustomer, CrmImportTargets.propose("Document ID", "text", true, emptyList(), emptyList()))
        assertEquals(CrmImportTargets.NewCustomer, CrmImportTargets.propose("Fecha de nacimiento", "date", true, emptyList(), emptyList()))
        assertEquals(CrmImportTargets.NewCrm, CrmImportTargets.propose("Tipo de propiedad", "status", true, emptyList(), emptyList()))
        assertEquals(CrmImportTargets.Files, CrmImportTargets.propose("Contratos", "file", true, emptyList(), emptyList()))
        assertEquals("", CrmImportTargets.propose("Notas viejas", "text", false, emptyList(), emptyList()))
    }

    @Test
    fun mondayTypesBecomeCrmTypes() {
        assertEquals(CrmFieldType.Dropdown to false, CrmImportTargets.crmType("status"))
        assertEquals(CrmFieldType.Dropdown to true, CrmImportTargets.crmType("dropdown"))
        assertEquals(CrmFieldType.Boolean to false, CrmImportTargets.crmType("checkbox"))
        assertEquals(CrmFieldType.Text to false, CrmImportTargets.crmType("people"))
        assertEquals(CrmFieldType.Text to false, CrmImportTargets.crmType(null))
    }

    @Test
    fun mondayTextsAreConvertedToFormValues() {
        assertEquals("true", CrmImportTargets.formValue("v", CrmFieldType.Boolean, false))
        assertEquals("false", CrmImportTargets.formValue("no", CrmFieldType.Boolean, false))
        assertEquals(UiFieldValues.options(listOf("Casa", "Lote")), CrmImportTargets.formValue("Casa, Lote", CrmFieldType.Dropdown, true))
        assertEquals("https://ejemplo.com/a", CrmImportTargets.formValue("Sitio - https://ejemplo.com/a", CrmFieldType.Url, false))
        assertEquals("2024-05-01", CrmImportTargets.formValue("2024-05-01 10:30", CrmFieldType.Date, false))
        assertEquals("1500.50", CrmImportTargets.formValue("1,500.50", CrmFieldType.Number, false))
        assertEquals("1500.50", CrmImportTargets.number("1.500,50"))
        assertEquals("1500.5", CrmImportTargets.number("1500,5"))
        assertEquals("1500", CrmImportTargets.number("$ 1,500"))
        assertEquals("1500000", CrmImportTargets.number("1.500.000"))
        assertEquals("", CrmImportTargets.formValue("   ", CrmFieldType.Text, false))
    }

    @Test
    fun customerValuesAreTyped() {
        assertEquals(JsonPrimitive("8-123-456"), CrmImportTargets.customerValue("8-123-456", cedula))
        assertEquals(JsonPrimitive(true), CrmImportTargets.customerValue("v", cedula.copy(type = "boolean")))
        assertEquals(JsonArray(listOf(JsonPrimitive("a"), JsonPrimitive("b"))), CrmImportTargets.customerValue("a, b", cedula.copy(type = "multi_select")))
        assertNull(CrmImportTargets.customerValue("mucho", cedula.copy(type = "number")))
        assertNull(CrmImportTargets.customerValue("", cedula))
    }

    @Test
    fun mondayFileColumnsKeepOnlyTheirAssets() {
        val value = """{"files":[{"fileType":"ASSET","assetId":123,"name":"contrato.pdf"},{"fileType":"GOOGLE_DRIVE","linkToFile":"https://drive","name":"x"}]}"""
        val files = CrmMondayFiles.fromColumnValue("Contratos", value)
        assertEquals(listOf(CrmMondayFile("123", "contrato.pdf", column = "Contratos")), files)
        // Encoded with the row and read back when applying it.
        val update = CrmMondayFile("456", "foto.jpg", update = "2024-05-01")
        assertEquals(files + update, CrmMondayFiles.decode(CrmMondayFiles.encode(files + update)))
        assertEquals(emptyList(), CrmMondayFiles.fromColumnValue("Contratos", "no es json"))
    }
}
