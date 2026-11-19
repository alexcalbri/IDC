package com.ideasdeveloper.idc.modules.mantenimiento

import com.ideasdeveloper.idc.server.modules.McpToolCall
import com.ideasdeveloper.idc.server.modules.McpToolException
import com.ideasdeveloper.idc.server.modules.ModuleAccessService
import com.ideasdeveloper.idc.server.modules.ModulePermissions
import com.ideasdeveloper.idc.server.modules.RegisteredMcpTool
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import org.slf4j.LoggerFactory
import java.sql.Connection
import java.util.UUID
import java.util.concurrent.CancellationException

/**
 * Puente hacia módulos opcionales (Tareas, Compras) sin depender de su código: se llaman sus herramientas MCP públicas, como hace
 * `agentes`, siempre con los permisos VIVOS de un usuario. Si el módulo no está, el usuario no tiene acceso o le falta el permiso de la
 * herramienta, el resultado es [BridgeResult.Unavailable] y nada se ejecuta.
 *
 * El handler de la herramienta usa la misma conexión de la empresa: llámalo fuera de una transacción propia abierta. Con la conexión
 * en autocommit el handler decide sus transacciones; dentro de una transacción de quien llama, el trabajo del handler se une a ella.
 */
object MantenimientoBridge {
    private val log = LoggerFactory.getLogger(MantenimientoBridge::class.java)

    /** Nombre que ven los registros de actividad como "agente" que hizo el cambio. */
    const val AgentName = "Mantenimiento"

    sealed class BridgeResult {
        data class Ok(val json: JsonElement) : BridgeResult()
        data class Unavailable(val reason: String) : BridgeResult()
    }

    /** Pura: null si [permissions] permite usar [tool]; si no, la razón. Es toda la decisión de acceso del puente. */
    fun denial(tool: RegisteredMcpTool?, permissions: ModulePermissions?): BridgeResult.Unavailable? = when {
        tool == null -> BridgeResult.Unavailable("módulo no instalado")
        permissions == null -> BridgeResult.Unavailable("módulo no activo o usuario sin acceso")
        !permissions.has(tool.tool.permission) -> BridgeResult.Unavailable("sin permiso")
        else -> null
    }

    private fun resolve(access: ModuleAccessService, tenant: Connection, companyCode: String, userId: UUID, serverOwner: Boolean, qualifiedName: String): Pair<RegisteredMcpTool?, ModulePermissions?> {
        val tool = access.mcpTools().firstOrNull { it.qualifiedName == qualifiedName }
        val permissions = if (tool == null) null else access.userAccess(tenant, companyCode, userId, serverOwner)?.permissions(tool.moduleId)
        return tool to permissions
    }

    /** Si la herramienta existe y el usuario puede usarla ahora mismo. */
    fun available(access: ModuleAccessService, tenant: Connection, companyCode: String, userId: UUID, serverOwner: Boolean, qualifiedName: String): Boolean {
        val (tool, permissions) = try {
            resolve(access, tenant, companyCode, userId, serverOwner, qualifiedName)
        } catch (stop: CancellationException) {
            throw stop
        } catch (failure: Exception) {
            log.warn("No se pudo comprobar la herramienta {} ({}).", qualifiedName, failure.javaClass.simpleName)
            return false
        }
        return denial(tool, permissions) == null
    }

    /** Ejecuta la herramienta con los permisos vivos del usuario. Un error de la herramienta o de la base es [BridgeResult.Unavailable] con su mensaje. */
    fun call(
        access: ModuleAccessService,
        tenant: Connection,
        companyCode: String,
        userId: UUID,
        serverOwner: Boolean,
        qualifiedName: String,
        arguments: JsonObject,
    ): BridgeResult {
        val (tool, permissions) = try {
            resolve(access, tenant, companyCode, userId, serverOwner, qualifiedName)
        } catch (stop: CancellationException) {
            throw stop
        } catch (failure: Exception) {
            log.warn("No se pudo resolver la herramienta {} ({}).", qualifiedName, failure.javaClass.simpleName)
            return BridgeResult.Unavailable("no se pudo comprobar el acceso")
        }
        denial(tool, permissions)?.let { return it }
        val registered = tool ?: return BridgeResult.Unavailable("módulo no instalado")
        val granted = permissions ?: return BridgeResult.Unavailable("módulo no activo o usuario sin acceso")
        return try {
            BridgeResult.Ok(registered.tool.handler(McpToolCall(arguments, granted, tenant, access, AgentName, registered.moduleId)))
        } catch (failure: McpToolException) {
            BridgeResult.Unavailable(failure.message.orEmpty().ifBlank { "la herramienta rechazó la petición" })
        } catch (stop: InterruptedException) {
            throw stop
        } catch (stop: CancellationException) {
            throw stop
        } catch (failure: Exception) {
            log.warn("Falló la herramienta {} ({}).", qualifiedName, failure.javaClass.simpleName)
            BridgeResult.Unavailable("la herramienta falló")
        }
    }
}
