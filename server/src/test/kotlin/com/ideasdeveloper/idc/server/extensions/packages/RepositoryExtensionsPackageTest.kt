package com.ideasdeveloper.idc.server.extensions.packages

import com.ideasdeveloper.idc.server.extensions.ExtensionManifest
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.invariantSeparatorsPathString
import kotlin.io.path.isDirectory
import kotlin.io.path.name
import kotlin.io.path.readText
import kotlin.io.path.relativeTo
import kotlin.io.path.walk
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Las extensiones reales de extensions/<id>/ tienen que poder instalarse: cumplen las mismas reglas que el
 * servidor aplica a un paquete externo (manifiesto, estructura, migraciones y clase proveedora).
 * Sin esto, un manifiesto mal escrito solo se descubre al instalar en un servidor.
 */
class RepositoryExtensionsPackageTest {
    private val json = Json { ignoreUnknownKeys = true }

    // Permisos propios de cada modulo (ModuleDefinition.extensionPermissions); el servidor no depende de los modulos.
    private val crmPermissions = listOf(
        "crm.leads.create", "crm.leads.update", "crm.fields.create",
        "crm.messages.write", "crm.items.write", "crm.reservations.write",
    )

    private val validator = ExtensionManifestValidator(
        installedModuleVersion = { "999.0.0" },
        modulePermissions = { module -> if (module == "crm") crmPermissions else emptyList() },
    )

    /** Sube desde el directorio de trabajo (server/ con Gradle) hasta la carpeta con settings.gradle.kts. */
    private fun repositoryRoot(): Path {
        var current: Path? = Path.of("").toAbsolutePath()
        while (current != null) {
            if (Files.isRegularFile(current.resolve("settings.gradle.kts"))) return current
            current = current.parent
        }
        error("No se encontro settings.gradle.kts subiendo desde ${Path.of("").toAbsolutePath()}")
    }

    private fun extensionFolders(): List<Path> {
        val root = repositoryRoot().resolve("extensions")
        assertTrue(root.isDirectory(), "no existe la carpeta extensions/ en ${repositoryRoot()}")
        return Files.list(root).use { stream -> stream.filter { it.isDirectory() && Files.isRegularFile(it.resolve("extension.json")) }.sorted().toList() }
    }

    @Test
    fun `the repository ships the eight extensions`() {
        assertEquals(
            listOf("cloudbeds", "encuentra24", "inmobiliaria", "inmobiliaria_monday", "meta", "monday", "unifi", "wordpress"),
            extensionFolders().map { it.name },
        )
    }

    @Test
    fun `every extension in the repository passes the package validator`() {
        extensionFolders().forEach { folder ->
            val manifest = json.decodeFromString(ExtensionManifest.serializer(), folder.resolve("extension.json").readText())
            val problems = validator.validate(manifest, folder.name, folder)
            assertTrue(problems.isEmpty(), "${folder.name}: ${problems.joinToString(" ")}")
        }
    }

    @Test
    fun `every extension in the repository has a valid package structure`() {
        extensionFolders().forEach { folder ->
            val problems = ExtensionPackageBuilder.structureProblems(folder.name, folder)
            assertTrue(problems.isEmpty(), "${folder.name}: ${problems.joinToString(" ")}")
        }
    }

    @Test
    fun `every extension declares the provider class the packager looks for`() {
        extensionFolders().forEach { folder ->
            val provider = ExtensionPackageBuilder.providerClassName(folder.name).substringAfterLast('.')
            val sources = folder.resolve("server/src/main/kotlin").walk().filter { it.name.endsWith(".kt") }.toList()
            assertTrue(sources.any { Regex("""(^|\s)class $provider\b""").containsMatchIn(it.readText()) }, "${folder.name}: falta class $provider")
        }
    }

    @Test
    fun `the migration paths in the code match the manifest as the loader requires`() {
        extensionFolders().forEach { folder ->
            val manifest = json.decodeFromString(ExtensionManifest.serializer(), folder.resolve("extension.json").readText())
            val code = folder.resolve("server/src/main/kotlin").walk().filter { it.name.endsWith(".kt") }.joinToString("\n") { it.readText() }
            manifest.migrations.forEach { migration ->
                // ExtensionPackageLoader.jarMigrationPath: el jar guarda la migracion en extensions/<id>/<ruta del manifiesto>.
                val jarPath = ExtensionPackageLoader.jarMigrationPath(manifest.id, migration)
                assertTrue(code.contains("\"$jarPath\""), "${folder.name}: el codigo debe declarar migrationPaths con \"$jarPath\"")
                assertTrue(code.contains("\"$migration\""), "${folder.name}: el manifiesto del codigo debe declarar \"$migration\"")
            }
        }
    }

    @Test
    fun `every file path in the extensions is accepted by the extractor`() {
        extensionFolders().forEach { folder ->
            folder.walk()
                .filter { Files.isRegularFile(it) }
                .map { it.relativeTo(folder).invariantSeparatorsPathString }
                .filterNot { it.startsWith("build/") || it.startsWith(".gradle/") }
                .forEach { relative ->
                    assertTrue(ExtensionTreeHash.isValidPath(relative), "${folder.name}: ruta no permitida '$relative'")
                }
        }
    }
}
