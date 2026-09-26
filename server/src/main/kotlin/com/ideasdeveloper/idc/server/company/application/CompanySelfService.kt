package com.ideasdeveloper.idc.server.company.application

import com.ideasdeveloper.idc.server.auth.infrastructure.database.LoginDatabases
import com.ideasdeveloper.idc.server.auth.infrastructure.security.SessionTokenGenerator
import com.ideasdeveloper.idc.server.company.api.CompanyBackupResponse
import com.ideasdeveloper.idc.server.company.api.CompanyProfileResponse
import com.ideasdeveloper.idc.server.company.api.UpdateCompanyProfileRequest
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.postgresql.ds.PGSimpleDataSource
import java.nio.file.Files
import java.nio.file.Path
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import javax.sql.DataSource
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.extension
import kotlin.io.path.fileSize
import kotlin.io.path.inputStream
import kotlin.io.path.name
import kotlin.io.path.outputStream

// Error de dominio para operaciones de autoservicio de empresa.
class CompanySelfException(message: String) : Exception(message)

// Servicio de autoservicio para que el business_owner administre su propia empresa.
class CompanySelfService(
    private val central: DataSource,
    private val loginDatabases: LoginDatabases,
    private val backupsRoot: Path,
) {
    // Reutiliza el hash de sesion para validar tokens sin exponerlos en base de datos.
    private val tokenGenerator = SessionTokenGenerator()
    // Valida colores aceptados por la tabla central companies.
    private val colorPattern = Regex("#[0-9A-Fa-f]{6}")
    // Restringe nombres de ZIP para evitar rutas arbitrarias o archivos ajenos al tenant.
    private val backupNamePattern = Regex("[a-z][a-z0-9_]{0,62}-backup-[0-9]{8}T[0-9]{6}Z\\.zip")
    // Serializador usado para manifest y metadata del ZIP.
    private val json = Json {
        encodeDefaults = true
        ignoreUnknownKeys = true
    }

    // Carga el perfil editable de la empresa de la sesion business_owner.
    fun profile(companyCode: String, accessToken: String): CompanyProfileResponse {
        val context = authorizeBusinessOwner(companyCode, accessToken)
        return context.profile
    }

    // Actualiza nombre, logo y colores sin tocar datos tenant.
    fun updateProfile(companyCode: String, accessToken: String, request: UpdateCompanyProfileRequest): CompanyProfileResponse {
        val context = authorizeBusinessOwner(companyCode, accessToken)
        val name = request.name.trim()
        val logoUrl = request.logoUrl?.trim()?.takeIf { it.isNotBlank() }
        if (name.isBlank()) {
            throw CompanySelfException("El nombre de la empresa es obligatorio.")
        }
        if (!colorPattern.matches(request.primaryColor) ||
            !colorPattern.matches(request.secondaryColor) ||
            !colorPattern.matches(request.accentColor)
        ) {
            throw CompanySelfException("Los colores deben usar formato hexadecimal #RRGGBB.")
        }

        central.connection.use { connection ->
            connection.prepareStatement(
                """
                UPDATE companies
                SET name = ?, logo_url = ?, primary_color = ?, secondary_color = ?, accent_color = ?
                WHERE code = ? AND is_active = TRUE
                """.trimIndent()
            ).use { update ->
                update.setString(1, name)
                update.setString(2, logoUrl)
                update.setString(3, request.primaryColor)
                update.setString(4, request.secondaryColor)
                update.setString(5, request.accentColor)
                update.setString(6, context.companyCode)
                update.executeUpdate()
            }
        }

        return profile(companyCode, accessToken)
    }

    // Lista ZIPs ya creados para la empresa.
    fun listBackups(companyCode: String, accessToken: String): List<CompanyBackupResponse> {
        val context = authorizeBusinessOwner(companyCode, accessToken)
        val directory = backupDirectory(context.companyCode)
        if (!directory.exists()) return emptyList()
        return Files.list(directory).use { stream ->
            stream
                .filter { it.extension == "zip" && backupNamePattern.matches(it.name) }
                .map { path ->
                    CompanyBackupResponse(
                        fileName = path.name,
                        sizeBytes = path.fileSize(),
                        createdAt = Files.getLastModifiedTime(path).toInstant().toString(),
                        downloadUrl = "/companies/me/backups/${path.name}/download",
                    )
                }
                .sorted { left, right -> right.createdAt.compareTo(left.createdAt) }
                .toList()
        }
    }

    // Genera un ZIP no destructivo con manifest y perfil; el dump completo queda pendiente de aprobacion.
    fun createProfileBackup(companyCode: String, accessToken: String): CompanyBackupResponse {
        val context = authorizeBusinessOwner(companyCode, accessToken)
        val createdAt = OffsetDateTime.now(java.time.ZoneOffset.UTC)
        val fileName = "${context.companyCode}-backup-${DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").format(createdAt)}.zip"
        val output = backupDirectory(context.companyCode).also { it.createDirectories() }.resolve(fileName)

        output.outputStream().use { file ->
            ZipOutputStream(file).use { zip ->
                zip.putNextEntry(ZipEntry("manifest.json"))
                zip.write(json.encodeToString(BackupManifest(context.companyCode, createdAt.toString(), destructiveRestore = false)).toByteArray())
                zip.closeEntry()

                zip.putNextEntry(ZipEntry("company.json"))
                zip.write(json.encodeToString(context.profile).toByteArray())
                zip.closeEntry()
            }
        }

        return CompanyBackupResponse(
            fileName = output.name,
            sizeBytes = output.fileSize(),
            createdAt = Files.getLastModifiedTime(output).toInstant().toString(),
            downloadUrl = "/companies/me/backups/${output.name}/download",
        )
    }

    // Resuelve el archivo ZIP solicitado dentro del directorio seguro de la empresa.
    fun backupPath(companyCode: String, accessToken: String, fileName: String): Path {
        val context = authorizeBusinessOwner(companyCode, accessToken)
        validateBackupName(fileName)
        val directory = backupDirectory(context.companyCode)
        val path = directory.resolve(fileName).normalize()
        if (!path.startsWith(directory) || !path.exists()) {
            throw CompanySelfException("Backup no encontrado.")
        }
        return path
    }

    // Elimina un ZIP existente despues de validar que pertenece a la empresa.
    fun deleteBackup(companyCode: String, accessToken: String, fileName: String) {
        Files.deleteIfExists(backupPath(companyCode, accessToken, fileName))
    }

    // Guarda un ZIP subido por el usuario sin ejecutar restauracion destructiva.
    fun storeImportedBackup(companyCode: String, accessToken: String, fileName: String, bytes: ByteArray): CompanyBackupResponse {
        val context = authorizeBusinessOwner(companyCode, accessToken)
        validateBackupName(fileName)
        validateZip(bytes)
        val output = backupDirectory(context.companyCode).also { it.createDirectories() }.resolve(fileName)
        output.outputStream().use { it.write(bytes) }
        return CompanyBackupResponse(
            fileName = output.name,
            sizeBytes = output.fileSize(),
            createdAt = Files.getLastModifiedTime(output).toInstant().toString(),
            downloadUrl = "/companies/me/backups/${output.name}/download",
        )
    }

    // Valida que el token pertenezca al business_owner de la empresa indicada.
    private fun authorizeBusinessOwner(companyCode: String, accessToken: String): BusinessOwnerContext {
        val code = companyCode.trim()
        val company = central.connection.use { connection ->
            connection.prepareStatement(
                """
                SELECT code, name, logo_url, primary_color, secondary_color, accent_color, database_name
                FROM companies
                WHERE code = ? AND is_active = TRUE
                """.trimIndent()
            ).use { query ->
                query.setString(1, code)
                query.executeQuery().use { rows ->
                    if (!rows.next()) throw CompanySelfException("Empresa no encontrada.")
                    CompanyRow(
                        code = rows.getString("code"),
                        databaseName = rows.getString("database_name"),
                        profile = CompanyProfileResponse(
                            code = rows.getString("code"),
                            name = rows.getString("name"),
                            logoUrl = rows.getString("logo_url"),
                            primaryColor = rows.getString("primary_color"),
                            secondaryColor = rows.getString("secondary_color"),
                            accentColor = rows.getString("accent_color"),
                        ),
                    )
                }
            }
        }
        // Comprueba la sesion dentro de la base tenant, no en la base central.
        val tenant = loginDatabases.tenantConfig(company.databaseName)
            ?: throw CompanySelfException("La empresa no tiene conexion tenant configurada.")
        val tokenHash = tokenGenerator.hash(accessToken)
        val ownerUserId = tenantConnection(tenant).use { connection ->
            connection.prepareStatement(
                """
                SELECT users.id
                FROM application_sessions sessions
                JOIN application_users users ON users.id = sessions.user_id
                JOIN business_owner owner ON owner.user_id = users.id
                WHERE sessions.token_hash = ?
                  AND sessions.revoked_at IS NULL
                  AND sessions.expires_at > CURRENT_TIMESTAMP
                  AND users.is_active = TRUE
                """.trimIndent()
            ).use { query ->
                query.setString(1, tokenHash)
                query.executeQuery().use { rows ->
                    if (!rows.next()) throw CompanySelfException("Debes iniciar sesion como business_owner.")
                    rows.getObject("id", UUID::class.java)
                }
            }
        }
        return BusinessOwnerContext(company.code, company.databaseName, company.profile, ownerUserId)
    }

    // Abre una conexion runtime al tenant ya resuelto por el servidor.
    private fun tenantConnection(tenant: com.ideasdeveloper.idc.server.auth.infrastructure.database.TenantDatabaseConfig) =
        PGSimpleDataSource().apply {
            setURL(tenant.jdbcUrl)
            setUser(tenant.user)
            setPassword(tenant.password)
        }.connection

    // Agrupa los ZIPs por codigo de empresa bajo el directorio configurado.
    private fun backupDirectory(companyCode: String): Path = backupsRoot.resolve(companyCode).normalize()

    // Rechaza nombres que no sigan el formato generado por el servidor.
    private fun validateBackupName(fileName: String) {
        if (!backupNamePattern.matches(fileName)) {
            throw CompanySelfException("Nombre de backup invalido.")
        }
    }

    // Verifica que el archivo subido sea un ZIP legible y no este vacio.
    private fun validateZip(bytes: ByteArray) {
        ZipInputStream(bytes.inputStream()).use { zip ->
            if (zip.nextEntry == null) {
                throw CompanySelfException("El archivo ZIP esta vacio.")
            }
        }
    }

    // Fila central de empresa necesaria para operaciones de autoservicio.
    private data class CompanyRow(
        val code: String,
        val databaseName: String,
        val profile: CompanyProfileResponse,
    )

    // Contexto autorizado para ejecutar acciones sobre una empresa tenant.
    private data class BusinessOwnerContext(
        val companyCode: String,
        val databaseName: String,
        val profile: CompanyProfileResponse,
        val businessOwnerUserId: UUID,
    )
}

@Serializable
// Manifest minimo incluido dentro de cada ZIP generado por Empresa.
private data class BackupManifest(
    val companyCode: String,
    val createdAt: String,
    val destructiveRestore: Boolean,
)
