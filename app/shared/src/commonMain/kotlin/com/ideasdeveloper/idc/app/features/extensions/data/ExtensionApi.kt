package com.ideasdeveloper.idc.app.features.extensions.data

import com.ideasdeveloper.idc.app.core.session.SessionExpiry
import com.ideasdeveloper.idc.app.core.version.installAppVersionHeader
import com.ideasdeveloper.idc.app.features.company.data.CompanyException
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.forms.MultiPartFormDataContent
import io.ktor.client.request.forms.formData
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.http.ContentType
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import io.ktor.http.Url
import io.ktor.http.contentType
import io.ktor.http.encodeURLPathPart
import io.ktor.http.isSuccess
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Empresa sobre la que se actua: el business owner usa `/companies/current` con su codigo en la
 * cabecera X-Company-Code; el server owner sobre otra empresa usa `/companies/{code}`.
 */
data class ExtensionTarget(val companyCode: String, val asServerOwner: Boolean) {
    internal val base: String
        get() = if (asServerOwner) "/companies/${companyCode.encodeURLPathPart()}" else "/companies/current"
}

// Cliente HTTP de extensiones por empresa, grupo vinculado y paquetes del servidor.
class ExtensionApi(serverUrl: String) {
    private val baseUrl = serverUrl.trim().trimEnd('/')

    init {
        Url(baseUrl)
    }

    private val client = HttpClient {
        installAppVersionHeader()
        expectSuccess = false
        followRedirects = false
        install(SessionExpiry)
        install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
        install(HttpTimeout) {
            requestTimeoutMillis = 120_000
            connectTimeoutMillis = 10_000
        }
    }

    // Ejecuta una llamada y convierte cualquier fallo en CompanyException con el mensaje del servidor.
    private suspend fun <T> call(block: suspend () -> T): T = try {
        block()
    } catch (exception: CancellationException) {
        throw exception
    } catch (exception: CompanyException) {
        throw exception
    } catch (_: Exception) {
        throw CompanyException("No se pudo comunicar con el servidor.", network = true)
    }

    // Lanza el mensaje {code, message} del servidor cuando la respuesta no es correcta.
    private suspend fun HttpResponse.ensureOk() {
        if (status.isSuccess()) return
        val text = try {
            body<ExtensionErrorResponse>().message
        } catch (_: Exception) {
            ""
        }
        throw CompanyException(text.ifBlank { "El servidor respondio ${status.value}." }, status = status.value)
    }

    private fun HttpRequestBuilder.scoped(token: String, target: ExtensionTarget) {
        bearerAuth(token)
        if (!target.asServerOwner) header("X-Company-Code", target.companyCode)
    }

    private fun HttpRequestBuilder.jsonBody(body: Any) {
        contentType(ContentType.Application.Json)
        setBody(body)
    }

    suspend fun list(token: String, target: ExtensionTarget): List<ExtensionSummary> = call {
        val response = client.get("$baseUrl${target.base}/extensions") { scoped(token, target) }
        response.ensureOk()
        response.body<ExtensionListResponse>().extensions
    }

    suspend fun install(token: String, target: ExtensionTarget, id: String) = call {
        client.post("$baseUrl${target.base}/extensions/${id.encodeURLPathPart()}/install") { scoped(token, target) }.ensureOk()
    }

    suspend fun configure(token: String, target: ExtensionTarget, id: String, settings: Map<String, String>) = call {
        client.put("$baseUrl${target.base}/extensions/${id.encodeURLPathPart()}/settings") {
            scoped(token, target)
            jsonBody(ConfigureExtensionRequest(settings))
        }.ensureOk()
    }

    suspend fun setEnabled(token: String, target: ExtensionTarget, id: String, enabled: Boolean) = call {
        client.put("$baseUrl${target.base}/extensions/${id.encodeURLPathPart()}/enabled") {
            scoped(token, target)
            jsonBody(SetExtensionEnabledRequest(enabled))
        }.ensureOk()
    }

    suspend fun uninstallPreview(token: String, target: ExtensionTarget, id: String): UninstallPreviewResponse = call {
        val response = client.get("$baseUrl${target.base}/extensions/${id.encodeURLPathPart()}/uninstall-preview") { scoped(token, target) }
        response.ensureOk()
        response.body<UninstallPreviewResponse>()
    }

    suspend fun uninstall(token: String, target: ExtensionTarget, id: String, choices: Map<String, String>) = call {
        client.post("$baseUrl${target.base}/extensions/${id.encodeURLPathPart()}/uninstall") {
            scoped(token, target)
            jsonBody(UninstallExtensionRequest(choices))
        }.ensureOk()
    }

    suspend fun previewGroup(token: String, target: ExtensionTarget, id: String, request: GroupOperationRequest): GroupOperationPreview = call {
        val response = client.post("$baseUrl${target.base}/extensions/${id.encodeURLPathPart()}/group/preview") {
            scoped(token, target)
            jsonBody(request)
        }
        response.ensureOk()
        response.body<GroupOperationPreview>()
    }

    suspend fun applyGroup(token: String, target: ExtensionTarget, id: String, request: GroupOperationRequest): GroupOperationResult = call {
        val response = client.post("$baseUrl${target.base}/extensions/${id.encodeURLPathPart()}/group/apply") {
            scoped(token, target)
            jsonBody(request)
        }
        response.ensureOk()
        response.body<GroupOperationResult>()
    }

    suspend fun retryGroup(
        token: String,
        target: ExtensionTarget,
        id: String,
        operationId: Long,
        request: GroupOperationRequest,
    ): GroupOperationResult = call {
        val response = client.post("$baseUrl${target.base}/extensions/${id.encodeURLPathPart()}/group/operations/$operationId/retry") {
            scoped(token, target)
            jsonBody(request)
        }
        response.ensureOk()
        response.body<GroupOperationResult>()
    }

    // Grupo vinculado: el business owner consulta el suyo; el server owner el de cualquier empresa.
    suspend fun companyGroup(token: String, target: ExtensionTarget): GroupResponse = call {
        val response = client.get("$baseUrl${target.base}/group") { scoped(token, target) }
        response.ensureOk()
        response.body<GroupResponse>()
    }

    suspend fun reviewGroup(token: String, companyCode: String): GroupResponse = call {
        val response = client.post("$baseUrl/companies/${companyCode.encodeURLPathPart()}/group/review") { bearerAuth(token) }
        response.ensureOk()
        response.body<GroupResponse>()
    }

    // Paquetes externos (solo server owner).
    suspend fun packageCatalog(token: String): List<ExtensionPackageEntry> = call {
        val response = client.get("$baseUrl/server/extensions/catalog") { bearerAuth(token) }
        response.ensureOk()
        response.body<List<ExtensionPackageEntry>>()
    }

    suspend fun packageStatus(token: String, id: String): ExtensionPackageStatus = call {
        val response = client.get("$baseUrl/server/extensions/${id.encodeURLPathPart()}/status") { bearerAuth(token) }
        response.ensureOk()
        response.body<ExtensionPackageStatus>()
    }

    suspend fun installPackage(token: String, id: String): ExtensionPackageStatus = call {
        val response = client.post("$baseUrl/server/extensions/${id.encodeURLPathPart()}/install") { bearerAuth(token) }
        // El servidor responde 202 mientras compila el paquete.
        response.ensureOk()
        response.body<ExtensionPackageStatus>()
    }

    // Descarga el paquete y devuelve commit y hash calculados; puede tardar.
    suspend fun inspectPackage(token: String, id: String): ExtensionPackageInspection = call {
        val response = client.get("$baseUrl/server/extensions/${id.encodeURLPathPart()}/inspect") { bearerAuth(token) }
        response.ensureOk()
        response.body<ExtensionPackageInspection>()
    }

    // Responde 409 (PACKAGE_CHANGED) si lo descargado ahora ya no es lo que se reviso.
    suspend fun authorizeUnsigned(token: String, id: String, confirmationText: String, expectedPackageSha256: String, expectedCommit: String) = call {
        client.post("$baseUrl/server/extensions/${id.encodeURLPathPart()}/authorize-unsigned") {
            bearerAuth(token)
            jsonBody(AuthorizeUnsignedRequest(confirmationText, expectedPackageSha256, expectedCommit))
        }.ensureOk()
    }

    // Instalacion desde ZIP o URL (solo server owner): el servidor deja el paquete en preparacion
    // (staging, caduca a los 30 min) y devuelve su inspeccion; luego se autoriza (si no esta firmado) e instala.
    suspend fun uploadPackage(token: String, id: String, fileName: String, bytes: ByteArray): ExtensionPackageStagedInspection = call {
        val response = client.post("$baseUrl/server/extensions/upload") {
            bearerAuth(token)
            setBody(
                MultiPartFormDataContent(
                    formData {
                        append("id", id)
                        append("file", bytes, Headers.build {
                            append(HttpHeaders.ContentType, "application/zip")
                            append(HttpHeaders.ContentDisposition, "filename=\"${fileName.replace("\"", "")}\"")
                        })
                    },
                ),
            )
        }
        response.ensureOk()
        response.body<ExtensionPackageStagedInspection>()
    }

    suspend fun packageFromUrl(token: String, id: String, url: String): ExtensionPackageStagedInspection = call {
        val response = client.post("$baseUrl/server/extensions/from-url") {
            bearerAuth(token)
            jsonBody(PackageFromUrlRequest(id, url))
        }
        response.ensureOk()
        response.body<ExtensionPackageStagedInspection>()
    }

    // Responde 409 (PACKAGE_CHANGED) si el paquete en preparacion ya no es el revisado, y 404 si caduco.
    suspend fun authorizeStaged(token: String, stagingToken: String, confirmationText: String, expectedPackageSha256: String): UnsignedAuthorizationResponse = call {
        val response = client.post("$baseUrl/server/extensions/staged/${stagingToken.encodeURLPathPart()}/authorize-unsigned") {
            bearerAuth(token)
            jsonBody(AuthorizeStagedRequest(confirmationText, expectedPackageSha256))
        }
        response.ensureOk()
        response.body<UnsignedAuthorizationResponse>()
    }

    suspend fun installStaged(token: String, stagingToken: String): ExtensionPackageStatus = call {
        val response = client.post("$baseUrl/server/extensions/staged/${stagingToken.encodeURLPathPart()}/install") { bearerAuth(token) }
        // El servidor responde 202 mientras compila el paquete.
        response.ensureOk()
        response.body<ExtensionPackageStatus>()
    }

    suspend fun removePackage(token: String, id: String): ExtensionPackageStatus = call {
        val response = client.delete("$baseUrl/server/extensions/${id.encodeURLPathPart()}") { bearerAuth(token) }
        response.ensureOk()
        response.body<ExtensionPackageStatus>()
    }

    fun close() {
        client.close()
    }
}

// --- Modelos espejo de ExtensionModels.kt del servidor (solo lo que usa la app). ---

@Serializable
data class ExtensionErrorResponse(val code: String = "", val message: String = "")

@Serializable
data class ExtensionSettingView(
    val key: String,
    val label: String = "",
    val secret: Boolean = false,
    val required: Boolean = false,
    val help: String = "",
    val value: String = "",
    val isSet: Boolean = false,
)

@Serializable
data class ExtensionUninstallOption(val id: String, val label: String = "", val default: String = "ask")

@Serializable
data class ExtensionSummary(
    val id: String,
    val module: String = "",
    val displayName: String = "",
    val description: String = "",
    val version: String = "",
    val minModuleVersion: String? = null,
    val sourceType: String = "",
    val permissions: List<String> = emptyList(),
    val uninstallOptions: List<ExtensionUninstallOption> = emptyList(),
    /** Estado en esta empresa; null si nunca se instalo o se quito. */
    val status: String? = null,
    val moduleInstalled: Boolean = true,
    val moduleEnabled: Boolean = true,
    val compatible: Boolean = true,
    val settings: List<ExtensionSettingView> = emptyList(),
    val missingRequired: List<String> = emptyList(),
)

@Serializable
data class ExtensionListResponse(val companyCode: String = "", val extensions: List<ExtensionSummary> = emptyList())

@Serializable
data class ConfigureExtensionRequest(val settings: Map<String, String> = emptyMap())

@Serializable
data class SetExtensionEnabledRequest(val enabled: Boolean)

@Serializable
data class UninstallItem(val optionId: String? = null, val label: String = "", val count: Int = 0, val action: String = "")

@Serializable
data class UninstallOptionView(val id: String, val label: String = "", val default: String = "ask")

@Serializable
data class UninstallPreviewResponse(
    val extensionId: String = "",
    val companyCode: String = "",
    val items: List<UninstallItem> = emptyList(),
    val options: List<UninstallOptionView> = emptyList(),
)

@Serializable
data class UninstallExtensionRequest(val choices: Map<String, String> = emptyMap())

object GroupOperationType {
    const val Apply = "apply"
    const val Uninstall = "uninstall"
}

@Serializable
data class GroupOperationRequest(
    val operation: String = GroupOperationType.Apply,
    val targets: List<String> = emptyList(),
    val choices: Map<String, String> = emptyMap(),
)

@Serializable
data class GroupTargetPreview(
    val companyCode: String,
    val name: String = "",
    val steps: List<String> = emptyList(),
    val problem: String? = null,
)

@Serializable
data class GroupOperationPreview(
    val extensionId: String = "",
    val operation: String = "",
    val sourceCompany: String = "",
    val targets: List<GroupTargetPreview> = emptyList(),
)

@Serializable
data class GroupTargetResult(
    val companyCode: String,
    /** pending, applied, failed o skipped. */
    val status: String = "pending",
    val reason: String? = null,
    val attempts: Int = 0,
)

@Serializable
data class GroupOperationResult(
    val operationId: Long = 0,
    val extensionId: String = "",
    val operation: String = "",
    val sourceCompany: String = "",
    val status: String = "",
    val targets: List<GroupTargetResult> = emptyList(),
)

@Serializable
data class GroupMemberView(
    val code: String,
    val name: String = "",
    val suspended: Boolean = false,
    val ownerEmailMatches: Boolean = true,
)

@Serializable
data class GroupResponse(val companyCode: String = "", val members: List<GroupMemberView> = emptyList())

// --- Paquetes externos del servidor (ExtensionPackageService del servidor). ---

@Serializable
data class ExtensionPackageEntry(
    val id: String,
    val module: String = "",
    val displayName: String = "",
    val description: String = "",
    val version: String = "",
    val minModuleVersion: String? = null,
    val permissions: List<String> = emptyList(),
    val signed: Boolean = false,
    val keyId: String? = null,
    val packageSha256: String? = null,
    val commit: String = "",
    val commitUrl: String = "",
    /** not_installed | installing | error | pending_restart | installed | removed */
    val status: String = "not_installed",
    val installedVersion: String? = null,
    val message: String? = null,
    val restartRequired: Boolean = false,
)

@Serializable
data class ExtensionPackageStatus(
    val id: String,
    val status: String = "not_installed",
    val installedVersion: String? = null,
    val message: String? = null,
    val restartRequired: Boolean = false,
)

@Serializable
data class AuthorizeUnsignedRequest(
    val confirmationText: String = "",
    val expectedPackageSha256: String = "",
    val expectedCommit: String = "",
)

@Serializable
data class ExtensionPackageInspection(
    val id: String = "",
    val commit: String = "",
    val commitUrl: String = "",
    /** Hash del arbol calculado sobre lo descargado. */
    val packageSha256: String = "",
    val signed: Boolean = false,
    val keyId: String? = null,
    val signatureProblem: String? = null,
)

// Paquete subido (ZIP) o descargado (URL) y dejado en preparacion por el servidor.
@Serializable
data class ExtensionPackageStagedInspection(
    val id: String = "",
    val source: String = "",
    val sourceUrl: String? = null,
    val stagingToken: String = "",
    val packageSha256: String = "",
    val signed: Boolean = false,
    val keyId: String? = null,
    val signatureProblem: String? = null,
    val installable: Boolean = false,
)

@Serializable
data class PackageFromUrlRequest(val id: String, val url: String)

@Serializable
data class AuthorizeStagedRequest(val confirmationText: String = "", val expectedPackageSha256: String = "")

// Respuesta de autorizar sin firma; la app solo necesita saber que salio bien.
@Serializable
data class UnsignedAuthorizationResponse(
    val id: String = "",
    val packageSha256: String = "",
    val authorized: Boolean = true,
)
