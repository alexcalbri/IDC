package com.ideasdeveloper.idc.modules.crm

import com.ideasdeveloper.idc.ui.UiFieldValues
import java.time.LocalDate
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** Several people assigned to a lead (change 11): all equal, parsed from forms and imports, listed in templates. */
class CrmAssigneesTest {
    private val ana = CrmUser(UUID.randomUUID(), "ana", "ana@example.com")
    private val beto = CrmUser(UUID.randomUUID(), "Beto Ruiz", "beto@example.com")
    private val users = listOf(ana, beto)
    private val names = CrmNames(users, emptyList())

    @Test
    fun csvListAcceptsCommaSemicolonAndNewLinesWithoutRepeats() {
        assertEquals(listOf("Ana", "beto@example.com", "Carla"), CrmAssignees.parseList("Ana, beto@example.com;Carla ; ana\n"))
        assertEquals(emptyList(), CrmAssignees.parseList("  ;, "))
        assertEquals(listOf("Ana"), CrmAssignees.parseList("Ana"))
    }

    @Test
    fun peopleMatchByEquivalenceThenEmailThenName() {
        val matched = CrmAssignees.match(
            listOf("BF", "BETO@example.com", "ANA", "Zoe"), users, equivalences = mapOf("BF" to "ana"),
        )
        // BF -> ana (table), the email -> Beto, ANA -> ana again (no repeat): Zoe has no user.
        assertEquals(listOf(ana.id, beto.id), matched.ids)
        assertEquals(listOf("Zoe"), matched.unmatched)
        assertEquals("Sin usuario para asignar (omitidos): Zoe.", CrmAssignees.unmatchedNote(matched.unmatched))
        assertEquals("", CrmAssignees.unmatchedNote(emptyList()))
    }

    @Test
    fun mondayRowWithSeveralPeopleImportsTheOnesWithAUserAndReportsTheRest() {
        val stages = listOf(CrmStage(UUID.randomUUID(), "Nuevo", StageKind.Open, StageTone.Info, 0))
        val context = CrmImport.Context(stages, emptyList(), users, LocalDate.of(2026, 10, 28), "monday")
        val mapping = CrmImportMapping(columns = mapOf("Name" to "name", "Involved" to "owner"), owners = mapOf("BF" to "Beto Ruiz"))
        // monday writes the people of a cell as "Ana, BF, Zoe Nadie".
        val row = mapOf("Name" to "Jason", "Involved" to "ana, BF, Zoe Nadie", CrmImport.KeyColumn to "monday:7")
        val values = CrmImport.values(row, mapping, context)
        assertEquals(listOf(ana.id, beto.id), values.ownerIds)
        assertEquals(listOf("Zoe Nadie"), values.ownersUnmatched)
        // Nobody is created for the people without a user: the users of the context are the only ones that can match.
        assertEquals(2, users.size)
    }

    @Test
    fun importingAgainOnlyAddsTheNewPeople() {
        val first = listOf(ana.id)
        // The lead already has Ana: the second import brings Ana and Beto.
        val second = CrmAssignees.combine(first, CrmAssignees.Mode.Add, listOf(ana.id, beto.id))
        assertEquals(listOf(ana.id, beto.id), second)
        assertEquals(listOf(beto.id), CrmAssignees.added(first, second))
        // A third identical import changes nothing and adds nobody.
        val third = CrmAssignees.combine(second, CrmAssignees.Mode.Add, listOf(ana.id, beto.id))
        assertEquals(second, third)
        assertEquals(emptyList(), CrmAssignees.added(second, third))
        // Repeats inside one import do not duplicate either.
        assertEquals(listOf(ana.id), CrmAssignees.combine(emptyList(), CrmAssignees.Mode.Add, listOf(ana.id, ana.id)))
    }

    @Test
    fun csvColumnAcceptsSeveralNamesOrEmails() {
        val stages = listOf(CrmStage(UUID.randomUUID(), "Nuevo", StageKind.Open, StageTone.Info, 0))
        val context = CrmImport.Context(stages, emptyList(), users, LocalDate.of(2026, 10, 28), "file")
        val mapping = CrmImportMapping(columns = mapOf("Nombre" to "name", "Asignados" to "owner"))
        val values = CrmImport.values(mapOf("Nombre" to "Luis", "Asignados" to "ana; beto@example.com"), mapping, context)
        assertEquals(listOf(ana.id, beto.id), values.ownerIds)
        assertEquals(emptyList(), values.ownersUnmatched)
    }

    @Test
    fun templateVariableListsEveryAssignedNameSeparatedByComma() {
        assertEquals("ana, Beto Ruiz", CrmAssignees.names(names, listOf(ana.id, beto.id)))
        assertEquals("ana", CrmAssignees.names(names, listOf(ana.id)))
        assertEquals("", CrmAssignees.names(names, emptyList()))
        assertEquals(CrmForms.Unassigned, CrmAssignees.label(names, emptyList()))
    }

    @Test
    fun formAcceptsSeveralUsersAndAPlainNameFromOlderClients() {
        val stages = listOf(CrmStage(UUID.randomUUID(), "Nuevo", StageKind.Open, StageTone.Info, 0))
        fun parse(owner: String) = CrmForms.parseLead(mapOf("name" to "Megan", "owner" to owner), stages, emptyList(), false, users)
        assertEquals(listOf(ana.id, beto.id), parse(UiFieldValues.options(listOf("ana", "Beto Ruiz"))).ownerUserIds)
        assertEquals(listOf(ana.id), parse("ana").ownerUserIds)
        assertEquals(emptyList(), parse("").ownerUserIds)
        assertFailsWith<CrmValidationException> { parse(UiFieldValues.options(listOf("ana", "luis"))) }
    }

    @Test
    fun selfAssignmentIsNotNotified() {
        // The user who assigns is never told; everybody else added is told once.
        assertEquals(listOf(beto.id), CrmNotify.notifyTargets(listOf(ana.id, beto.id, beto.id), byUserId = ana.id))
        assertEquals(listOf(ana.id), CrmNotify.notifyTargets(listOf(ana.id), byUserId = null))
        assertEquals(emptyList(), CrmNotify.notifyTargets(listOf(ana.id), byUserId = ana.id))
        assertEquals("Te asignaron un lead", CrmNotify.AssignedTitle)
    }

    @Test
    fun initialsOfTheAssignedUsersForCardsAndRows() {
        assertEquals("BR", CrmAssignees.initials("Beto Ruiz"))
        assertEquals("A", CrmAssignees.initials("ana"))
        assertEquals("MG", CrmAssignees.initials("maria.gomez"))
        val many = List(5) { CrmUser(UUID.randomUUID(), "Usuario$it Apellido") }
        val labels = CrmAssignees.initialsLabels(CrmNames(many, emptyList()), many.map { it.id }, max = 3)
        assertEquals(listOf("UA", "UA", "UA", "+2"), labels)
    }

    @Test
    fun sqlFragmentsUseOnlyTheAssignedUsersTable() {
        listOf(CrmAssignees.AnySql, CrmAssignees.NoneSql, CrmAssignees.OfUserSql, CrmAssignees.ListSql).forEach {
            assertTrue("crm_lead_owners" in it && "owner_user_id" !in it, it)
        }
    }

    // ---- Review fixes ----

    private fun args(json: String) = kotlinx.serialization.json.Json.parseToJsonElement(json) as kotlinx.serialization.json.JsonObject

    @Test
    fun savingWithoutPermissionToAssignLeavesTheAssignedUsersUntouched() {
        // null = do not touch: nothing is added or removed whatever the form carried.
        assertEquals(null, CrmAssignees.wanted(canAssign = false, parsed = listOf(ana.id)))
        assertEquals(listOf(ana.id), CrmAssignees.wanted(canAssign = true, parsed = listOf(ana.id)))
    }

    @Test
    fun onlyTheDifferenceWithTheListTheFormWasDrawnWithIsApplied() {
        val carla = UUID.randomUUID()
        // The form showed [ana]; the user added beto. Carla was assigned by somebody else meanwhile: she is not removed.
        val change = CrmAssignees.diff(before = listOf(ana.id), wanted = listOf(ana.id, beto.id))
        assertEquals(listOf(beto.id), change.added)
        assertEquals(emptyList(), change.removed)
        // Unchecking ana only removes ana.
        val removal = CrmAssignees.diff(before = listOf(ana.id, beto.id), wanted = listOf(beto.id))
        assertEquals(emptyList(), removal.added)
        assertEquals(listOf(ana.id), removal.removed)
        assertTrue(CrmAssignees.diff(listOf(ana.id), listOf(ana.id)).isEmpty)
        assertTrue(carla !in change.removed)
    }

    @Test
    fun anInactiveAssignedUserCanBeRemovedButNeverAdded() {
        val inactive = CrmUser(UUID.randomUUID(), "pepe (sin acceso)")
        val assignable = users.map { it.id }
        // Bulk "Quitar asignado" finds it by its label and removes it although it is not in the active users.
        assertEquals(inactive.id, CrmBulkActions.userFor(users + inactive, "pepe (sin acceso)"))
        assertEquals(listOf(ana.id), CrmAssignees.combine(listOf(ana.id, inactive.id), CrmAssignees.Mode.Remove, listOf(inactive.id)))
        assertTrue(CrmAssignees.canApply(CrmAssignees.Mode.Remove, listOf(inactive.id), assignable))
        assertFalse(CrmAssignees.canApply(CrmAssignees.Mode.Add, listOf(inactive.id), assignable))
        // The agent can remove it with ownersMode=remove using the label it got from get_lead.
        val change = CrmMcpTools.ownersRequest(args("""{"owners": ["pepe (sin acceso)"], "ownersMode": "remove"}"""), true, users, legacy = listOf(inactive))
        assertEquals(listOf(inactive.id), change?.ids)
        // ...but adding it is refused.
        assertFailsWith<com.ideasdeveloper.idc.server.modules.McpToolException> {
            CrmMcpTools.ownersRequest(args("""{"owners": ["pepe (sin acceso)"], "ownersMode": "add"}"""), true, users, legacy = listOf(inactive))
        }
    }

    @Test
    fun aSuggestionOfAUserWithoutAccessIsOnlyDiscarded() {
        val gone = UUID.randomUUID()
        val hasAccess = { id: UUID -> id in users.map { it.id } }
        assertTrue(CrmAssignees.suggestionLacksAccess(approve = true, suggested = gone, hasAccess = hasAccess))
        assertFalse(CrmAssignees.suggestionLacksAccess(approve = true, suggested = ana.id, hasAccess = hasAccess))
        // Discarding never needs access.
        assertFalse(CrmAssignees.suggestionLacksAccess(approve = false, suggested = gone, hasAccess = hasAccess))
        assertFalse(CrmNames(users, emptyList()).knows(gone))
    }

    @Test
    fun bulkWithoutOwnerNorOwnersIsAnErrorAndEmptyingNeedsAnExplicitEmptyList() {
        assertFailsWith<com.ideasdeveloper.idc.server.modules.McpToolException> { CrmMcpTools.ownersRequiredForBulk(args("""{"action": "assign"}"""), true, users) }
        val cleared = CrmMcpTools.ownersRequiredForBulk(args("""{"owners": []}"""), true, users)
        assertEquals(CrmAssignees.Mode.Replace, cleared.mode)
        assertEquals(emptyList(), cleared.ids)
        assertEquals(emptyList(), CrmMcpTools.ownersRequiredForBulk(args("""{"owner": "Sin asignar"}"""), true, users).ids)
        assertEquals(emptyList(), CrmMcpTools.ownersRequiredForBulk(args("""{"owner": ""}"""), true, users).ids)
    }

    @Test
    fun createWithABlankOwnerAndNoPermissionToAssignDoesNotFail() {
        assertEquals(null, CrmMcpTools.ownersRequest(args("""{"owner": ""}"""), canAssign = false, users = users, blankNeedsPermission = false))
        // Asking for somebody without permission is still refused, and so is a blank owner when updating.
        assertFailsWith<com.ideasdeveloper.idc.server.modules.McpToolException> {
            CrmMcpTools.ownersRequest(args("""{"owner": "ana"}"""), canAssign = false, users = users, blankNeedsPermission = false)
        }
        assertFailsWith<com.ideasdeveloper.idc.server.modules.McpToolException> {
            CrmMcpTools.ownersRequest(args("""{"owner": ""}"""), canAssign = false, users = users)
        }
    }

    @Test
    fun ownerAcceptsSeveralNamesSeparatedByComma() {
        val change = CrmMcpTools.ownersRequest(args("""{"owner": "ana, beto@example.com; Beto Ruiz"}"""), canAssign = true, users = users)
        assertEquals(listOf(ana.id, beto.id), change?.ids)
        assertEquals(CrmAssignees.Mode.Replace, change?.mode)
    }

    @Test
    fun templatesNeverShowAssignedUsersWithoutAccess() {
        val gone = UUID.randomUUID()
        assertEquals("ana", CrmAssignees.names(names, listOf(ana.id, gone)))
        assertEquals("", CrmAssignees.names(names, listOf(gone)))
    }

    @Test
    fun theListColumnCanBeBuiltForAnotherAlias() {
        assertEquals(CrmAssignees.ListSql, CrmAssignees.listSql("l"))
        assertTrue("o.lead_id = leads.id" in CrmAssignees.listSql("leads"))
    }
}
