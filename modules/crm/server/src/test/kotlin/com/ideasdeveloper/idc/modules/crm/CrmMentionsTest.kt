package com.ideasdeveloper.idc.modules.crm

import com.ideasdeveloper.idc.ui.UiFieldValues
import java.time.Instant
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Mentions in notes (change 8): parsing, visibility by mention, what a mention can NOT do, notices and the email preference. */
class CrmMentionsTest {
    private val ana = CrmUser(UUID.randomUUID(), "Ana", "ana@example.com")
    private val beto = CrmUser(UUID.randomUUID(), "Beto Ruiz", "beto@example.com")
    private val carla = CrmUser(UUID.randomUUID(), "Carla", "")
    private val users = listOf(ana, beto, carla)

    private val surf = UUID.randomUUID()
    private val yoga = UUID.randomUUID()
    private val other = UUID.randomUUID()

    // A coordinator of surf only: a yoga lead is outside their scope.
    private val coordinator = CrmScope(beto.id, false, false, true, setOf(surf))
    private val admin = CrmScope(ana.id, true, true, true, emptySet())

    private fun lead(interest: UUID?, owners: List<UUID> = listOf(other)) = CrmLead(
        UUID.randomUUID(), UUID.randomUUID(), "Lead", "lead@example.com", "", UUID.randomUUID(), interest, owners, "Web", "", "Hola", "",
        null, null, null, null, Instant.now(), null, Instant.now(),
    )

    // ---- Parsing @mentions ----

    @Test
    fun atNameMentionsThatUserIgnoringCase() {
        val parsed = CrmMentions.parseText("@ana revisa esto", users)
        assertEquals(listOf(ana), parsed.users)
        assertEquals(emptyList(), parsed.unknown)
    }

    @Test
    fun namesWithSpacesAreMatchedWhole() {
        val parsed = CrmMentions.parseText("Pasale esto a @beto ruiz, gracias", users)
        assertEquals(listOf(beto), parsed.users)
        // The longest name wins over a shorter one that is a prefix of it.
        val prefix = CrmUser(UUID.randomUUID(), "Beto", "")
        assertEquals(listOf(beto), CrmMentions.parseText("@Beto Ruiz", users + prefix).users)
        assertEquals(listOf(prefix), CrmMentions.parseText("@Beto llamo", users + prefix).users)
    }

    @Test
    fun atEmailMentionsThatUser() {
        val parsed = CrmMentions.parseText("cc @BETO@example.com.", users)
        assertEquals(listOf(beto), parsed.users)
    }

    @Test
    fun anEmailAddressInTheTextIsNotAMention() {
        // The "@" glued to a word is part of an address: nobody is mentioned and nothing is reported.
        val parsed = CrmMentions.parseText("Escribele a ana@example.com o a carla@otro.com", users)
        assertEquals(emptyList(), parsed.users)
        assertEquals(emptyList(), parsed.unknown)
        // A lone "@" is only a symbol.
        assertEquals(CrmMentions.Parsed(emptyList(), emptyList()), CrmMentions.parseText("nos vemos @ las 5", users))
    }

    @Test
    fun unknownMentionsAreReportedAndDoNotBreakTheKnownOnes() {
        val parsed = CrmMentions.parseText("@Zoe y @Ana, mira. Tambien @zoe.", users)
        assertEquals(listOf(ana), parsed.users)
        assertEquals(listOf("Zoe"), parsed.unknown)
        // A longer word is not a user: "@Anabel" is not "@Ana".
        val anabel = CrmMentions.parseText("@Anabel", users)
        assertEquals(emptyList(), anabel.users)
        assertEquals(listOf("Anabel"), anabel.unknown)
    }

    @Test
    fun repeatedMentionsCountOnceInOrderOfAppearance() {
        val parsed = CrmMentions.parseText("@Carla, @ana, @Carla otra vez y @ANA", users)
        assertEquals(listOf(carla, ana), parsed.users)
    }

    @Test
    fun theFieldAndTheTextAreMergedWithoutRepeats() {
        val resolved = CrmMentions.resolve("@Ana mira esto", listOf("ana@example.com", "Beto Ruiz", "Zoe"), users)
        // The field comes first (Ana by email, Beto), the text adds nobody new (Ana is already there).
        assertEquals(listOf(ana, beto), resolved.users)
        assertEquals(listOf("Zoe"), resolved.unknown)
        assertEquals(listOf(carla), CrmMentions.resolve("sin menciones", listOf("carla"), users).users)
        assertEquals(CrmMentions.Parsed(emptyList(), emptyList()), CrmMentions.resolve("nada", emptyList(), users))
    }

    @Test
    fun theNoteFormSendsTheChosenNames() {
        assertEquals(listOf("Ana", "Beto Ruiz"), CrmForms.mentionNames(mapOf("mentions" to UiFieldValues.options(listOf("Ana", "Beto Ruiz", "ana")))))
        assertEquals(emptyList(), CrmForms.mentionNames(mapOf("note" to "Hola")))
        // An old client sends one plain name.
        assertEquals(listOf("Ana"), CrmForms.mentionNames(mapOf("mentions" to "Ana")))
    }

    // ---- Visibility by mention (read and notes) ----

    @Test
    fun aMentionLetsTheUserReadALeadOutsideTheirScope() {
        val yogaLead = lead(yoga)
        // Without the mention the lead is not theirs...
        assertFalse(coordinator.canSee(yogaLead.interestId, yogaLead.ownerUserIds))
        assertEquals(CrmMentions.LeadAccess.None, CrmMentions.leadAccess(coordinator, yoga, yogaLead.ownerUserIds, mentioned = false))
        // ...with it they can read it and add notes.
        assertTrue(coordinator.canRead(yoga, yogaLead.ownerUserIds, mentioned = true))
        assertEquals(CrmMentions.LeadAccess.ReadNotes, CrmMentions.leadAccess(coordinator, yoga, yogaLead.ownerUserIds, mentioned = true))
        // A lead inside the scope stays full access with or without a mention.
        assertEquals(CrmMentions.LeadAccess.Full, CrmMentions.leadAccess(coordinator, surf, listOf(other), mentioned = true))
        assertEquals(CrmMentions.LeadAccess.Full, CrmMentions.leadAccess(admin, yoga, listOf(other), mentioned = false))
    }

    @Test
    fun revokingTheMentionTakesTheAccessAway() {
        // Revoked = no active mention: back to not seeing the lead.
        assertEquals(CrmMentions.LeadAccess.None, CrmMentions.leadAccess(coordinator, yoga, listOf(other), mentioned = false))
        assertFalse(CrmMentions.leadAccess(coordinator, yoga, listOf(other), mentioned = false).canRead)
        // A server owner has no user id: a mention never applies to them.
        val serverOwner = CrmScope(null, false, false, true, emptySet())
        assertFalse(serverOwner.canRead(yoga, listOf(other), mentioned = true))
    }

    @Test
    fun theSqlOnlyAddsMentionsWhenAskedAndOnlyForUsers() {
        val (strict, strictParams) = coordinator.sqlCondition()
        assertFalse(strict.contains("crm_lead_mentions"))
        assertEquals(2, strictParams.size)

        val (reading, readingParams) = coordinator.sqlCondition(withMentions = true)
        assertTrue(reading.contains("crm_lead_mentions"))
        assertTrue(reading.contains("m.revoked_at IS NULL"))
        // Assigned (user), mention (user) and the interests.
        assertEquals(listOf<Any>(beto.id, beto.id, listOf(surf)), readingParams)

        // See-all users and companies without interests see everything anyway: nothing to add.
        assertEquals("TRUE", admin.sqlCondition(withMentions = true).first)
        assertEquals("TRUE", CrmScope(beto.id, false, false, false, emptySet()).sqlCondition(withMentions = true).first)
        // A user without interests or reception only sees what they were assigned or mentioned in.
        val nothingElse = CrmScope(carla.id, false, false, true, emptySet())
        assertEquals("FALSE", CrmScope(null, false, false, true, emptySet()).sqlCondition(withMentions = true).first)
        assertTrue(nothingElse.sqlCondition(withMentions = true).first.contains("crm_lead_mentions"))
    }

    @Test
    fun anArchivedLeadLeavesTheViewsUnlessArchivedAreShown() {
        // The mention does not change the archived rule: the same filter hides or shows archived leads.
        assertEquals("l.archived_at IS NULL", archivedSql(CrmLeadFilter(withMentions = true)))
        assertNull(archivedSql(CrmLeadFilter(withMentions = true, includeArchived = true)))
        assertEquals("l.archived_at IS NOT NULL", archivedSql(CrmLeadFilter(withMentions = true, archived = true)))
    }

    // ---- What a mention can NOT do ----

    @Test
    fun aMentionOnlyAllowsReadingAndNotes() {
        val access = CrmMentions.LeadAccess.ReadNotes
        assertTrue(access.canRead)
        assertFalse(access.canWrite)
        // Notes: through a mention even without crm.leads.manage.
        assertTrue(CrmMentions.canAddNote(access, canManage = false))
        assertTrue(CrmMentions.canAddNote(access, canManage = true))
        // Nobody who does not see the lead adds notes.
        assertFalse(CrmMentions.canAddNote(CrmMentions.LeadAccess.None, canManage = true))
        // Inside the scope the note still needs the permission, as before.
        assertTrue(CrmMentions.canAddNote(CrmMentions.LeadAccess.Full, canManage = true))
        assertFalse(CrmMentions.canAddNote(CrmMentions.LeadAccess.Full, canManage = false))
    }

    @Test
    fun theMentionedUserCannotMoveEditOrAssignBecauseWritesAskTheScope() {
        val yogaLead = lead(yoga)
        // Every write (move, save, restore, send, assign, archive, delete, items, rooms) asks canSee, which a mention never grants.
        assertFalse(coordinator.canSee(yogaLead.interestId, yogaLead.ownerUserIds))
        assertFalse(CrmMentions.leadAccess(coordinator, yoga, yogaLead.ownerUserIds, mentioned = true).canWrite)
        // Not even the permission to manage helps: the lead is read-only for them.
        assertFalse(CrmMentions.canRevoke(CrmMentions.LeadAccess.ReadNotes, canManage = true))
    }

    @Test
    fun theBulkBarNeverTouchesALeadSeenOnlyThroughAMention() {
        val mine = lead(surf)
        val mentionedOnly = lead(yoga)
        val (writable, readOnly) = CrmBulkActions.writable(coordinator, listOf(mine, mentionedOnly))
        assertEquals(listOf(mine), writable)
        assertEquals(listOf(mentionedOnly), readOnly)
        // Who sees everything acts on everything.
        assertEquals(listOf(mine, mentionedOnly), CrmBulkActions.writable(admin, listOf(mine, mentionedOnly)).first)
        // The message says why a lead was left out.
        assertTrue(CrmBulkActions.ReadOnlyReason.contains("mencion"))
    }

    @Test
    fun onlyWhoManagesTheLeadTakesAccessAway() {
        assertTrue(CrmMentions.canRevoke(CrmMentions.LeadAccess.Full, canManage = true))
        assertFalse(CrmMentions.canRevoke(CrmMentions.LeadAccess.Full, canManage = false))
        assertFalse(CrmMentions.canRevoke(CrmMentions.LeadAccess.None, canManage = true))
    }

    // ---- Notices ----

    @Test
    fun nobodyIsToldTwiceForTheSameNoteNorForMentioningThemselves() {
        // The writer mentions Ana, Beto and themselves (Carla).
        val first = CrmMentions.toNotify(emptyList(), listOf(ana.id, beto.id, carla.id, ana.id), byUserId = carla.id)
        assertEquals(listOf(ana.id, beto.id), first)
        // The note is saved again (an edit) mentioning the same people: nobody is told again, and the writer never is.
        assertEquals(emptyList(), CrmMentions.toNotify(first, listOf(ana.id, beto.id, carla.id), byUserId = carla.id))
        // A new user in the second save is told once.
        val david = UUID.randomUUID()
        assertEquals(listOf(david), CrmMentions.toNotify(first, listOf(ana.id, david, david), byUserId = carla.id))
        // A revoked mention of the same note counts as already there: it is not given back or told again.
        assertEquals(emptyList(), CrmMentions.toNotify(listOf(ana.id), listOf(ana.id), byUserId = null))
    }

    @Test
    fun theNoticeTextCutsTheNote() {
        val long = "Revisa esto " + "muy ".repeat(100) + "importante"
        val text = CrmNotify.mentionText("Lead de prueba", "Beto Ruiz", long)
        assertTrue(text.startsWith("Lead de prueba - Beto Ruiz: Revisa esto muy"))
        assertTrue(text.endsWith("..."))
        assertTrue(text.length < 200)
        assertEquals("Hola mundo", CrmNotify.excerpt("  Hola\n\n   mundo  "))
        assertEquals("Te mencionaron en un lead", CrmNotify.MentionedTitle)
        assertEquals("@Ana, @Beto Ruiz", CrmMentions.label(listOf("Ana", "Beto Ruiz")))
    }

    @Test
    fun theMentionEmailGoesToTheProfileEmailUnlessTurnedOff() {
        // On by default: a user without a settings row gets it, at the email of their profile.
        assertEquals("ana@example.com", CrmNotify.mentionEmailTarget(ana, null))
        val on = CrmUserSettings(ana.id, "otro@example.com", false, true, false, notifyMentioned = true)
        assertEquals("ana@example.com", CrmNotify.mentionEmailTarget(ana, on))
        // Turned off in Equipo: no email (the notice in the bell is still created).
        assertNull(CrmNotify.mentionEmailTarget(ana, on.copy(notifyMentioned = false)))
        // Without a profile email there is nobody to write to.
        assertNull(CrmNotify.mentionEmailTarget(carla, null))
        assertNull(CrmNotify.mentionEmailTarget(CrmUser(UUID.randomUUID(), "Sin correo", "   "), on))
        // The other preferences keep their meaning and the new one defaults to true.
        assertTrue(CrmUserSettings(ana.id, "", false, true, false).notifyMentioned)
    }

    // ---- Review fixes ----

    @Test
    fun onlyWhoHasTheLeadInScopeGivesAccessByMentioning() {
        // Full (scope): their @ create access. ReadNotes (through a mention): plain text, no rows, no notices.
        assertTrue(CrmMentions.canGrant(CrmMentions.LeadAccess.Full))
        assertFalse(CrmMentions.canGrant(CrmMentions.LeadAccess.ReadNotes))
        assertFalse(CrmMentions.canGrant(CrmMentions.LeadAccess.None))
        // A mentioned user (read-only) cannot bring back an access that was revoked: after revoking, the user reads nothing
        // again (no active mention) and only a Full user can give it with a new note.
        val yogaLead = lead(yoga)
        val readOnly = CrmMentions.leadAccess(coordinator, yoga, yogaLead.ownerUserIds, mentioned = true)
        assertEquals(CrmMentions.LeadAccess.ReadNotes, readOnly)
        assertFalse(CrmMentions.canGrant(readOnly))
        assertEquals(CrmMentions.LeadAccess.None, CrmMentions.leadAccess(coordinator, yoga, yogaLead.ownerUserIds, mentioned = false))
        assertTrue(CrmMentions.canGrant(CrmMentions.leadAccess(admin, yoga, yogaLead.ownerUserIds, mentioned = false)))
    }

    @Test
    fun anArchivedLeadIsNotReadThroughAMention() {
        assertEquals(CrmMentions.LeadAccess.None, CrmMentions.leadAccess(coordinator, yoga, listOf(other), mentioned = true, archived = true))
        assertFalse(coordinator.canRead(yoga, listOf(other), mentioned = true, archived = true))
        // Inside the scope an archived lead is still theirs.
        assertEquals(CrmMentions.LeadAccess.Full, CrmMentions.leadAccess(coordinator, surf, listOf(other), mentioned = true, archived = true))
        // The SQL of the views says the same.
        assertTrue(coordinator.sqlCondition(withMentions = true).first.contains("l.archived_at IS NULL AND EXISTS"))
    }

    @Test
    fun aNameFollowedByMoreOfAWordIsNotThatUser() {
        val anaOnly = listOf(ana)
        listOf("@Ana-Maria", "@Ana.Perez", "@Ana_x", "@Ana+1", "@Ana-2 hola").forEach { text ->
            assertEquals(emptyList(), CrmMentions.parseText(text, anaOnly).users, text)
        }
        // Punctuation that ends the word still mentions.
        listOf("@Ana.", "@Ana, hola", "@Ana - hola", "(@Ana)", "@Ana+").forEach { text ->
            assertEquals(listOf(ana), CrmMentions.parseText(text, anaOnly).users, text)
        }
    }

    @Test
    fun spacesInNamesAreNormalized() {
        assertEquals(listOf(beto), CrmMentions.parseText("@Beto   Ruiz gracias", users).users)
        assertEquals(listOf(beto), CrmMentions.parseText("@Beto Ruiz gracias", users).users)
        assertEquals(listOf(beto), CrmMentions.parseText("@beto 	 Ruiz", users).users)
        // A user whose stored name has two spaces matches a single one.
        val double = CrmUser(UUID.randomUUID(), "Luis  Gomez", "")
        assertEquals(listOf(double), CrmMentions.parseText("@Luis Gomez", listOf(double)).users)
    }
}
