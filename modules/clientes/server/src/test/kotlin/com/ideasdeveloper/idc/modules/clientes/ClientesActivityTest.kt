package com.ideasdeveloper.idc.modules.clientes

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ClientesActivityTest {
    @Test
    fun everyKindHasItsOwnTitle() {
        val kinds = ClientesActivity.customerKinds + ClientesActivity.companyKinds
        val titles = kinds.filter { it != "updated" }.map { ClientesActivity.title(it) }
        assertEquals(titles.size, titles.toSet().size)
        assertEquals("Datos del cliente modificados", ClientesActivity.title("updated"))
        assertEquals("Nota borrada", ClientesActivity.title("note_deleted"))
        assertEquals("Entrada del historial borrada", ClientesActivity.title("history_deleted"))
    }

    @Test
    fun customerAndCompanyKindsDoNotOverlap() {
        assertTrue((ClientesActivity.customerKinds intersect ClientesActivity.companyKinds).isEmpty())
    }

    // The kinds the code writes must be exactly the ones the CHECK of V008 accepts.
    @Test
    fun kindsMatchTheCheckOfMigrationV008() {
        var dir: java.io.File? = java.io.File("").absoluteFile
        var migration: java.io.File? = null
        while (dir != null && migration == null) {
            migration = java.io.File(dir, "modules/clientes/migrations/V008__company_links_and_merges.sql").takeIf { it.exists() }
            dir = dir.parentFile
        }
        val text = checkNotNull(migration) { "No se encontro V008 subiendo desde el directorio de pruebas." }.readText()
        val check = text.substringAfter("customer_activity_kind_check CHECK (kind IN (").substringBefore("));")
        val inMigration = Regex("'([a-z_]+)'").findAll(check).map { it.groupValues[1] }.toSet()
        assertEquals(inMigration, ClientesActivity.customerKinds + ClientesActivity.companyKinds)
    }

    @Test
    fun historyRowsOfHiddenModulesAreFilteredAndEachModuleIsAskedOnce() {
        val asked = mutableListOf<String>()
        val rows = listOf("crm", "inmobiliaria", "crm", "clientes", "crm")
        val kept = ClientesActivity.filterByModule(rows, { it }) { moduleId -> asked += moduleId; moduleId != "inmobiliaria" }
        assertEquals(listOf("crm", "crm", "clientes", "crm"), kept)
        assertEquals(3, asked.size)
        assertFalse("inmobiliaria" in kept)
    }

    @Test
    fun deletedNotesAndEntriesKeepTheirText() {
        assertEquals("Nota borrada: alergico al mani", ClientesProfile.noteDeletedBody("  alergico al mani "))
        assertEquals("Entrada borrada del historial: Paquete de 3 noches", ClientesProfile.historyDeletedBody("Paquete de 3 noches"))
    }
}
