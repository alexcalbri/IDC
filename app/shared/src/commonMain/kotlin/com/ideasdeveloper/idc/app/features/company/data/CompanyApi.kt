package com.ideasdeveloper.idc.app.features.company.data

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.Url
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json

// Cliente HTTP para administrar tenants, estado de empresas y modulos instalados.
class CompanyApi(serverUrl: String) {
    // Normaliza la URL base configurada en el login.
    private val baseUrl = serverUrl.trim().trimEnd('/')

    init {
        // Valida que la URL sea parseable antes de lanzar solicitudes.
        Url(baseUrl)
    }

    // Carga el perfil editable de la empresa de la sesion business_owner.
    suspend fun companyProfile(accessToken: String, companyCode: String): CompanyProfileResponse {
        try {
            val response = client.get("$baseUrl/companies/me") {
                bearerAuth(accessToken)
                header("X-Company-Code", companyCode)
            }

            if (response.status == HttpStatusCode.OK) {
                return response.body()
            }

            if (response.status == HttpStatusCode.Forbidden || response.status == HttpStatusCode.ServiceUnavailable) {
                val error = response.body<CompanyErrorResponse>()
                throw CompanyException(error.message)
            }

            throw CompanyException("El servidor no pudo cargar la empresa.")
        } catch (exception: CancellationException) {
            throw exception
        } catch (exception: CompanyException) {
            throw exception
        } catch (_: Exception) {
            throw CompanyException("No se pudo comunicar con el servidor.")
        }
    }

    // Actualiza nombre, logo y colores de la empresa actual.
    suspend fun updateCompanyProfile(
        accessToken: String,
        companyCode: String,
        request: UpdateCompanyProfileRequest,
    ): CompanyProfileResponse {
        try {
            val response = client.put("$baseUrl/companies/me") {
                bearerAuth(accessToken)
                header("X-Company-Code", companyCode)
                contentType(ContentType.Application.Json)
                setBody(request)
            }

            if (response.status == HttpStatusCode.OK) {
                return response.body()
            }

            if (
                response.status == HttpStatusCode.BadRequest ||
                response.status == HttpStatusCode.Forbidden ||
                response.status == HttpStatusCode.Conflict ||
                response.status == HttpStatusCode.ServiceUnavailable
            ) {
                val error = response.body<CompanyErrorResponse>()
                throw CompanyException(error.message)
            }

            throw CompanyException("El servidor no pudo actualizar la empresa.")
        } catch (exception: CancellationException) {
            throw exception
        } catch (exception: CompanyException) {
            throw exception
        } catch (_: Exception) {
            throw CompanyException("No se pudo comunicar con el servidor.")
        }
    }

    // Lista los ZIPs de backup creados o importados para esta empresa.
    suspend fun listBackups(accessToken: String, companyCode: String): List<CompanyBackupResponse> {
        try {
            val response = client.get("$baseUrl/companies/me/backups") {
                bearerAuth(accessToken)
                header("X-Company-Code", companyCode)
            }

            if (response.status == HttpStatusCode.OK) {
                return response.body<CompanyBackupListResponse>().backups
            }

            val error = response.body<CompanyErrorResponse>()
            throw CompanyException(error.message)
        } catch (exception: CancellationException) {
            throw exception
        } catch (exception: CompanyException) {
            throw exception
        } catch (_: Exception) {
            throw CompanyException("No se pudo comunicar con el servidor.")
        }
    }

    // Crea un ZIP descargable desde el servidor.
    suspend fun createBackup(accessToken: String, companyCode: String): CompanyBackupResponse {
        try {
            val response = client.post("$baseUrl/companies/me/backups") {
                bearerAuth(accessToken)
                header("X-Company-Code", companyCode)
            }

            if (response.status == HttpStatusCode.Created) {
                return response.body()
            }

            val error = response.body<CompanyErrorResponse>()
            throw CompanyException(error.message)
        } catch (exception: CancellationException) {
            throw exception
        } catch (exception: CompanyException) {
            throw exception
        } catch (_: Exception) {
            throw CompanyException("No se pudo comunicar con el servidor.")
        }
    }

    // Elimina un ZIP de backup ya creado en el servidor.
    suspend fun deleteBackup(accessToken: String, companyCode: String, fileName: String) {
        try {
            val response = client.delete("$baseUrl/companies/me/backups/$fileName") {
                bearerAuth(accessToken)
                header("X-Company-Code", companyCode)
            }

            if (response.status == HttpStatusCode.NoContent) return

            val error = response.body<CompanyErrorResponse>()
            throw CompanyException(error.message)
        } catch (exception: CancellationException) {
            throw exception
        } catch (exception: CompanyException) {
            throw exception
        } catch (_: Exception) {
            throw CompanyException("No se pudo comunicar con el servidor.")
        }
    }

    // Cliente Ktor usado por las operaciones administrativas de empresa.
    private val client = HttpClient {
        expectSuccess = false
        followRedirects = false

        install(ContentNegotiation) {
            json(Json { ignoreUnknownKeys = true })
        }

        install(HttpTimeout) {
            requestTimeoutMillis = 60_000
            connectTimeoutMillis = 10_000
        }
    }

    // Crea un tenant y su usuario business owner inicial.
    suspend fun createCompany(accessToken: String, request: CreateCompanyRequest): CreateCompanyResponse {
        try {
            val response = client.post("$baseUrl/companies") {
                bearerAuth(accessToken)
                contentType(ContentType.Application.Json)
                setBody(request)
            }

            if (response.status == HttpStatusCode.Created) {
                return response.body<CreateCompanyResponse>()
            }

            if (
                response.status == HttpStatusCode.BadRequest ||
                response.status == HttpStatusCode.Forbidden ||
                response.status == HttpStatusCode.Conflict ||
                response.status == HttpStatusCode.ServiceUnavailable
            ) {
                val error = response.body<CompanyErrorResponse>()
                throw CompanyException(error.message)
            }

            throw CompanyException("El servidor no pudo crear la empresa.")
        } catch (exception: CancellationException) {
            throw exception
        } catch (exception: CompanyException) {
            throw exception
        } catch (_: Exception) {
            throw CompanyException("No se pudo comunicar con el servidor.")
        }
    }

    // Lista empresas existentes junto con sus modulos disponibles.
    suspend fun listCompanies(accessToken: String): List<CompanySummaryResponse> {
        try {
            val response = client.get("$baseUrl/companies") {
                bearerAuth(accessToken)
            }

            if (response.status == HttpStatusCode.OK) {
                return response.body()
            }

            if (
                response.status == HttpStatusCode.Forbidden ||
                response.status == HttpStatusCode.ServiceUnavailable
            ) {
                val error = response.body<CompanyErrorResponse>()
                throw CompanyException(error.message)
            }

            throw CompanyException("El servidor no pudo cargar las empresas.")
        } catch (exception: CancellationException) {
            throw exception
        } catch (exception: CompanyException) {
            throw exception
        } catch (_: Exception) {
            throw CompanyException("No se pudo comunicar con el servidor.")
        }
    }


    // Activa o desactiva una empresa sin cambiar sus modulos individualmente.
    suspend fun updateCompanyStatus(
        accessToken: String,
        companyCode: String,
        active: Boolean,
    ): CompanySummaryResponse {
        try {
            val response = client.put("$baseUrl/companies/$companyCode/status") {
                bearerAuth(accessToken)
                contentType(ContentType.Application.Json)
                setBody(UpdateCompanyStatusRequest(active))
            }

            if (response.status == HttpStatusCode.OK) {
                return response.body()
            }

            if (
                response.status == HttpStatusCode.BadRequest ||
                response.status == HttpStatusCode.Forbidden ||
                response.status == HttpStatusCode.Conflict ||
                response.status == HttpStatusCode.ServiceUnavailable
            ) {
                val error = response.body<CompanyErrorResponse>()
                throw CompanyException(error.message)
            }

            throw CompanyException("El servidor no pudo actualizar la empresa.")
        } catch (exception: CancellationException) {
            throw exception
        } catch (exception: CompanyException) {
            throw exception
        } catch (_: Exception) {
            throw CompanyException("No se pudo comunicar con el servidor.")
        }
    }

    // Elimina una empresa desactivada y delega al servidor el borrado de su base tenant.
    suspend fun deleteCompany(accessToken: String, companyCode: String) {
        try {
            val response = client.delete("$baseUrl/companies/$companyCode") {
                bearerAuth(accessToken)
            }

            if (response.status == HttpStatusCode.NoContent) {
                return
            }

            if (
                response.status == HttpStatusCode.Forbidden ||
                response.status == HttpStatusCode.Conflict ||
                response.status == HttpStatusCode.ServiceUnavailable
            ) {
                val error = response.body<CompanyErrorResponse>()
                throw CompanyException(error.message)
            }

            throw CompanyException("El servidor no pudo eliminar la empresa.")
        } catch (exception: CancellationException) {
            throw exception
        } catch (exception: CompanyException) {
            throw exception
        } catch (_: Exception) {
            throw CompanyException("No se pudo comunicar con el servidor.")
        }
    }

    // Activa o desactiva un modulo especifico para una empresa.
    suspend fun updateModule(
        accessToken: String,
        companyCode: String,
        moduleId: String,
        enabled: Boolean,
    ): CompanySummaryResponse {
        try {
            val response = client.put("$baseUrl/companies/$companyCode/modules/$moduleId") {
                bearerAuth(accessToken)
                contentType(ContentType.Application.Json)
                setBody(UpdateCompanyModuleRequest(enabled))
            }

            if (response.status == HttpStatusCode.OK) {
                return response.body()
            }

            if (
                response.status == HttpStatusCode.BadRequest ||
                response.status == HttpStatusCode.Forbidden ||
                response.status == HttpStatusCode.Conflict ||
                response.status == HttpStatusCode.ServiceUnavailable
            ) {
                val error = response.body<CompanyErrorResponse>()
                throw CompanyException(error.message)
            }

            throw CompanyException("El servidor no pudo actualizar el modulo.")
        } catch (exception: CancellationException) {
            throw exception
        } catch (exception: CompanyException) {
            throw exception
        } catch (_: Exception) {
            throw CompanyException("No se pudo comunicar con el servidor.")
        }
    }

    // Libera recursos del cliente HTTP.
    fun close() {
        client.close()
    }
}

class CompanyException(message: String) : Exception(message)
