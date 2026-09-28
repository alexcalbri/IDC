package com.ideasdeveloper.idc.server.company.application

import com.ideasdeveloper.idc.server.auth.infrastructure.database.LoginDatabases
import com.ideasdeveloper.idc.server.auth.infrastructure.security.ApplicationPasswordHasher
import com.ideasdeveloper.idc.server.auth.infrastructure.security.SessionTokenGenerator
import com.ideasdeveloper.idc.server.company.api.CompanyBackupResponse
import com.ideasdeveloper.idc.server.company.api.CompanyProfileResponse
import com.ideasdeveloper.idc.server.company.api.CompanyPermissionResponse
import com.ideasdeveloper.idc.server.company.api.CompanyUserResponse
import com.ideasdeveloper.idc.server.company.api.CompanyUsersResponse
import com.ideasdeveloper.idc.server.company.api.CreateCompanyUserRequest
import com.ideasdeveloper.idc.server.company.api.UpdateCompanyUserPermissionsRequest
import com.ideasdeveloper.idc.server.company.api.UpdateCompanyProfileRequest
import com.ideasdeveloper.idc.server.modules.ModuleRegistry
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

// Servicio de autoservicio para que el business_owner administre solo su propia empresa
// y, en futuras rutas, usuarios/permisos dentro de su propia base tenant.
// Este servicio no cambia disponibilidad de modulos; esa decision pertenece a server_owner.
class CompanySelfService(
    private val central: DataSource,
    private val loginDatabases: LoginDatabases,
    private val moduleRegistry: ModuleRegistry,
    private val backupsRoot: Path,
) {
    // Reutiliza el hash de sesion para validar tokens sin exponerlos en base de datos.
    private val tokenGenerator = SessionTokenGenerator()
    // Valida colores aceptados por la tabla central companies.
    private val colorPattern = Regex("#[0-9A-Fa-f]{6}")
    // Restringe nombres de ZIP para evitar rutas arbitrarias o archivos ajenos al tenant.
    private val backupNamePattern = Regex("[a-z][a-z0-9_]{0,62}-backup-[0-9]{8}T[0-9]{6}Z\\.zip")
    private val postgresRolePattern = Regex("[a-z][a-z0-9_]{0,62}")
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

    // Genera un ZIP no destructivo con manifest y perfil; la restauracion completa de PostgreSQL es operativa y externa a la app.
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

    fun users(companyCode: String, accessToken: String): CompanyUsersResponse {
        val context = authorizeBusinessOwner(companyCode, accessToken)
        tenantConnection(context.tenant).use { connection ->
            syncPermissionCatalog(connection)
            return CompanyUsersResponse(
                users = loadUsers(connection),
                permissions = loadPermissions(connection),
            )
        }
    }

    fun createUser(companyCode: String, accessToken: String, request: CreateCompanyUserRequest): CompanyUserResponse {
        val context = authorizeBusinessOwner(companyCode, accessToken)
        val username = request.username.trim()
        if (!postgresRolePattern.matches(username)) {
            throw CompanySelfException("El usuario debe iniciar con una letra minuscula y usar solo letras, numeros o guion bajo.")
        }
        if (request.password.length < 12) {
            throw CompanySelfException("La contrasena del usuario debe tener al menos 12 caracteres.")
        }

        val userId = UUID.randomUUID()
        val passwordHash = ApplicationPasswordHasher.hash(request.password)
        tenantConnection(context.tenant).use { connection ->
            syncPermissionCatalog(connection)
            validatePermissions(connection, request.permissions)
            connection.autoCommit = false
            try {
                if (connection.existsUser(username)) {
                    throw CompanySelfException("Ya existe un usuario con ese nombre.")
                }
                connection.prepareStatement("INSERT INTO application_users (id, postgres_role, is_active) VALUES (?, ?, TRUE)").use { insert ->
                    insert.setObject(1, userId)
                    insert.setString(2, username)
                    insert.executeUpdate()
                }
                connection.prepareStatement(
                    """
                    INSERT INTO application_user_credentials (user_id, password_salt, password_hash, iterations)
                    VALUES (?, ?, ?, ?)
                    """.trimIndent()
                ).use { insert ->
                    insert.setObject(1, userId)
                    insert.setString(2, passwordHash.salt)
                    insert.setString(3, passwordHash.hash)
                    insert.setInt(4, passwordHash.iterations)
                    insert.executeUpdate()
                }
                replaceUserPermissions(connection, userId, request.permissions)
                connection.commit()
            } catch (failure: Exception) {
                connection.rollback()
                throw failure
            } finally {
                connection.autoCommit = true
            }
            return loadUser(connection, userId) ?: throw CompanySelfException("No se pudo cargar el usuario creado.")
        }
    }

    fun updateUserPermissions(
        companyCode: String,
        accessToken: String,
        userId: String,
        request: UpdateCompanyUserPermissionsRequest,
    ): CompanyUserResponse {
        val context = authorizeBusinessOwner(companyCode, accessToken)
        val targetUserId = try {
            UUID.fromString(userId)
        } catch (_: IllegalArgumentException) {
            throw CompanySelfException("Usuario invalido.")
        }
        if (targetUserId == context.businessOwnerUserId) {
            throw CompanySelfException("No puedes quitar permisos del business_owner desde este flujo.")
        }
        tenantConnection(context.tenant).use { connection ->
            syncPermissionCatalog(connection)
            validatePermissions(connection, request.permissions)
            connection.autoCommit = false
            try {
                if (!connection.userExists(targetUserId)) {
                    throw CompanySelfException("Usuario no encontrado.")
                }
                replaceUserPermissions(connection, targetUserId, request.permissions)
                connection.commit()
            } catch (failure: Exception) {
                connection.rollback()
                throw failure
            } finally {
                connection.autoCommit = true
            }
            return loadUser(connection, targetUserId) ?: throw CompanySelfException("Usuario no encontrado.")
        }
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
                SELECT users.id, users.postgres_role
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
                    rows.getObject("id", UUID::class.java) to rows.getString("postgres_role")
                }
            }
        }
        return BusinessOwnerContext(
            companyCode = company.code,
            databaseName = company.databaseName,
            tenant = tenant,
            profile = company.profile,
            businessOwnerUserId = ownerUserId.first,
            businessOwnerRole = ownerUserId.second,
        )
    }

    private fun syncPermissionCatalog(connection: java.sql.Connection) {
        val enabledModuleIds = connection.prepareStatement(
            "SELECT module_id FROM tenant_modules WHERE status = 'enabled'"
        ).use { query ->
            query.executeQuery().use { rows ->
                buildSet {
                    add("empresa")
                    while (rows.next()) add(rows.getString("module_id"))
                }
            }
        }
        moduleRegistry.definitions.flatMap { definition ->
            definition.permissions.map { permission -> definition.id to permission }
        }.filter { (moduleId, _) -> moduleId in enabledModuleIds }.forEach { (moduleId, permission) ->
            connection.prepareStatement(
                """
                INSERT INTO application_permissions (permission_id, module_id, title, description)
                VALUES (?, ?, ?, ?)
                ON CONFLICT (permission_id)
                DO UPDATE SET module_id = EXCLUDED.module_id, title = EXCLUDED.title, description = EXCLUDED.description
                """.trimIndent()
            ).use { upsert ->
                upsert.setString(1, permission.id)
                upsert.setString(2, moduleId)
                upsert.setString(3, permission.title)
                upsert.setString(4, permission.description)
                upsert.executeUpdate()
            }
        }
    }

    private fun loadPermissions(connection: java.sql.Connection): List<CompanyPermissionResponse> =
        connection.prepareStatement(
            "SELECT permission_id, module_id, title, description FROM application_permissions ORDER BY module_id, permission_id"
        ).use { query ->
            query.executeQuery().use { rows ->
                buildList {
                    while (rows.next()) {
                        add(
                            CompanyPermissionResponse(
                                permissionId = rows.getString("permission_id"),
                                moduleId = rows.getString("module_id"),
                                title = rows.getString("title"),
                                description = rows.getString("description"),
                            )
                        )
                    }
                }
            }
        }

    private fun loadUsers(connection: java.sql.Connection): List<CompanyUserResponse> =
        connection.prepareStatement("SELECT id FROM application_users ORDER BY postgres_role").use { query ->
            query.executeQuery().use { rows ->
                buildList {
                    while (rows.next()) {
                        loadUser(connection, rows.getObject("id", UUID::class.java))?.let(::add)
                    }
                }
            }
        }

    private fun loadUser(connection: java.sql.Connection, userId: UUID): CompanyUserResponse? =
        connection.prepareStatement("SELECT id, postgres_role, is_active FROM application_users WHERE id = ?").use { query ->
            query.setObject(1, userId)
            query.executeQuery().use { rows ->
                if (!rows.next()) return null
                CompanyUserResponse(
                    userId = rows.getObject("id", UUID::class.java).toString(),
                    username = rows.getString("postgres_role"),
                    isActive = rows.getBoolean("is_active"),
                    permissions = loadUserPermissions(connection, userId),
                )
            }
        }

    private fun loadUserPermissions(connection: java.sql.Connection, userId: UUID): List<String> =
        connection.prepareStatement("SELECT permission_id FROM application_user_permissions WHERE user_id = ? ORDER BY permission_id").use { query ->
            query.setObject(1, userId)
            query.executeQuery().use { rows ->
                buildList {
                    while (rows.next()) add(rows.getString("permission_id"))
                }
            }
        }

    private fun validatePermissions(connection: java.sql.Connection, permissions: List<String>) {
        val known = loadPermissions(connection).map { it.permissionId }.toSet()
        val unknown = permissions.toSet() - known
        if (unknown.isNotEmpty()) {
            throw CompanySelfException("Permisos no reconocidos: ${unknown.joinToString()}.")
        }
    }

    private fun replaceUserPermissions(connection: java.sql.Connection, userId: UUID, permissions: List<String>) {
        connection.prepareStatement("DELETE FROM application_user_permissions WHERE user_id = ?").use { delete ->
            delete.setObject(1, userId)
            delete.executeUpdate()
        }
        permissions.distinct().forEach { permissionId ->
            connection.prepareStatement("INSERT INTO application_user_permissions (user_id, permission_id) VALUES (?, ?)").use { insert ->
                insert.setObject(1, userId)
                insert.setString(2, permissionId)
                insert.executeUpdate()
            }
        }
    }

    private fun java.sql.Connection.userExists(userId: UUID): Boolean =
        prepareStatement("SELECT 1 FROM application_users WHERE id = ?").use { query ->
            query.setObject(1, userId)
            query.executeQuery().use { rows -> rows.next() }
        }

    private fun java.sql.Connection.existsUser(name: String): Boolean =
        prepareStatement("SELECT 1 FROM application_users WHERE postgres_role = ?").use { query ->
            query.setString(1, name)
            query.executeQuery().use { rows -> rows.next() }
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
        val tenant: com.ideasdeveloper.idc.server.auth.infrastructure.database.TenantDatabaseConfig,
        val profile: CompanyProfileResponse,
        val businessOwnerUserId: UUID,
        val businessOwnerRole: String,
    )

}

@Serializable
// Manifest minimo incluido dentro de cada ZIP generado por Empresa.
private data class BackupManifest(
    val companyCode: String,
    val createdAt: String,
    val destructiveRestore: Boolean,
)
