package com.ideasdeveloper.idc.app.features.modules.data

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
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.Json

// Cliente HTTP para consultar metadata de modulos server-driven.
class ModuleApi(serverUrl: String) {
    // Normaliza la URL base configurada durante el login.
    private val baseUrl = serverUrl.trim().trimEnd('/')

    init {
        // Valida que la URL sea parseable antes de construir endpoints.
        Url(baseUrl)
    }

    // Cliente Ktor ligero para obtener definiciones de modulo.
    private val client = HttpClient {
        expectSuccess = false
        followRedirects = false

        install(ContentNegotiation) {
            json(Json { ignoreUnknownKeys = true })
        }

        install(HttpTimeout) {
            requestTimeoutMillis = 20_000
            connectTimeoutMillis = 10_000
        }
    }

    // Carga la definicion que la UI generica usara para presentar el modulo.
    suspend fun metadata(moduleId: String): ModuleDefinition {
        try {
            val response = client.get("$baseUrl/modules/$moduleId/metadata")
            if (response.status == HttpStatusCode.OK) {
                return response.body()
            }
            throw ModuleException("El servidor no encontro el modulo.")
        } catch (exception: CancellationException) {
            throw exception
        } catch (exception: ModuleException) {
            throw exception
        } catch (_: Exception) {
            throw ModuleException("No se pudo cargar el modulo desde el servidor.")
        }
    }

    suspend fun customers(accessToken: String, companyCode: String): CustomersResponse {
        try {
            val response = client.get("$baseUrl/modules/clientes/customers") {
                bearerAuth(accessToken)
                header("X-Company-Code", companyCode)
            }
            if (response.status == HttpStatusCode.OK) return response.body()
            throw ModuleException("No se pudieron cargar los clientes.")
        } catch (exception: CancellationException) {
            throw exception
        } catch (exception: ModuleException) {
            throw exception
        } catch (_: Exception) {
            throw ModuleException("No se pudo comunicar con el servidor.")
        }
    }

    suspend fun createCustomer(accessToken: String, companyCode: String, request: CreateCustomerRequest): CustomerResponse {
        try {
            val response = client.post("$baseUrl/modules/clientes/customers") {
                bearerAuth(accessToken)
                header("X-Company-Code", companyCode)
                contentType(ContentType.Application.Json)
                setBody(request)
            }
            if (response.status == HttpStatusCode.Created) return response.body()
            throw ModuleException("No se pudo crear el cliente. Verifica nombre, correo y telefono.")
        } catch (exception: CancellationException) {
            throw exception
        } catch (exception: ModuleException) {
            throw exception
        } catch (_: Exception) {
            throw ModuleException("No se pudo comunicar con el servidor.")
        }
    }

    suspend fun updateCustomer(accessToken: String, companyCode: String, customerId: String, request: CreateCustomerRequest): CustomerResponse {
        try {
            val response = client.put("$baseUrl/modules/clientes/customers/$customerId") {
                bearerAuth(accessToken)
                header("X-Company-Code", companyCode)
                contentType(ContentType.Application.Json)
                setBody(request)
            }
            if (response.status == HttpStatusCode.OK) return response.body()
            throw ModuleException("No se pudo actualizar el cliente.")
        } catch (exception: CancellationException) {
            throw exception
        } catch (exception: ModuleException) {
            throw exception
        } catch (_: Exception) {
            throw ModuleException("No se pudo comunicar con el servidor.")
        }
    }

    suspend fun deleteCustomer(accessToken: String, companyCode: String, customerId: String) {
        try {
            val response = client.delete("$baseUrl/modules/clientes/customers/$customerId") {
                bearerAuth(accessToken)
                header("X-Company-Code", companyCode)
            }
            if (response.status == HttpStatusCode.NoContent) return
            throw ModuleException("No se pudo eliminar el cliente.")
        } catch (exception: CancellationException) {
            throw exception
        } catch (exception: ModuleException) {
            throw exception
        } catch (_: Exception) {
            throw ModuleException("No se pudo comunicar con el servidor.")
        }
    }

    suspend fun customerFields(accessToken: String, companyCode: String): CustomerFieldsResponse {
        try {
            val response = client.get("$baseUrl/modules/clientes/fields") {
                bearerAuth(accessToken)
                header("X-Company-Code", companyCode)
            }
            if (response.status == HttpStatusCode.OK) return response.body()
            throw ModuleException("No se pudieron cargar los campos dinamicos.")
        } catch (exception: CancellationException) {
            throw exception
        } catch (exception: ModuleException) {
            throw exception
        } catch (_: Exception) {
            throw ModuleException("No se pudo comunicar con el servidor.")
        }
    }

    suspend fun createCustomerField(accessToken: String, companyCode: String, request: CreateCustomerFieldRequest): CustomerFieldResponse {
        try {
            val response = client.post("$baseUrl/modules/clientes/fields") {
                bearerAuth(accessToken)
                header("X-Company-Code", companyCode)
                contentType(ContentType.Application.Json)
                setBody(request)
            }
            if (response.status == HttpStatusCode.Created) return response.body()
            throw ModuleException("No se pudo crear el campo dinamico.")
        } catch (exception: CancellationException) {
            throw exception
        } catch (exception: ModuleException) {
            throw exception
        } catch (_: Exception) {
            throw ModuleException("No se pudo comunicar con el servidor.")
        }
    }

    // Libera recursos del cliente HTTP.
    fun close() {
        client.close()
    }
}

class ModuleException(message: String) : Exception(message)

@Serializable
data class CreateCustomerRequest(
    val displayName: String,
    val primaryEmail: String,
    val primaryPhone: String,
    val dynamicFields: JsonObject = JsonObject(emptyMap()),
)

@Serializable
data class CustomerResponse(
    val id: String,
    val displayName: String,
    val primaryEmail: String,
    val primaryPhone: String,
    val status: String,
    val dynamicFields: JsonObject = JsonObject(emptyMap()),
)

@Serializable
data class CustomersResponse(val customers: List<CustomerResponse>)

@Serializable
data class CreateCustomerFieldRequest(
    val fieldKey: String,
    val label: String,
    val fieldType: String,
    val controlType: String = "",
    val required: Boolean = false,
    val displayOrder: Int = 0,
)

@Serializable
data class CustomerFieldResponse(
    val id: String,
    val fieldKey: String,
    val label: String,
    val fieldType: String,
    val required: Boolean,
    val active: Boolean,
    val displayOrder: Int,
)

@Serializable
data class CustomerFieldsResponse(val fields: List<CustomerFieldResponse>)
