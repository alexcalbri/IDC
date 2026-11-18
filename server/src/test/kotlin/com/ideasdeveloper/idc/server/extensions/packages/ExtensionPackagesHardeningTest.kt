package com.ideasdeveloper.idc.server.extensions.packages

import com.ideasdeveloper.idc.server.extensions.ExtensionManifest
import com.ideasdeveloper.idc.server.extensions.ServerExtension
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.HexFormat
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Endurecimiento de paquetes externos: SQL, rutas, extraccion, contenido y comprobaciones al cargar. */
class ExtensionPackagesHardeningTest {
    private fun write(dir: Path, path: String, content: String) {
        val file = dir.resolve(path)
        Files.createDirectories(file.parent)
        Files.writeString(file, content)
    }

    private fun sql(body: String, id: String = "x") = MigrationTableValidator.validate(id, "BEGIN;\n$body\nCOMMIT;")

    private fun assertProblem(list: List<String>, fragment: String) =
        assertTrue(list.any { it.contains(fragment) }, "esperaba '$fragment' en $list")

    private fun rejected(sql: String) = assertTrue(MigrationTableValidator.validate("x", sql).isNotEmpty(), "debia rechazar: $sql")

    // --- SQL: bypasses conocidos ---

    @Test
    fun `sql rejects quoted identifiers escape strings and block comments`() {
        rejected("BEGIN; CREATE TABLE ext_x__t (\"a--\" int); DROP TABLE application_users; --\"\nCOMMIT;")
        rejected("BEGIN; INSERT INTO ext_x__t VALUES (E'\\''); DROP TABLE application_users; --'\nCOMMIT;")
        rejected("INSERT INTO ext_x__t VALUES (e'a');")
        rejected("INSERT INTO ext_x__t VALUES (U&'a');")
        rejected("CREATE TABLE ext_x__t (a int); /* comentario */")
        rejected("INSERT INTO ext_x__t VALUES ('a\\');")
        rejected("INSERT INTO ext_x__t VALUES ('sin cerrar);")
    }

    @Test
    fun `sql rejects TABLE and VALUES statements and inserts that are not VALUES`() {
        rejected("INSERT INTO ext_x__t TABLE application_users;")
        rejected("TABLE application_users;")
        rejected("VALUES (1);")
        rejected("INSERT INTO ext_x__t (a) SELECT 1;")
        rejected("UPDATE ext_x__t SET a = 1 WHERE a IN (TABLE application_users);")
        rejected("DELETE FROM ext_x__t WHERE a IN (VALUES (1));")
        rejected("CREATE TABLE ext_x__t AS TABLE application_users;")
        assertEquals(emptyList(), sql("INSERT INTO ext_x__t (a, b) VALUES (1, 'x'), (2, 'y');"))
        assertEquals(emptyList(), sql("INSERT INTO ext_x__t DEFAULT VALUES;"))
    }

    @Test
    fun `sql rejects function calls outside the whitelist`() {
        assertProblem(sql("INSERT INTO ext_x__t VALUES (pg_sleep(1));"), "pg_sleep(")
        assertProblem(sql("UPDATE ext_x__t SET a = pg_read_file('x');"), "pg_read_file(")
        assertProblem(sql("CREATE TABLE ext_x__t (id int DEFAULT nextval('s'));"), "nextval(")
        assertEquals(
            emptyList(),
            sql("CREATE TABLE ext_x__t (id uuid DEFAULT gen_random_uuid(), name varchar(50) NOT NULL, price numeric(10,2), at timestamptz DEFAULT now(), CHECK (length(name) > 0));"),
        )
        assertEquals(emptyList(), sql("UPDATE ext_x__t SET name = lower(coalesce(name, 'x')) WHERE id IN (1, 2);"))
    }

    @Test
    fun `alter table only allows the whitelisted actions`() {
        assertEquals(
            emptyList(),
            sql(
                """
                ALTER TABLE ext_x__t ADD COLUMN c numeric(10,2) DEFAULT 0, DROP COLUMN old, ALTER COLUMN b SET NOT NULL;
                ALTER TABLE ext_x__t ADD CONSTRAINT ext_x__t_c CHECK (c > 0);
                ALTER TABLE ext_x__t DROP CONSTRAINT ext_x__t_c;
                ALTER TABLE ext_x__t ALTER COLUMN b TYPE varchar(20);
                ALTER TABLE ext_x__t ALTER COLUMN b SET DEFAULT 'x';
                ALTER TABLE ext_x__t ALTER COLUMN b DROP DEFAULT;
                ALTER TABLE ext_x__t RENAME COLUMN b TO c;
                ALTER TABLE ext_x__t RENAME TO ext_x__u;
                """.trimIndent(),
            ),
        )
        listOf(
            "ALTER TABLE ext_x__t INHERIT customers;",
            "ALTER TABLE ext_x__t NO INHERIT customers;",
            "ALTER TABLE ext_x__t ATTACH PARTITION customers FOR VALUES IN (1);",
            "ALTER TABLE ext_x__t DETACH PARTITION ext_x__p;",
            "ALTER TABLE ext_x__t DISABLE TRIGGER ALL;",
            "ALTER TABLE ext_x__t ENABLE TRIGGER ALL;",
            "ALTER TABLE ext_x__t FORCE ROW LEVEL SECURITY;",
            "ALTER TABLE ext_x__t RENAME TO customers;",
            "ALTER TABLE ext_x__t SET (fillfactor = 70);",
            "ALTER TABLE ext_x__t OWNER TO postgres;",
        ).forEach { rejected("BEGIN; $it COMMIT;") }
    }

    // --- Rutas del arbol ---

    @Test
    fun `tree paths are restricted`() {
        assertTrue(ExtensionTreeHash.isValidPath("server/src/main/kotlin/A-b_c.1.kt"))
        listOf("a b", "../x", "a/../b", "a//b", "/a", "a/", "./a", "a\\b", "ñ.txt", "").forEach {
            assertTrue(!ExtensionTreeHash.isValidPath(it), "debia rechazar '$it'")
        }
        assertFailsWith<ExtensionPackageException> { ExtensionTreeHash.ofEntries(mapOf("a b" to ByteArray(0))) }
        assertFailsWith<ExtensionPackageException> { ExtensionTreeHash.ofEntries(mapOf("../x" to ByteArray(0))) }
        val dir = Files.createTempDirectory("ext-bad")
        write(dir, "bad name.txt", "x")
        assertFailsWith<ExtensionPackageException> { ExtensionTreeHash.compute(dir) }
    }

    // --- Extraccion y contenido del paquete ---

    private fun zip(entries: List<Pair<String, ByteArray>>): Path {
        val file = Files.createTempFile("ext", ".zip")
        ZipOutputStream(Files.newOutputStream(file)).use { out ->
            entries.forEach { (name, content) ->
                out.putNextEntry(ZipEntry(name))
                out.write(content)
                out.closeEntry()
            }
        }
        return file
    }

    private fun target() = Files.createTempDirectory("ext-x")

    @Test
    fun `extraction takes only the extension folder and enforces limits`() {
        val ok = zip(listOf("repo-abc/README.md" to "x".toByteArray(), "repo-abc/extensions/e/extension.json" to "{}".toByteArray(), "repo-abc/extensions/e/server/A.kt" to "a".toByteArray()))
        val root = ExtensionPackageSafety.extract(ok, "extensions/e", target())
        assertTrue(Files.exists(root.resolve("extension.json")) && Files.exists(root.resolve("server/A.kt")) && !Files.exists(root.resolve("README.md")))

        val big = zip(listOf("repo-abc/extensions/e/big.txt" to ByteArray(2000)))
        assertFailsWith<ExtensionPackageException> { ExtensionPackageSafety.extract(big, "extensions/e", target(), maxEntryBytes = 1000) }
        val two = zip(listOf("repo-abc/extensions/e/a.txt" to ByteArray(800), "repo-abc/extensions/e/b.txt" to ByteArray(800)))
        assertFailsWith<ExtensionPackageException> { ExtensionPackageSafety.extract(two, "extensions/e", target(), maxTotalBytes = 1000) }
        assertFailsWith<ExtensionPackageException> { ExtensionPackageSafety.extract(two, "extensions/e", target(), maxEntries = 1) }
        val badPath = zip(listOf("repo-abc/extensions/e/../../evil.txt" to ByteArray(1)))
        assertFailsWith<ExtensionPackageException> { ExtensionPackageSafety.extract(badPath, "extensions/e", target()) }
        val spaces = zip(listOf("repo-abc/extensions/e/a b.txt" to ByteArray(1)))
        assertFailsWith<ExtensionPackageException> { ExtensionPackageSafety.extract(spaces, "extensions/e", target()) }
        assertFailsWith<ExtensionPackageException> { ExtensionPackageSafety.extract(ok, "extensions/otra", target()) }
    }

    @Test
    fun `content check allows only whitelisted resources and rejects binaries`() {
        val good = Files.createTempDirectory("ext-c")
        write(good, "server/src/main/kotlin/com/A.kt", "x")
        write(good, "server/src/main/resources/a.json", "{}")
        write(good, "server/src/main/resources/img/b.png", "x")
        assertEquals(emptyList(), ExtensionPackageSafety.contentProblems(good))

        val bad = Files.createTempDirectory("ext-c")
        write(bad, "server/src/main/resources/a.class", "x")
        write(bad, "server/src/main/resources/a.exe", "x")
        write(bad, "server/src/main/resources/META-INF/services/x.txt", "x")
        write(bad, "server/src/main/kotlin/com/B.java", "x")
        write(bad, "lib/dep.jar", "x")
        val problems = ExtensionPackageSafety.contentProblems(bad)
        assertProblem(problems, "a.class")
        assertProblem(problems, "a.exe")
        assertProblem(problems, "META-INF")
        assertProblem(problems, "B.java")
        assertProblem(problems, "dep.jar")
    }

    // --- Comprobaciones al cargar ---

    private fun jar(root: Path, entries: Map<String, String>): Path {
        val file = root.resolve("e-0.1.0.jar")
        ZipOutputStream(Files.newOutputStream(file)).use { out ->
            entries.forEach { (name, content) ->
                out.putNextEntry(ZipEntry(name))
                out.write(content.toByteArray())
                out.closeEntry()
            }
        }
        return file
    }

    private fun sha(file: Path) = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file)))

    @Test
    fun `loader rejects jars outside the root with another hash or another manifest`() {
        val root = Files.createTempDirectory("ext-root")
        val manifestJson = """{"id":"e","module":"crm","displayName":"E","version":"0.1.0","migrations":["migrations/V001__a.sql"]}"""
        val file = jar(root, mapOf("extensions/e/extension.json" to manifestJson, "extensions/e/migrations/V001__a.sql" to "BEGIN;COMMIT;"))
        val ok = LoadCandidate(file, "e", "crm", "0.1.0", sha(file))
        assertNull(ExtensionPackageLoader.checkJar(ok, root))
        assertProblem(listOfNotNull(ExtensionPackageLoader.checkJar(ok, Files.createTempDirectory("otra-raiz"))), "fuera de")
        assertProblem(listOfNotNull(ExtensionPackageLoader.checkJar(ok.copy(jarSha256 = "0".repeat(64)), root)), "no coincide")
        assertProblem(listOfNotNull(ExtensionPackageLoader.checkJar(ok.copy(jarSha256 = null), root)), "SHA-256")
        assertProblem(listOfNotNull(ExtensionPackageLoader.checkJar(ok.copy(version = "9.9.9"), root)), "no coincide")
        assertProblem(listOfNotNull(ExtensionPackageLoader.checkJar(ok.copy(module = "hotel"), root)), "no coincide")
        val noMigration = jar(Files.createTempDirectory("ext-root2"), mapOf("extensions/e/extension.json" to manifestJson))
        assertProblem(
            listOfNotNull(ExtensionPackageLoader.checkJar(LoadCandidate(noMigration, "e", "crm", "0.1.0", sha(noMigration)), noMigration.parent)),
            "faltan migraciones",
        )
    }

    private fun extension(declared: ExtensionManifest, paths: List<String> = emptyList(), tables: List<String> = emptyList()) = object : ServerExtension {
        override val manifest = declared
        override val migrationPaths = paths
        override val runtimeTables = tables
    }

    @Test
    fun `loader rejects extensions whose code disagrees with the registry`() {
        val candidate = LoadCandidate(Path.of("e.jar"), "e", "crm", "0.1.0", null)
        val manifest = ExtensionManifest(id = "e", module = "crm", displayName = "E", version = "0.1.0", migrations = listOf("migrations/V001__a.sql"))
        val paths = listOf("extensions/e/migrations/V001__a.sql")
        assertNull(ExtensionPackageLoader.checkExtension(candidate, extension(manifest, paths, listOf("ext_e__t"))))
        assertProblem(listOfNotNull(ExtensionPackageLoader.checkExtension(candidate, extension(manifest.copy(id = "otra")))), "no coincide")
        assertProblem(listOfNotNull(ExtensionPackageLoader.checkExtension(candidate, extension(manifest.copy(version = "2.0.0")))), "no coincide")
        assertProblem(listOfNotNull(ExtensionPackageLoader.checkExtension(candidate, extension(manifest, listOf("modules/crm/migrations/V999__x.sql")))), "migrationPaths")
        assertProblem(listOfNotNull(ExtensionPackageLoader.checkExtension(candidate, extension(manifest, paths, listOf("customers")))), "runtimeTables")
        assertProblem(listOfNotNull(ExtensionPackageLoader.checkExtension(candidate, extension(manifest, paths, listOf("ext_e_t")))), "runtimeTables")
    }
}
