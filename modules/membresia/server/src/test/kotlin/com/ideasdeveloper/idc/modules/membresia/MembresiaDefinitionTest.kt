package com.ideasdeveloper.idc.modules.membresia

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

// Las pruebas corren desde server/; el modulo vive en la raiz del repositorio.
class MembresiaDefinitionTest {
    private fun file(relative: String): Path =
        listOf(Path.of(relative), Path.of("..").resolve(relative)).first { Files.exists(it) }

    private val migrationsDirectory = file("modules/membresia/migrations")

    private fun migrationFiles(): List<String> = Files.list(migrationsDirectory).use { files ->
        files.map { it.fileName.toString() }.filter { it.endsWith(".sql") }.sorted().toList()
    }

    private fun allMigrationsSql(): String = migrationFiles().joinToString("\n") { Files.readString(migrationsDirectory.resolve(it)) }

    @Test
    fun moduleJsonVersionMatchesDefinition() {
        val json = Files.readString(file("modules/membresia/module.json"))
        val version = Regex("\"version\"\\s*:\\s*\"([^\"]+)\"").find(json)?.groupValues?.get(1)
        assertEquals(MembresiaServerModule.definition.version, version)
        assertEquals(MembresiaModule.Version, version)
        val id = Regex("\"id\"\\s*:\\s*\"([^\"]+)\"").find(json)?.groupValues?.get(1)
        assertEquals(MembresiaModule.Id, id)
    }

    @Test
    fun everyMigrationFileIsListedInMigrationPaths() {
        val declared = MembresiaServerModule.migrationPaths.map { it.substringAfterLast('/') }.sorted()
        assertEquals(migrationFiles(), declared)
        MembresiaServerModule.migrationPaths.forEach { assertTrue(Files.isRegularFile(file(it)), "$it no existe") }
    }

    @Test
    fun migrationsAreWrappedInOneTransaction() {
        migrationFiles().forEach { name ->
            val sql = Files.readString(migrationsDirectory.resolve(name)).trim()
            assertTrue(sql.startsWith("BEGIN;"), "$name debe empezar con BEGIN;")
            assertTrue(sql.endsWith("COMMIT;"), "$name debe terminar con COMMIT;")
        }
    }

    @Test
    fun everyRuntimeTableIsCreatedByAMigration() {
        val sql = allMigrationsSql()
        MembresiaServerModule.runtimeTables.forEach { table ->
            assertTrue(Regex("CREATE (TABLE|VIEW|OR REPLACE VIEW) $table\\b").containsMatchIn(sql), "$table no aparece en las migraciones")
        }
    }

    @Test
    fun duplicatedSettingsAreRuntimeTablesWithParentFirst() {
        val names = MembresiaServerModule.duplicatedSettings.map { it.name }
        assertEquals(listOf("membresia_settings", "membresia_plans"), names)
        assertTrue(MembresiaServerModule.runtimeTables.containsAll(names))
    }

    @Test
    fun permissionIdsUseTheModulePrefixAndMatchTheConstants() {
        val ids = MembresiaServerModule.definition.permissions.map { it.id }
        assertTrue(ids.all { it.startsWith("membresia.") })
        assertEquals(setOf(MembresiaModule.View, MembresiaModule.Visits, MembresiaModule.Manage, MembresiaModule.Plans, MembresiaModule.Import), ids.toSet())
        assertEquals(ids.size, ids.toSet().size)
    }

    @Test
    fun uiEntryPointsToTheNativeRoute() {
        val ui = MembresiaServerModule.definition.ui
        assertEquals("native", ui?.type)
        assertEquals(MembresiaModule.UiRoute, ui?.entry)
        assertEquals("recepcion", ui?.home)
    }

    @Test
    fun dailyJobIsScheduledEarlyInTheMorning() {
        val job = MembresiaServerModule.jobs.single { it.id == "membresia-diario" }
        assertEquals(java.time.LocalTime.of(7, 0), job.dailyAt)
    }

    @Test
    fun importJobRunsEveryMinuteBecauseTheCoreDoesNotScheduleExtensionJobs() {
        val job = MembresiaServerModule.jobs.single { it.id == "membresia-import" }
        assertEquals(java.time.Duration.ofMinutes(1), job.interval)
    }
}
