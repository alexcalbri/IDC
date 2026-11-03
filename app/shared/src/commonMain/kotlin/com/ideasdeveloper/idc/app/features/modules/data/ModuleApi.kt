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
import io.ktor.client.request.parameter
import io.ktor.client.request.put
import io.ktor.client.request.request
import io.ktor.client.statement.bodyAsText
import io.ktor.client.statement.readRawBytes
import io.ktor.http.HttpMethod
import io.ktor.http.isSuccess
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.Url
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import com.ideasdeveloper.idc.app.core.session.SessionExpiry
import com.ideasdeveloper.idc.ui.UiJson
import com.ideasdeveloper.idc.ui.UiRequest
import com.ideasdeveloper.idc.ui.UiScreen
import com.ideasdeveloper.idc.ui.UiSuggestions
import com.ideasdeveloper.idc.ui.UiFileRef
import io.ktor.client.plugins.timeout
import io.ktor.http.encodeURLParameter
import io.ktor.http.encodeURLPathPart
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

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
        install(SessionExpiry)

        install(ContentNegotiation) {
            json(Json { ignoreUnknownKeys = true })
        }

        install(HttpTimeout) {
            requestTimeoutMillis = 20_000
            connectTimeoutMillis = 10_000
        }
    }

    // Cliente para imagenes externas; se crea solo si una pantalla las usa.
    private val lazyImageClient = lazy {
        HttpClient {
            expectSuccess = false
            install(HttpTimeout) {
                requestTimeoutMillis = 20_000
                connectTimeoutMillis = 10_000
            }
        }
    }
    private val imageClient by lazyImageClient

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

    // Historial del cliente: cambios en Clientes y lo que aportan los demas modulos instalados.
    suspend fun customerHistory(accessToken: String, companyCode: String, customerId: String): List<CustomerHistoryEntry> {
        try {
            val response = client.get("$baseUrl/modules/clientes/customers/$customerId/history") {
                bearerAuth(accessToken)
                header("X-Company-Code", companyCode)
            }
            if (response.status == HttpStatusCode.OK) return response.body<CustomerHistoryResponse>().entries
            throw ModuleException("No se pudo cargar el historial del cliente.")
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

    // Pide una pantalla nativa del modulo: GET {entry}/{screen}?params.
    suspend fun screen(accessToken: String, companyCode: String, entry: String, screen: String, params: Map<String, String>): UiScreen =
        decodeScreen(send("GET", "$entry/$screen", accessToken, companyCode, params))

    // Ejecuta una accion "request"; el servidor responde con la siguiente pantalla.
    suspend fun request(accessToken: String, companyCode: String, action: UiRequest, formValues: Map<String, String>?): UiScreen {
        val body = formValues?.let { values -> JsonObject(values.mapValues { JsonPrimitive(it.value) }).toString() }
        return decodeScreen(send(action.method, action.route, accessToken, companyCode, action.params, body))
    }

    /**
     * Descarga los bytes de una imagen de pieza UiImage. Las rutas de modulo usan la sesion;
     * las URL externas se piden sin credenciales y pueden seguir redirecciones (CDN).
     */
    suspend fun image(accessToken: String, companyCode: String, url: String): ByteArray {
        val moduleRoute = url.startsWith("/modules/") && !url.contains("..")
        if (!moduleRoute && !url.startsWith("https://") && !url.startsWith("http://")) throw ModuleException("Imagen invalida.")
        try {
            val response = if (moduleRoute) {
                client.get("$baseUrl$url") {
                    bearerAuth(accessToken)
                    header("X-Company-Code", companyCode)
                }
            } else {
                imageClient.get(url)
            }
            if (!response.status.isSuccess()) throw ModuleException("No se pudo cargar la imagen.")
            val bytes = response.readRawBytes()
            if (bytes.size > MaxImageBytes) throw ModuleException("La imagen es demasiado grande.")
            return bytes
        } catch (exception: CancellationException) {
            throw exception
        } catch (exception: ModuleException) {
            throw exception
        } catch (_: Exception) {
            throw ModuleException("No se pudo cargar la imagen.")
        }
    }

    // Sugerencias de un campo mientras se escribe; si fallan no se muestra nada en lugar de un error.
    suspend fun suggestions(accessToken: String, companyCode: String, route: String, params: Map<String, String>): UiSuggestions = try {
        UiJson.decodeFromString(UiSuggestions.serializer(), send("GET", route, accessToken, companyCode, params))
    } catch (exception: CancellationException) {
        throw exception
    } catch (_: Exception) {
        UiSuggestions()
    }

    // Sube un archivo de un campo "files" (UiFiles.upload); el servidor responde la referencia que guarda el formulario.
    suspend fun upload(accessToken: String, companyCode: String, route: String, name: String, path: String, mimeType: String, bytes: ByteArray): UiFileRef {
        if (!route.startsWith("/modules/") || route.contains("..")) throw ModuleException("La accion no es valida.")
        try {
            val response = client.post("$baseUrl$route") {
                bearerAuth(accessToken)
                header("X-Company-Code", companyCode)
                header("X-File-Name", name.encodeURLParameter())
                header("X-File-Path", path.encodeURLParameter())
                // Un archivo grande en una conexion lenta tarda mas que una pantalla.
                timeout { requestTimeoutMillis = 10 * 60_000 }
                contentType(runCatching { ContentType.parse(mimeType) }.getOrDefault(ContentType.Application.OctetStream))
                setBody(bytes)
            }
            val text = response.bodyAsText()
            if (response.status.isSuccess()) return UiJson.decodeFromString(UiFileRef.serializer(), text)
            val message = runCatching { Json.parseToJsonElement(text).jsonObject["message"]?.jsonPrimitive?.contentOrNull }.getOrNull()
            throw ModuleException(message ?: "No se pudo subir $name (${response.status.value}).")
        } catch (exception: CancellationException) {
            throw exception
        } catch (exception: ModuleException) {
            throw exception
        } catch (_: Exception) {
            throw ModuleException("No se pudo subir $name.")
        }
    }

    // Descarga un adjunto de un campo "files": GET {download}/{id}.
    suspend fun downloadFile(accessToken: String, companyCode: String, route: String, id: String): ByteArray {
        if (!route.startsWith("/modules/") || route.contains("..")) throw ModuleException("La accion no es valida.")
        try {
            val response = client.get("$baseUrl$route/${id.encodeURLPathPart()}") {
                bearerAuth(accessToken)
                header("X-Company-Code", companyCode)
                timeout { requestTimeoutMillis = 10 * 60_000 }
            }
            if (!response.status.isSuccess()) throw ModuleException("No se pudo descargar el archivo (${response.status.value}).")
            return response.readRawBytes()
        } catch (exception: CancellationException) {
            throw exception
        } catch (exception: ModuleException) {
            throw exception
        } catch (_: Exception) {
            throw ModuleException("No se pudo descargar el archivo.")
        }
    }

    // Descarga un archivo de texto (p. ej. CSV) desde una ruta del modulo.
    suspend fun download(accessToken: String, companyCode: String, route: String, params: Map<String, String>): String =
        send("GET", route, accessToken, companyCode, params)

    // Cambia la sesion por un enlace de un solo uso para abrir la interfaz web del modulo.
    suspend fun webSession(accessToken: String, companyCode: String, moduleId: String): String {
        val text = send("POST", "/modules/$moduleId/web-session", accessToken, companyCode, emptyMap())
        val path = UiJson.parseToJsonElement(text).jsonObject["url"]?.jsonPrimitive?.contentOrNull
            ?: throw ModuleException("El servidor no devolvio el enlace del modulo.")
        return baseUrl + path
    }

    private fun decodeScreen(text: String): UiScreen = try {
        UiJson.decodeFromString(UiScreen.serializer(), text)
    } catch (_: IllegalArgumentException) {
        throw ModuleException("El servidor devolvio una pantalla que esta version de la app no entiende.")
    }

    private suspend fun send(
        method: String,
        route: String,
        accessToken: String,
        companyCode: String,
        query: Map<String, String>,
        body: String? = null,
    ): String {
        // Las acciones vienen del servidor; solo se permiten rutas de modulos del mismo servidor.
        if (!route.startsWith("/modules/") || route.contains("..")) throw ModuleException("La accion no es valida.")
        try {
            val response = client.request("$baseUrl$route") {
                this.method = HttpMethod.parse(method.uppercase())
                bearerAuth(accessToken)
                header("X-Company-Code", companyCode)
                query.forEach { (key, value) -> if (value.isNotBlank()) parameter(key, value) }
                if (body != null) {
                    contentType(ContentType.Application.Json)
                    setBody(body)
                }
            }
            val text = response.bodyAsText()
            if (response.status.isSuccess()) return text
            val message = runCatching {
                Json.parseToJsonElement(text).jsonObject["message"]?.jsonPrimitive?.contentOrNull
            }.getOrNull()
            throw ModuleException(message ?: "El servidor rechazo la accion (${response.status.value}).")
        } catch (exception: CancellationException) {
            throw exception
        } catch (exception: ModuleException) {
            throw exception
        } catch (_: Exception) {
            throw ModuleException("No se pudo conectar con el servidor.")
        }
    }

    // Libera recursos del cliente HTTP.
    fun close() {
        client.close()
        if (lazyImageClient.isInitialized()) imageClient.close()
    }

    private companion object {
        const val MaxImageBytes = 5 * 1024 * 1024
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
data class CustomerHistoryEntry(
    val at: String,
    val moduleId: String,
    val moduleName: String,
    val kind: String,
    val title: String,
    val detail: String = "",
    val author: String = "",
)

@Serializable
data class CustomerHistoryResponse(val entries: List<CustomerHistoryEntry> = emptyList())

@Serializable
data class CustomerResponse(
    val id: String,
    val displayName: String,
    val primaryEmail: String,
    val primaryPhone: String,
    val status: String,
    val dynamicFields: JsonObject = JsonObject(emptyMap()),
    val customerType: String = "general",
    // Random unique code generated by the server; empty on servers older than Clientes 0.4.0.
    val customerCode: String = "",
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
