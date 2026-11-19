package com.ideasdeveloper.idc.extensions.tareas_monday

import com.ideasdeveloper.idc.modules.tareas.ImportedCheck
import com.ideasdeveloper.idc.modules.tareas.TaskPriority
import com.ideasdeveloper.idc.modules.tareas.TaskStatus
import com.ideasdeveloper.idc.server.extensions.ExtensionManifest
import kotlinx.serialization.json.Json
import java.io.File
import java.time.Instant
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TareasMondayMappingTest {
    private fun cell(id: String, title: String, type: String, text: String, value: String = "") = MondayCell(id, title, type, text, value)

    @Test
    fun statusWordsInSpanishAndEnglishIgnoreCaseAndAccents() {
        val expected = mapOf(
            "Done" to TaskStatus.Done, "LISTO" to TaskStatus.Done, "Hecho" to TaskStatus.Done, "Completed" to TaskStatus.Done,
            "Working on it" to TaskStatus.InProgress, "In progress" to TaskStatus.InProgress, "En curso" to TaskStatus.InProgress, "Stuck" to TaskStatus.InProgress,
            "Waiting for review" to TaskStatus.Review, "Review" to TaskStatus.Review, "Revisión" to TaskStatus.Review,
            "Cancelled" to TaskStatus.Cancelled, "Cancelada" to TaskStatus.Cancelled,
            "" to TaskStatus.Pending, "Pending" to TaskStatus.Pending, "Not started" to TaskStatus.Pending, "Algo raro" to TaskStatus.Pending,
        )
        expected.forEach { (label, status) -> assertEquals(status, TareasMondayMapping.statusOf(label), "estado de '$label'") }
        assertEquals(TaskStatus.Pending, TareasMondayMapping.statusOf(null))
    }

    @Test
    fun priorityWordsInSpanishAndEnglishIgnoreCaseAndAccents() {
        val expected = mapOf(
            "Critical" to TaskPriority.Urgent, "Urgent" to TaskPriority.Urgent, "URGENT" to TaskPriority.Urgent, "Crítica" to TaskPriority.Urgent,
            "High" to TaskPriority.High, "Alta" to TaskPriority.High,
            "Low" to TaskPriority.Low, "Baja" to TaskPriority.Low,
            "Medium" to TaskPriority.Medium, "Media" to TaskPriority.Medium, "" to TaskPriority.Medium,
            "Low, High" to TaskPriority.High,
        )
        expected.forEach { (label, priority) -> assertEquals(priority, TareasMondayMapping.priorityOf(label), "prioridad de '$label'") }
    }

    @Test
    fun suggestsColumnsByTitleAndTypeInEnglish() {
        val columns = listOf(
            MondayColumn("name", "Name", "name"),
            MondayColumn("person", "Owner", "people"),
            MondayColumn("people2", "Reviewer", "people"),
            MondayColumn("status", "Status", "status"),
            MondayColumn("prio", "Priority", "status"),
            MondayColumn("date4", "Due date", "date"),
            MondayColumn("text", "Notes", "long_text"),
            MondayColumn("budget", "Budget", "numbers"),
        )
        val mapping = TareasMondayMapping.suggest(columns).columns
        assertEquals("person", mapping[MondayTarget.Assignees])
        assertEquals("people2", mapping[MondayTarget.Reviewer])
        assertEquals("prio", mapping[MondayTarget.Priority])
        assertEquals("status", mapping[MondayTarget.Status])
        assertEquals("date4", mapping[MondayTarget.Due])
        assertEquals("text", mapping[MondayTarget.Detail])
        assertNull(mapping[MondayTarget.Collaborators])
        // Una columna sirve para un solo dato.
        assertEquals(mapping.values.size, mapping.values.toSet().size)
    }

    @Test
    fun suggestsColumnsByTitleAndTypeInSpanish() {
        val columns = listOf(
            MondayColumn("estado", "Estado", "status"),
            MondayColumn("prioridad", "Prioridad", "status"),
            MondayColumn("resp", "Responsable", "people"),
            MondayColumn("colab", "Colaborador", "people"),
            MondayColumn("fecha", "Fecha límite", "date"),
            MondayColumn("detalle", "Detalle", "text"),
        )
        val mapping = TareasMondayMapping.suggest(columns).columns
        assertEquals(
            mapOf(
                MondayTarget.Collaborators to "colab", MondayTarget.Assignees to "resp", MondayTarget.Priority to "prioridad",
                MondayTarget.Status to "estado", MondayTarget.Due to "fecha", MondayTarget.Detail to "detalle",
            ),
            mapping,
        )
    }

    @Test
    fun convertsAnItemWithSubitemsUpdatesPeopleAndExtraColumns() {
        val people = mapOf("1" to MondayPerson("1", "Ana", "ana@x.com"), "2" to MondayPerson("2", "Bruno", ""))
        val item = MondayItem(
            id = "555",
            name = "  Preparar informe ",
            group = "Backlog",
            cells = listOf(
                cell("status", "Status", "status", "Working on it"),
                cell("prio", "Priority", "status", "High"),
                cell("due", "Due", "timeline", "2026-01-01 - 2026-02-15", """{"from":"2026-01-01","to":"2026-02-15"}"""),
                cell("owner", "Owner", "people", "Ana, Bruno", """{"personsAndTeams":[{"id":1,"kind":"person"},{"id":2,"kind":"person"}]}"""),
                cell("col", "Collaborator", "people", "Carla", """{"personsAndTeams":[{"id":3,"kind":"person"}]}"""),
                cell("budget", "Budget", "numbers", "500"),
                cell("empty", "Empty", "text", ""),
                cell("files", "Files", "file", "a.pdf", """{"files":[{"fileType":"ASSET","assetId":77,"name":"a.pdf"},{"fileType":"GOOGLE_DRIVE","name":"x"}]}"""),
            ),
            subitems = listOf(
                MondaySubitem("10", "Paso 1", listOf(cell("s", "Status", "status", "Done"))),
                MondaySubitem("11", "Paso 2", listOf(cell("s", "Status", "status", "Working on it"))),
                MondaySubitem("12", "  ", emptyList()),
            ),
            updates = listOf(
                MondayUpdate(
                    "100", "Hola", "2026-03-05T10:20:30Z", "Ana", "ana@x.com", listOf(MondayAsset("88", "foto.jpg")),
                    replies = listOf(MondayUpdate("101", "Respuesta", "2026-03-06T00:00:00Z", "Bruno")),
                ),
                MondayUpdate("102", "   ", "2026-03-07T00:00:00Z", "Ana"),
            ),
        )
        val mapping = MondayMapping(
            mapOf(
                MondayTarget.Status to "status", MondayTarget.Priority to "prio", MondayTarget.Due to "due",
                MondayTarget.Assignees to "owner", MondayTarget.Collaborators to "col",
            ),
        )
        val converted = TareasMondayMapping.toTask(item, mapping, people)
        val task = converted.task
        assertEquals("555", task.key)
        assertEquals("Preparar informe", task.title)
        assertEquals("Backlog", task.groupName)
        assertEquals(TaskStatus.InProgress, task.status)
        assertEquals(TaskPriority.High, task.priority)
        assertEquals(LocalDate.of(2026, 2, 15), task.dueDate)
        // Ana tiene correo; Bruno no (queda por nombre); Carla no vino de users(ids): su nombre sale del texto de la celda.
        assertEquals(listOf("ana@x.com"), task.assigneeEmails)
        assertEquals(listOf("Bruno", "Carla"), task.assigneeNames)
        // Solo la columna con valor y sin destino va al detalle (los archivos y las vacias no).
        assertEquals("Budget: 500", task.detail)
        assertEquals(
            listOf(ImportedCheck("monday:sub:10", "Paso 1", true), ImportedCheck("monday:sub:11", "Paso 2", false)),
            task.checklist,
        )
        assertEquals(listOf("monday:upd:100", "monday:upd:101"), task.comments.map { it.key })
        assertEquals("Hola", task.comments[0].body)
        assertEquals("Ana", task.comments[0].author)
        assertEquals(Instant.parse("2026-03-05T10:20:30Z"), task.comments[0].at)
        assertEquals("Respuesta", task.comments[1].body)
        assertEquals(listOf("77", "88"), converted.files.map { it.asset })
        assertEquals("Files", converted.files[0].column)
        assertEquals("100", converted.files[1].update)
    }

    @Test
    fun anItemWithoutMappingKeepsEveryValueInTheDetail() {
        val item = MondayItem("1", "Tarea", "", listOf(cell("a", "Cliente", "text", "ACME"), cell("b", "Monto", "numbers", "10")))
        val task = TareasMondayMapping.toTask(item, MondayMapping()).task
        assertEquals("Cliente: ACME\nMonto: 10", task.detail)
        assertEquals(TaskStatus.Pending, task.status)
        assertEquals(TaskPriority.Medium, task.priority)
        assertNull(task.groupName)
        assertNull(task.dueDate)
    }

    @Test
    fun peopleWithoutValidJsonFallBackToNamesAndTeamsAreNeverEmails() {
        val plain = cell("p", "Owner", "people", "Ana, Luis")
        assertEquals(emptyList<String>() to listOf("Ana", "Luis"), TareasMondayMapping.peopleOf(plain, emptyMap()))
        val team = cell("t", "Owner", "people", "Equipo", """{"personsAndTeams":[{"id":9,"kind":"team"}]}""")
        // Aunque el id del equipo coincida con una persona conocida, un equipo no es un correo.
        val known = mapOf("9" to MondayPerson("9", "Equipo", "equipo@x.com"))
        assertEquals(emptyList<String>() to listOf("Equipo"), TareasMondayMapping.peopleOf(team, known))
        assertEquals(setOf("1"), TareasMondayMapping.personIds(listOf(cell("o", "Owner", "people", "", """{"personsAndTeams":[{"id":1,"kind":"person"},{"id":2,"kind":"team"}]}"""))))
    }

    @Test
    fun storedRowsAndFilesSurviveTheRoundTrip() {
        val item = MondayItem(
            "9", "Con todo", "G", listOf(cell("s", "Status", "status", "Done")),
            subitems = listOf(MondaySubitem("1", "Paso", emptyList())),
            updates = listOf(MondayUpdate("2", "Texto", "2026-03-05T10:20:30Z", "Ana")),
        )
        val converted = TareasMondayMapping.toTask(item, MondayMapping(mapOf(MondayTarget.Status to "s")))
        assertEquals(converted.task, MondayRowDto.decode(MondayRowDto.encode(converted.task)))
        val files = listOf(MondayFile("5", "a.pdf", column = "Files"), MondayFile("6", "b.jpg", update = "2"), MondayFile("x", "malo"))
        assertEquals(files.take(2), TareasMondayMapping.decodeFiles(TareasMondayMapping.encodeFiles(files)))
        assertEquals(emptyList(), TareasMondayMapping.decodeFiles("no es json"))
    }

    @Test
    fun filesOnlyComeFromMondayHosts() {
        assertTrue(TareasMondayClient.isAllowedFileHost("files.monday.com"))
        assertTrue(TareasMondayClient.isAllowedFileHost("FILES.Monday.com."))
        assertTrue(TareasMondayClient.isAllowedFileHost("files-monday-com.s3.amazonaws.com"))
        assertTrue(TareasMondayClient.isAllowedFileHost("files-monday-com-eu.s3.eu-west-1.amazonaws.com"))
        listOf("monday.com.evil.com", "evilmonday.com", "monday.com", "bucket.s3.amazonaws.com", "files-monday-com.evil.com", "", "169.254.169.254")
            .forEach { assertTrue(!TareasMondayClient.isAllowedFileHost(it), "no debe permitirse '$it'") }
        assertTrue(!TareasMondayClient.isAllowedFileHost(null))
    }

    @Test
    fun callTimeoutsNeverExceedTheRunBudget() {
        val now = 1_000_000_000_000L
        // Sin presupuesto, el tiempo normal; con presupuesto, el menor entre ese y lo que queda (minimo 1 s).
        assertEquals(30_000L, TareasMondayClient.timeoutFor(30_000, null, now))
        assertEquals(30_000L, TareasMondayClient.timeoutFor(30_000, now + 60_000L * 1_000_000L, now))
        assertEquals(12_000L, TareasMondayClient.timeoutFor(30_000, now + 12_000L * 1_000_000L, now))
        assertEquals(1_000L, TareasMondayClient.timeoutFor(30_000, now - 5L, now))
        // No se empieza una llamada con menos de 5 s por delante.
        assertTrue(TareasMondayClient.hasTime(now + 5_000L * 1_000_000L, now))
        assertTrue(!TareasMondayClient.hasTime(now + 4_999L * 1_000_000L, now))
    }

    @Test
    fun batchSummariesMergeWithoutRepeatingNames() {
        val a = MondaySummaryDto(2, listOf("G1", "G2"), 3, 4, 0, listOf("Ana"), listOf("Bruno"), listOf("p1"), 1)
        val b = MondaySummaryDto(1, listOf("G2", "G3"), 1, 0, 0, listOf("Ana", "Eva"), listOf("Bruno"), emptyList(), 0)
        val merged = MondaySummaryDto.merge(listOf(a, b))
        assertEquals(3, merged.tasks)
        assertEquals(listOf("G1", "G2", "G3"), merged.groups)
        assertEquals(4, merged.checklistItems)
        assertEquals(4, merged.comments)
        assertEquals(listOf("Ana", "Eva"), merged.matchedPeople)
        assertEquals(listOf("Bruno"), merged.unmatchedPeople)
        assertEquals(1, merged.problemCount)
        assertEquals(a.toSummary().tasks, 2)
    }

    @Test
    fun theReadLimitsAreBounded() {
        assertEquals(2400, TareasMondayRunner.MaxPages)
        assertEquals(5, TareasMondayRunner.MaxRetries)
        assertTrue(TareasMondayException("x", transient = true).transient)
        assertTrue(!TareasMondayException("x").transient)
    }

    @Test
    fun theManifestInCodeMatchesExtensionJson() {
        val text = repoFile("extensions/tareas_monday/extension.json").readText()
        val fromDisk = Json { ignoreUnknownKeys = true }.decodeFromString(ExtensionManifest.serializer(), text)
        assertEquals(fromDisk, TareasMondayServerExtension.manifest)
        assertEquals(TareasMondayId, TareasMondayServerExtension.sourceId)
        assertEquals(listOf("extensions/tareas_monday/migrations/V001__settings.sql"), TareasMondayServerExtension.migrationPaths)
        assertTrue(repoFile("extensions/tareas_monday/migrations/V001__settings.sql").isFile)
        // Solo tablas propias de la extension.
        assertTrue(TareasMondayServerExtension.runtimeTables.all { it.startsWith("ext_tareas_monday__") })
    }

    @Test
    fun mcpToolsNeedTheImportPermissionAndFitTheQualifiedNameLimit() {
        val tools = TareasMondayMcpTools.tools
        assertEquals(listOf("monday_list_boards", "monday_read_board", "monday_import_status", "monday_apply"), tools.map { it.name })
        assertTrue(tools.all { it.permission == TareasImportPermission })
        assertTrue(tools.all { "tareas_${it.name}".length <= 64 })
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
