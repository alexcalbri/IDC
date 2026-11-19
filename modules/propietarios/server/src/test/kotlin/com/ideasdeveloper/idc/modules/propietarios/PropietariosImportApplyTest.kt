package com.ideasdeveloper.idc.modules.propietarios

import java.lang.reflect.Proxy
import java.math.BigDecimal
import java.sql.Connection
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Reglas puras de la importacion: vista previa, claves estables (idempotencia) y lo que se omite antes de tocar la base de datos. */
class PropietariosImportApplyTest {
    // Una conexion que falla ante cualquier uso: las reglas que validan antes de la base de datos no deben llegar a ella.
    private val noDatabase: Connection = Proxy.newProxyInstance(javaClass.classLoader, arrayOf(Connection::class.java)) { _, method, _ ->
        throw AssertionError("No debe usar la base de datos: ${method.name}")
    } as Connection

    private fun owner(name: String, email: String = "", share: String? = null) = ImportedOwner(name, email, sharePercent = share?.let { BigDecimal(it) })

    private fun row(key: String, lot: String = "L-$key", development: String? = "Valle", area: String? = "100.50", vararg owners: ImportedOwner) =
        ImportedLot(key = key, lotNumber = lot, developmentName = development, areaM2 = area?.let { BigDecimal(it) }, owners = owners.toList())

    @Test
    fun summaryCountsLotsDevelopmentsOwnersAndArea() {
        val rows = listOf(
            row("1", "10", "Valle", "100.50", owner("Ana", "ana@x.com"), owner("Luis")),
            row("2", "11", "valle", "200", owner("ANA", "Ana@x.com")),
            row("3", "12", "Bosque", null, owner("Luis")),
        )
        val summary = PropietariosImportApply.summary(rows)
        assertEquals(3, summary.lots)
        assertEquals(listOf("Valle", "Bosque"), summary.developments)
        // Ana (por correo, una sola vez) y Luis (por nombre, una sola vez).
        assertEquals(2, summary.owners)
        assertEquals(listOf("Luis"), summary.ownersWithoutEmail)
        assertEquals(0, BigDecimal("300.50").compareTo(summary.totalAreaM2))
        assertTrue(summary.problems.isEmpty())
        assertTrue(summary.duplicateLots.isEmpty())
    }

    @Test
    fun summaryReportsRowsWithoutKeyOrLotNumberAndRepeatedKeys() {
        val rows = listOf(row("1"), row("", "5"), row("2", "  "), row("1", "9"))
        val summary = PropietariosImportApply.summary(rows)
        assertEquals(1, summary.lots)
        assertEquals(3, summary.problems.size)
        assertTrue(summary.problems.any { "identificador" in it })
        assertTrue(summary.problems.any { "número de lote" in it })
        assertTrue(summary.problems.any { "más de una vez" in it })
    }

    @Test
    fun summaryFlagsDuplicateLotNumbersInTheSameDevelopment() {
        val rows = listOf(row("1", "7", "Valle"), row("2", "7", "VALLE"), row("3", "7", "Bosque"))
        val summary = PropietariosImportApply.summary(rows)
        assertEquals(listOf("Valle / 7"), summary.duplicateLots)
    }

    @Test
    fun summaryWarnsAboutSharesOverHundredAndBadAreas() {
        val rows = listOf(
            row("1", "1", "Valle", "100.50", owner("Ana", "a@x.com", "70"), owner("Luis", "l@x.com", "60")),
            row("2", "2", area = "-5"),
        )
        val summary = PropietariosImportApply.summary(rows)
        assertTrue(summary.problems.any { "porcentajes" in it && "1" in it })
        assertTrue(summary.problems.any { "área" in it })
        // El area negativa no suma.
        assertEquals(0, BigDecimal("100.50").compareTo(summary.totalAreaM2))
    }

    @Test
    fun ownerIdentityPrefersEmailThenName() {
        assertEquals("ana@x.com", PropietariosImportApply.ownerIdentity(ImportedOwner("Ana", " Ana@X.com ")))
        assertEquals("ana pérez", PropietariosImportApply.ownerIdentity(ImportedOwner(" Ana Pérez ")))
        assertEquals("", PropietariosImportApply.ownerIdentity(ImportedOwner(" ")))
    }

    @Test
    fun ownershipKeyIsStableCaseInsensitiveAndFitsTheColumn() {
        val first = PropietariosImportApply.ownerKey("item:1", "ana@x.com")
        assertEquals("item:1:owner:ana@x.com", first)
        assertEquals(first, PropietariosImportApply.ownerKey(" item:1 ", "ANA@x.com"))
        val long = PropietariosImportApply.ownerKey("k".repeat(150), "n".repeat(300))
        assertTrue(long.length <= 200)
        assertEquals(long, PropietariosImportApply.ownerKey("k".repeat(150), "n".repeat(300)))
        // Dos nombres largos distintos no chocan por el acortado.
        assertTrue(long != PropietariosImportApply.ownerKey("k".repeat(150), "n".repeat(299) + "x"))
    }

    @Test
    fun developmentKeyIsLowercaseAndBounded() {
        assertEquals("name:valle verde", PropietariosImportApply.developmentKey(" Valle Verde "))
        assertTrue(PropietariosImportApply.developmentKey("V".repeat(500)).length <= 200)
    }

    @Test
    fun emailValidation() {
        assertTrue(PropietariosImportApply.validEmail("a@b.co"))
        assertTrue(!PropietariosImportApply.validEmail("sin arroba"))
        assertTrue(!PropietariosImportApply.validEmail("a @b.co"))
        assertTrue(!PropietariosImportApply.validEmail(""))
    }

    @Test
    fun rowsWithoutKeyOrLotNumberAreSkippedBeforeTouchingTheDatabase() {
        assertTrue("identificador" in PropietariosImportApply.skipReason(row(" ")).orEmpty())
        assertTrue("número de lote" in PropietariosImportApply.skipReason(row("1", "  ")).orEmpty())
        assertTrue("demasiado largo" in PropietariosImportApply.skipReason(row("k".repeat(201))).orEmpty())
        assertNull(PropietariosImportApply.skipReason(row("1", "7")))
    }

    @Test
    fun theOutcomesHaveTheContractShape() {
        val id = UUID.randomUUID()
        assertEquals(id, (LotImportOutcome.Created(id) as LotImportOutcome.Created).lotId)
        assertIs<LotImportOutcome>(LotImportOutcome.Updated(id))
        assertEquals("x", LotImportOutcome.Skipped("x").reason)
    }

    @Test
    fun ensureDevelopmentValidatesItsKeyBeforeTheDatabase() {
        assertFailsWith<PropietariosValidationException> { PropietariosImportApply.ensureDevelopment(noDatabase, " ", "Valle", null, null) }
        assertFailsWith<PropietariosValidationException> { PropietariosImportApply.ensureDevelopment(noDatabase, "k".repeat(201), "Valle", null, null) }
    }
}
