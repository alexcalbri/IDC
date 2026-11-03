package com.ideasdeveloper.idc.server.modules

import java.nio.file.Files
import java.nio.file.Path
import java.util.ServiceLoader
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

// A wrong table or column in duplicatedSettings would only fail when someone duplicates a company.
class DuplicatedSettingsTest {
    private val modules = ServiceLoader.load(ServerModuleProvider::class.java).map { it.create() }

    // Migrations of a module as one text (tests run from server/, migrations live at the repository root).
    private fun migrations(module: ServerModule): String = module.migrationPaths.joinToString("\n") { relative ->
        listOf(Path.of(relative), Path.of("..").resolve(relative)).first { Files.isRegularFile(it) }.let(Files::readString)
    }

    @Test
    fun copiedTablesAndResetColumnsExistInTheModuleMigrations() {
        val problems = modules.flatMap { module ->
            val sql = migrations(module)
            module.duplicatedSettings.flatMap { table ->
                val created = Regex("CREATE TABLE (IF NOT EXISTS )?${table.name}\\b").containsMatchIn(sql)
                listOfNotNull("${module.definition.id}: tabla ${table.name}".takeUnless { created }) +
                    table.reset.keys.filterNot { Regex("\\b$it\\b").containsMatchIn(sql) }.map { "${module.definition.id}: columna ${table.name}.$it" }
            }
        }
        assertEquals(emptyList(), problems)
    }

    @Test
    fun integrationCredentialsAreNeverCopied() {
        val crm = modules.first { it.definition.id == "crm" }.duplicatedSettings.map { it.name }
        // Mail, Meta, WordPress, Encuentra24 and Cloudbeds hold credentials: the copy connects them again.
        listOf("crm_mail_settings", "crm_meta_settings", "crm_wordpress_settings", "crm_encuentra24_settings", "crm_cloudbeds_settings")
            .forEach { assertTrue(it !in crm, "$it no debe copiarse") }
        assertEquals(null, modules.first { it.definition.id == "crm" }.duplicatedSettings.first { it.name == "crm_settings" }.reset.getValue("monday_token"))
        val hotspot = modules.first { it.definition.id == "hotspot" }.duplicatedSettings.first { it.name == "hotspot_settings" }.reset
        listOf("unifi_api_key", "smtp_password", "domain").forEach { assertTrue(it in hotspot && hotspot[it] == null, "$it debe quedar vacio") }
    }
}
