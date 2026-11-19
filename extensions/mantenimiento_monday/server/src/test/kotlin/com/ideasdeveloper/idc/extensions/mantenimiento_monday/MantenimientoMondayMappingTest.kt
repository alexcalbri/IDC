package com.ideasdeveloper.idc.extensions.mantenimiento_monday

import com.ideasdeveloper.idc.modules.mantenimiento.OrderKind
import com.ideasdeveloper.idc.modules.mantenimiento.OrderPriority
import com.ideasdeveloper.idc.modules.mantenimiento.OrderStatus
import com.ideasdeveloper.idc.modules.mantenimiento.PlanFrequency
import com.ideasdeveloper.idc.server.extensions.ExtensionManifest
import kotlinx.serialization.json.Json
import java.io.File
import java.math.BigDecimal
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MantenimientoMondayMappingTest {
    private fun cell(id: String, title: String, type: String, text: String, value: String = "") = MondayCell(id, title, type, text, value)

    @Test
    fun statusesOfTheEmeraldProjectBoardAndTheirEnglishEquivalents() {
        val expected = mapOf(
            "Pendiente de aprobación" to OrderStatus.Requested, "En cotización" to OrderStatus.Quoting,
            "Aprobado - En progreso" to OrderStatus.InProgress, "Aprobado" to OrderStatus.Open,
            "Completado" to OrderStatus.Done, "Rechazado" to OrderStatus.Rejected,
            "Working on it" to OrderStatus.InProgress, "Done" to OrderStatus.Done, "Listo" to OrderStatus.Done,
            "Stuck" to OrderStatus.OnHold, "Detenido" to OrderStatus.OnHold, "Cancelado" to OrderStatus.Cancelled,
            "Pending approval" to OrderStatus.Requested, "Approved" to OrderStatus.Open,
        )
        expected.forEach { (label, status) -> assertEquals(status, MantenimientoMondayMapping.orderStatusOf(label), "estado de '$label'") }
        assertNull(MantenimientoMondayMapping.orderStatusOf(""))
        assertNull(MantenimientoMondayMapping.orderStatusOf("Algo raro"))
        assertNull(MantenimientoMondayMapping.orderStatusOf(null))
    }

    @Test
    fun onlyProjectsKeepTheApprovalStatuses() {
        assertEquals(OrderStatus.Requested, MantenimientoMondayMapping.statusForKind(OrderKind.Project, OrderStatus.Requested))
        assertEquals(OrderStatus.Rejected, MantenimientoMondayMapping.statusForKind(OrderKind.Project, OrderStatus.Rejected))
        assertEquals(OrderStatus.Open, MantenimientoMondayMapping.statusForKind(OrderKind.Corrective, OrderStatus.Requested))
        assertEquals(OrderStatus.Open, MantenimientoMondayMapping.statusForKind(OrderKind.Corrective, OrderStatus.Quoting))
        assertEquals(OrderStatus.Cancelled, MantenimientoMondayMapping.statusForKind(OrderKind.Corrective, OrderStatus.Rejected))
        assertEquals(OrderStatus.Done, MantenimientoMondayMapping.statusForKind(OrderKind.Corrective, OrderStatus.Done))
    }

    @Test
    fun frequencyComesFromTheGroupOfTheLaJollaBoard() {
        assertEquals(PlanFrequency.Yearly to null, MantenimientoMondayMapping.frequencyOf("Anuales"))
        assertEquals(PlanFrequency.Quarterly to null, MantenimientoMondayMapping.frequencyOf("Trimestrales"))
        assertEquals(PlanFrequency.Monthly to null, MantenimientoMondayMapping.frequencyOf("Mensuales"))
        assertEquals(PlanFrequency.Yearly to null, MantenimientoMondayMapping.frequencyOf("Yearly"))
        assertEquals(PlanFrequency.Quarterly to null, MantenimientoMondayMapping.frequencyOf("Quarterly"))
        assertEquals(PlanFrequency.Monthly to null, MantenimientoMondayMapping.frequencyOf("Monthly"))
        assertEquals(PlanFrequency.EveryNDays to 7, MantenimientoMondayMapping.frequencyOf("Semanal"))
        assertEquals(PlanFrequency.EveryNDays to 7, MantenimientoMondayMapping.frequencyOf("Weekly"))
        assertEquals(PlanFrequency.EveryNDays to 45, MantenimientoMondayMapping.frequencyOf("Cada 45 días"))
        assertEquals(PlanFrequency.EveryNDays to 10, MantenimientoMondayMapping.frequencyOf("Every 10 days"))
        assertNull(MantenimientoMondayMapping.frequencyOf("Pendientes"))
        assertNull(MantenimientoMondayMapping.frequencyOf("Cada 0 días"))
        assertNull(MantenimientoMondayMapping.frequencyOf(""))
    }

    @Test
    fun quotesParseWithCommaOrPointAsDecimalOrThousands() {
        val expected = mapOf(
            "1500" to "1500.00", "1,234.50" to "1234.50", "1.234,50" to "1234.50", "\$2,500" to "2500.00", "USD 2.500" to "2500.00",
            "1234,5" to "1234.50", "1234.5" to "1234.50", "1 500" to "1500.00", "0.500" to "0.50", "1,5" to "1.50",
            "1,234,567" to "1234567.00", "1.234.567" to "1234567.00", "2500.999" to "2501.00",
        )
        expected.forEach { (text, amount) -> assertEquals(BigDecimal(amount), MantenimientoMondayMapping.amountOf(text), "monto de '$text'") }
        listOf("", "abc", "-50", null, "9999999999999").forEach { assertNull(MantenimientoMondayMapping.amountOf(it), "'$it' no es un monto") }
    }

    @Test
    fun suggestsTheColumnsOfTheLaJollaMaintenanceBoard() {
        val columns = listOf(
            MondayColumn("name", "Name", "name"),
            MondayColumn("estado", "Estado", "status"),
            MondayColumn("asig", "Asignado a", "people"),
            MondayColumn("prio", "Prioridad", "status"),
            MondayColumn("fecha", "Fecha límite", "date"),
            MondayColumn("notas", "Notas", "long_text"),
        )
        assertEquals(
            mapOf(
                MondayTarget.Assignees to "asig", MondayTarget.Priority to "prio", MondayTarget.Status to "estado",
                MondayTarget.Due to "fecha", MondayTarget.Description to "notas",
            ),
            MantenimientoMondayMapping.suggest(columns).columns,
        )
    }

    @Test
    fun suggestsTheColumnsOfTheEmeraldProjectBoard() {
        val columns = listOf(
            MondayColumn("name", "Name", "name"),
            MondayColumn("depto", "Departamento", "dropdown"),
            MondayColumn("prop", "Propiedad", "status"),
            MondayColumn("desc", "Descripción", "long_text"),
            MondayColumn("monto", "Cotización en dólares", "numbers"),
            MondayColumn("cotpor", "Cotizado por", "people"),
            MondayColumn("prio", "Prioridad", "status"),
            MondayColumn("aprob", "Estado de aprobación", "status"),
        )
        val mapping = MantenimientoMondayMapping.suggest(columns).columns
        assertEquals("depto", mapping[MondayTarget.Department])
        assertEquals("prop", mapping[MondayTarget.Property])
        assertEquals("desc", mapping[MondayTarget.Description])
        assertEquals("monto", mapping[MondayTarget.Quote])
        assertEquals("cotpor", mapping[MondayTarget.QuotedBy])
        assertEquals("prio", mapping[MondayTarget.Priority])
        assertEquals("aprob", mapping[MondayTarget.Status])
        // "Cotizado por" no se convierte en el responsable.
        assertNull(mapping[MondayTarget.Assignees])
        assertEquals(mapping.values.size, mapping.values.toSet().size)
    }

    @Test
    fun suggestsTheDestinationFromTheBoardName() {
        assertEquals(MondayDestination.Orders to MondayDestination.KindProject, MantenimientoMondayMapping.suggestTarget("Solicitudes de Proyectos - Mantenimiento"))
        assertEquals(MondayDestination.Plans to MondayDestination.KindCorrective, MantenimientoMondayMapping.suggestTarget("Tareas de Mantenimiento"))
        assertEquals(MondayDestination.Plans to MondayDestination.KindCorrective, MantenimientoMondayMapping.suggestTarget("Maintenance tasks"))
        assertEquals(MondayDestination.Orders to MondayDestination.KindCorrective, MantenimientoMondayMapping.suggestTarget("Otra cosa"))
    }

    @Test
    fun convertsAPlanOfTheLaJollaBoard() {
        val people = mapOf("1" to MondayPerson("1", "Ana", "Ana@X.com"), "2" to MondayPerson("2", "Bruno", ""))
        val item = MondayItem(
            id = "700",
            name = "  Revisar caldera ",
            group = "Trimestrales",
            cells = listOf(
                cell("estado", "Estado", "status", "Working on it"),
                cell("asig", "Asignado a", "people", "Ana, Bruno", """{"personsAndTeams":[{"id":1,"kind":"person"},{"id":2,"kind":"person"}]}"""),
                cell("prio", "Prioridad", "status", "Alta"),
                cell("fecha", "Fecha límite", "date", "2026-03-15", """{"date":"2026-03-15"}"""),
                cell("notas", "Notas", "long_text", "Cambiar filtros"),
                cell("zona", "Zona", "text", "Sótano"),
            ),
            subitems = listOf(
                MondaySubitem("10", "Purgar", listOf(cell("s", "Status", "status", "Done"))),
                MondaySubitem("11", "Medir presión", listOf(cell("s", "Status", "status", "Working on it"))),
                MondaySubitem("12", "  ", emptyList()),
            ),
            updates = listOf(MondayUpdate("100", "Se pidió el repuesto", "2026-03-05T10:20:30Z", "Ana")),
        )
        val mapping = MondayMapping(
            mapOf(
                MondayTarget.Status to "estado", MondayTarget.Assignees to "asig", MondayTarget.Priority to "prio",
                MondayTarget.Due to "fecha", MondayTarget.Description to "notas",
            ),
        )
        val converted = MantenimientoMondayMapping.toPlan(item, mapping, people)
        val plan = assertNotNull(converted.plan)
        assertNull(converted.order)
        assertTrue(converted.problems.isEmpty())
        assertEquals("monday:700", plan.key)
        assertEquals("Revisar caldera", plan.title)
        assertEquals(PlanFrequency.Quarterly, plan.frequency)
        assertNull(plan.intervalDays)
        assertEquals(LocalDate.of(2026, 3, 15), plan.nextDue)
        assertEquals(OrderPriority.High, plan.priority)
        // Ana tiene correo (primero); Bruno no: queda anotado en la descripcion.
        assertEquals("ana@x.com", plan.assigneeEmail)
        assertNull(plan.assigneeName)
        assertEquals(
            "Cambiar filtros\nEstado en Monday: Working on it\nZona: Sótano\nOtros responsables en Monday: Bruno\n\n" +
                "Actualizaciones de Monday:\n[Ana · 2026-03-05] Se pidió el repuesto",
            plan.description,
        )
        assertEquals(listOf("monday:sub:10" to true, "monday:sub:11" to false), plan.checklist.map { it.key to it.done })
        assertTrue(converted.files.isEmpty())
    }

    @Test
    fun aPlanInAnUnknownGroupDefaultsToMonthlyAndRecordsTheProblem() {
        val converted = MantenimientoMondayMapping.toPlan(MondayItem("1", "Algo", "Pendientes", emptyList()), MondayMapping())
        assertEquals(PlanFrequency.Monthly, converted.plan?.frequency)
        assertEquals(1, converted.problems.size)
        assertTrue("Pendientes" in converted.problems[0])
        val daily = MantenimientoMondayMapping.toPlan(MondayItem("2", "Algo", "Cada 30 días", emptyList()), MondayMapping())
        assertEquals(PlanFrequency.EveryNDays, daily.plan?.frequency)
        assertEquals(30, daily.plan?.intervalDays)
    }

    @Test
    fun convertsAProjectOfTheEmeraldBoardWithTheQuote() {
        val people = mapOf("5" to MondayPerson("5", "Carla Pérez", "carla@x.com"))
        val item = MondayItem(
            id = "900",
            name = "Remodelar lobby",
            group = "En cotización",
            cells = listOf(
                cell("depto", "Departamento", "dropdown", "Ama de llaves"),
                cell("prop", "Propiedad", "status", "Emerald"),
                cell("desc", "Descripción", "long_text", "Cambiar pisos"),
                cell("monto", "Cotización en dólares", "numbers", "12,500.50"),
                cell("cotpor", "Cotizado por", "people", "Carla Pérez", """{"personsAndTeams":[{"id":5,"kind":"person"}]}"""),
                cell("prio", "Prioridad", "status", "Urgente"),
                cell("aprob", "Estado de aprobación", "status", "Pendiente de aprobación"),
                cell("extra", "Observación", "text", "Con vista"),
                cell("files", "Archivos", "file", "plano.pdf", """{"files":[{"fileType":"ASSET","assetId":77,"name":"plano.pdf"}]}"""),
            ),
            updates = listOf(
                MondayUpdate("1", "Revisado", "2026-03-05T10:20:30Z", "Ana", "ana@x.com", listOf(MondayAsset("88", "foto.jpg")), listOf(MondayUpdate("2", "Gracias", "2026-03-06T00:00:00Z", "Bruno"))),
            ),
        )
        val mapping = MondayMapping(
            mapOf(
                MondayTarget.Department to "depto", MondayTarget.Property to "prop", MondayTarget.Description to "desc", MondayTarget.Quote to "monto",
                MondayTarget.QuotedBy to "cotpor", MondayTarget.Priority to "prio", MondayTarget.Status to "aprob",
            ),
        )
        val order = assertNotNull(MantenimientoMondayMapping.toOrder(item, mapping, OrderKind.Project, people).order)
        assertEquals("monday:900", order.key)
        assertEquals(OrderKind.Project, order.kind)
        // La columna de estado manda sobre el grupo.
        assertEquals(OrderStatus.Requested, order.status)
        assertEquals("Ama de llaves", order.department)
        assertEquals("Emerald", order.propertyName)
        assertEquals(OrderPriority.Urgent, order.priority)
        assertEquals(BigDecimal("12500.50"), order.quoteAmount)
        assertEquals("USD", order.quoteCurrency)
        assertEquals("Carla Pérez", order.quotedBy)
        assertEquals("Cambiar pisos\nGrupo en Monday: En cotización\nObservación: Con vista", order.description)
        assertEquals(listOf("monday:upd:1", "monday:upd:2"), order.notes.map { it.key })
        assertEquals(listOf("77", "88"), MantenimientoMondayMapping.toOrder(item, mapping, OrderKind.Project, people).files.map { it.asset })
    }

    @Test
    fun theGroupGivesTheStatusWhenThereIsNoStatusColumn() {
        val groups = mapOf(
            "Pendiente de aprobación" to OrderStatus.Requested, "En cotización" to OrderStatus.Quoting, "Aprobado - En progreso" to OrderStatus.InProgress,
            "Completado" to OrderStatus.Done, "Rechazado" to OrderStatus.Rejected,
        )
        groups.forEach { (group, status) ->
            val order = MantenimientoMondayMapping.toOrder(MondayItem("1", "X", group, emptyList()), MondayMapping(), OrderKind.Project).order
            assertEquals(status, order?.status, "grupo '$group'")
            // En una averia, pendiente y en cotizacion son abiertas y rechazada es cancelada.
            val corrective = MantenimientoMondayMapping.toOrder(MondayItem("1", "X", group, emptyList()), MondayMapping(), OrderKind.Corrective).order
            assertEquals(MantenimientoMondayMapping.statusForKind(OrderKind.Corrective, status), corrective?.status)
        }
        // Sin columna ni grupo conocido: proyecto nace pendiente, el resto abierta.
        assertEquals(OrderStatus.Requested, MantenimientoMondayMapping.toOrder(MondayItem("1", "X", "", emptyList()), MondayMapping(), OrderKind.Project).order?.status)
        assertEquals(OrderStatus.Open, MantenimientoMondayMapping.toOrder(MondayItem("1", "X", "", emptyList()), MondayMapping(), OrderKind.Corrective).order?.status)
    }

    @Test
    fun anUnreadableQuoteOrStatusIsRecordedAsAProblem() {
        val item = MondayItem("1", "X", "Completado", listOf(cell("m", "Monto", "numbers", "mucho"), cell("e", "Estado", "status", "Raro")))
        val mapping = MondayMapping(mapOf(MondayTarget.Quote to "m", MondayTarget.Status to "e"))
        val converted = MantenimientoMondayMapping.toOrder(item, mapping, OrderKind.Project)
        assertNull(converted.order?.quoteAmount)
        assertEquals(2, converted.problems.size)
        // El estado de la columna no se reconoce: se usa el del grupo.
        assertEquals(OrderStatus.Done, converted.order?.status)
    }

    @Test
    fun peopleWithoutEmailStayByNameAndTeamsAreNeverEmails() {
        val plain = cell("p", "Owner", "people", "Ana, Luis")
        assertEquals(emptyList<String>() to listOf("Ana", "Luis"), MantenimientoMondayMapping.peopleOf(plain, emptyMap()))
        val guest = cell("g", "Owner", "people", "Invitado", """{"personsAndTeams":[{"id":4,"kind":"person"}]}""")
        assertEquals(emptyList<String>() to listOf("Invitado"), MantenimientoMondayMapping.peopleOf(guest, mapOf("4" to MondayPerson("4", "", ""))))
        val team = cell("t", "Owner", "people", "Equipo", """{"personsAndTeams":[{"id":9,"kind":"team"}]}""")
        val known = mapOf("9" to MondayPerson("9", "Equipo", "equipo@x.com"))
        assertEquals(emptyList<String>() to listOf("Equipo"), MantenimientoMondayMapping.peopleOf(team, known))
        // Una persona sin correo se asigna por nombre y no por correo.
        val item = MondayItem("1", "X", "", listOf(guest))
        val order = MantenimientoMondayMapping.toOrder(item, MondayMapping(mapOf(MondayTarget.Assignees to "g")), OrderKind.Corrective).order
        assertNull(order?.assigneeEmail)
        assertEquals("Invitado", order?.assigneeName)
    }

    @Test
    fun storedRowsAndFilesSurviveTheRoundTrip() {
        val item = MondayItem(
            "9", "Con todo", "En cotización",
            listOf(cell("m", "Monto", "numbers", "1,500"), cell("p", "Prioridad", "status", "Baja")),
            subitems = listOf(MondaySubitem("1", "Paso", emptyList())),
            updates = listOf(MondayUpdate("2", "Texto", "2026-03-05T10:20:30Z", "Ana")),
        )
        val mapping = MondayMapping(mapOf(MondayTarget.Quote to "m", MondayTarget.Priority to "p"))
        val order = MantenimientoMondayMapping.toOrder(item, mapping, OrderKind.Project)
        assertEquals(order.order, MondayRowDto.decode(MondayRowDto.encode(order)).toOrder())
        val plan = MantenimientoMondayMapping.toPlan(MondayItem("3", "Plan", "Cada 20 días", emptyList()), MondayMapping())
        assertEquals(plan.plan, MondayRowDto.decode(MondayRowDto.encode(plan)).toPlan())
        val files = listOf(MondayFile("5", "a.pdf", column = "Files"), MondayFile("6", "b.jpg", update = "2"), MondayFile("x", "malo"))
        assertEquals(files.take(2), MantenimientoMondayMapping.decodeFiles(MantenimientoMondayMapping.encodeFiles(files)))
        assertEquals(emptyList(), MantenimientoMondayMapping.decodeFiles("no es json"))
    }

    @Test
    fun filesOnlyComeFromMondayHosts() {
        assertTrue(MantenimientoMondayClient.isAllowedFileHost("files.monday.com"))
        assertTrue(MantenimientoMondayClient.isAllowedFileHost("files-monday-com.s3.amazonaws.com"))
        assertTrue(MantenimientoMondayClient.isAllowedFileHost("FILES.Monday.com."))
        assertTrue(MantenimientoMondayClient.isAllowedFileHost("files-monday-com-eu.s3.eu-west-1.amazonaws.com"))
        listOf(
            "monday.com.evil.com", "evilmonday.com", "monday.com", "bucket.s3.amazonaws.com", "files-monday-com.evil.com", "", "169.254.169.254",
            "files-monday-com.s3.amazonaws.com.evil.com", "evil.com/.monday.com", "a_b.monday.com", "x..monday.com",
            "files-monday-com..s3.amazonaws.com", "files-monday-comevil.s3.amazonaws.com", "files-monday-com.s3.evil.amazonaws.com.attacker.net",
        )
            .forEach { assertTrue(!MantenimientoMondayClient.isAllowedFileHost(it), "no debe permitirse '$it'") }
        assertTrue(!MantenimientoMondayClient.isAllowedFileHost(null))
    }

    @Test
    fun batchSummariesMergeWithoutRepeatingNames() {
        val a = MondaySummaryDto(plans = 2, properties = listOf("P1", "P2"), checklistItems = 3, notes = 4, matchedPeople = listOf("Ana"), unmatchedPeople = listOf("Bruno"), problems = listOf("p1"), problemCount = 1)
        val b = MondaySummaryDto(plans = 1, properties = listOf("P2", "P3"), checklistItems = 1, matchedPeople = listOf("Ana", "Eva"), unmatchedPeople = listOf("Bruno"))
        val merged = MondaySummaryDto.merge(listOf(a, b))
        assertEquals(3, merged.plans)
        assertEquals(listOf("P1", "P2", "P3"), merged.properties)
        assertEquals(4, merged.checklistItems)
        assertEquals(listOf("Ana", "Eva"), merged.matchedPeople)
        assertEquals(listOf("Bruno"), merged.unmatchedPeople)
        assertEquals(1, merged.problemCount)
    }

    @Test
    fun theReadLimitsAreBounded() {
        assertEquals(2400, MantenimientoMondayRunner.MaxPages)
        assertEquals(5, MantenimientoMondayRunner.MaxRetries)
        assertTrue(MantenimientoMondayException("x", transient = true).transient)
        assertTrue(!MantenimientoMondayException("x").transient)
    }

    @Test
    fun theManifestInCodeMatchesExtensionJson() {
        val text = repoFile("extensions/mantenimiento_monday/extension.json").readText()
        val fromDisk = Json { ignoreUnknownKeys = true }.decodeFromString(ExtensionManifest.serializer(), text)
        assertEquals(fromDisk, MantenimientoMondayServerExtension.manifest)
        assertEquals(MantenimientoMondayId, MantenimientoMondayServerExtension.sourceId)
        assertEquals(listOf("extensions/mantenimiento_monday/migrations/V001__settings.sql"), MantenimientoMondayServerExtension.migrationPaths)
        assertTrue(repoFile("extensions/mantenimiento_monday/migrations/V001__settings.sql").isFile)
        // Solo tablas propias de la extension.
        assertTrue(MantenimientoMondayServerExtension.runtimeTables.all { it.startsWith("ext_mantenimiento_monday__") })
        // El trabajo de importacion es del modulo: la extension no declara trabajos propios.
        assertTrue(MantenimientoMondayServerExtension.jobs.isEmpty())
    }

    @Test
    fun mcpToolsNeedTheImportPermissionAndFitTheQualifiedNameLimit() {
        val tools = MantenimientoMondayMcpTools.tools
        assertEquals(listOf("monday_list_boards", "monday_read_board", "monday_import_status", "monday_apply"), tools.map { it.name })
        assertTrue(tools.all { it.permission == MantenimientoImportPermission })
        assertTrue(tools.all { "mantenimiento_${it.name}".length <= 64 })
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
