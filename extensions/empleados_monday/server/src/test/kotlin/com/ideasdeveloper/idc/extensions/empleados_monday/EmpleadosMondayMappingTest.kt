package com.ideasdeveloper.idc.extensions.empleados_monday

import com.ideasdeveloper.idc.modules.empleados.ImportedSlot
import com.ideasdeveloper.idc.server.extensions.ExtensionManifest
import com.ideasdeveloper.idc.server.modules.ModulePermissions
import kotlinx.serialization.json.Json
import java.io.File
import java.time.LocalTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class EmpleadosMondayMappingTest {
    private fun cell(id: String, title: String, type: String, text: String) = MondayCell(id, title, type, text)

    // El tablero "Horario del Equipo de Mantenimiento" de Emerald.
    private val emeraldColumns = listOf(
        MondayColumn("name", "Técnico", "name"),
        MondayColumn("status", "Propiedad", "status"),
        MondayColumn("text1", "Días de trabajo", "text"),
        MondayColumn("text2", "Horario", "text"),
        MondayColumn("text3", "Disponibilidad", "text"),
        MondayColumn("notes", "Notas", "long_text"),
    )

    private val emeraldMapping = MondayMapping(
        mapOf(
            MondayTarget.Site to "status", MondayTarget.Days to "text1", MondayTarget.Hours to "text2",
            MondayTarget.Availability to "text3", MondayTarget.Notes to "notes",
        ),
        department = "Mantenimiento",
        jobTitle = "Técnico",
    )

    private fun emeraldItem(id: String, name: String, site: String, days: String, hours: String, availability: String = "", notes: String = "") = MondayItem(
        id, name, "Equipo",
        listOf(
            cell("name", "Técnico", "name", name),
            cell("status", "Propiedad", "status", site),
            cell("text1", "Días de trabajo", "text", days),
            cell("text2", "Horario", "text", hours),
            cell("text3", "Disponibilidad", "text", availability),
            cell("notes", "Notas", "long_text", notes),
        ),
    )

    @Test
    fun suggestsTheEmeraldBoardColumnsAndDepartment() {
        val suggested = EmpleadosMondayMapping.suggest(emeraldColumns, "Horario del Equipo de Mantenimiento")
        assertEquals(
            mapOf(
                MondayTarget.Site to "status", MondayTarget.Days to "text1", MondayTarget.Hours to "text2",
                MondayTarget.Availability to "text3", MondayTarget.Notes to "notes",
            ),
            suggested.columns,
        )
        // Sin columna de departamento: sale del nombre del tablero. Sin columna de puesto, la columna "Técnico" da el puesto.
        assertEquals("Mantenimiento", suggested.department)
        assertEquals("Técnico", suggested.jobTitle)
    }

    @Test
    fun suggestsColumnsInEnglishByTitleAndType() {
        val columns = listOf(
            MondayColumn("name", "Name", "name"),
            MondayColumn("loc", "Location", "dropdown"),
            MondayColumn("role", "Role", "text"),
            MondayColumn("dept", "Department", "status"),
            MondayColumn("days", "Work days", "text"),
            MondayColumn("hours", "Hours", "text"),
            MondayColumn("avail", "Availability", "text"),
            MondayColumn("ph", "Mobile", "phone"),
            MondayColumn("em", "Contact", "email"),
            MondayColumn("n", "Notes", "text"),
        )
        val suggested = EmpleadosMondayMapping.suggest(columns, "Maintenance Team")
        assertEquals("loc", suggested.columns[MondayTarget.Site])
        assertEquals("role", suggested.columns[MondayTarget.JobTitle])
        assertEquals("dept", suggested.columns[MondayTarget.Department])
        assertEquals("days", suggested.columns[MondayTarget.Days])
        assertEquals("hours", suggested.columns[MondayTarget.Hours])
        assertEquals("avail", suggested.columns[MondayTarget.Availability])
        assertEquals("ph", suggested.columns[MondayTarget.Phone])
        assertEquals("em", suggested.columns[MondayTarget.Email])
        assertEquals("n", suggested.columns[MondayTarget.Notes])
        // Con columna de departamento y de puesto no se sugieren valores para todos.
        assertEquals("", suggested.department)
        assertEquals("", suggested.jobTitle)
        // Una columna sirve para un solo dato.
        assertEquals(suggested.columns.values.size, suggested.columns.values.toSet().size)
    }

    @Test
    fun theDepartmentComesFromTheBoardName() {
        assertEquals("Mantenimiento", EmpleadosMondayMapping.departmentFromBoardName("Horario del Equipo de Mantenimiento"))
        assertEquals("Limpieza", EmpleadosMondayMapping.departmentFromBoardName("Equipo de limpieza"))
        assertEquals("Maintenance", EmpleadosMondayMapping.departmentFromBoardName("Maintenance Team"))
        assertEquals("", EmpleadosMondayMapping.departmentFromBoardName("Tareas de enero"))
        assertEquals("", EmpleadosMondayMapping.departmentFromBoardName(""))
    }

    @Test
    fun convertsAnEmeraldRowToAnEmployeeWithWeeklySlots() {
        val converted = EmpleadosMondayMapping.toEmployee(
            emeraldItem("101", " Carlos Pérez ", "La Jolla", "Lunes a Viernes", "8:00-17:00", "Llamadas urgentes", "Tiene herramientas propias"),
            emeraldMapping,
        )
        val employee = converted.employee
        assertEquals("monday:101", employee.key)
        assertEquals("Carlos Pérez", employee.fullName)
        assertEquals("Técnico", employee.jobTitle)
        assertEquals("Mantenimiento", employee.department)
        assertEquals("La Jolla", employee.site)
        assertEquals("Llamadas urgentes", employee.availability)
        assertEquals("Tiene herramientas propias", employee.notes)
        assertEquals((1..5).map { ImportedSlot(it, LocalTime.of(8, 0), LocalTime.of(17, 0)) }, employee.weekly)
        assertTrue(employee.active)
        assertEquals("", converted.problem)
    }

    @Test
    fun anUnderstoodScheduleNeverLeavesProblemsOrNotes() {
        val converted = EmpleadosMondayMapping.toEmployee(emeraldItem("102", "Ana", "Magrock", "Lun-Vie", "08:00 – 16:00"), emeraldMapping)
        assertEquals("", converted.problem)
        assertEquals("", converted.employee.notes)
        assertEquals(5, converted.employee.weekly.size)
    }

    @Test
    fun aScheduleThatIsNotUnderstoodGoesToNotesAndBecomesAProblem() {
        val converted = EmpleadosMondayMapping.toEmployee(
            emeraldItem("103", "Luis", "Escape", "Segun la semana", "Rotativo", notes = "Prefiere mananas"),
            emeraldMapping,
        )
        assertTrue(converted.employee.weekly.isEmpty())
        assertEquals("Prefiere mananas\nHorario en Monday: Segun la semana · Rotativo", converted.employee.notes)
        assertTrue(converted.problem.startsWith("Luis: no se entendio el horario"))
    }

    @Test
    fun daysWithoutHoursAreNotesAndAProblemToo() {
        val converted = EmpleadosMondayMapping.toEmployee(emeraldItem("104", "Eva", "Escape", "Lunes a Viernes", ""), emeraldMapping)
        assertTrue(converted.employee.weekly.isEmpty())
        assertTrue("Horario en Monday: Lunes a Viernes · -" in converted.employee.notes)
        assertTrue(converted.problem.isNotEmpty())
    }

    @Test
    fun noScheduleAtAllLeavesTheWeeklyEmptyWithoutProblems() {
        val converted = EmpleadosMondayMapping.toEmployee(emeraldItem("105", "Marta", "Magrock", "", ""), emeraldMapping)
        assertTrue(converted.employee.weekly.isEmpty())
        assertEquals("", converted.problem)
        assertEquals("", converted.employee.notes)
    }

    @Test
    fun severalSitesKeepTheFirstAndNoteTheRest() {
        val converted = EmpleadosMondayMapping.toEmployee(emeraldItem("106", "Raul", "Magrock, La Jolla", "", ""), emeraldMapping)
        assertEquals("Magrock", converted.employee.site)
        assertEquals("Otras sedes en Monday: La Jolla", converted.employee.notes)
    }

    @Test
    fun unmappedColumnsWithValueGoToNotesAndEmptyDepartmentFallsBackToTheDefault() {
        val item = MondayItem(
            "107", "Sofia", "Equipo",
            listOf(cell("name", "Técnico", "name", "Sofia"), cell("extra", "Vehículo", "text", "Camioneta 3"), cell("vacia", "Otra", "text", "")),
        )
        val employee = EmpleadosMondayMapping.toEmployee(item, MondayMapping(department = "Mantenimiento")).employee
        assertEquals("Vehículo: Camioneta 3", employee.notes)
        assertEquals("Mantenimiento", employee.department)
        assertNull(employee.site)
    }

    @Test
    fun theMappedNameColumnWinsOverTheItemName() {
        val item = MondayItem("108", "Elemento", "", listOf(cell("n", "Empleado", "text", "Juan Lopez"), cell("e", "Correo", "email", "JUAN@Ejemplo.com")))
        val employee = EmpleadosMondayMapping.toEmployee(item, MondayMapping(mapOf(MondayTarget.Name to "n", MondayTarget.Email to "e"))).employee
        assertEquals("Juan Lopez", employee.fullName)
        assertEquals("juan@ejemplo.com", employee.email)
    }

    @Test
    fun storedRowsSurviveTheRoundTrip() {
        val converted = EmpleadosMondayMapping.toEmployee(emeraldItem("109", "Pedro", "Magrock", "Lunes, Miércoles", "7:30-15:30"), emeraldMapping)
        val back = MondayRowDto.decode(MondayRowDto.encode(converted))
        assertEquals(converted.employee, back.toEmployee())
        assertEquals(converted.problem, back.problem)
    }

    @Test
    fun batchSummariesMergeWithoutRepeatingNames() {
        val a = MondaySummaryDto(2, listOf("Magrock", "La Jolla"), listOf("Mantenimiento"), 2, listOf("Ana"), listOf("p1"), 1)
        val b = MondaySummaryDto(1, listOf("La Jolla", "Escape"), listOf("Mantenimiento"), 0, listOf("Ana", "Eva"), emptyList(), 0)
        val merged = MondaySummaryDto.merge(listOf(a, b))
        assertEquals(3, merged.employees)
        assertEquals(listOf("Magrock", "La Jolla", "Escape"), merged.sites)
        assertEquals(listOf("Mantenimiento"), merged.departments)
        assertEquals(2, merged.withSchedule)
        assertEquals(listOf("Ana", "Eva"), merged.linkedUsers)
        assertEquals(1, merged.problemCount)
    }

    @Test
    fun callTimeoutsNeverExceedTheRunBudget() {
        val now = 1_000_000_000_000L
        assertEquals(30_000L, EmpleadosMondayClient.timeoutFor(30_000, null, now))
        assertEquals(30_000L, EmpleadosMondayClient.timeoutFor(30_000, now + 60_000L * 1_000_000L, now))
        assertEquals(12_000L, EmpleadosMondayClient.timeoutFor(30_000, now + 12_000L * 1_000_000L, now))
        assertEquals(1_000L, EmpleadosMondayClient.timeoutFor(30_000, now - 5L, now))
        assertTrue(EmpleadosMondayClient.hasTime(now + 5_000L * 1_000_000L, now))
        assertTrue(!EmpleadosMondayClient.hasTime(now + 4_999L * 1_000_000L, now))
    }

    private fun permissions(vararg granted: String, businessOwner: Boolean = false, serverOwner: Boolean = false) =
        ModulePermissions("acme", "token", businessOwner, granted.toSet(), null, serverOwner)

    @Test
    fun applyingNeedsImportAndManageOrAnOwner() {
        assertTrue(!EmpleadosMondayFlow.canApply(permissions()))
        assertTrue(!EmpleadosMondayFlow.canApply(permissions(EmpleadosImportPermission)))
        assertTrue(!EmpleadosMondayFlow.canApply(permissions(EmpleadosManagePermission)))
        assertTrue(EmpleadosMondayFlow.canApply(permissions(EmpleadosImportPermission, EmpleadosManagePermission)))
        assertTrue(EmpleadosMondayFlow.canApply(permissions(businessOwner = true)))
        assertTrue(EmpleadosMondayFlow.canApply(permissions(EmpleadosImportPermission, serverOwner = true)))
        val denied = assertFailsWith<EmpleadosMondayException> { EmpleadosMondayFlow.requireApply(permissions(EmpleadosImportPermission)) }
        assertTrue("empleados.manage" in denied.message.orEmpty())
    }

    @Test
    fun remoteErrorMessagesAreOneShortLine() {
        assertEquals("a b c", EmpleadosMondayClient.oneLine("a\nb\r\n\tc "))
        assertEquals(200, EmpleadosMondayClient.oneLine("x".repeat(500)).length)
    }

    @Test
    fun theReadLimitsAreBounded() {
        assertEquals(240, EmpleadosMondayRunner.MaxPages)
        assertEquals(5, EmpleadosMondayRunner.MaxRetries)
        assertTrue(EmpleadosMondayException("x", transient = true).transient)
        assertTrue(!EmpleadosMondayException("x").transient)
        assertTrue(EmpleadosMondayClient.isBoardId("123456"))
        assertTrue(!EmpleadosMondayClient.isBoardId("12; drop"))
    }

    @Test
    fun theManifestInCodeMatchesExtensionJson() {
        val text = repoFile("extensions/empleados_monday/extension.json").readText()
        val fromDisk = Json { ignoreUnknownKeys = true }.decodeFromString(ExtensionManifest.serializer(), text)
        assertEquals(fromDisk, EmpleadosMondayServerExtension.manifest)
        assertEquals(EmpleadosMondayId, EmpleadosMondayServerExtension.sourceId)
        assertEquals(listOf("extensions/empleados_monday/migrations/V001__settings.sql"), EmpleadosMondayServerExtension.migrationPaths)
        assertTrue(repoFile("extensions/empleados_monday/migrations/V001__settings.sql").isFile)
        // Solo tablas propias de la extension.
        assertTrue(EmpleadosMondayServerExtension.runtimeTables.all { it.startsWith("ext_empleados_monday__") })
    }

    @Test
    fun mcpToolsNeedTheImportPermissionAndFitTheQualifiedNameLimit() {
        val tools = EmpleadosMondayMcpTools.tools
        assertEquals(listOf("monday_list_boards", "monday_read_board", "monday_import_status", "monday_apply"), tools.map { it.name })
        assertTrue(tools.all { it.permission == EmpleadosImportPermission })
        assertTrue(tools.all { "empleados_${it.name}".length <= 64 })
        assertEquals(listOf(true, false, true, false), tools.map { it.readOnly })
    }

    // El directorio de trabajo de las pruebas es el del proyecto Gradle: se sube hasta encontrar el archivo.
    private fun repoFile(relative: String): File {
        var dir: File? = File(System.getProperty("user.dir")).absoluteFile
        while (dir != null) {
            val candidate = File(dir, relative)
            if (candidate.isFile) return candidate
            dir = dir.parentFile
        }
        error("No se encontro $relative")
    }
}
