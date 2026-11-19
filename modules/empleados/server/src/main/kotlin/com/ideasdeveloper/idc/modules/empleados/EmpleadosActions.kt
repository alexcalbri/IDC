package com.ideasdeveloper.idc.modules.empleados

import com.ideasdeveloper.idc.ui.UiFieldValues
import com.ideasdeveloper.idc.ui.UiJson
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import java.time.LocalDate
import java.time.format.DateTimeParseException
import java.util.UUID

/**
 * Lectura pura de lo que envian las pantallas (etiquetas que el usuario eligio, fechas, valores JSON) hacia los tipos del
 * modulo. Nada aqui toca la base de datos: el servicio aplica las reglas despues.
 */
object EmpleadosActions {
    const val NoSite = "Sin sede"
    const val NoDepartment = "Sin departamento"
    const val NoUser = "Sin usuario"
    const val AllOption = "Todos"
    const val ActiveOption = "Activos"
    const val InactiveOption = "Bajas"
    const val InvalidForm = "Formulario demasiado grande o no válido."
    private const val MaxBodyChars = 1_000_000

    val WeekdayLabels = listOf("Lunes", "Martes", "Miércoles", "Jueves", "Viernes", "Sábado", "Domingo")

    /** Valores de un formulario: textos tal cual y un arreglo JSON (desplegable multiple) como su texto codificado. */
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

    /** Como [bodyValues] pero un cuerpo demasiado grande o que no es un objeto JSON es un error para el usuario. */
    fun formValues(body: String): Map<String, String> {
        if (body.length > MaxBodyChars) throw EmpleadosValidationException(InvalidForm)
        val parsed = runCatching { UiJson.parseToJsonElement(body).jsonObject }.getOrNull() ?: throw EmpleadosValidationException(InvalidForm)
        return bodyValues(parsed.toString())
    }

    fun uuid(text: String?): UUID? = text?.trim()?.takeIf { it.isNotEmpty() }?.let { runCatching { UUID.fromString(it) }.getOrNull() }

    /** Una fecha ISO (AAAA-MM-DD); vacio es "sin fecha". */
    fun date(text: String?): LocalDate? {
        val clean = text?.trim().orEmpty()
        if (clean.isEmpty()) return null
        return try {
            LocalDate.parse(clean)
        } catch (_: DateTimeParseException) {
            throw EmpleadosValidationException("La fecha debe tener el formato AAAA-MM-DD.")
        }
    }

    fun requiredDate(text: String?, what: String): LocalDate = date(text) ?: throw EmpleadosValidationException("$what es obligatoria.")

    /** Una hora HH:mm; vacio es "sin hora". */
    fun time(text: String?): java.time.LocalTime? {
        val clean = text?.trim().orEmpty()
        if (clean.isEmpty()) return null
        return EmpleadosSchedule.parseTime(clean) ?: throw EmpleadosValidationException("La hora debe tener el formato HH:mm (de 00:00 a 23:59).")
    }

    fun requiredTime(text: String?, what: String): java.time.LocalTime = time(text) ?: throw EmpleadosValidationException("$what es obligatoria (HH:mm).")

    /** Dia de la semana ISO por su nombre (Lunes...Domingo); desconocido es un error. */
    fun weekday(label: String?): Int {
        val index = WeekdayLabels.indexOfFirst { it.equals(label?.trim(), ignoreCase = true) }
        if (index < 0) throw EmpleadosValidationException("Elige un día de la semana.")
        return index + 1
    }

    fun absenceKind(label: String?): AbsenceKind =
        AbsenceKind.fromLabel(label) ?: throw EmpleadosValidationException("Tipo de ausencia no válido: ${label.orEmpty()}.")

    fun shiftKind(label: String?): ShiftKind {
        if (label.isNullOrBlank()) return ShiftKind.Work
        return ShiftKind.fromLabel(label) ?: throw EmpleadosValidationException("Tipo de turno no válido: $label.")
    }

    // ---- etiquetas ----

    /**
     * Etiqueta unica de cada empleado para los desplegables: el nombre; si se repite, "nombre · puesto"; si aun se repite,
     * se numera. La misma funcion arma las opciones y lee la eleccion, asi que nunca se confunde a dos personas.
     */
    fun employeeLabels(employees: List<Employee>): Map<UUID, String> {
        val byName = employees.groupBy { it.fullName.trim().lowercase() }
        val result = LinkedHashMap<UUID, String>()
        val used = HashSet<String>()
        employees.forEach { employee ->
            val name = employee.fullName.trim()
            var label = if ((byName[name.lowercase()]?.size ?: 0) > 1 && employee.jobTitle.isNotBlank()) "$name · ${employee.jobTitle.trim()}" else name
            var n = 2
            val base = label
            while (!used.add(label.lowercase())) label = "$base ($n)".also { n++ }
            result[employee.id] = label
        }
        return result
    }

    fun employeeId(label: String?, labels: Map<UUID, String>): UUID {
        val clean = label?.trim().orEmpty()
        if (clean.isEmpty()) throw EmpleadosValidationException("Elige un empleado de la lista.")
        return labels.entries.firstOrNull { it.value.equals(clean, ignoreCase = true) }?.key
            ?: throw EmpleadosValidationException("Empleado no válido: $clean. Elige solo empleados de la lista.")
    }

    private fun catalogId(label: String?, none: String, items: List<CatalogItem>, what: String): UUID? {
        val clean = label?.trim().orEmpty()
        if (clean.isEmpty() || clean.equals(none, ignoreCase = true)) return null
        return items.firstOrNull { it.name.equals(clean, ignoreCase = true) }?.id
            ?: throw EmpleadosValidationException("Elige $what de la lista.")
    }

    fun siteId(label: String?, sites: List<CatalogItem>): UUID? = catalogId(label, NoSite, sites, "una sede")

    fun departmentId(label: String?, departments: List<CatalogItem>): UUID? = catalogId(label, NoDepartment, departments, "un departamento")

    fun userId(label: String?, users: List<UserRef>): UUID? {
        val clean = label?.trim().orEmpty()
        if (clean.isEmpty() || clean.equals(NoUser, ignoreCase = true)) return null
        return users.firstOrNull { it.label.equals(clean, ignoreCase = true) }?.id
            ?: throw EmpleadosValidationException("Elige un usuario de la lista.")
    }

    // ---- formularios ----

    /**
     * El formulario de la ficha a [EmployeeInput]. Si [users] es null el formulario no mostraba el usuario enlazado y se
     * conserva el de [existing]. El servicio comprueba que sedes, departamentos y usuario sean validos.
     */
    fun employeeInput(
        values: Map<String, String>,
        sites: List<CatalogItem>,
        departments: List<CatalogItem>,
        users: List<UserRef>?,
        existing: Employee?,
        today: LocalDate,
    ): EmployeeInput {
        val input = EmployeeInput(
            fullName = values["full_name"].orEmpty().trim(),
            jobTitle = values["job_title"].orEmpty().trim(),
            departmentId = departmentId(values["department"], departments),
            siteId = siteId(values["site"], sites),
            phone = values["phone"].orEmpty().trim(),
            email = values["email"].orEmpty().trim(),
            hireDate = date(values["hire_date"]),
            availability = values["availability"].orEmpty().trim(),
            notes = values["notes"].orEmpty(),
            userId = if (users == null) existing?.userId else userId(values["user"], users),
        )
        val problems = input.validate(today)
        if (problems.isNotEmpty()) throw EmpleadosValidationException(problems.joinToString(" "))
        return input
    }

    /** Filtros de la pantalla Equipo y de la vista semanal ("Todos" y etiquetas desconocidas son sin filtro). */
    fun filter(params: Map<String, String>, sites: List<CatalogItem>, departments: List<CatalogItem>, limit: Int): EmployeeFilter {
        fun pick(key: String, items: List<CatalogItem>): UUID? =
            params[key]?.trim()?.takeIf { it.isNotEmpty() && !it.equals(AllOption, ignoreCase = true) }
                ?.let { label -> items.firstOrNull { it.name.equals(label, ignoreCase = true) }?.id }
        val status = when (params["status"]?.trim()?.lowercase()) {
            ActiveOption.lowercase(), EmployeeStatus.Active.code -> EmployeeStatus.Active
            InactiveOption.lowercase(), EmployeeStatus.Inactive.code -> EmployeeStatus.Inactive
            AllOption.lowercase() -> null
            // Sin elegir: la lista muestra a quien trabaja.
            else -> EmployeeStatus.Active
        }
        return EmployeeFilter(
            query = params["q"]?.trim()?.take(EmpleadosRepository.MaxQuery)?.takeIf { it.isNotEmpty() },
            siteId = pick("site", sites),
            departmentId = pick("department", departments),
            status = status,
            limit = limit,
        )
    }

    /** Los parametros de filtro que conviene conservar al cambiar de semana o tras una accion. */
    fun filterParams(params: Map<String, String>): Map<String, String> =
        listOf("q", "site", "department", "status").mapNotNull { key ->
            params[key]?.trim()?.takeIf { it.isNotEmpty() && !it.equals(AllOption, ignoreCase = true) }?.let { key to it }
        }.toMap()

    /** El lunes de la semana pedida en `week` o `start` (cualquier fecha de la semana sirve); sin parametro o invalido, la de hoy. */
    fun weekOf(text: String?, today: LocalDate): LocalDate {
        val date = runCatching { date(text) }.getOrNull() ?: today
        return EmpleadosSchedule.weekStart(date)
    }

    /**
     * Permisos que sirven para una operacion (basta uno): ver, gestionar turnos y administrar fichas se incluyen hacia arriba,
     * asi que quien gestiona turnos o fichas tambien abre las pantallas de ver. El servicio aplica las reglas finas despues.
     */
    fun requiredPermissions(screen: String, op: String?): List<String> = when (screen) {
        "week", "shift" -> ShiftsAny
        "employee-new", "admin" -> ManageAny
        "employee" -> when (op) {
            null, "" -> ViewAny
            "slot-add", "slot-remove" -> ShiftsAny
            else -> ManageAny
        }
        "absences" -> when (op) {
            "decide-approve", "decide-reject", "register" -> ShiftsAny
            else -> ViewAny
        }
        "importar" -> listOf(EmpleadosModule.Import)
        else -> ViewAny
    }

    private val ViewAny = listOf(EmpleadosModule.View, EmpleadosModule.Shifts, EmpleadosModule.Manage)
    private val ShiftsAny = listOf(EmpleadosModule.Shifts, EmpleadosModule.Manage)
    private val ManageAny = listOf(EmpleadosModule.Manage)
}
