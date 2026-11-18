package com.ideasdeveloper.idc.server.extensions.packages

import com.ideasdeveloper.idc.ModuleVersion
import com.ideasdeveloper.idc.server.extensions.ExtensionIdPattern
import com.ideasdeveloper.idc.server.extensions.ExtensionManifest
import com.ideasdeveloper.idc.server.extensions.ExtensionPermissions
import com.ideasdeveloper.idc.server.extensions.ExtensionSourceType
import java.nio.file.Files
import java.nio.file.Path

/**
 * Reglas de EXTENSIONS.md "Validation" para un paquete externo. Devuelve todos los problemas
 * a la vez, cada uno con un mensaje que dice que regla se rompio.
 *
 * Las rutas HTTP bajo /modules/<module>/extensions/<id>/ no se revisan aqui: las monta el Core
 * (ExtensionGate), la extension no elige su prefijo.
 */
class ExtensionManifestValidator(
    /** Version instalada del modulo, o null si no esta instalado en el servidor. */
    private val installedModuleVersion: (moduleId: String) -> String?,
    /** ModuleDefinition.extensionPermissions del modulo. */
    private val modulePermissions: (moduleId: String) -> List<String>,
) {
    /** [folderName] es el nombre de la carpeta del paquete; [packageDir] su contenido ya extraido. */
    fun validate(manifest: ExtensionManifest, folderName: String, packageDir: Path): List<String> {
        val problems = mutableListOf<String>()
        if (!ExtensionIdPattern.matches(manifest.id)) problems += "id '${manifest.id}' debe cumplir [a-z][a-z0-9_]{0,62}."
        if (manifest.id != folderName) problems += "id '${manifest.id}' debe ser igual al nombre de la carpeta '$folderName'."
        if (manifest.version.isBlank()) problems += "version es obligatoria."

        val installed = installedModuleVersion(manifest.module)
        if (installed == null) {
            problems += "el modulo '${manifest.module}' no esta instalado en el servidor."
        } else {
            manifest.minModuleVersion?.takeIf { it.isNotBlank() }?.let { minimum ->
                if (ModuleVersion.compare(installed, minimum) < 0) {
                    problems += "requiere el modulo '${manifest.module}' $minimum o superior; esta instalado $installed."
                }
            }
            val allowed = modulePermissions(manifest.module)
            manifest.permissions.filterNot { ExtensionPermissions.isAllowed(it, allowed) }
                .forEach { problems += "permiso '$it' no esta en la lista cerrada." }
        }

        sourceProblem(manifest)?.let { problems += it }
        problems += migrationProblems(manifest, packageDir)
        return problems
    }

    private fun sourceProblem(manifest: ExtensionManifest): String? {
        // ZIP subido o URL: source.url es solo informativo. La garantia real es la firma o la autorizacion por hash.
        if (manifest.source.type == ExtensionSourceType.Zip || manifest.source.type == ExtensionSourceType.Url) return null
        if (manifest.source.type != ExtensionSourceType.GitHub) return "source.type debe ser 'github', 'zip' o 'url' en un paquete externo."
        val url = manifest.source.url ?: return "source.url es obligatorio."
        val match = SourceUrl.matchEntire(url.trim())
            ?: return "source.url debe ser https://github.com/<owner>/<repo>/tree/<commit de 40 hex>/extensions/<id>; una rama no se acepta."
        if (match.groupValues[3] != manifest.id) return "source.url debe apuntar a extensions/${manifest.id}."
        return null
    }

    private fun migrationProblems(manifest: ExtensionManifest, packageDir: Path): List<String> {
        val problems = mutableListOf<String>()
        val root = packageDir.toAbsolutePath().normalize()
        manifest.migrations.forEach { path ->
            if (!MigrationPath.matches(path)) {
                problems += "migracion '$path' debe llamarse migrations/VNNN__descripcion.sql."
                return@forEach
            }
            val file = root.resolve(path).normalize()
            if (!file.startsWith(root) || !Files.isRegularFile(file)) {
                problems += "migracion '$path' no existe en el paquete."
                return@forEach
            }
            val sql = Files.readString(file).trim()
            if (!sql.startsWith("BEGIN;") || !sql.endsWith("COMMIT;")) problems += "migracion '$path' debe ir entre BEGIN; y COMMIT;."
            MigrationTableValidator.validate(manifest.id, sql).forEach { problems += "migracion '$path': $it" }
        }
        return problems
    }

    companion object {
        private val SourceUrl = Regex("https://github\\.com/([A-Za-z0-9_.-]+)/([A-Za-z0-9_.-]+)/tree/[0-9a-f]{40}/extensions/([a-z][a-z0-9_]*)/?")
        private val MigrationPath = Regex("migrations/V[0-9]{3}__[a-z0-9_]+\\.sql")
    }
}
