package com.ideasdeveloper.idc.modules.clientes

import com.ideasdeveloper.idc.server.modules.CompanyLinkException
import com.ideasdeveloper.idc.server.modules.ModuleAccessService
import com.ideasdeveloper.idc.server.modules.ModulePermissions
import com.ideasdeveloper.idc.server.modules.requireModuleAccess
import com.ideasdeveloper.idc.ui.UiButton
import com.ideasdeveloper.idc.ui.UiCard
import com.ideasdeveloper.idc.ui.UiJson
import com.ideasdeveloper.idc.ui.UiList
import com.ideasdeveloper.idc.ui.UiListItem
import com.ideasdeveloper.idc.ui.UiNavigate
import com.ideasdeveloper.idc.ui.UiNode
import com.ideasdeveloper.idc.ui.UiNotice
import com.ideasdeveloper.idc.ui.UiRequest
import com.ideasdeveloper.idc.ui.UiRow
import com.ideasdeveloper.idc.ui.UiScreen
import com.ideasdeveloper.idc.ui.UiText
import com.ideasdeveloper.idc.ui.UiTextStyle
import com.ideasdeveloper.idc.ui.UiTone
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.receiveText
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.UUID

/**
 * Linked companies ("Empresas vinculadas", business owner only) and "Registro de Clientes" (business owner
 * or clientes.fields.manage), drawn by the app from server-driven screens:
 * GET/POST /modules/clientes/ui/links and GET /modules/clientes/ui/activity. The permission is checked
 * here, in the server, on every request; hiding a button is never the protection.
 */
internal object ClientesLinkScreens {
    const val UiRoute = ClientesProfile.UiRoute
    private const val LinksRoute = "$UiRoute/links"
    private const val ActivityLimit = 200

    /** Linking changes what every company of the group sees: only the business owner. */
    fun canManageLinks(permissions: ModulePermissions): Boolean = permissions.businessOwner

    /** The company's log (fields created or deleted, companies linked) is for who manages the company's customers. */
    fun canSeeActivity(permissions: ModulePermissions): Boolean = permissions.businessOwner || permissions.has("clientes.fields.manage")

    fun routes(route: Route, access: ModuleAccessService) {
        route.route(UiRoute) {
            get("/links") {
                val permissions = call.linkPermissions(access) ?: return@get
                call.respondLinks(access, permissions, notice = null)
            }

            post("/links") {
                val permissions = call.linkPermissions(access) ?: return@post
                val params = call.request.queryParameters.entries().associate { it.key to it.value.firstOrNull().orEmpty() }
                call.receiveText() // The buttons send no form values.
                val notice = try {
                    withContext(Dispatchers.IO) { act(access, permissions, params) }
                } catch (failure: ClientesSharingException) {
                    UiNotice(failure.message.orEmpty(), UiTone.Danger)
                } catch (failure: CompanyLinkException) {
                    UiNotice(failure.message.orEmpty(), UiTone.Danger)
                }
                call.respondLinks(access, permissions, notice)
            }

            get("/activity") {
                val permissions = call.requireModuleAccess(access, ClientesModule.id) ?: return@get
                if (!canSeeActivity(permissions)) {
                    return@get call.respond(HttpStatusCode.Forbidden, ClientesErrorResponse("missing_permission", "Solo el business owner o quien administra los campos de clientes ve este registro."))
                }
                val screen = withContext(Dispatchers.IO) {
                    access.tenantConnectionForCompany(permissions.companyCode)?.use { tenant ->
                        activityScreen(ClientesActivity.companyEvents(tenant, ActivityLimit), permissions.businessOwner)
                    }
                } ?: unavailable("Registro de Clientes")
                call.respondText(UiJson.encodeToString(UiScreen.serializer(), screen), ContentType.Application.Json)
            }
        }
    }

    // The user's permissions when they are the business owner; otherwise answers 401/403 and returns null.
    private suspend fun ApplicationCall.linkPermissions(access: ModuleAccessService): ModulePermissions? {
        val permissions = requireModuleAccess(access, ClientesModule.id) ?: return null
        if (!canManageLinks(permissions)) {
            respond(HttpStatusCode.Forbidden, ClientesErrorResponse("missing_permission", "Solo el business owner puede administrar las empresas vinculadas."))
            return null
        }
        return permissions
    }

    private fun unavailable(title: String) = UiScreen(title, notice = UiNotice("No se pudo abrir la empresa.", UiTone.Danger))

    private suspend fun ApplicationCall.respondLinks(access: ModuleAccessService, permissions: ModulePermissions, notice: UiNotice?) {
        val screen = withContext(Dispatchers.IO) {
            val status = ClientesLinking.status(access, permissions.companyCode)
            val reviews = ClientesLinking.reviews(access, permissions.companyCode)
            linksScreen(permissions.companyCode, status, reviews, canSeeActivity(permissions), notice)
        }
        respondText(UiJson.encodeToString(UiScreen.serializer(), screen), ContentType.Application.Json)
    }

    // Runs the action of a POST (params["op"]) and says how it went. The author is recorded where the change is made.
    private fun act(access: ModuleAccessService, permissions: ModulePermissions, params: Map<String, String>): UiNotice {
        val currentCode = permissions.companyCode
        val company = params["company"].orEmpty().trim()
        if (company.isEmpty()) throw ClientesSharingException("Elige una empresa.")
        val tenant = access.tenantConnectionForCompany(currentCode) ?: throw ClientesSharingException("No se pudo abrir la empresa.")
        return tenant.use { connection ->
            val author = ClientesActivity.author(connection, permissions, access)
            val message = when (params["op"]) {
                "link" -> ClientesLinking.link(access, connection, currentCode, company, author)
                "unlink" -> ClientesLinking.unlink(access, connection, currentCode, company, author)
                "separate" -> ClientesLinking.separate(access, currentCode, company, mergeId(params), author)
                "retry" -> ClientesLinking.retry(access, currentCode, company, mergeId(params), author)
                else -> throw ClientesSharingException("Accion desconocida.")
            }
            UiNotice(message, UiTone.Success)
        }
    }

    private fun mergeId(params: Map<String, String>): UUID =
        params["merge"]?.let { runCatching { UUID.fromString(it) }.getOrNull() } ?: throw ClientesSharingException("Fusion invalida.")

    /** What the owner reads before linking: every customer is shared and duplicates are merged. */
    fun linkConfirm(currentName: String, targetName: String): String =
        "Se vincularan $currentName y $targetName: se compartiran TODOS los clientes de ambas empresas y los que tengan el mismo correo " +
            "se fusionaran solos (queda el mas antiguo). La copia se hace en segundo plano. Continuar?"

    fun unlinkConfirm(targetName: String): String =
        "$targetName dejara de ver los clientes de las demas empresas y las demas los suyos. No se borra ningun cliente. " +
            "Las fusiones ya hechas se conservan: las notas y el historial que se movieron quedan en el cliente que se quedo. " +
            "Si hace falta devolverlos, usa Separar en \"Por revisar\" antes de desvincular. Continuar?"

    private fun jobStatusText(status: String): String = when (status) {
        "pending" -> "pendiente"
        "running" -> "copiando"
        "done" -> "terminado"
        "error" -> "con error"
        else -> status
    }

    /** One line of the progress card: from where to where, how it goes and what needs attention. */
    fun jobLine(job: LinkJob): String = buildString {
        append("${job.from.name} -> ${job.to.name}: ${jobStatusText(job.status)}, ${job.copied} de ${job.total} copiados")
        if (job.merged > 0) append(", ${job.merged} fusionados")
        if (job.review > 0) append(", ${job.review} por revisar")
        if (job.error.isNotBlank()) append(". Error: ${job.error}")
    }

    fun reviewTitle(item: MergeItem): String = "${item.mergedName} y ${item.keptName}"

    fun reviewSubtitle(item: MergeItem): String =
        listOf(
            if (item.email.isNotBlank()) "Correo: ${item.email}" else "",
            if (item.status == "merged") "Se fusiono en ${item.keptCompany}, donde queda ${item.keptName}." else item.reason,
            item.company.name,
        ).filter { it.isNotBlank() }.joinToString(" - ")

    fun linksScreen(currentCode: String, status: LinkStatus, reviews: List<MergeItem>, canSeeActivity: Boolean, notice: UiNotice? = null): UiScreen {
        val currentName = status.linked.firstOrNull { it.code == currentCode }?.name ?: currentCode
        val others = status.linked.filter { it.code != currentCode }

        fun post(op: String, extra: Map<String, String>, confirm: String? = null) = UiRequest(LinksRoute, params = mapOf("op" to op) + extra, confirm = confirm)

        val linked = UiCard(
            title = "Empresas vinculadas",
            children = buildList<UiNode> {
                add(UiText("Las empresas vinculadas comparten TODOS sus clientes. Los que tienen el mismo correo se fusionan solos y queda el mas antiguo; lo que no se pueda fusionar queda en \"Por revisar\".", UiTextStyle.Caption))
                if (others.isEmpty()) {
                    add(UiText("$currentName no esta vinculada con otras empresas.", tone = UiTone.Neutral))
                } else {
                    others.forEach { company ->
                        add(
                            UiRow(
                                listOf(
                                    UiText(company.name),
                                    UiButton("Desvincular", post("unlink", mapOf("company" to company.code), unlinkConfirm(company.name)), style = "danger"),
                                ),
                            ),
                        )
                    }
                }
            },
        )

        val candidates = UiCard(
            title = "Empresas que se pueden vincular",
            children = buildList<UiNode> {
                if (status.candidates.isEmpty()) add(UiText("No hay otras empresas del mismo dueno para vincular.", UiTextStyle.Caption))
                status.candidates.forEach { company ->
                    add(
                        UiRow(
                            listOf(
                                UiText(company.name),
                                UiButton("Vincular", post("link", mapOf("company" to company.code), linkConfirm(currentName, company.name))),
                            ),
                        ),
                    )
                }
            },
        )

        val progress = UiCard(
            title = "Progreso de la copia",
            children = if (status.jobs.isEmpty()) {
                listOf(UiText("No hay copias en curso.", UiTextStyle.Caption))
            } else {
                status.jobs.map { job -> UiText(jobLine(job), tone = if (job.status == "error") UiTone.Danger else null) }
            },
        )

        val toReview = UiCard(
            title = "Por revisar (${status.reviewCount})",
            children = if (reviews.isEmpty()) {
                listOf(UiText("Nada por revisar.", UiTextStyle.Caption))
            } else {
                reviews.map { item ->
                    val params = mapOf("company" to item.company.code, "merge" to item.id.toString())
                    UiCard(
                        title = reviewTitle(item),
                        children = listOf(
                            UiText(reviewSubtitle(item), UiTextStyle.Caption),
                            UiRow(
                                buildList {
                                    if (item.status == "review") add(UiButton("Reintentar", post("retry", params), style = "secondary"))
                                    add(UiButton("Separar", post("separate", params, "Los clientes quedan separados y no se volveran a fusionar solos. Continuar?"), style = "danger"))
                                },
                            ),
                        ),
                    )
                }
            },
        )

        val body = buildList<UiNode> {
            add(linked)
            add(candidates)
            add(progress)
            add(toReview)
            if (canSeeActivity) add(UiButton("Registro de Clientes", UiNavigate("activity"), style = "secondary"))
        }
        return UiScreen("Empresas vinculadas", notice = notice, body = body)
    }

    fun activityScreen(events: List<ClientesActivity.CompanyEvent>, businessOwner: Boolean): UiScreen = UiScreen(
        "Registro de Clientes",
        body = listOfNotNull(
            UiText("Cambios de la empresa en Clientes: campos personalizados creados o borrados y empresas vinculadas o desvinculadas. Los cambios de cada cliente estan en su ficha.", UiTextStyle.Caption),
            UiList(
                items = events.map { event ->
                    UiListItem(
                        title = ClientesActivity.title(event.kind),
                        subtitle = listOf(event.body, event.author).filter { it.isNotBlank() }.joinToString("\n"),
                        trailing = event.at.toLocalDate().toString(),
                    )
                },
                emptyText = "Sin eventos todavia.",
            ),
            if (businessOwner) UiButton("Empresas vinculadas", UiNavigate("links"), style = "secondary") else null,
        ),
    )
}
