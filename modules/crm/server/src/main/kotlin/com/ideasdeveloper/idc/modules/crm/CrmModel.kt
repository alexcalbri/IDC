package com.ideasdeveloper.idc.modules.crm

import com.ideasdeveloper.idc.ui.UiTone
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.time.format.DateTimeParseException
import java.util.UUID

class CrmValidationException(message: String) : Exception(message)

enum class StageKind(val id: String, val label: String) {
    Open("open", "Abierta"),
    Won("won", "Ganada"),
    Lost("lost", "Perdida");

    companion object {
        fun fromId(id: String): StageKind = entries.firstOrNull { it.id == id } ?: Open
        fun fromLabel(label: String): StageKind? = entries.firstOrNull { it.label == label }
    }
}

// Stage colors are tones: the app paints them with the user's theme.
enum class StageTone(val id: String, val label: String) {
    Info(UiTone.Info, "Azul"),
    Warning(UiTone.Warning, "Amarillo"),
    Danger(UiTone.Danger, "Rojo"),
    Success(UiTone.Success, "Verde"),
    Neutral(UiTone.Neutral, "Gris");

    companion object {
        fun fromId(id: String): StageTone = entries.firstOrNull { it.id == id } ?: Neutral
        fun fromLabel(label: String): StageTone? = entries.firstOrNull { it.label == label }
    }
}

data class CrmStage(val id: UUID, val name: String, val kind: StageKind, val tone: StageTone, val position: Int)

data class CrmInterest(val id: UUID, val name: String, val position: Int)

// A user that can own leads: active, business owner or holder of a CRM permission.
data class CrmUser(val id: UUID, val name: String)

data class CrmLead(
    val id: UUID,
    val customerId: UUID,
    val name: String,
    val email: String,
    val phone: String,
    val stageId: UUID,
    val interestId: UUID?,
    val ownerUserId: UUID?,
    val source: String,
    val language: String,
    val message: String,
    val details: String,
    val targetStart: LocalDate?,
    val targetEnd: LocalDate?,
    val estimatedValue: BigDecimal?,
    val followUpOn: LocalDate?,
    val stageChangedAt: Instant,
    val closedAt: Instant?,
    val createdAt: Instant,
    // Responsible user proposed by the source (e.g. a listing's agent), waiting for approval.
    val suggestedOwnerUserId: UUID? = null,
    // Why the lead needs a person to check it (e.g. its phone belongs to another customer); "" when not.
    val reviewReason: String = "",
)

data class CrmActivity(
    val kind: String,
    val body: String,
    val fromStage: String?,
    val toStage: String?,
    val author: String,
    val createdAt: Instant,
)

/**
 * Which leads a user sees. With interests off everybody with the CRM sees every lead. With
 * interests on, a user sees the leads of the interests assigned to them, the leads they own and,
 * with the reception permission, the leads nobody has categorized yet; "see all" sees everything.
 */
data class CrmScope(
    val userId: UUID?,
    val seesAll: Boolean,
    val reception: Boolean,
    val interestsEnabled: Boolean,
    val interests: Set<UUID>,
) {
    fun canSee(interestId: UUID?, ownerUserId: UUID?): Boolean = when {
        !interestsEnabled || seesAll -> true
        userId != null && ownerUserId == userId -> true
        interestId == null -> reception
        else -> interestId in interests
    }

    // Whether the user may give leads any interest (otherwise only the ones assigned to them).
    fun canUseInterest(interestId: UUID?): Boolean =
        !interestsEnabled || seesAll || if (interestId == null) reception else interestId in interests

    /**
     * SQL condition over crm_leads aliased "l", equivalent to [canSee]. Parameters are UUIDs or a
     * collection of UUIDs (bound as uuid[]).
     */
    fun sqlCondition(): Pair<String, List<Any>> {
        if (!interestsEnabled || seesAll) return "TRUE" to emptyList()
        val parts = mutableListOf<String>()
        val params = mutableListOf<Any>()
        if (userId != null) {
            parts += "l.owner_user_id = ?"
            params += userId
        }
        if (reception) parts += "l.interest_id IS NULL"
        if (interests.isNotEmpty()) {
            parts += "l.interest_id = ANY(?)"
            params.add(interests.sortedBy { it.toString() })
        }
        return (if (parts.isEmpty()) "FALSE" else parts.joinToString(" OR ", "(", ")")) to params
    }
}

// Filters of the pipeline and the lead list, on top of the user's scope.
data class CrmLeadFilter(
    val interestId: UUID? = null,
    // Only leads without interest (reception inbox).
    val withoutInterest: Boolean = false,
    val ownerUserId: UUID? = null,
    val unassigned: Boolean = false,
    val stageId: UUID? = null,
    val search: String = "",
    val openOnly: Boolean = false,
)

data class CrmLeadInput(
    val name: String,
    val email: String,
    val phone: String,
    val stageId: UUID,
    val interestId: UUID?,
    val ownerUserId: UUID?,
    val source: String,
    val language: String,
    val message: String,
    val details: String,
    val targetStart: LocalDate?,
    val targetEnd: LocalDate?,
    val estimatedValue: BigDecimal?,
    val followUpOn: LocalDate?,
)

data class CrmStageInput(val name: String, val kind: StageKind, val tone: StageTone, val position: Int)

object CrmForms {
    const val NoInterest = "Sin interes (recepcion)"
    const val Unassigned = "Sin asignar"
    private const val MaxText = 5000

    /**
     * Reads the lead form. Dropdowns send the visible names, which are mapped back to ids; with
     * interests off the interest is always empty.
     */
    fun parseLead(
        values: Map<String, String>,
        stages: List<CrmStage>,
        interests: List<CrmInterest>,
        interestsEnabled: Boolean,
        users: List<CrmUser>,
    ): CrmLeadInput {
        fun text(key: String, max: Int, label: String): String {
            val value = values[key].orEmpty().trim()
            if (value.length > max) throw CrmValidationException("$label admite hasta $max caracteres.")
            return value
        }
        val name = text("name", 200, "El nombre")
        if (name.isEmpty()) throw CrmValidationException("Escribe el nombre del lead.")
        // Email and phone are optional (chats and calls often bring only one); a typed email must be valid.
        val email = text("email", 254, "El correo")
        if (email.isNotEmpty() && !email.matches(Regex("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$"))) throw CrmValidationException("Escribe un correo valido o dejalo vacio.")
        val phone = text("phone", 40, "El telefono")
        val stageName = values["stage"].orEmpty()
        val stage = stages.firstOrNull { it.name == stageName } ?: stages.firstOrNull()
            ?: throw CrmValidationException("Crea al menos una etapa en Configuracion.")
        val interestName = values["interest"].orEmpty()
        val interest = if (!interestsEnabled || interestName.isBlank() || interestName == NoInterest) null else {
            interests.firstOrNull { it.name == interestName } ?: throw CrmValidationException("Ese interes ya no existe.")
        }
        val ownerName = values["owner"].orEmpty()
        val owner = if (ownerName.isBlank() || ownerName == Unassigned) null else {
            users.firstOrNull { it.name == ownerName } ?: throw CrmValidationException("Ese responsable ya no puede tener leads.")
        }
        val targetStart = date(values["targetStart"], "La fecha de inicio")
        val targetEnd = date(values["targetEnd"], "La fecha de fin")
        if (targetStart != null && targetEnd != null && targetEnd.isBefore(targetStart)) {
            throw CrmValidationException("La fecha de fin no puede ser anterior a la de inicio.")
        }
        return CrmLeadInput(
            name = name,
            email = email,
            phone = phone,
            stageId = stage.id,
            interestId = interest?.id,
            ownerUserId = owner?.id,
            source = text("source", 120, "El origen"),
            language = text("language", 40, "El idioma"),
            message = text("message", MaxText, "El mensaje"),
            details = text("details", MaxText, "Los detalles"),
            targetStart = targetStart,
            targetEnd = targetEnd,
            estimatedValue = money(values["estimatedValue"]),
            followUpOn = date(values["followUpOn"], "La fecha de seguimiento"),
        )
    }

    fun parseStage(values: Map<String, String>): CrmStageInput {
        val name = values["name"].orEmpty().trim()
        if (name.isEmpty() || name.length > 60) throw CrmValidationException("El nombre de la etapa debe tener entre 1 y 60 caracteres.")
        val kind = StageKind.fromLabel(values["kind"].orEmpty()) ?: StageKind.Open
        val tone = StageTone.fromLabel(values["tone"].orEmpty()) ?: StageTone.Neutral
        val position = values["position"].orEmpty().trim().ifEmpty { "0" }.toIntOrNull()?.takeIf { it in 0..999 }
            ?: throw CrmValidationException("La posicion debe ser un numero entre 0 y 999.")
        return CrmStageInput(name, kind, tone, position)
    }

    fun parseInterestName(values: Map<String, String>): String {
        val name = values["name"].orEmpty().trim()
        if (name.isEmpty() || name.length > 60) throw CrmValidationException("El nombre del interes debe tener entre 1 y 60 caracteres.")
        return name
    }

    fun note(values: Map<String, String>): String {
        val body = values["note"].orEmpty().trim()
        if (body.isEmpty()) throw CrmValidationException("Escribe la nota.")
        if (body.length > MaxText) throw CrmValidationException("La nota admite hasta $MaxText caracteres.")
        return body
    }

    private fun date(raw: String?, label: String): LocalDate? {
        val value = raw.orEmpty().trim()
        if (value.isEmpty()) return null
        return try {
            LocalDate.parse(value)
        } catch (failure: DateTimeParseException) {
            throw CrmValidationException("$label debe tener el formato AAAA-MM-DD.")
        }
    }

    private fun money(raw: String?): BigDecimal? {
        val value = raw.orEmpty().trim().replace(",", "")
        if (value.isEmpty()) return null
        val amount = value.toBigDecimalOrNull()?.takeIf { it.signum() >= 0 && it.scale() <= 2 && it < BigDecimal("1000000000000") }
            ?: throw CrmValidationException("El valor estimado debe ser un numero positivo con hasta 2 decimales.")
        return amount
    }
}

// Stage sets a company can start from (only while it has no leads).
object CrmTemplates {
    data class Template(val id: String, val label: String, val stages: List<CrmStageInput>)

    private fun stage(name: String, kind: StageKind, tone: StageTone, position: Int) = CrmStageInput(name, kind, tone, position)

    val all = listOf(
        Template(
            "general", "General",
            listOf(
                stage("Nuevo", StageKind.Open, StageTone.Info, 0),
                stage("Contactado", StageKind.Open, StageTone.Warning, 1),
                stage("En seguimiento", StageKind.Open, StageTone.Danger, 2),
                stage("Ganado", StageKind.Won, StageTone.Success, 3),
                stage("Perdido", StageKind.Lost, StageTone.Neutral, 4),
            ),
        ),
        // Tourism / hospitality: the flow used to answer and book surf, yoga or event inquiries.
        Template(
            "turismo", "Turismo y hospedaje",
            listOf(
                stage("Nuevo", StageKind.Open, StageTone.Info, 0),
                stage("Respondido", StageKind.Open, StageTone.Warning, 1),
                stage("En comunicacion", StageKind.Open, StageTone.Danger, 2),
                stage("Reservado", StageKind.Won, StageTone.Success, 3),
                stage("Frio", StageKind.Lost, StageTone.Neutral, 4),
                stage("Huesped anterior", StageKind.Won, StageTone.Success, 5),
            ),
        ),
        Template(
            "inmobiliaria", "Bienes raices",
            listOf(
                stage("Nuevo", StageKind.Open, StageTone.Info, 0),
                stage("Contactado", StageKind.Open, StageTone.Warning, 1),
                stage("Visita", StageKind.Open, StageTone.Danger, 2),
                stage("Oferta", StageKind.Open, StageTone.Warning, 3),
                stage("Cerrado", StageKind.Won, StageTone.Success, 4),
                stage("Perdido", StageKind.Lost, StageTone.Neutral, 5),
            ),
        ),
    )

    fun find(id: String): Template? = all.firstOrNull { it.id == id }
}

// Follow-up badge of an open lead: overdue, today or upcoming.
fun followUpState(followUpOn: LocalDate?, today: LocalDate): Pair<String, String>? = when {
    followUpOn == null -> null
    followUpOn.isBefore(today) -> "Seguimiento vencido $followUpOn" to UiTone.Danger
    followUpOn == today -> "Seguimiento hoy" to UiTone.Warning
    else -> "Seguimiento $followUpOn" to UiTone.Info
}
