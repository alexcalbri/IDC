package com.ideasdeveloper.idc.server.company.application

import com.ideasdeveloper.idc.server.company.api.CompanySummaryResponse
import com.ideasdeveloper.idc.server.company.api.CreateCompanyRequest
import com.ideasdeveloper.idc.server.company.api.ServerModuleCatalogResponse
import com.ideasdeveloper.idc.server.company.api.ServerModuleResponse
import com.ideasdeveloper.idc.server.company.api.UpdateCompanyRequest
import com.ideasdeveloper.idc.server.modules.McpSchema
import com.ideasdeveloper.idc.server.modules.McpTool
import com.ideasdeveloper.idc.server.modules.McpToolCall
import com.ideasdeveloper.idc.server.modules.McpToolException
import com.ideasdeveloper.idc.server.modules.RegisteredMcpTool
import com.ideasdeveloper.idc.server.modules.RegisteredModuleMcp
import com.ideasdeveloper.idc.server.modules.ServerMcpModuleId
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.security.SecureRandom

/**
 * The server's own MCP: what a server owner's AI agent can do with the server itself (companies and
 * modules), next to the MCP of each module. Only server owners see these tools (UserModuleAccess).
 * Credentials never go through it: a company created here gets a random business owner password
 * that nobody sees, and the server owner sets the real one in the app.
 */
object ServerMcpTools {
    const val Name = "Servidor"
    // Not a module permission: UserModuleAccess grants the server tools to server owners only.
    private const val Permission = "server_owner"

    private val json = Json { encodeDefaults = true }
    private val random = SecureRandom()

    private const val Instructions =
        "Administras el servidor como su dueno. Las empresas (servidor_list_companies) tienen cada una su propia base de datos; " +
            "el espacio del servidor no aparece en esa lista. Para trabajar dentro de una empresa con las herramientas de los modulos, " +
            "pasa su codigo en el argumento acting_company; el modulo debe estar activo en esa empresa (servidor_set_company_module). " +
            "Un modulo debe estar instalado en el servidor (servidor_install_module) antes de poder activarlo en una empresa. " +
            "Las contrasenas nunca pasan por aqui: una empresa creada con servidor_create_company no tiene contrasena utilizable " +
            "hasta que el business owner la pone con el codigo que le llega a su correo (\"Olvidaste tu contrasena\" en el login) " +
            "o el dueno del servidor la pone desde la app (Empresas > Editar > Contrasena del business owner); avisale."

    fun mcp(provisioning: CompanyProvisioningService): RegisteredModuleMcp =
        RegisteredModuleMcp(ServerMcpModuleId, Name, Instructions, tools { provisioning }.map { RegisteredMcpTool(ServerMcpModuleId, Name, it) })

    // [provisioning] is only called when a tool runs.
    fun tools(provisioning: () -> CompanyProvisioningService): List<McpTool> {
        // Every change runs as the server owner's own role, like the app's requests.
        fun role(call: McpToolCall): String = call.permissions.userId?.let { provisioning().serverOwnerRole(it) }
            ?: throw McpToolException("Solo un dueno del servidor activo puede usar esta herramienta.")

        fun <T> run(block: () -> T): T = try {
            block()
        } catch (failure: CompanyProvisioningException) {
            throw McpToolException(failure.message.orEmpty())
        }

        fun existing(call: McpToolCall): CompanySummaryResponse {
            val code = call.requiredString("code", 63)
            return provisioning().listCompanies().firstOrNull { it.code == code }
                ?: throw McpToolException("Empresa no encontrada: $code.")
        }

        val code = "code" to McpSchema.string("Codigo de la empresa (servidor_list_companies).")
        val moduleId = "module_id" to McpSchema.string("Id del modulo (servidor_list_modules).")
        val color = { what: String -> McpSchema.string("Color $what en formato #RRGGBB.") }

        return listOf(
            McpTool(
                name = "list_companies",
                title = "Listar empresas",
                description = "Empresas del servidor con su estado y los modulos activos en cada una.",
                permission = Permission,
                readOnly = true,
            ) { companies(provisioning().listCompanies()) },
            McpTool(
                name = "create_company",
                title = "Crear empresa",
                description = "Crea una empresa con su base de datos y su usuario business owner (con nombre y correo). El business owner " +
                    "pone su contrasena con \"Olvidaste tu contrasena\" en el login (le llega un codigo a su correo), o el dueno del " +
                    "servidor se la pone desde la app; no pidas ni envies contrasenas.",
                permission = Permission,
                inputSchema = McpSchema.obj(
                    mapOf(
                        "code" to McpSchema.string("Codigo unico: minusculas, numeros o guion bajo, iniciando con letra."),
                        "name" to McpSchema.string("Nombre de la empresa."),
                        "business_owner_username" to McpSchema.string("Usuario del business owner: minusculas, numeros o guion bajo."),
                        "business_owner_name" to McpSchema.string("Nombre completo del business owner."),
                        "business_owner_email" to McpSchema.string("Correo del business owner; recibe los codigos para poner y recuperar su contrasena."),
                        "logo_url" to McpSchema.string("URL del logo (opcional)."),
                        "primary_color" to color("primario"),
                        "secondary_color" to color("secundario"),
                        "accent_color" to color("de acento"),
                    ),
                    required = listOf("code", "name", "business_owner_username", "business_owner_name", "business_owner_email"),
                ),
            ) { call ->
                val created = run {
                    provisioning().createCompany(
                        CreateCompanyRequest(
                            code = call.requiredString("code", 63),
                            name = call.requiredString("name", 200),
                            businessOwnerUsername = call.requiredString("business_owner_username", 63),
                            businessOwnerName = call.requiredString("business_owner_name", 120),
                            businessOwnerEmail = call.requiredString("business_owner_email", 254),
                            // Nobody learns it; the server owner sets the real password in the app.
                            businessOwnerPassword = randomPassword(),
                            logoUrl = call.string("logo_url", 2_000).ifEmpty { null },
                            primaryColor = call.string("primary_color", 7).ifEmpty { "#667EEA" },
                            secondaryColor = call.string("secondary_color", 7).ifEmpty { "#764BA2" },
                            accentColor = call.string("accent_color", 7).ifEmpty { "#FFFFFF" },
                        ),
                        role(call),
                    )
                }
                buildJsonObject {
                    put("code", created.code)
                    put("name", created.name)
                    put("businessOwnerUsername", created.businessOwnerUsername)
                    put(
                        "nextStep",
                        "El business owner aun no tiene contrasena: puede ponerla con \"Olvidaste tu contrasena\" en el login usando el " +
                            "codigo de empresa ${created.code} (le llega un codigo a su correo, si el servidor tiene SMTP), o el dueno del " +
                            "servidor se la pone en la app (Empresas > Editar > Contrasena del business owner).",
                    )
                }
            },
            McpTool(
                name = "update_company",
                title = "Editar empresa",
                description = "Cambia nombre, logo o colores de una empresa. Solo cambia los campos enviados.",
                permission = Permission,
                inputSchema = McpSchema.obj(
                    mapOf(
                        code,
                        "name" to McpSchema.string("Nuevo nombre."),
                        "logo_url" to McpSchema.string("URL del logo; vacio para quitarlo."),
                        "primary_color" to color("primario"),
                        "secondary_color" to color("secundario"),
                        "accent_color" to color("de acento"),
                    ),
                    required = listOf("code"),
                ),
            ) { call ->
                val current = existing(call)
                val updated = run {
                    provisioning().updateCompany(
                        current.code,
                        UpdateCompanyRequest(
                            name = if (call.has("name")) call.string("name", 200) else current.name,
                            logoUrl = if (call.has("logo_url")) call.string("logo_url", 2_000).ifEmpty { null } else current.logoUrl,
                            primaryColor = if (call.has("primary_color")) call.string("primary_color", 7) else current.primaryColor,
                            secondaryColor = if (call.has("secondary_color")) call.string("secondary_color", 7) else current.secondaryColor,
                            accentColor = if (call.has("accent_color")) call.string("accent_color", 7) else current.accentColor,
                        ),
                        role(call),
                    )
                }
                companyJson(updated)
            },
            McpTool(
                name = "set_company_active",
                title = "Activar o desactivar empresa",
                description = "Desactiva una empresa (nadie puede entrar) o la reactiva. Sus datos se conservan.",
                permission = Permission,
                inputSchema = McpSchema.obj(
                    mapOf(code, "active" to McpSchema.boolean("true para activar, false para desactivar.")),
                    required = listOf("code", "active"),
                ),
            ) { call ->
                companyJson(run { provisioning().setCompanyActive(call.requiredString("code", 63), call.boolean("active"), role(call)) })
            },
            McpTool(
                name = "delete_company",
                title = "Eliminar empresa",
                description = "Elimina una empresa desactivada junto con su base de datos. No se puede deshacer; confirma con el usuario antes.",
                permission = Permission,
                inputSchema = McpSchema.obj(mapOf(code), required = listOf("code")),
                destructive = true,
            ) { call ->
                val target = call.requiredString("code", 63)
                run { provisioning().deleteInactiveCompany(target, role(call)) }
                buildJsonObject { put("deleted", target) }
            },
            McpTool(
                name = "set_company_module",
                title = "Activar modulo en empresa",
                description = "Activa o desactiva un modulo instalado en una empresa. Al activarlo se preparan sus tablas.",
                permission = Permission,
                inputSchema = McpSchema.obj(
                    mapOf(code, moduleId, "enabled" to McpSchema.boolean("true para activar, false para desactivar.")),
                    required = listOf("code", "module_id", "enabled"),
                ),
            ) { call ->
                companyJson(
                    run {
                        provisioning().setModuleEnabled(call.requiredString("code", 63), call.requiredString("module_id", 63), call.boolean("enabled"), role(call))
                    }
                )
            },
            McpTool(
                name = "list_modules",
                title = "Listar modulos del servidor",
                description = "Modulos base, instalados y disponibles en el catalogo, con su version, la ultima del catalogo, " +
                    "en cuantas empresas estan activos y el estado de una instalacion en curso.",
                permission = Permission,
                readOnly = true,
            ) { json.encodeToJsonElement(ListSerializer(ServerModuleResponse.serializer()), provisioning().listServerModules()) },
            McpTool(
                name = "install_module",
                title = "Instalar o actualizar modulo",
                description = "Instala un modulo del catalogo o lo actualiza a su ultima version. Los modulos opcionales se compilan " +
                    "en segundo plano y el servidor se reinicia al terminar (la conexion se corta un momento); " +
                    "consulta el avance con servidor_list_modules.",
                permission = Permission,
                inputSchema = McpSchema.obj(mapOf(moduleId), required = listOf("module_id")),
            ) { call ->
                json.encodeToJsonElement(ServerModuleResponse.serializer(), run { provisioning().installServerModule(call.requiredString("module_id", 63)) })
            },
            McpTool(
                name = "remove_module",
                title = "Quitar modulo del servidor",
                description = "Quita un modulo opcional del servidor. Solo funciona si no esta activo en ninguna empresa ni en el espacio del servidor.",
                permission = Permission,
                inputSchema = McpSchema.obj(mapOf(moduleId), required = listOf("module_id")),
                destructive = true,
            ) { call ->
                val target = call.requiredString("module_id", 63)
                run { provisioning().deleteServerModule(target) }
                buildJsonObject { put("removed", target) }
            },
            McpTool(
                name = "set_workspace_module",
                title = "Usar modulo en el espacio del servidor",
                description = "Activa o desactiva un modulo instalado en el espacio del servidor (la empresa propia del dueno del servidor).",
                permission = Permission,
                inputSchema = McpSchema.obj(
                    mapOf(moduleId, "enabled" to McpSchema.boolean("true para activar, false para desactivar.")),
                    required = listOf("module_id", "enabled"),
                ),
            ) { call ->
                json.encodeToJsonElement(
                    ServerModuleResponse.serializer(),
                    run { provisioning().setServerModuleUsage(call.requiredString("module_id", 63), call.boolean("enabled"), role(call)) },
                )
            },
            McpTool(
                name = "get_module_catalog",
                title = "Ver catalogo de modulos",
                description = "URL del catalogo de donde se instalan los modulos opcionales.",
                permission = Permission,
                readOnly = true,
            ) { json.encodeToJsonElement(ServerModuleCatalogResponse.serializer(), provisioning().serverModuleCatalog()) },
            McpTool(
                name = "set_module_catalog",
                title = "Cambiar catalogo de modulos",
                description = "Cambia la URL del catalogo de modulos, por ejemplo https://github.com/owner/repo/tree/develop/modules.",
                permission = Permission,
                inputSchema = McpSchema.obj(mapOf("url" to McpSchema.string("URL del catalogo.")), required = listOf("url")),
            ) { call ->
                json.encodeToJsonElement(ServerModuleCatalogResponse.serializer(), run { provisioning().updateServerModuleCatalog(call.requiredString("url")) })
            },
        )
    }

    private fun companyJson(company: CompanySummaryResponse): JsonElement =
        json.encodeToJsonElement(CompanySummaryResponse.serializer(), company)

    private fun companies(companies: List<CompanySummaryResponse>): JsonElement =
        json.encodeToJsonElement(ListSerializer(CompanySummaryResponse.serializer()), companies)

    private fun randomPassword(): String {
        val alphabet = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz23456789"
        return (1..40).map { alphabet[random.nextInt(alphabet.length)] }.joinToString("")
    }
}
