package com.ideasdeveloper.idc.server.extensions.packages

import com.ideasdeveloper.idc.server.extensions.ExtensionManifest
import com.ideasdeveloper.idc.server.extensions.ExtensionSignature
import com.ideasdeveloper.idc.server.extensions.ExtensionSource
import java.nio.file.Files
import java.nio.file.Path
import java.security.KeyPairGenerator
import java.security.Signature
import java.util.Base64
import java.util.HexFormat
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ExtensionPackagesTest {
    private fun write(dir: Path, path: String, content: String) {
        val file = dir.resolve(path)
        Files.createDirectories(file.parent)
        Files.writeString(file, content)
    }

    // --- Hash del arbol ---

    // El mismo vector lo da scripts/extensions/sign.sh --hash sobre una carpeta con estos archivos.
    private val vectorHash = "c150c9682b88eff57a6f5875b6ea38cdc48d1a46ee26061441ce02bfd6e7dbe3"

    @Test
    fun `tree hash matches the fixed vector shared with sign sh`() {
        val dir = Files.createTempDirectory("ext-hash")
        write(dir, "a.txt", "hello\n")
        write(dir, "sub/b.txt", "world\n")
        write(dir, "extension.sig.json", "ignored")
        assertEquals(vectorHash, ExtensionTreeHash.compute(dir))
        assertEquals(vectorHash, ExtensionTreeHash.ofEntries(mapOf("sub/b.txt" to "world\n".toByteArray(), "a.txt" to "hello\n".toByteArray())))
    }

    @Test
    fun `tree hash is deterministic and sensitive to content and paths`() {
        val one = ExtensionTreeHash.ofEntries(mapOf("a" to "1".toByteArray(), "b" to "2".toByteArray()))
        val reordered = ExtensionTreeHash.ofEntries(linkedMapOf("b" to "2".toByteArray(), "a" to "1".toByteArray()))
        assertEquals(one, reordered)
        assertEquals(64, one.length)
        assertNotEquals(one, ExtensionTreeHash.ofEntries(mapOf("a" to "1".toByteArray(), "b" to "3".toByteArray())))
        assertNotEquals(one, ExtensionTreeHash.ofEntries(mapOf("a" to "1".toByteArray(), "c" to "2".toByteArray())))
    }

    // --- Firma ---

    private class Keys {
        val pair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair()
        val publicBase64: String = Base64.getEncoder().encodeToString(pair.public.encoded)

        fun sign(hashHex: String): String {
            val signer = Signature.getInstance("Ed25519")
            signer.initSign(pair.private)
            signer.update(HexFormat.of().parseHex(hashHex))
            return Base64.getEncoder().encodeToString(signer.sign())
        }
    }

    @Test
    fun `valid signature is accepted`() {
        val keys = Keys()
        val verifier = ExtensionSignatureVerifier(mapOf("ideascore-2026" to keys.publicBase64))
        assertNull(verifier.verify(vectorHash, ExtensionSignature("ideascore-2026", keys.sign(vectorHash))))
    }

    @Test
    fun `signature of another hash or another key is rejected`() {
        val keys = Keys()
        val verifier = ExtensionSignatureVerifier(mapOf("ideascore-2026" to keys.publicBase64))
        val otherHash = "0".repeat(64)
        assertEquals("invalid signature", verifier.verify(otherHash, ExtensionSignature("ideascore-2026", keys.sign(vectorHash))))
        assertEquals("invalid signature", verifier.verify(vectorHash, ExtensionSignature("ideascore-2026", Keys().sign(vectorHash))))
        assertEquals("invalid signature", verifier.verify(vectorHash, ExtensionSignature("ideascore-2026", "not base64 !!")))
    }

    @Test
    fun `unknown key and unconfigured key are rejected`() {
        val keys = Keys()
        assertEquals(
            "unknown signing key 'other'",
            ExtensionSignatureVerifier(mapOf("ideascore-2026" to keys.publicBase64)).verify(vectorHash, ExtensionSignature("other", "x")),
        )
        assertEquals(
            "signing key not configured",
            ExtensionSignatureVerifier(mapOf("ideascore-2026" to "")).verify(vectorHash, ExtensionSignature("ideascore-2026", keys.sign(vectorHash))),
        )
    }

    @Test
    fun `the shipped key resource is empty until the owner generates the key`() {
        assertEquals("", IdeasCorePublicKeys.keys.getValue("ideascore-2026"))
        assertEquals(
            "signing key not configured",
            ExtensionSignatureVerifier().verify(vectorHash, ExtensionSignature("ideascore-2026", "AAAA")),
        )
    }

    // --- Validador del manifiesto ---

    private val commit = "a".repeat(40)

    private fun manifest(
        id: String = "monday_import",
        module: String = "crm",
        minModuleVersion: String? = "0.23.0",
        permissions: List<String> = listOf("secrets.write", "crm.leads.create", "network.outbound:api.monday.com"),
        source: ExtensionSource = ExtensionSource("github", "https://github.com/acme/idc/tree/$commit/extensions/$id"),
        migrations: List<String> = emptyList(),
    ) = ExtensionManifest(
        id = id, module = module, displayName = "X", version = "0.1.0", minModuleVersion = minModuleVersion,
        source = source, permissions = permissions, migrations = migrations,
    )

    private val validator = ExtensionManifestValidator(
        installedModuleVersion = { if (it == "crm") "0.24.0" else null },
        modulePermissions = { if (it == "crm") listOf("crm.leads.create", "crm.fields.create") else emptyList() },
    )

    private fun problems(manifest: ExtensionManifest, folder: String = manifest.id, dir: Path = Files.createTempDirectory("ext-val")) =
        validator.validate(manifest, folder, dir)

    private fun assertProblem(list: List<String>, fragment: String) =
        assertTrue(list.any { it.contains(fragment) }, "esperaba '$fragment' en $list")

    @Test
    fun `a correct manifest has no problems`() {
        assertEquals(emptyList(), problems(manifest()))
    }

    @Test
    fun `invalid id and folder mismatch are rejected`() {
        assertProblem(problems(manifest(id = "Bad-Id"), folder = "Bad-Id"), "debe cumplir")
        assertProblem(problems(manifest(), folder = "otra"), "igual al nombre de la carpeta")
    }

    @Test
    fun `module must be installed and meet the minimum version`() {
        assertProblem(problems(manifest(module = "hotel")), "no esta instalado")
        assertProblem(problems(manifest(minModuleVersion = "0.30.0")), "0.30.0 o superior")
        assertEquals(emptyList(), problems(manifest(minModuleVersion = "0.9.0")))
    }

    @Test
    fun `permissions must come from the closed list`() {
        assertProblem(problems(manifest(permissions = listOf("crm.leads.delete"))), "crm.leads.delete")
        assertProblem(problems(manifest(permissions = listOf("network.outbound:localhost"))), "network.outbound:localhost")
        assertEquals(emptyList(), problems(manifest(permissions = listOf("crm.fields.create", "webhook.public"))))
    }

    @Test
    fun `source must be github url pinned to a 40 hex commit`() {
        val branch = manifest(source = ExtensionSource("github", "https://github.com/acme/idc/tree/develop/extensions/monday_import"))
        assertProblem(problems(branch), "una rama no se acepta")
        val short = manifest(source = ExtensionSource("github", "https://github.com/acme/idc/tree/${"a".repeat(39)}/extensions/monday_import"))
        assertProblem(problems(short), "una rama no se acepta")
        val otherFolder = manifest(source = ExtensionSource("github", "https://github.com/acme/idc/tree/$commit/extensions/otra"))
        assertProblem(problems(otherFolder), "debe apuntar a extensions/monday_import")
        assertProblem(problems(manifest(source = ExtensionSource("bundled", null))), "source.type")
        assertProblem(problems(manifest(source = ExtensionSource("github", null))), "source.url")
    }

    @Test
    fun `migrations must exist have a valid name and be wrapped in a transaction`() {
        val dir = Files.createTempDirectory("ext-mig")
        write(dir, "migrations/V001__ok.sql", "BEGIN;\nCREATE TABLE ext_monday_import__state (id int);\nCOMMIT;")
        write(dir, "migrations/V002__nowrap.sql", "CREATE TABLE ext_monday_import__b (id int);")
        write(dir, "migrations/V003__core.sql", "BEGIN;\nDROP TABLE customers;\nCOMMIT;")
        assertEquals(emptyList(), problems(manifest(migrations = listOf("migrations/V001__ok.sql")), dir = dir))
        assertProblem(problems(manifest(migrations = listOf("migrations/V009__missing.sql")), dir = dir), "no existe en el paquete")
        assertProblem(problems(manifest(migrations = listOf("migrations/bad.sql")), dir = dir), "VNNN__descripcion.sql")
        assertProblem(problems(manifest(migrations = listOf("migrations/V002__nowrap.sql")), dir = dir), "BEGIN; y COMMIT;")
        assertProblem(problems(manifest(migrations = listOf("migrations/V003__core.sql")), dir = dir), "customers")
        assertProblem(problems(manifest(migrations = listOf("migrations/../extension.json")), dir = dir), "VNNN__descripcion.sql")
    }

    // --- Validador de SQL ---

    private fun sql(body: String, id: String = "x") = MigrationTableValidator.validate(id, "BEGIN;\n$body\nCOMMIT;")

    @Test
    fun `sql on ext tables of the extension is allowed`() {
        val allowed = """
            -- comentario con DROP TABLE customers
            CREATE TABLE IF NOT EXISTS public.ext_x__state (id serial PRIMARY KEY, note text DEFAULT 'select from customers');
            CREATE TABLE ext_x__item (id bigint PRIMARY KEY, state_id int REFERENCES ext_x__state(id) ON DELETE CASCADE);
            CREATE UNIQUE INDEX ext_x__item_state ON ext_x__item (state_id);
            ALTER TABLE ext_x__state ADD COLUMN extra text;
            INSERT INTO ext_x__state (note) VALUES ('a');
            UPDATE ext_x__state SET note = 'b' WHERE id = 1;
            DELETE FROM ext_x__item WHERE id = 1;
            COMMENT ON TABLE ext_x__state IS 'estado';
            DROP INDEX IF EXISTS ext_x__item_state;
            TRUNCATE TABLE ext_x__item;
            DROP TABLE IF EXISTS ext_x__item, ext_x__state CASCADE;
        """.trimIndent()
        assertEquals(emptyList(), sql(allowed))
    }

    @Test
    fun `sql on core tables is rejected for every verb`() {
        listOf(
            "CREATE TABLE customers (id int);",
            "ALTER TABLE customers ADD COLUMN x int;",
            "DROP TABLE crm_leads;",
            "TRUNCATE crm_leads;",
            "INSERT INTO customers (id) VALUES (1);",
            "UPDATE crm_leads SET name = 'x';",
            "DELETE FROM customers;",
            "GRANT ALL ON customers TO PUBLIC;",
            "REVOKE ALL ON crm_leads FROM PUBLIC;",
            "COMMENT ON TABLE customers IS 'x';",
            "COMMENT ON COLUMN customers.email IS 'x';",
            "CREATE INDEX ext_x__i ON customers (email);",
            "CREATE INDEX customers_idx ON ext_x__a (id);",
            "ALTER TABLE ext_x__a RENAME TO customers;",
            "DROP TABLE ext_x__a, customers;",
        ).forEach { statement -> assertTrue(sql(statement).isNotEmpty(), "debia rechazar: $statement") }
        assertProblem(sql("DROP TABLE customers;"), "customers")
        assertProblem(sql("UPDATE crm_leads SET name = 'x';"), "crm_leads")
    }

    @Test
    fun `sql rejects another extension prefix and the id as a bare prefix`() {
        assertProblem(sql("CREATE TABLE ext_y_state (id int);"), "ext_y_state")
        assertProblem(sql("CREATE TABLE ext_x (id int);"), "ext_x")
        // Doble guion bajo: la extension "x" no toca las tablas de la extension "x_y".
        assertProblem(sql("CREATE TABLE ext_x_y__t (id int);"), "ext_x_y__t")
        assertProblem(sql("CREATE TABLE ext_x_state (id int);"), "ext_x_state")
        assertEquals(emptyList(), sql("CREATE TABLE ext_x_y__t (id int);", id = "x_y"))
    }

    @Test
    fun `sql rejects dangerous statements`() {
        assertProblem(sql("DO \$\$ BEGIN DELETE FROM customers; END \$\$;"), "dolar")
        assertProblem(sql("CREATE FUNCTION ext_x__f() RETURNS int AS 'select 1' LANGUAGE sql;"), "CREATE FUNCTION")
        assertProblem(sql("CREATE TRIGGER ext_x__t BEFORE INSERT ON ext_x__a FOR EACH ROW EXECUTE FUNCTION f();"), "CREATE TRIGGER")
        assertProblem(sql("CREATE EXTENSION pgcrypto;"), "CREATE EXTENSION")
        assertProblem(sql("CREATE ROLE evil SUPERUSER;"), "CREATE ROLE")
        assertProblem(sql("SET ROLE postgres;"), "SET")
        assertProblem(sql("COPY ext_x__a FROM '/etc/passwd';"), "COPY")
        assertProblem(sql("INSERT INTO ext_x__a SELECT * FROM customers;"), "SELECT")
        assertProblem(sql("CREATE TABLE ext_x__a (c int REFERENCES customers(id));"), "REFERENCES customers")
        assertProblem(sql("VACUUM;"), "no permitida")
    }

    @Test
    fun `sql rejects other schemas`() {
        assertProblem(sql("CREATE TABLE other.ext_x__a (id int);"), "'other.'")
        assertProblem(sql("INSERT INTO pg_catalog.pg_class VALUES (1);"), "'pg_catalog.'")
        assertProblem(sql("ALTER TABLE ext_x__a SET SCHEMA other;"), "SET SCHEMA")
        assertEquals(emptyList(), sql("CREATE TABLE public.ext_x__a (id int);"))
    }
}
