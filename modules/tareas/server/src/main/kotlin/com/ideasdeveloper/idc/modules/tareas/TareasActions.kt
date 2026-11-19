package com.ideasdeveloper.idc.modules.tareas

import com.ideasdeveloper.idc.ui.UiFieldValues
import com.ideasdeveloper.idc.ui.UiFileRef
import com.ideasdeveloper.idc.ui.UiJson
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import java.time.LocalDate
import java.time.format.DateTimeParseException
import java.util.UUID

/** Result of comparing the files of a form with the ones already linked to the task. */
data class FileChanges(val added: List<UiFileRef>, val removed: List<UUID>)

/** What the board form sends. */
data class BoardForm(val name: String, val description: String, val visibility: BoardVisibility, val members: List<UUID>)

/**
 * Pure parsing of what the screens send (labels the user picked, dates, JSON values) into the types of the module.
 * Nothing here touches the database: the service applies the rules afterwards.
 */
object TareasActions {
    const val NoGroup = "Sin grupo"
    const val NoReviewer = "Sin revisor"
    const val AllOption = "Todos"
    private const val MaxBodyChars = 1_000_000

    /** Values of a form body: strings as they come and a JSON array (multiple dropdown) as its encoded text. */
    fun bodyValues(body: String): Map<String, String> {
        if (body.length > MaxBodyChars) return emptyMap()
        return runCatching {
            UiJson.parseToJsonElement(body).jsonObject.mapValues { (_, value) ->
                when (value) {
                    is JsonPrimitive -> value.contentOrNull.orEmpty()
                    is JsonArray -> UiFieldValues.options(value.map { (it as? JsonPrimitive)?.contentOrNull.orEmpty() })
                    else -> ""
                }
            }
        }.getOrDefault(emptyMap())
    }

    /** Like [bodyValues] but a body that is too big or not a JSON object is an error for the user. */
    fun formValues(body: String): Map<String, String> {
        if (body.length > MaxBodyChars) throw TareasValidationException(InvalidForm)
        val parsed = runCatching { UiJson.parseToJsonElement(body).jsonObject }.getOrNull() ?: throw TareasValidationException(InvalidForm)
        return bodyValues(parsed.toString())
    }

    const val InvalidForm = "Formulario demasiado grande o no válido."

    fun uuid(text: String?): UUID? = text?.trim()?.takeIf { it.isNotEmpty() }?.let { runCatching { UUID.fromString(it) }.getOrNull() }

    /** An ISO date (yyyy-MM-dd); blank is "no date". */
    fun date(text: String?): LocalDate? {
        val clean = text?.trim().orEmpty()
        if (clean.isEmpty()) return null
        return try {
            LocalDate.parse(clean)
        } catch (_: DateTimeParseException) {
            throw TareasValidationException("La fecha debe tener el formato AAAA-MM-DD.")
        }
    }

    fun priority(text: String?): TaskPriority {
        if (text.isNullOrBlank()) return TaskPriority.Medium
        return TaskPriority.fromLabel(text) ?: throw TareasValidationException("Prioridad no válida: $text.")
    }

    fun status(text: String?): TaskStatus =
        TaskStatus.fromLabel(text) ?: throw TareasValidationException("Estado no válido: ${text.orEmpty()}.")

    /** Labels of the users (as the screens show them) to ids; an unknown label is an error. */
    fun userIds(labels: Collection<String>, users: List<TareasUser>): List<UUID> =
        labels.map { it.trim() }.filter { it.isNotEmpty() }.map { label ->
            users.firstOrNull { it.label.equals(label, ignoreCase = true) }?.id
                ?: throw TareasValidationException("Persona no válida: $label. Elige solo personas de la lista.")
        }.distinct()

    fun reviewerId(label: String?, users: List<TareasUser>): UUID? {
        val clean = label?.trim().orEmpty()
        if (clean.isEmpty() || clean.equals(NoReviewer, ignoreCase = true)) return null
        return userIds(listOf(clean), users).firstOrNull()
    }

    fun groupId(label: String?, groups: List<BoardGroup>): UUID? {
        val clean = label?.trim().orEmpty()
        if (clean.isEmpty() || clean.equals(NoGroup, ignoreCase = true)) return null
        return groups.firstOrNull { !it.archived && it.name.equals(clean, ignoreCase = true) }?.id
            ?: throw TareasValidationException("El grupo \"$clean\" no existe en el tablero.")
    }

    /**
     * The task form to [TaskInput]. [existing] is the task being edited: it keeps its customer when the user cannot see
     * customers ([canSeeCustomers] false), because the form did not show it. The service checks the people and the customer.
     */
    fun taskInput(
        boardId: UUID,
        values: Map<String, String>,
        users: List<TareasUser>,
        groups: List<BoardGroup>,
        canSeeCustomers: Boolean,
        existing: Task? = null,
    ): TaskInput {
        val customerText = values["customer"]?.trim().orEmpty()
        val customerId = when {
            !canSeeCustomers -> existing?.customerId
            customerText.isEmpty() -> null
            else -> uuid(values["customer_id"]) ?: throw TareasValidationException("Elige el cliente de la lista de sugerencias.")
        }
        val input = TaskInput(
            boardId = boardId,
            groupId = groupId(values["group"], groups),
            title = values["title"].orEmpty().trim(),
            detail = values["detail"].orEmpty(),
            priority = priority(values["priority"]),
            dueDate = date(values["due"]),
            reviewerId = reviewerId(values["reviewer"], users),
            customerId = customerId,
            assignees = userIds(UiFieldValues.options(values["assignees"].orEmpty()), users),
        )
        val problems = input.validate()
        if (problems.isNotEmpty()) throw TareasValidationException(problems.joinToString(" "))
        return input
    }

    fun boardForm(values: Map<String, String>, users: List<TareasUser>): BoardForm {
        val name = values["name"].orEmpty().trim()
        if (name.isEmpty()) throw TareasValidationException("El nombre del tablero es obligatorio.")
        if (name.length > 120) throw TareasValidationException("El nombre del tablero admite hasta 120 caracteres.")
        val description = values["description"].orEmpty().trim()
        if (description.length > 2000) throw TareasValidationException("La descripción admite hasta 2000 caracteres.")
        val visibility = values["visibility"]?.takeIf { it.isNotBlank() }?.let {
            BoardVisibility.fromLabel(it) ?: throw TareasValidationException("Visibilidad no válida: $it.")
        } ?: BoardVisibility.Company
        return BoardForm(name, description, visibility, userIds(UiFieldValues.options(values["members"].orEmpty()), users))
    }

    /** Filters of the board screen (labels from the filter form); "Todos" and unknown labels mean no filter. */
    fun filter(params: Map<String, String>, users: List<TareasUser>, boardId: UUID?, limit: Int): TaskFilter {
        val assignee = params["assignee"]?.trim()?.takeIf { it.isNotEmpty() && !it.equals(AllOption, ignoreCase = true) }
            ?.let { label -> users.firstOrNull { it.label.equals(label, ignoreCase = true) }?.id }
        val status = params["status"]?.takeIf { !it.equals(AllOption, ignoreCase = true) }?.let { TaskStatus.fromLabel(it) }
        val due = params["due"]?.trim()?.takeIf { !it.equals(AllOption, ignoreCase = true) }
            ?.let { label -> DueFilter.entries.firstOrNull { it.label.equals(label, ignoreCase = true) } ?: DueFilter.fromCode(label) }
        return TaskFilter(
            boardId = boardId,
            assignee = assignee,
            status = status,
            due = due,
            query = params["q"]?.trim()?.takeIf { it.isNotEmpty() },
            limit = limit,
        )
    }

    /** The filter keys of [params] worth keeping when moving between views or after an action. */
    fun filterParams(params: Map<String, String>): Map<String, String> =
        listOf("q", "assignee", "status", "due").mapNotNull { key ->
            params[key]?.trim()?.takeIf { it.isNotEmpty() && !it.equals(AllOption, ignoreCase = true) }?.let { key to it }
        }.toMap()

    /**
     * Files of the form against the ones linked now: new ones are linked, missing ones are removed. A form without the
     * "files" key (a read-only one) changes nothing.
     */
    fun fileChanges(values: Map<String, String>, current: List<TaskAttachment>): FileChanges {
        val raw = values["files"] ?: return FileChanges(emptyList(), emptyList())
        val sent = UiFieldValues.files(raw)
        val currentIds = current.map { it.id.toString() }.toSet()
        val sentIds = sent.map { it.id }.toSet()
        return FileChanges(
            added = sent.filter { it.id !in currentIds },
            removed = current.filter { it.id.toString() !in sentIds }.map { it.id },
        )
    }

    /**
     * Permission the route asks before opening the connection: tasks.manage creates, edits and archives; tareas.boards
     * runs the board screens; everything else needs tareas.view (the service applies the fine rules).
     */
    fun requiredPermission(screen: String, op: String?): String = when (screen) {
        "new-task" -> TareasModule.Manage
        "task" -> when (op) {
            "save", "archive", "check-remove" -> TareasModule.Manage
            else -> TareasModule.View
        }
        "admin", "board-edit" -> TareasModule.Boards
        else -> TareasModule.View
    }
}
