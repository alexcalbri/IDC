package com.ideasdeveloper.idc.server.modules

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CompanyFilesTest {
    private val root = Files.createTempDirectory("company-files")
    private val files = CompanyFiles.local(root)

    @Test
    fun storesReadsAndDeletesUnderTheCompanyAndModuleFolder() {
        val module = files.module("acme", "crm")
        val stored = module.put("../../contrato final.pdf", "application/pdf", "hola".byteInputStream(), maxBytes = 100)

        assertEquals("local", stored.backend)
        assertEquals(4, stored.size)
        // The user's name never reaches the disk.
        assertTrue(Files.isRegularFile(root.resolve("acme/crm").resolve(stored.ref)))
        assertContentEquals("hola".toByteArray(), module.open(stored)!!.use { it.readBytes() })

        module.delete(stored)
        assertNull(module.open(stored))
        module.delete(stored)
    }

    @Test
    fun anOversizeFileLeavesNothingBehind() {
        val module = files.module("acme", "crm")
        assertFailsWith<FileTooLargeException> { module.put("grande.bin", "", ByteArray(50).inputStream(), maxBytes = 10) }
        val left = Files.walk(root).use { paths -> paths.filter { Files.isRegularFile(it) }.count() }
        assertEquals(0, left)
    }

    @Test
    fun refsCannotLeaveTheModuleFolder() {
        val module = files.module("acme", "crm")
        assertFailsWith<IllegalArgumentException> { module.open(StoredFileRef("local", "../../otra/2026/01/x", 0)) }
        assertFailsWith<IllegalArgumentException> { files.module("../acme", "crm").put("a", "", "x".byteInputStream(), 10) }
    }

    @Test
    fun deletingACompanyRemovesOnlyItsFiles() {
        val acme = files.module("acme", "crm").put("a.txt", "", "a".byteInputStream(), 10)
        val other = files.module("otra", "crm").put("b.txt", "", "b".byteInputStream(), 10)

        files.deleteCompany("acme")

        assertFalse(Files.exists(root.resolve("acme")))
        assertNull(files.module("acme", "crm").open(acme))
        assertTrue(files.module("otra", "crm").open(other)!!.use { it.readBytes() }.isNotEmpty())
    }

    @Test
    fun filesKeepTheBackendTheyWereWrittenWith() {
        val unknown = StoredFileRef("gdrive", "abc", 1)
        assertFailsWith<IllegalStateException> { files.module("acme", "crm").open(unknown) }
    }
}
