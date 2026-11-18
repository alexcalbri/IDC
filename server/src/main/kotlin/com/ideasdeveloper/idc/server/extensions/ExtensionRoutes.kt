package com.ideasdeveloper.idc.server.extensions

import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.receiveText
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import com.ideasdeveloper.idc.server.company.application.CompanyProvisioningException
import com.ideasdeveloper.idc.server.security.SecretDecryptionException
import com.ideasdeveloper.idc.server.security.SecretsUnavailableException
import java.sql.SQLException
import java.sql.SQLTransientConnectionException
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.ideasdeveloper.idc.server.modules.ModuleAccessDecision
import com.ideasdeveloper.idc.server.modules.ModuleAccessErrorResponse

/*
 * JSON routes of extensions per company (PLAN_CAMBIOS_2.md 11.3):
 *   /companies/current/extensions...   business owner of X-Company-Code (a server owner also works)
 *   /companies/{code}/extensions...    server owner, in any company
 *   /companies/{code}/group...         server owner: show, link, unlink and review a company's group
 * Package routes (/server/extensions...) belong to extensions/packages. No response carries a secret.
 */
fun Route.extensionRoutes(service: ExtensionService, actors: ExtensionActors) {
    // Business owner (or server owner) acting in the company of X-Company-Code.
    extensionEndpoints(service, "/companies/current") { call ->
        when (val lookup = actors.currentCompany(call)) {
            is ExtensionActorLookup.Denied -> {
                call.deny(lookup.decision)
                null
            }
            is ExtensionActorLookup.Found -> ExtensionScope(lookup.actor, lookup.actor.companyCode.orEmpty())
        }
    }

    // Server owner acting in the company named in the path.
    extensionEndpoints(service, "/companies/{code}") { call ->
        when (val lookup = actors.serverOwner(call)) {
            is ExtensionActorLookup.Denied -> {
                call.deny(lookup.decision)
                null
            }
            is ExtensionActorLookup.Found -> ExtensionScope(lookup.actor, call.parameters["code"].orEmpty())
        }
    }

    // Groups: the business owner only looks; linking, unlinking and reviewing a suspension are for the server owner.
    get("/companies/current/group") {
        val scope = when (val lookup = actors.currentCompany(call)) {
            is ExtensionActorLookup.Denied -> return@get call.deny(lookup.decision)
            is ExtensionActorLookup.Found -> ExtensionScope(lookup.actor, lookup.actor.companyCode.orEmpty())
        }
        call.respondService { service.group(scope.company, scope.actor) }
    }
    get("/companies/{code}/group") {
        val actor = serverOwnerOrDeny(actors) ?: return@get
        call.respondService { service.group(call.parameters["code"].orEmpty(), actor) }
    }
    put("/companies/{code}/group") {
        val actor = serverOwnerOrDeny(actors) ?: return@put
        val request = call.bodyOrNull<LinkGroupRequest>(null) ?: return@put call.invalidRequest()
        call.respondService { service.linkGroup(call.parameters["code"].orEmpty(), request.targetCode, actor) }
    }
    delete("/companies/{code}/group") {
        val actor = serverOwnerOrDeny(actors) ?: return@delete
        call.respondService { service.unlinkGroup(call.parameters["code"].orEmpty(), actor) }
    }
    post("/companies/{code}/group/review") {
        val actor = serverOwnerOrDeny(actors) ?: return@post
        call.respondService { service.reviewSuspension(call.parameters["code"].orEmpty(), actor) }
    }
}

/** Who acts and in which company. */
class ExtensionScope(val actor: ExtensionActor, val company: String)

private fun Route.extensionEndpoints(service: ExtensionService, base: String, scopeOf: suspend (ApplicationCall) -> ExtensionScope?) {
    get("$base/extensions") {
        val scope = scopeOf(call) ?: return@get
        call.respondService { service.list(scope.company, scope.actor) }
    }
    post("$base/extensions/{id}/install") {
        val scope = scopeOf(call) ?: return@post
        call.respondService(HttpStatusCode.OK) { service.install(scope.company, call.parameters["id"].orEmpty(), scope.actor) }
    }
    put("$base/extensions/{id}/settings") {
        val scope = scopeOf(call) ?: return@put
        val request = call.bodyOrNull<ConfigureExtensionRequest>(null) ?: return@put call.invalidRequest()
        call.respondService { service.configure(scope.company, call.parameters["id"].orEmpty(), request.settings, scope.actor) }
    }
    put("$base/extensions/{id}/enabled") {
        val scope = scopeOf(call) ?: return@put
        val request = call.bodyOrNull<SetExtensionEnabledRequest>(null) ?: return@put call.invalidRequest()
        val id = call.parameters["id"].orEmpty()
        call.respondService {
            if (request.enabled) service.enable(scope.company, id, scope.actor) else service.disable(scope.company, id, scope.actor)
        }
    }
    get("$base/extensions/{id}/uninstall-preview") {
        val scope = scopeOf(call) ?: return@get
        call.respondService { service.uninstallPreview(scope.company, call.parameters["id"].orEmpty(), scope.actor) }
    }
    post("$base/extensions/{id}/uninstall") {
        val scope = scopeOf(call) ?: return@post
        val request = call.bodyOrNull(UninstallExtensionRequest()) ?: return@post call.invalidRequest()
        call.respondService<Any>(HttpStatusCode.NoContent) {
            service.uninstall(scope.company, call.parameters["id"].orEmpty(), request.choices, scope.actor)
            null
        }
    }
    // Apply (or uninstall) to the companies of the group: preview, run and retry only the failed ones.
    post("$base/extensions/{id}/group/preview") {
        val scope = scopeOf(call) ?: return@post
        val request = call.bodyOrNull<GroupOperationRequest>(null) ?: return@post call.invalidRequest()
        call.respondService { service.previewGroupOperation(scope.company, call.parameters["id"].orEmpty(), request, scope.actor) }
    }
    post("$base/extensions/{id}/group/apply") {
        val scope = scopeOf(call) ?: return@post
        val request = call.bodyOrNull<GroupOperationRequest>(null) ?: return@post call.invalidRequest()
        call.respondService { service.applyToGroup(scope.company, call.parameters["id"].orEmpty(), request, scope.actor) }
    }
    post("$base/extensions/{id}/group/operations/{operationId}/retry") {
        val scope = scopeOf(call) ?: return@post
        val operationId = call.parameters["operationId"]?.toLongOrNull() ?: return@post call.invalidRequest()
        // Retrying an uninstall needs the same answers again; an apply needs no body.
        val request = call.bodyOrNull(GroupOperationRequest()) ?: return@post call.invalidRequest()
        call.respondService { service.retryFailed(scope.company, call.parameters["id"].orEmpty(), operationId, request, scope.actor) }
    }
}

private suspend fun io.ktor.server.routing.RoutingContext.serverOwnerOrDeny(actors: ExtensionActors): ExtensionActor? =
    when (val lookup = actors.serverOwner(call)) {
        is ExtensionActorLookup.Denied -> {
            call.deny(lookup.decision)
            null
        }
        is ExtensionActorLookup.Found -> lookup.actor
    }

private suspend fun ApplicationCall.deny(decision: ModuleAccessDecision) {
    response.header(HttpHeaders.CacheControl, "no-store")
    when (decision) {
        is ModuleAccessDecision.Unauthorized -> respond(HttpStatusCode.Unauthorized, ModuleAccessErrorResponse(decision.code, decision.message))
        is ModuleAccessDecision.Forbidden -> respond(HttpStatusCode.Forbidden, ModuleAccessErrorResponse(decision.code, decision.message))
        ModuleAccessDecision.Allowed -> Unit
    }
}

private suspend fun ApplicationCall.invalidRequest() =
    respond(HttpStatusCode.BadRequest, ExtensionErrorResponse("INVALID_REQUEST", "Debes enviar un JSON valido."))

private val routeLog = LoggerFactory.getLogger("com.ideasdeveloper.idc.server.extensions.ExtensionRoutes")

private val bodyJson = Json { ignoreUnknownKeys = true }

/**
 * The JSON body as [T]. An empty body gives [default] (null = a body is required); a body that is not
 * valid JSON for [T] gives null, which the caller answers with 400.
 */
private suspend inline fun <reified T : Any> ApplicationCall.bodyOrNull(default: T?): T? {
    val text = receiveText()
    if (text.isBlank()) return default
    return try {
        bodyJson.decodeFromString<T>(text)
    } catch (_: SerializationException) {
        null
    } catch (_: IllegalArgumentException) {
        null
    }
}

/**
 * Runs [block] off the request thread and answers with its result; a null result answers only the
 * status (204). Refusals become their HTTP status; messages of database errors are never echoed.
 * Cancellation is not caught, so a cancelled request stops normally.
 */
private suspend inline fun <reified T : Any> ApplicationCall.respondService(status: HttpStatusCode = HttpStatusCode.OK, crossinline block: () -> T?) {
    response.header(HttpHeaders.CacheControl, "no-store")
    try {
        val result = withContext(Dispatchers.IO) { block() }
        if (result == null) respond(status) else respond(status, result)
    } catch (refusal: ExtensionException) {
        respond(refusal.kind.status, ExtensionErrorResponse(refusal.code, refusal.message))
    } catch (refusal: CompanyProvisioningException) {
        respond(HttpStatusCode.Conflict, ExtensionErrorResponse(refusal.code ?: "COMPANY_ERROR", refusal.message.orEmpty()))
    } catch (_: SecretsUnavailableException) {
        respond(HttpStatusCode.ServiceUnavailable, ExtensionErrorResponse("SECRETS_UNAVAILABLE", "El servidor no tiene IDC_MASTER_KEY configurada: no se pueden guardar ni leer secretos."))
    } catch (_: SecretDecryptionException) {
        respond(HttpStatusCode.Conflict, ExtensionErrorResponse("SECRET_DECRYPTION_FAILED", "No se pudo descifrar un secreto de la empresa; vuelve a escribirlo."))
    } catch (_: IllegalArgumentException) {
        respond(HttpStatusCode.BadRequest, ExtensionErrorResponse("INVALID_REQUEST", "La solicitud no es valida."))
    } catch (failure: SQLException) {
        // Only the SQLSTATE is logged: the message of a database error can contain data.
        routeLog.warn("Extension request failed in the database (SQLSTATE {})", failure.sqlState)
        when {
            failure is SQLTransientConnectionException || failure.sqlState?.startsWith("08") == true ->
                respond(HttpStatusCode.ServiceUnavailable, ExtensionErrorResponse("DATABASE_UNAVAILABLE", "No se pudo conectar con la base de datos."))
            failure.sqlState?.startsWith("23") == true ->
                respond(HttpStatusCode.Conflict, ExtensionErrorResponse("DATA_CONFLICT", "La operacion choca con datos existentes."))
            else -> respond(HttpStatusCode.InternalServerError, ExtensionErrorResponse("DATABASE_ERROR", "La operacion no se pudo completar."))
        }
    }
}
