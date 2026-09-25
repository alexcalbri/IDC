package com.ideasdeveloper.idc.app.features.company.data

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
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

class CompanyApi(serverUrl: String) {
    private val baseUrl = serverUrl.trim().trimEnd('/')

    init {
        Url(baseUrl)
    }

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

    fun close() {
        client.close()
    }
}

class CompanyException(message: String) : Exception(message)
