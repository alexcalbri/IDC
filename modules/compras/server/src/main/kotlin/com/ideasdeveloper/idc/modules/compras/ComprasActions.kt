package com.ideasdeveloper.idc.modules.compras

import com.ideasdeveloper.idc.server.modules.ModuleNotifier
import com.ideasdeveloper.idc.ui.UiFieldValues
import com.ideasdeveloper.idc.ui.UiFileRef
import com.ideasdeveloper.idc.ui.UiJson
import kotlinx.serialization.SerializationException
import kotlinx.serialization.builtins.ListSerializer
import com.ideasdeveloper.idc.ui.UiNotice
import com.ideasdeveloper.idc.ui.UiTone
import org.slf4j.LoggerFactory
import java.sql.Connection
import java.time.LocalDate
import java.util.UUID

/**
 * Acciones (POST) de las pantallas de Compras. [permitted] decide, sin tocar datos, si el permiso del usuario
 * alcanza para la operacion; [run] la ejecuta (JDBC bloqueante: dentro de withContext(Dispatchers.IO)) y dice
 * que pantalla mostrar despues. Las reglas de fondo (flujo, limites, visibilidad) las aplican ComprasRepository,
 * ComprasFlow y ComprasApproval; un [ComprasValidationException] vuelve a la misma pantalla con un aviso rojo.
 */
object ComprasActions {
    private val log = LoggerFactory.getLogger(ComprasActions::class.java)

    /** Pantalla que se muestra tras la accion. [submitted] repuebla el formulario cuando hubo un error. */
    class Outcome(val screen: String, val params: Map<String, String>, val notice: UiNotice, val submitted: Map<String, String>? = null)

    /** El permiso exacto de la operacion `screen:op`. [can] ya cuenta al dueno. Una operacion desconocida se rechaza. */
    fun permitted(screen: String, params: Map<String, String>, can: (String) -> Boolean): Boolean {
        if (!can(ComprasModule.View)) return false
        // Importar: la operacion es "<fuente>:<op>" y pide compras.import; la fuente valida el resto.
        if (screen == "import") return can(ComprasModule.Import) && params["op"]?.contains(':') == true
        val writer = can(ComprasModule.Create) || can(ComprasModule.Manage)
        return when ("$screen:${params["op"]}") {
            "new:create" -> can(ComprasModule.Create)
            "request:save", "request:quoteAdd", "request:quoteSelect", "request:quoteDelete", "request:saveFiles" -> writer
            "request:transition" -> when (RequestStatus.fromId(params["to"])) {
                null -> false
                RequestStatus.Approved, RequestStatus.Rejected -> can(ComprasModule.Approve)
                // Quien aprobo tambien puede marcar comprada o completar lo suyo: el flujo decide.
                else -> writer || can(ComprasModule.Approve)
            }
            "board:move" -> writer || can(ComprasModule.Approve)
            "approvals:approve", "approvals:reject" -> can(ComprasModule.Approve)
            "settings:currency", "settings:deptSave", "settings:deptActive", "settings:approverSave", "settings:approverRemove" -> can(ComprasModule.Manage)
            else -> false
        }
    }

    /**
     * Las fuentes de importacion activas en la empresa: las que [enabled] confirma. Si [enabled] lanza, se registra
     * y la fuente cuenta como no activa (la cancelacion de corrutina no aplica: es codigo bloqueante).
     */
    fun activeSources(sources: List<ComprasImportSource>, enabled: (ComprasImportSource) -> Boolean): List<ComprasImportSource> =
        sources.filter { source ->
            try {
                enabled(source)
            } catch (failure: Exception) {
                log.warn("No se pudo saber si la importacion {} esta activa ({}).", source.id, failure.javaClass.simpleName)
                false
            }
        }

    private fun uuid(text: String?): UUID? = text?.let { runCatching { UUID.fromString(it) }.getOrNull() }

    private fun invalid(message: String) = ComprasValidationException(message)

    /** Datos de una solicitud a partir de los valores del formulario; [ComprasValidationException] si algo no es valido. */
    fun draftOf(values: Map<String, String>, departments: List<Department>, defaultCurrency: String): RequestDraft {
        val departmentName = values["department"]?.trim().orEmpty()
        val department = if (departmentName.isEmpty() || departmentName == ComprasScreens.NoDepartment) {
            null
        } else {
            departments.firstOrNull { it.name.equals(departmentName, ignoreCase = true) } ?: throw invalid("El departamento '$departmentName' no existe.")
        }
        val priorityText = values["priority"]?.trim().orEmpty()
        val priority = if (priorityText.isEmpty()) {
            Priority.Medium
        } else {
            Priority.entries.firstOrNull { it.label.equals(priorityText, ignoreCase = true) || it.id == priorityText } ?: throw invalid("La prioridad no es valida.")
        }
        val dateText = values["requiredDate"]?.trim().orEmpty()
        val requiredDate = if (dateText.isEmpty()) null else runCatching { LocalDate.parse(dateText) }.getOrNull() ?: throw invalid("La fecha requerida no es valida.")
        return RequestDraft(
            title = values["title"].orEmpty(),
            description = values["description"].orEmpty(),
            departmentId = department?.id,
            estimatedAmount = ComprasMoney.parseOptional(values["amount"]),
            currency = values["currency"]?.takeIf { it.isNotBlank() } ?: defaultCurrency,
            requiredDate = requiredDate,
            priority = priority,
        )
    }

    /** Ids de archivos del valor de un campo "files". */
    fun fileIds(value: String?): List<UUID> = UiFieldValues.files(value.orEmpty()).mapNotNull { uuid(it.id) }

    /**
     * Ids del campo "files" cuando guardarlos tambien QUITA los que faltan: sin la clave, o con un valor que no es
     * una lista JSON valida, es un error (no se borra nada). Un valor vacio es la lista vacia a proposito.
     */
    fun strictFileIds(values: Map<String, String>): List<UUID> {
        val value = values["files"]?.trim() ?: throw invalid("No llegaron los archivos del formulario; recarga la solicitud e intenta de nuevo.")
        if (value.isEmpty()) return emptyList()
        val refs = try {
            UiJson.decodeFromString(ListSerializer(UiFileRef.serializer()), value)
        } catch (_: SerializationException) {
            throw invalid("La lista de archivos no es valida; recarga la solicitud e intenta de nuevo.")
        } catch (_: IllegalArgumentException) {
            throw invalid("La lista de archivos no es valida; recarga la solicitud e intenta de nuevo.")
        }
        return refs.map { uuid(it.id) ?: throw invalid("La lista de archivos no es valida; recarga la solicitud e intenta de nuevo.") }
    }

    private fun notFound() = invalid("La solicitud no existe o no tienes acceso a ella.")

    private fun checkedRequest(tenant: Connection, actor: Actor, id: UUID?): PurchaseRequest =
        id?.let { ComprasRepository.get(tenant, it, actor) } ?: throw notFound()

    /**
     * Mueve la solicitud, avisa (nunca falla por el aviso) y devuelve el mensaje. Si pasa a pendiente de aprobacion
     * y ningun aprobador cubre el monto, el aviso es una advertencia.
     */
    private fun move(tenant: Connection, actor: Actor, notifier: ModuleNotifier?, id: UUID, to: RequestStatus, reason: String?): UiNotice {
        val result = ComprasRepository.transition(tenant, actor, id, to, reason)
        ComprasNotify.afterTransition(tenant, notifier, result, actor)
        val request = result.request
        val text = "${request.folio} paso a ${request.status.label}."
        if (to == RequestStatus.PendingApproval) {
            val amount = request.approvalAmount
            val covered = if (amount == null) true else runCatching { ComprasApproval.eligibleApprovers(tenant, amount, request.approvalCurrency).isNotEmpty() }
                .onFailure { log.warn("No se pudo comprobar quien cubre el monto de una solicitud de compra ({}).", it.javaClass.simpleName) }
                .getOrDefault(true)
            if (!covered) {
                return UiNotice("$text Ningun aprobador cubre este monto (${ComprasScreens.money(amount, request.approvalCurrency)}): pide a quien administra Compras que designe uno.", UiTone.Warning)
            }
        }
        return UiNotice(text, UiTone.Success)
    }

    private fun linkFiles(tenant: Connection, actor: Actor, requestId: UUID, value: String?, quoteId: UUID? = null, kind: String = "support"): String? {
        val ids = fileIds(value)
        if (ids.isEmpty()) return null
        return ComprasAttachments.describe(ComprasAttachments.link(tenant, actor, requestId, ids, quoteId, kind))
    }

    /** Ejecuta la operacion `screen:op` ya autorizada con [permitted]. */
    fun run(tenant: Connection, actor: Actor, notifier: ModuleNotifier?, screen: String, params: Map<String, String>, values: Map<String, String>, importContext: (() -> ComprasImportContext)? = null): Outcome {
        val back = params - setOf("op", "to", "quote", "active", "card", "column", "user", "submit")
        return try {
            execute(tenant, actor, notifier, screen, params, values, back, importContext)
        } catch (failure: ComprasValidationException) {
            val target = if (screen in setOf("approvals", "board", "settings", "new", "import")) screen else "request"
            // En Importar no se reflejan los valores (pueden traer el token de una fuente).
            Outcome(target, back, UiNotice(failure.message.orEmpty(), UiTone.Danger), if (screen == "import") null else values)
        }
    }

    private fun execute(tenant: Connection, actor: Actor, notifier: ModuleNotifier?, screen: String, params: Map<String, String>, values: Map<String, String>, back: Map<String, String>, importContext: (() -> ComprasImportContext)?): Outcome {
        fun ok(target: String, text: String, targetParams: Map<String, String> = back) = Outcome(target, targetParams, UiNotice(text, UiTone.Success))
        if (screen == "import") {
            // "<fuente>:<op>": la extension de la fuente atiende la operacion y devuelve el aviso.
            val full = params["op"].orEmpty()
            val source = ComprasImportSources.byId(full.substringBefore(':')) ?: throw invalid("Esa fuente de importacion no esta instalada.")
            val ctx = importContext?.invoke() ?: throw invalid("No se pudo preparar la importacion.")
            if (activeSources(listOf(source)) { it.isEnabled(ctx) }.isEmpty()) {
                throw invalid("La importacion desde ${source.label} no esta activa en esta empresa.")
            }
            val notice = source.action(ctx, full.substringAfter(':'), values) ?: UiNotice("Listo.", UiTone.Success)
            return Outcome("import", back, notice)
        }
        val id = uuid(params["id"])
        return when ("$screen:${params["op"]}") {
            "new:create" -> {
                val settings = ComprasRepository.settings(tenant)
                val draft = draftOf(values, ComprasRepository.departments(tenant), settings.defaultCurrency)
                val request = ComprasRepository.create(tenant, actor, draft)
                val shown = mapOf("id" to request.id.toString())
                val files = try {
                    linkFiles(tenant, actor, request.id, values["files"])
                } catch (failure: ComprasValidationException) {
                    return Outcome("request", shown, UiNotice("${request.folio} se guardo como borrador, pero los archivos no: ${failure.message}", UiTone.Warning))
                }
                if (params["submit"] == "true") {
                    try {
                        Outcome("request", shown, move(tenant, actor, notifier, request.id, RequestStatus.PendingApproval, null))
                    } catch (failure: ComprasValidationException) {
                        Outcome("request", shown, UiNotice("${request.folio} se guardo como borrador, pero no se pudo enviar a aprobacion: ${failure.message}", UiTone.Warning))
                    }
                } else {
                    ok("request", "${request.folio} guardada como borrador." + (files?.let { " $it." } ?: ""), shown)
                }
            }
            "request:save" -> {
                val request = checkedRequest(tenant, actor, id)
                val draft = draftOf(values, ComprasRepository.departments(tenant), request.currency)
                val saved = ComprasRepository.update(tenant, actor, request.id, draft)
                ok("request", "Cambios guardados en ${saved.folio}.")
            }
            "request:transition" -> {
                val to = RequestStatus.fromId(params["to"]) ?: throw invalid("Estado no valido.")
                val request = checkedRequest(tenant, actor, id)
                Outcome("request", back, move(tenant, actor, notifier, request.id, to, values["reason"]))
            }
            "request:quoteAdd" -> {
                val request = checkedRequest(tenant, actor, id)
                val currency = values["quoteCurrency"]?.takeIf { it.isNotBlank() } ?: request.currency
                val quote = ComprasRepository.addQuote(
                    tenant, actor, request.id, values["supplier"].orEmpty(), ComprasMoney.parse(values["quoteAmount"]), ComprasMoney.currency(currency), values["quoteNotes"].orEmpty(),
                )
                linkFiles(tenant, actor, request.id, values["quoteFiles"], quote.id, "quote")
                ok("request", "Cotizacion de ${quote.supplier} agregada.")
            }
            "request:quoteSelect" -> {
                val request = checkedRequest(tenant, actor, id)
                val quoteId = if (params["quote"] == "none") null else uuid(params["quote"]) ?: throw invalid("La cotizacion no existe.")
                ComprasRepository.selectQuote(tenant, actor, request.id, quoteId)
                ok("request", if (quoteId == null) "Se quito la cotizacion elegida: el monto a aprobar vuelve a ser el estimado." else "Cotizacion elegida: ahora es el monto a aprobar.")
            }
            "request:quoteDelete" -> {
                val request = checkedRequest(tenant, actor, id)
                ComprasRepository.deleteQuote(tenant, actor, request.id, uuid(params["quote"]) ?: throw invalid("La cotizacion no existe."))
                ok("request", "Cotizacion quitada.")
            }
            "request:saveFiles" -> {
                val request = checkedRequest(tenant, actor, id)
                val wanted = strictFileIds(values).toSet()
                val current = ComprasAttachments.forRequest(tenant, actor, request.id)
                val toLink = wanted.filter { wantedId -> current.none { it.ref.id == wantedId.toString() } }
                val toRemove = current.filter { uuid(it.ref.id) !in wanted }
                val added = if (toLink.isEmpty()) 0 else ComprasAttachments.link(tenant, actor, request.id, toLink).added.size
                var removed = 0
                toRemove.forEach { attachment -> if (ComprasAttachments.remove(tenant, actor, request.id, UUID.fromString(attachment.ref.id)) != null) removed++ }
                ok("request", "Archivos guardados (+$added, -$removed).")
            }
            "board:move" -> boardMove(tenant, actor, notifier, params)
            "approvals:approve", "approvals:reject" -> {
                val to = if (params["op"] == "approve") RequestStatus.Approved else RequestStatus.Rejected
                val request = checkedRequest(tenant, actor, id)
                Outcome("approvals", emptyMap(), move(tenant, actor, notifier, request.id, to, values["reason"]))
            }
            "settings:currency" -> {
                ComprasRepository.setDefaultCurrency(tenant, ComprasMoney.currency(values["currency"]))
                ok("settings", "Moneda por defecto guardada.")
            }
            "settings:deptSave" -> {
                ComprasRepository.saveDepartment(tenant, actor, id, values["name"].orEmpty())
                ok("settings", "Departamento guardado.")
            }
            "settings:deptActive" -> {
                ComprasRepository.setDepartmentActive(tenant, actor, id ?: throw invalid("El departamento no existe."), params["active"] == "true")
                ok("settings", "Departamento actualizado.")
            }
            "settings:approverSave" -> {
                val userId = uuid(params["user"]) ?: ComprasAdminScreens.userLabels(ComprasRepository.companyUsers(tenant))[values["user"].orEmpty()]?.id
                    ?: throw invalid("Elige un usuario de la lista.")
                val currency = values["limitCurrency"]?.takeIf { it.isNotBlank() } ?: ComprasRepository.settings(tenant).defaultCurrency
                ComprasRepository.saveApprover(tenant, actor, userId, ComprasMoney.parseOptional(values["maxAmount"]), ComprasMoney.currency(currency), values["active"] == "true")
                ok("settings", "Aprobador guardado.")
            }
            "settings:approverRemove" -> {
                ComprasRepository.removeApprover(tenant, actor, uuid(params["user"]) ?: throw invalid("El aprobador no existe."))
                ok("settings", "Aprobador quitado.")
            }
            else -> throw invalid("Operacion no reconocida.")
        }
    }

    // Arrastrar una tarjeta del tablero: valida el flujo igual que el detalle. Rechazar pide motivo, asi que se hace en el detalle.
    private fun boardMove(tenant: Connection, actor: Actor, notifier: ModuleNotifier?, params: Map<String, String>): Outcome {
        fun warn(text: String) = Outcome("board", emptyMap(), UiNotice(text, UiTone.Warning))
        val request = uuid(params["card"])?.let { ComprasRepository.get(tenant, it, actor) } ?: return warn("Esa solicitud ya no esta disponible.")
        val to = RequestStatus.fromId(params["column"]) ?: return warn("Esa columna no existe.")
        if (to == request.status) return Outcome("board", emptyMap(), UiNotice("${request.folio} ya esta en ${to.label}.", UiTone.Info))
        if (to == RequestStatus.Rejected) return warn("Para rechazar ${request.folio} abre la solicitud e indica el motivo.")
        if ((to == RequestStatus.Approved) && !actor.can(ComprasModule.Approve)) return warn("No tienes permiso para aprobar compras.")
        return Outcome("board", emptyMap(), move(tenant, actor, notifier, request.id, to, null))
    }
}
