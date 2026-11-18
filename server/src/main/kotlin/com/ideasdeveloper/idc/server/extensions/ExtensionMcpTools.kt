package com.ideasdeveloper.idc.server.extensions

import com.ideasdeveloper.idc.server.extensions.packages.ExtensionPackageEntry
import com.ideasdeveloper.idc.server.extensions.packages.ExtensionPackageService
import com.ideasdeveloper.idc.server.extensions.packages.ExtensionPackageServiceException
import com.ideasdeveloper.idc.server.modules.McpSchema
import com.ideasdeveloper.idc.server.modules.McpTool
import com.ideasdeveloper.idc.server.modules.McpToolCall
import com.ideasdeveloper.idc.server.modules.McpToolException
import java.sql.SQLException
import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put

/**
 * Extension tools of the server's own MCP (server owners only; names are prefixed with "servidor_").
 * The actor is always the server owner behind the agent. Nothing here handles credentials: a
 * setting marked secret cannot be written through the agent (the app does it), responses carry the
 * masked values of ExtensionService, and installing packages or authorizing unsigned ones stays in
 * the app.
 */
object ExtensionMcpTools {
    private const val Permission = "server_owner"
    private val json = Json { encodeDefaults = true }

    const val Instructions =
        "Extensiones: funciones opcionales de un modulo que cada empresa instala por su cuenta (servidor_list_extensions). " +
            "Confirma con el usuario antes de instalar, configurar, activar, desinstalar o aplicar a un grupo. " +
            "Los ajustes secretos (claves, tokens) no se escriben por aqui: se piden al usuario en la app (Empresa > Extensiones); " +
            "las respuestas nunca los muestran. Desinstalar nunca borra leads ni clientes; primero usa servidor_preview_extension_uninstall " +
            "y pregunta las opciones que no tienen valor por defecto. Para aplicar una configuracion a otras empresas del mismo grupo " +
            "usa servidor_apply_extension_to_group sin confirm para ver la vista previa y con confirm=true solo cuando el usuario acepte. " +
            "Instalar paquetes externos y autorizar paquetes sin firma solo se hace desde la app."

    /**
     * [service] and [packages] are only called when a tool runs. [role] gives the PostgreSQL role of
     * the server owner of the call (it throws when the user is no longer one).
     */
    fun tools(service: () -> ExtensionService, packages: () -> ExtensionPackageService?, role: (McpToolCall) -> String): List<McpTool> {
        fun actor(call: McpToolCall) = ExtensionActor(role(call), serverOwner = true)

        fun <T> run(block: () -> T): T = try {
            block()
        } catch (failure: ExtensionException) {
            throw McpToolException(failure.message)
        } catch (failure: ExtensionPackageServiceException) {
            throw McpToolException(failure.message.orEmpty())
        } catch (_: SQLException) {
            throw McpToolException("No se pudo completar la operacion con la base de datos.")
        }

        val code = "code" to McpSchema.string("Codigo de la empresa (servidor_list_companies).")
        val extensionId = "extension_id" to McpSchema.string("Id de la extension (servidor_list_extensions).")
        val choices = "choices" to stringMap("Respuesta a cada opcion de desinstalacion: id de la opcion -> keep o delete.")

        return listOf(
            McpTool(
                name = "list_extensions",
                title = "Listar extensiones de una empresa",
                description = "Extensiones disponibles para los modulos instalados, su estado en la empresa (instalada, activa, desactivada, " +
                    "incompatible), compatibilidad, ajustes (los secretos aparecen enmascarados) y los obligatorios que faltan.",
                permission = Permission,
                inputSchema = McpSchema.obj(mapOf(code), required = listOf("code")),
                readOnly = true,
            ) { call -> encode(ExtensionListResponse.serializer(), run { service().list(call.requiredString("code", 63), actor(call)) }) },
            McpTool(
                name = "install_extension",
                title = "Instalar extension en una empresa",
                description = "Instala una extension en la empresa (queda instalada, sin activar). El modulo debe estar activo en la empresa.",
                permission = Permission,
                inputSchema = McpSchema.obj(mapOf(code, extensionId), required = listOf("code", "extension_id")),
            ) { call ->
                encode(
                    ExtensionSummary.serializer(),
                    run { service().install(call.requiredString("code", 63), call.requiredString("extension_id", 63), actor(call)) },
                )
            },
            McpTool(
                name = "configure_extension",
                title = "Configurar extension",
                description = "Cambia ajustes que no son secretos de una extension instalada. Solo cambia los enviados; un valor vacio lo borra. " +
                    "Los ajustes secretos se rechazan: los escribe el usuario en la app.",
                permission = Permission,
                inputSchema = McpSchema.obj(
                    mapOf(code, extensionId, "settings" to stringMap("Ajustes a cambiar: clave -> valor.")),
                    required = listOf("code", "extension_id", "settings"),
                ),
            ) { call ->
                val company = call.requiredString("code", 63)
                val id = call.requiredString("extension_id", 63)
                val settings = call.stringMap("settings")
                if (settings.isEmpty()) throw McpToolException("Envia al menos un ajuste en 'settings'.")
                val actor = actor(call)
                val definitions = run { service().list(company, actor) }.extensions.firstOrNull { it.id == id }?.settings.orEmpty().associateBy { it.key }
                settings.keys.firstOrNull { definitions[it]?.secret == true }?.let {
                    throw McpToolException("El ajuste '$it' es un secreto: pidele al usuario que lo escriba en la app (Empresa > Extensiones).")
                }
                encode(ExtensionSummary.serializer(), run { service().configure(company, id, settings, actor) })
            },
            McpTool(
                name = "set_extension_enabled",
                title = "Activar o desactivar extension",
                description = "Activa una extension instalada (exige sus ajustes obligatorios) o la desactiva; desactivar no borra datos.",
                permission = Permission,
                inputSchema = McpSchema.obj(
                    mapOf(code, extensionId, "enabled" to McpSchema.boolean("true para activar, false para desactivar.")),
                    required = listOf("code", "extension_id", "enabled"),
                ),
            ) { call ->
                val company = call.requiredString("code", 63)
                val id = call.requiredString("extension_id", 63)
                val summary = run {
                    if (call.boolean("enabled")) service().enable(company, id, actor(call)) else service().disable(company, id, actor(call))
                }
                encode(ExtensionSummary.serializer(), summary)
            },
            McpTool(
                name = "preview_extension_uninstall",
                title = "Ver que borra desinstalar una extension",
                description = "Lista lo que se borra o conserva al desinstalar (con cantidades) y las opciones que el usuario debe responder. No cambia nada.",
                permission = Permission,
                inputSchema = McpSchema.obj(mapOf(code, extensionId), required = listOf("code", "extension_id")),
                readOnly = true,
            ) { call ->
                encode(
                    UninstallPreviewResponse.serializer(),
                    run { service().uninstallPreview(call.requiredString("code", 63), call.requiredString("extension_id", 63), actor(call)) },
                )
            },
            McpTool(
                name = "uninstall_extension",
                title = "Desinstalar extension",
                description = "Desinstala la extension de la empresa: borra sus ajustes y lo que el usuario elija; nunca borra leads ni clientes. " +
                    "Muestra antes la vista previa y confirma con el usuario; las opciones sin valor por defecto necesitan respuesta en 'choices'.",
                permission = Permission,
                inputSchema = McpSchema.obj(mapOf(code, extensionId, choices), required = listOf("code", "extension_id")),
                destructive = true,
            ) { call ->
                val company = call.requiredString("code", 63)
                val id = call.requiredString("extension_id", 63)
                run { service().uninstall(company, id, call.stringMap("choices"), actor(call)) }
                buildJsonObject {
                    put("uninstalled", id)
                    put("company", company)
                }
            },
            McpTool(
                name = "apply_extension_to_group",
                title = "Aplicar extension a empresas del grupo",
                description = "Copia la configuracion de una extension de la empresa de origen a otras empresas de su grupo (mismo business owner): " +
                    "instala si falta, copia los ajustes de la aplicacion cifrados de nuevo (los de conexion no se copian) y activa. " +
                    "Sin confirm=true solo devuelve la vista previa por empresa; con confirm=true la ejecuta y devuelve el resultado por empresa. " +
                    "Con operation=uninstall desinstala en las empresas elegidas (usa 'choices').",
                permission = Permission,
                inputSchema = McpSchema.obj(
                    mapOf(
                        "code" to McpSchema.string("Empresa de origen."),
                        extensionId,
                        "targets" to buildJsonObject {
                            put("type", "array")
                            put("description", "Codigos de las empresas de destino del mismo grupo.")
                            put("items", buildJsonObject { put("type", "string") })
                        },
                        "operation" to McpSchema.string("apply (por defecto) o uninstall.", enum = listOf(GroupOperationType.Apply, GroupOperationType.Uninstall)),
                        choices,
                        "confirm" to McpSchema.boolean("true solo cuando el usuario ya vio la vista previa y acepto."),
                    ),
                    required = listOf("code", "extension_id", "targets"),
                ),
                destructive = true,
            ) { call ->
                val company = call.requiredString("code", 63)
                val id = call.requiredString("extension_id", 63)
                val targets = (call.arguments["targets"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull?.trim()?.take(63)?.ifEmpty { null } }.orEmpty()
                if (targets.isEmpty()) throw McpToolException("Envia al menos una empresa en 'targets'.")
                val request = GroupOperationRequest(
                    operation = call.string("operation", 16).ifEmpty { GroupOperationType.Apply },
                    targets = targets,
                    choices = call.stringMap("choices"),
                )
                if (call.boolean("confirm")) {
                    encode(GroupOperationResult.serializer(), run { service().applyToGroup(company, id, request, actor(call)) })
                } else {
                    encode(GroupOperationPreview.serializer(), run { service().previewGroupOperation(company, id, request, actor(call)) })
                }
            },
            McpTool(
                name = "list_extension_packages",
                title = "Listar paquetes de extensiones",
                description = "Catalogo de paquetes de extensiones externos con su firma y estado en este servidor. Solo lectura: instalarlos o " +
                    "autorizar uno sin firma se hace desde la app.",
                permission = Permission,
                readOnly = true,
            ) {
                val service = packages() ?: throw McpToolException("Los paquetes de extensiones no estan disponibles en este servidor.")
                json.encodeToJsonElement(ListSerializer(ExtensionPackageEntry.serializer()), run { service.catalog() })
            },
        )
    }

    private fun stringMap(description: String): JsonObject = buildJsonObject {
        put("type", "object")
        put("description", description)
        put("additionalProperties", buildJsonObject { put("type", "string") })
    }

    private fun McpToolCall.stringMap(key: String): Map<String, String> =
        (arguments[key] as? JsonObject)?.entries?.associate { (name, value) -> name.take(127) to ((value as? JsonPrimitive)?.contentOrNull.orEmpty().take(2_000)) }.orEmpty()

    private fun <T> encode(serializer: KSerializer<T>, value: T): JsonElement = json.encodeToJsonElement(serializer, value)
}
