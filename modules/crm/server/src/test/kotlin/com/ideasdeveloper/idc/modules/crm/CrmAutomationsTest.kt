package com.ideasdeveloper.idc.modules.crm

import com.ideasdeveloper.idc.ui.UiFieldValues
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Automations (change 9.2): validation, conditions, the data actions need, date schedule, run keys and the form. */
class CrmAutomationsTest {
    private val booked = CrmStage(UUID.randomUUID(), "Booked", StageKind.Won, StageTone.Success, 1)
    private val fresh = CrmStage(UUID.randomUUID(), "Nuevo", StageKind.Open, StageTone.Info, 0)
    private val surf = CrmInterest(UUID.randomUUID(), "Surf", 0)
    private val welcomeTemplate = CrmTemplate(UUID.randomUUID(), "Bienvenida", "Hola {nombre}", "Texto")
    private val ana = CrmUser(UUID.randomUUID(), "ana", "ana@example.com")
    private val beto = CrmUser(UUID.randomUUID(), "beto")
    private val arrival = CrmField(UUID.randomUUID(), "fecha_ingreso", "Fecha de ingreso", CrmFieldType.Date)
    private val departure = CrmField(UUID.randomUUID(), "fecha_salida", "Fecha de salida", CrmFieldType.Date)
    private val language = CrmField(UUID.randomUUID(), "cf_idioma", "Idioma", CrmFieldType.Dropdown, listOf("Espanol", "Ingles"))
    private val vip = CrmField(UUID.randomUUID(), "cf_vip", "VIP", CrmFieldType.Boolean)
    private val button = CrmField(UUID.randomUUID(), "cf_confirmar", "Confirmar", CrmFieldType.Button)
    private val files = CrmField(UUID.randomUUID(), "cf_contrato", "Contrato", CrmFieldType.Files)
    private val refs = CrmAutomationRefs(
        stages = listOf(fresh, booked), interests = listOf(surf), templates = listOf(welcomeTemplate), users = listOf(ana, beto),
        fields = listOf(arrival, departure, language, vip, button, files), cloudbedsReady = true,
    )

    private fun welcome() = CrmAutomation(
        UUID.randomUUID(), "Bienvenida antes del check-in", true,
        CrmTrigger(CrmTriggerType.Date, field = "fecha_ingreso", offsetDays = -1, hour = 9),
        conditions = listOf(CrmCondition(CrmConditionKind.StageIn, stageIds = listOf(booked.id)), CrmCondition(CrmConditionKind.HasEmail)),
        actions = listOf(CrmAction(CrmActionKind.SendTemplate, templateId = welcomeTemplate.id)),
        warnBeforeMinutes = 2880,
        notifyUserIds = listOf(ana.id),
    )

    private fun lead(
        email: String = "huesped@example.com", phone: String = "", stage: UUID = booked.id, interest: UUID? = null,
        custom: JsonObject = JsonObject(emptyMap()), followUp: LocalDate? = null, start: LocalDate? = null, end: LocalDate? = null,
    ) = CrmLead(
        id = UUID.randomUUID(), customerId = UUID.randomUUID(), name = "Maria", email = email, phone = phone, stageId = stage, interestId = interest,
        ownerUserIds = emptyList(), source = "", language = "", message = "", details = "", targetStart = start, targetEnd = end, estimatedValue = null,
        followUpOn = followUp, stageChangedAt = Instant.EPOCH, closedAt = null, createdAt = Instant.EPOCH, customFields = custom,
    )

    private fun invalid(automation: CrmAutomation, others: List<CrmAutomation> = emptyList()): String =
        assertFailsWith<CrmValidationException> { CrmAutomationRules.validate(automation, refs, others) }.message.orEmpty()

    // ---- Validation ----

    @Test
    fun `a complete date rule is valid`() {
        CrmAutomationRules.validate(welcome(), refs)
    }

    @Test
    fun `the name is required and unique`() {
        assertTrue(invalid(welcome().copy(name = "  ")).contains("nombre"))
        val other = welcome().copy(id = UUID.randomUUID())
        assertTrue(invalid(welcome().copy(name = other.name.uppercase()), listOf(other)).contains("Ya existe"))
        // Editing a rule keeps its own name.
        CrmAutomationRules.validate(other, refs, listOf(other))
    }

    @Test
    fun `a date rule needs a date field or the follow-up date`() {
        val onText = welcome().copy(trigger = CrmTrigger(CrmTriggerType.Date, field = "cf_idioma"))
        assertTrue(invalid(onText).contains("fecha"))
        assertTrue(invalid(welcome().copy(trigger = CrmTrigger(CrmTriggerType.Date, field = ""))).contains("campo de fecha"))
        CrmAutomationRules.validate(welcome().copy(trigger = CrmTrigger(CrmTriggerType.Date, field = CrmAutomationRefs.FollowUpField, offsetDays = 0, hour = 8)), refs)
    }

    @Test
    fun `days and hour are bounded`() {
        fun date(days: Int, hour: Int) = welcome().copy(trigger = CrmTrigger(CrmTriggerType.Date, field = "fecha_ingreso", offsetDays = days, hour = hour))
        CrmAutomationRules.validate(date(-60, 0), refs)
        CrmAutomationRules.validate(date(60, 23), refs)
        assertTrue(invalid(date(-61, 9)).contains("-60"))
        assertTrue(invalid(date(61, 9)).contains("60"))
        assertTrue(invalid(date(0, 24)).contains("hora"))
        assertTrue(invalid(date(0, -1)).contains("hora"))
    }

    @Test
    fun `the warning belongs to date rules and is bounded`() {
        assertTrue(invalid(welcome().copy(warnBeforeMinutes = 43201)).contains("aviso"))
        val manual = welcome().copy(trigger = CrmTrigger(CrmTriggerType.Manual), warnBeforeMinutes = 60)
        assertTrue(invalid(manual).contains("fecha"))
        // normalized() drops it for the rest, which is what the screens save.
        assertEquals(0, manual.normalized().warnBeforeMinutes)
        CrmAutomationRules.validate(manual.normalized(), refs)
    }

    @Test
    fun `event triggers need the stage or the field they watch`() {
        val stage = welcome().copy(trigger = CrmTrigger(CrmTriggerType.StageChanged, stageId = booked.id), warnBeforeMinutes = 0)
        CrmAutomationRules.validate(stage, refs)
        assertTrue(invalid(stage.copy(trigger = CrmTrigger(CrmTriggerType.StageChanged))).contains("etapa"))
        assertTrue(invalid(stage.copy(trigger = CrmTrigger(CrmTriggerType.StageChanged, stageId = UUID.randomUUID()))).contains("etapa"))
        val field = stage.copy(trigger = CrmTrigger(CrmTriggerType.FieldChanged, field = "cf_idioma"))
        CrmAutomationRules.validate(field, refs)
        assertTrue(invalid(field.copy(trigger = CrmTrigger(CrmTriggerType.FieldChanged, field = "cf_confirmar"))).contains("campo"))
        assertTrue(invalid(field.copy(trigger = CrmTrigger(CrmTriggerType.FieldChanged, field = "cf_nada"))).contains("campo"))
        CrmAutomationRules.validate(stage.copy(trigger = CrmTrigger(CrmTriggerType.LeadCreated)), refs)
        CrmAutomationRules.validate(stage.copy(trigger = CrmTrigger(CrmTriggerType.MessageReceived)), refs)
    }

    @Test
    fun `ids of conditions and actions must exist`() {
        val base = welcome()
        assertTrue(invalid(base.copy(actions = emptyList())).contains("al menos una accion"))
        assertTrue(invalid(base.copy(actions = listOf(CrmAction(CrmActionKind.SendTemplate, templateId = UUID.randomUUID())))).contains("plantilla"))
        assertTrue(invalid(base.copy(actions = listOf(CrmAction(CrmActionKind.SendTemplate)))).contains("plantilla"))
        assertTrue(invalid(base.copy(actions = listOf(CrmAction(CrmActionKind.SetStage, stageId = UUID.randomUUID())))).contains("etapa"))
        assertTrue(invalid(base.copy(conditions = listOf(CrmCondition(CrmConditionKind.StageIn, stageIds = listOf(UUID.randomUUID()))))).contains("etapa"))
        assertTrue(invalid(base.copy(conditions = listOf(CrmCondition(CrmConditionKind.StageIn)))).contains("etapa"))
        assertTrue(invalid(base.copy(conditions = listOf(CrmCondition(CrmConditionKind.InterestIn)))).contains("interes"))
        assertTrue(invalid(base.copy(conditions = listOf(CrmCondition(CrmConditionKind.InterestIn, interestIds = listOf(UUID.randomUUID()))))).contains("interes"))
        CrmAutomationRules.validate(base.copy(conditions = listOf(CrmCondition(CrmConditionKind.InterestIn, noInterest = true))), refs)
        assertTrue(invalid(base.copy(notifyUserIds = listOf(UUID.randomUUID()))).contains("usuarios"))
    }

    @Test
    fun `field conditions need a real value field`() {
        fun cond(kind: CrmConditionKind, field: String, value: String = "") = welcome().copy(conditions = listOf(CrmCondition(kind, field = field, value = value)))
        CrmAutomationRules.validate(cond(CrmConditionKind.FieldEquals, "cf_idioma", "Ingles"), refs)
        CrmAutomationRules.validate(cond(CrmConditionKind.FieldFilled, "fecha_ingreso"), refs)
        assertTrue(invalid(cond(CrmConditionKind.FieldEquals, "cf_idioma", "")).contains("valor"))
        assertTrue(invalid(cond(CrmConditionKind.FieldEmpty, "cf_contrato")).contains("campo"))
        assertTrue(invalid(cond(CrmConditionKind.FieldEmpty, "cf_confirmar")).contains("campo"))
        assertTrue(invalid(cond(CrmConditionKind.FieldEmpty, "no_existe")).contains("campo"))
    }

    @Test
    fun `set field values are checked with the type of the field`() {
        fun set(field: String, value: String) = welcome().copy(actions = listOf(CrmAction(CrmActionKind.SetField, field = field, value = value)))
        CrmAutomationRules.validate(set("cf_idioma", "Ingles"), refs)
        CrmAutomationRules.validate(set("cf_idioma", ""), refs)
        CrmAutomationRules.validate(set("fecha_salida", "2026-12-01"), refs)
        CrmAutomationRules.validate(set(CrmAutomationRefs.FollowUpField, "2026-12-01"), refs)
        assertTrue(invalid(set("cf_idioma", "Frances")).contains("Accion 1"))
        assertTrue(invalid(set("fecha_salida", "01/12/2026")).contains("Accion 1"))
        assertTrue(invalid(set(CrmAutomationRefs.FollowUpField, "manana")).contains("seguimiento"))
        assertTrue(invalid(set("cf_confirmar", "x")).contains("campo"))
        assertTrue(invalid(set("", "x")).contains("campo"))
    }

    @Test
    fun `notify needs people when it is for chosen users`() {
        fun notify(who: CrmNotifyWho, users: List<UUID>) = welcome().copy(actions = listOf(CrmAction(CrmActionKind.Notify, who = who, userIds = users)))
        CrmAutomationRules.validate(notify(CrmNotifyWho.Assigned, emptyList()), refs)
        CrmAutomationRules.validate(notify(CrmNotifyWho.SeeAll, emptyList()), refs)
        CrmAutomationRules.validate(notify(CrmNotifyWho.Users, listOf(beto.id)), refs)
        assertTrue(invalid(notify(CrmNotifyWho.Users, emptyList())).contains("a quien avisar"))
        assertTrue(invalid(notify(CrmNotifyWho.Users, listOf(UUID.randomUUID()))).contains("usuario"))
    }

    @Test
    fun `cloudbeds actions need the integration`() {
        val reserve = welcome().copy(trigger = CrmTrigger(CrmTriggerType.Manual), warnBeforeMinutes = 0, actions = listOf(CrmAction(CrmActionKind.CloudbedsReserve)))
        CrmAutomationRules.validate(reserve, refs)
        val off = CrmAutomationRefs(refs.stages, refs.interests, refs.templates, refs.users, refs.fields, cloudbedsReady = false)
        assertTrue(assertFailsWith<CrmValidationException> { CrmAutomationRules.validate(reserve, off) }.message.orEmpty().contains("Cloudbeds"))
        assertTrue(assertFailsWith<CrmValidationException> { CrmAutomationRules.validate(reserve.copy(actions = listOf(CrmAction(CrmActionKind.CloudbedsCheckIn))), off) }.message.orEmpty().contains("Cloudbeds"))
    }

    @Test
    fun `the number of conditions and actions is limited`() {
        val many = List(CrmAutomationRules.MaxActions + 1) { CrmAction(CrmActionKind.Notify) }
        assertTrue(invalid(welcome().copy(actions = many)).contains("${CrmAutomationRules.MaxActions}"))
        val conditions = List(CrmAutomationRules.MaxConditions + 1) { CrmCondition(CrmConditionKind.HasEmail) }
        assertTrue(invalid(welcome().copy(conditions = conditions)).contains("${CrmAutomationRules.MaxConditions}"))
    }

    @Test
    fun `a rule that mails a stay date warns about the cloudbeds welcome`() {
        val keys = listOf("fecha_ingreso", "fecha_salida")
        assertTrue(CrmAutomationRules.duplicatesCloudbedsWelcome(welcome(), keys, welcomeActive = true))
        // Cloudbeds welcome off, rule off, not a date rule, not a stay date or not sending a template: no duplicate.
        assertFalse(CrmAutomationRules.duplicatesCloudbedsWelcome(welcome(), keys, welcomeActive = false))
        assertFalse(CrmAutomationRules.duplicatesCloudbedsWelcome(welcome().copy(enabled = false), keys, welcomeActive = true))
        assertFalse(CrmAutomationRules.duplicatesCloudbedsWelcome(welcome().copy(trigger = CrmTrigger(CrmTriggerType.Manual)), keys, welcomeActive = true))
        assertFalse(CrmAutomationRules.duplicatesCloudbedsWelcome(welcome().copy(trigger = CrmTrigger(CrmTriggerType.Date, field = CrmAutomationRefs.FollowUpField)), keys, welcomeActive = true))
        assertFalse(CrmAutomationRules.duplicatesCloudbedsWelcome(welcome().copy(actions = listOf(CrmAction(CrmActionKind.Notify))), keys, welcomeActive = true))
        assertFalse(CrmAutomationRules.duplicatesCloudbedsWelcome(welcome(), emptyList(), welcomeActive = true))
    }

    // ---- Conditions ----

    @Test
    fun `stage and interest conditions`() {
        assertTrue(CrmAutomationConditions.matches(CrmCondition(CrmConditionKind.StageIn, stageIds = listOf(booked.id, fresh.id)), lead(stage = booked.id)))
        assertFalse(CrmAutomationConditions.matches(CrmCondition(CrmConditionKind.StageIn, stageIds = listOf(fresh.id)), lead(stage = booked.id)))
        val surfOrNone = CrmCondition(CrmConditionKind.InterestIn, interestIds = listOf(surf.id), noInterest = true)
        assertTrue(CrmAutomationConditions.matches(surfOrNone, lead(interest = surf.id)))
        assertTrue(CrmAutomationConditions.matches(surfOrNone, lead(interest = null)))
        assertFalse(CrmAutomationConditions.matches(surfOrNone.copy(noInterest = false), lead(interest = null)))
        assertFalse(CrmAutomationConditions.matches(surfOrNone, lead(interest = UUID.randomUUID())))
    }

    @Test
    fun `contact conditions`() {
        assertTrue(CrmAutomationConditions.matches(CrmCondition(CrmConditionKind.HasEmail), lead(email = "a@b.co")))
        assertFalse(CrmAutomationConditions.matches(CrmCondition(CrmConditionKind.HasEmail), lead(email = "")))
        assertFalse(CrmAutomationConditions.matches(CrmCondition(CrmConditionKind.HasEmail), lead(email = "no-es-correo")))
        assertTrue(CrmAutomationConditions.matches(CrmCondition(CrmConditionKind.HasPhone), lead(phone = "+50766668888")))
        assertFalse(CrmAutomationConditions.matches(CrmCondition(CrmConditionKind.HasPhone), lead(phone = "  ")))
    }

    @Test
    fun `field conditions compare shown values without caring about case`() {
        val custom = JsonObject(
            mapOf(
                "cf_idioma" to JsonPrimitive("Ingles"),
                "cf_vip" to JsonPrimitive(true),
                "cf_etiquetas" to JsonArray(listOf(JsonPrimitive("Rojo"), JsonPrimitive("Azul"))),
                "cf_vacio" to JsonPrimitive("  "),
            )
        )
        val l = lead(custom = custom)
        fun same(field: String, value: String) = CrmAutomationConditions.matches(CrmCondition(CrmConditionKind.FieldEquals, field = field, value = value), l)
        assertTrue(same("cf_idioma", "ingles"))
        assertFalse(same("cf_idioma", "Espanol"))
        assertTrue(same("cf_vip", "Si"))
        assertTrue(same("cf_vip", "true"))
        assertFalse(same("cf_vip", "No"))
        // Any option of a list matches, and so does the whole list.
        assertTrue(same("cf_etiquetas", "azul"))
        assertTrue(same("cf_etiquetas", "Rojo, Azul"))
        assertFalse(same("cf_etiquetas", "Verde"))
        // A field the lead never filled is different from any value and empty.
        assertFalse(same("cf_nada", "x"))
        assertTrue(CrmAutomationConditions.matches(CrmCondition(CrmConditionKind.FieldNotEquals, field = "cf_nada", value = "x"), l))
        assertTrue(CrmAutomationConditions.matches(CrmCondition(CrmConditionKind.FieldEmpty, field = "cf_nada"), l))
        assertTrue(CrmAutomationConditions.matches(CrmCondition(CrmConditionKind.FieldEmpty, field = "cf_vacio"), l))
        assertFalse(CrmAutomationConditions.matches(CrmCondition(CrmConditionKind.FieldEmpty, field = "cf_idioma"), l))
        assertTrue(CrmAutomationConditions.matches(CrmCondition(CrmConditionKind.FieldFilled, field = "cf_etiquetas"), l))
        assertFalse(CrmAutomationConditions.matches(CrmCondition(CrmConditionKind.FieldFilled, field = "cf_vacio"), l))
    }

    @Test
    fun `the first failing condition is reported and all must hold`() {
        val conditions = welcome().conditions
        assertNull(CrmAutomationConditions.failing(conditions, lead()))
        assertEquals(conditions[1], CrmAutomationConditions.failing(conditions, lead(email = "")))
        assertEquals(conditions[0], CrmAutomationConditions.failing(conditions, lead(stage = fresh.id)))
        assertNull(CrmAutomationConditions.failing(emptyList(), lead(email = "")))
        assertEquals("etapa en Booked", CrmAutomationConditions.label(conditions[0], refs))
    }

    // ---- Data the actions need ----

    private fun facts(
        email: String = "huesped@example.com", start: LocalDate? = LocalDate.of(2026, 12, 1), end: LocalDate? = LocalDate.of(2026, 12, 5),
        rooms: List<Pair<LocalDate?, LocalDate?>> = listOf(LocalDate.of(2026, 12, 1) to LocalDate.of(2026, 12, 5)),
        statuses: List<String> = emptyList(), ready: Boolean = true,
    ) = CrmLeadFacts(email, start, end, rooms, statuses, ready)

    @Test
    fun `sending a template needs a valid email`() {
        val send = CrmAction(CrmActionKind.SendTemplate, templateId = welcomeTemplate.id)
        assertEquals(emptyList(), CrmAutomationRequirements.missing(send, facts()))
        assertEquals(listOf("correo del lead"), CrmAutomationRequirements.missing(send, facts(email = "")))
        assertEquals(listOf("correo del lead"), CrmAutomationRequirements.missing(send, facts(email = "sin-arroba")))
    }

    @Test
    fun `a reservation needs dates, a chosen room and an email`() {
        val reserve = CrmAction(CrmActionKind.CloudbedsReserve)
        assertEquals(emptyList(), CrmAutomationRequirements.missing(reserve, facts()))
        assertEquals(
            listOf("fecha de ingreso", "fecha de salida", "habitacion elegida en Cloudbeds", "correo del lead"),
            CrmAutomationRequirements.missing(reserve, facts(email = "", start = null, end = null, rooms = emptyList())),
        )
        assertEquals(listOf("fecha de salida"), CrmAutomationRequirements.missing(reserve, facts(end = null)))
        // Rooms chosen for other dates than the lead's are not the lead's stay.
        val other = CrmAutomationRequirements.missing(reserve, facts(rooms = listOf(LocalDate.of(2027, 1, 1) to LocalDate.of(2027, 1, 3))))
        assertEquals(1, other.size)
        assertTrue(other.single().contains("2026-12-01 a 2026-12-05"))
        assertEquals(listOf("integracion de Cloudbeds activa"), CrmAutomationRequirements.missing(reserve, facts(ready = false)))
        // The messages use the labels of the mapped fields.
        assertEquals(listOf("llegada"), CrmAutomationRequirements.missing(reserve, facts(start = null), CrmStayLabels("llegada", "partida")))
    }

    @Test
    fun `a check-in needs a reservation that can still check in`() {
        val checkIn = CrmAction(CrmActionKind.CloudbedsCheckIn)
        val pending = "reserva de Cloudbeds pendiente de check-in"
        assertEquals(listOf(pending), CrmAutomationRequirements.missing(checkIn, facts()))
        assertEquals(emptyList(), CrmAutomationRequirements.missing(checkIn, facts(statuses = listOf("confirmed"))))
        assertEquals(emptyList(), CrmAutomationRequirements.missing(checkIn, facts(statuses = listOf("canceled", "not_confirmed"))))
        assertEquals(listOf(pending), CrmAutomationRequirements.missing(checkIn, facts(statuses = listOf("canceled", "checked_in", "checked_out", "no_show"))))
    }

    @Test
    fun `stage, field and notify actions need nothing`() {
        val nothing = facts(email = "", start = null, end = null, rooms = emptyList(), ready = false)
        listOf(CrmActionKind.SetStage, CrmActionKind.SetField, CrmActionKind.Notify).forEach { kind ->
            assertEquals(emptyList(), CrmAutomationRequirements.missing(CrmAction(kind), nothing))
        }
    }

    @Test
    fun `a chain lists what is missing once and a reserve covers the check-in after it`() {
        val chain = listOf(
            CrmAction(CrmActionKind.SendTemplate, templateId = welcomeTemplate.id),
            CrmAction(CrmActionKind.CloudbedsReserve),
            CrmAction(CrmActionKind.CloudbedsCheckIn),
            CrmAction(CrmActionKind.Notify),
        )
        // Email missing is asked by the template and by the reservation, but listed once; the check-in waits for the reservation.
        assertEquals(
            listOf("correo del lead", "fecha de ingreso", "habitacion elegida en Cloudbeds"),
            CrmAutomationRequirements.missing(chain, facts(email = "", start = null, rooms = emptyList())),
        )
        assertEquals(emptyList(), CrmAutomationRequirements.missing(chain, facts()))
        // Without the reserve before it, the check-in does need its own reservation.
        assertEquals(1, CrmAutomationRequirements.missing(listOf(chain[2]), facts()).size)
        assertEquals(1, CrmAutomationRequirements.missing(listOf(chain[2], chain[1]), facts()).size)
    }

    @Test
    fun `the missing items read as a sentence`() {
        assertEquals("", CrmAutomationRequirements.sentence(emptyList()))
        assertEquals("el correo", CrmAutomationRequirements.sentence(listOf("el correo")))
        assertEquals("a y b", CrmAutomationRequirements.sentence(listOf("a", "b")))
        assertEquals("a, b y c", CrmAutomationRequirements.sentence(listOf("a", "b", "c")))
    }

    @Test
    fun `the missing data notice says what, which lead and when`() {
        val rule = welcome()
        val (title, early) = CrmAutomationNotices.missingTexts(rule, lead(), listOf("correo del lead"), "el 2026-12-01 09:00", ran = false)
        assertEquals("Faltan datos: Bienvenida antes del check-in", title)
        assertTrue(early.contains("Maria") && early.contains("correo del lead") && early.contains("el 2026-12-01 09:00"))
        val (_, late) = CrmAutomationNotices.missingTexts(rule, lead(), listOf("correo del lead", "fecha de salida"), null, ran = true)
        assertTrue(late.contains("correo del lead y fecha de salida") && late.contains("no se ejecuto"))
    }

    // ---- Dates ----

    private val panama = ZoneId.of("America/Panama")
    private val trigger = CrmTrigger(CrmTriggerType.Date, field = "fecha_ingreso", offsetDays = -1, hour = 9)
    private val arrivalDate = LocalDate.of(2026, 12, 2)

    @Test
    fun `the run time is the offset day at the hour in the company zone`() {
        val at = CrmAutomationSchedule.runAt(arrivalDate, trigger, panama)
        assertEquals("2026-12-01T09:00-05:00[America/Panama]", at.toString())
        // The same rule in another zone is another instant.
        val tokyo = CrmAutomationSchedule.runAt(arrivalDate, trigger, ZoneId.of("Asia/Tokyo"))
        assertEquals(Instant.parse("2026-12-01T00:00:00Z"), tokyo.toInstant())
        assertEquals(Instant.parse("2026-12-01T14:00:00Z"), at.toInstant())
        // After the date: positive offsets.
        assertEquals(LocalDate.of(2026, 12, 5), CrmAutomationSchedule.runAt(arrivalDate, trigger.copy(offsetDays = 3), panama).toLocalDate())
        assertEquals(LocalDate.of(2026, 12, 2), CrmAutomationSchedule.runAt(arrivalDate, trigger.copy(offsetDays = 0, hour = 0), panama).toLocalDate())
    }

    @Test
    fun `a rule is due from its time until the catch-up day ends`() {
        val atTime = Instant.parse("2026-12-01T14:00:00Z")
        assertFalse(CrmAutomationSchedule.isDue(arrivalDate, trigger, panama, atTime.minusSeconds(1)))
        assertTrue(CrmAutomationSchedule.isDue(arrivalDate, trigger, panama, atTime))
        assertTrue(CrmAutomationSchedule.isDue(arrivalDate, trigger, panama, atTime.plusSeconds(3600 * 23)))
        assertTrue(CrmAutomationSchedule.isDue(arrivalDate, trigger, panama, atTime.plus(CrmAutomationSchedule.CatchUp).minusSeconds(1)))
        // Turning a rule on later does not fire for old dates.
        assertFalse(CrmAutomationSchedule.isDue(arrivalDate, trigger, panama, atTime.plus(CrmAutomationSchedule.CatchUp)))
        assertFalse(CrmAutomationSchedule.isDue(arrivalDate, trigger, panama, atTime.plusSeconds(3600 * 24 * 10)))
    }

    @Test
    fun `the warning comes before the run and stops at it`() {
        val warn = 2880
        val runAt = Instant.parse("2026-12-01T14:00:00Z")
        val warnAt = CrmAutomationSchedule.warnAt(arrivalDate, trigger, warn, panama)
        assertEquals(runAt.minusSeconds(2 * 24 * 3600), warnAt.toInstant())
        assertFalse(CrmAutomationSchedule.isWarnDue(arrivalDate, trigger, warn, panama, warnAt.toInstant().minusSeconds(1)))
        assertTrue(CrmAutomationSchedule.isWarnDue(arrivalDate, trigger, warn, panama, warnAt.toInstant()))
        assertTrue(CrmAutomationSchedule.isWarnDue(arrivalDate, trigger, warn, panama, runAt.minusSeconds(1)))
        // At the run time the rule itself reports what is missing.
        assertFalse(CrmAutomationSchedule.isWarnDue(arrivalDate, trigger, warn, panama, runAt))
        // 0 minutes: no warning.
        assertFalse(CrmAutomationSchedule.isWarnDue(arrivalDate, trigger, 0, panama, runAt.minusSeconds(60)))
    }

    @Test
    fun `the window of dates covers what can be due or warn today`() {
        val today = LocalDate.of(2026, 11, 30)
        // Run on the day before the date, warning 2 days ahead: dates from today up to 4 days ahead (with margin).
        val (from, to) = CrmAutomationSchedule.window(trigger, 2880, today)
        assertEquals(LocalDate.of(2026, 11, 30), from)
        assertEquals(LocalDate.of(2026, 12, 4), to)
        // Every date whose run is due now or whose warning is due now is inside.
        val now = Instant.parse("2026-11-30T20:00:00Z")
        (0L..40L).map { LocalDate.of(2026, 11, 1).plusDays(it) }.forEach { date ->
            val relevant = CrmAutomationSchedule.isDue(date, trigger, panama, now) || CrmAutomationSchedule.isWarnDue(date, trigger, 2880, panama, now)
            if (relevant) assertTrue(date in from..to, "$date fuera de $from..$to")
        }
        // Without warning and with a positive offset the window moves with it.
        val after = CrmAutomationSchedule.window(trigger.copy(offsetDays = 3), 0, today)
        assertEquals(LocalDate.of(2026, 11, 26), after.first)
        assertEquals(LocalDate.of(2026, 11, 28), after.second)
    }

    @Test
    fun `the date of a lead comes from the follow-up or a custom date field`() {
        val custom = JsonObject(mapOf("fecha_ingreso" to JsonPrimitive("2026-12-02"), "fecha_salida" to JsonPrimitive("manana")))
        val l = lead(custom = custom, followUp = LocalDate.of(2026, 12, 9))
        assertEquals(LocalDate.of(2026, 12, 2), CrmAutomationSchedule.dateValue(l, "fecha_ingreso"))
        assertNull(CrmAutomationSchedule.dateValue(l, "fecha_salida"))
        assertNull(CrmAutomationSchedule.dateValue(l, "cf_nada"))
        assertEquals(LocalDate.of(2026, 12, 9), CrmAutomationSchedule.dateValue(l, CrmAutomationRefs.FollowUpField))
        assertNull(CrmAutomationSchedule.dateValue(lead(), CrmAutomationRefs.FollowUpField))
    }

    // ---- Run keys ----

    @Test
    fun `a date rule runs once per value of the date`() {
        val recorded = mutableSetOf<String>()
        val first = CrmAutomationKeys.date(LocalDate.of(2026, 12, 2))
        assertEquals("date:2026-12-02", first)
        assertTrue(CrmAutomationKeys.isNew(recorded, first))
        recorded += first
        assertFalse(CrmAutomationKeys.isNew(recorded, first))
        // The date changed: a new key, so it runs again.
        assertTrue(CrmAutomationKeys.isNew(recorded, CrmAutomationKeys.date(LocalDate.of(2026, 12, 3))))
        // The warning has its own key: it does not use up the run, and goes out once.
        val warn = CrmAutomationKeys.warn(LocalDate.of(2026, 12, 2))
        assertEquals("warn:2026-12-02", warn)
        assertTrue(CrmAutomationKeys.isNew(recorded, warn))
        recorded += warn
        assertFalse(CrmAutomationKeys.isNew(recorded, warn))
    }

    @Test
    fun `events and button presses are different runs each time`() {
        val now = Instant.parse("2026-12-01T10:00:00Z")
        assertEquals("evt:${now.toEpochMilli()}-ab12", CrmAutomationKeys.event(now, "ab12"))
        assertTrue(CrmAutomationKeys.event(now, "ab12") != CrmAutomationKeys.event(now, "cd34"))
        assertTrue(CrmAutomationKeys.event(now, "ab12") != CrmAutomationKeys.event(now.plusMillis(1), "ab12"))
        val press = UUID.randomUUID()
        assertEquals("btn:$press", CrmAutomationKeys.button(press))
        assertTrue(CrmAutomationKeys.button(press) != CrmAutomationKeys.button(UUID.randomUUID()))
    }

    @Test
    fun `a message rule queues once a day per lead and the missing notice once a day`() {
        val monday = LocalDate.of(2026, 12, 7)
        assertEquals("msg:2026-12-07", CrmAutomationKeys.message(monday))
        assertEquals("missing:2026-12-07", CrmAutomationKeys.missing(monday))
        val recorded = setOf(CrmAutomationKeys.message(monday), CrmAutomationKeys.missing(monday))
        // Same day: the second message and the second notice are not new; the next day they are.
        assertFalse(CrmAutomationKeys.isNew(recorded, CrmAutomationKeys.message(monday)))
        assertFalse(CrmAutomationKeys.isNew(recorded, CrmAutomationKeys.missing(monday)))
        assertTrue(CrmAutomationKeys.isNew(recorded, CrmAutomationKeys.message(monday.plusDays(1))))
        assertTrue(CrmAutomationKeys.isNew(recorded, CrmAutomationKeys.missing(monday.plusDays(1))))
    }

    @Test
    fun `rules that reply to messages warn and the hourly limits are exact`() {
        val reply = welcome().copy(trigger = CrmTrigger(CrmTriggerType.MessageReceived), warnBeforeMinutes = 0)
        assertTrue(CrmAutomationRules.messageReplyNotice(reply).orEmpty().contains("una vez al dia"))
        assertNull(CrmAutomationRules.messageReplyNotice(reply.copy(actions = listOf(CrmAction(CrmActionKind.Notify)))))
        assertNull(CrmAutomationRules.messageReplyNotice(welcome()))
        assertFalse(CrmRunStates.templateLimitReached(2))
        assertTrue(CrmRunStates.templateLimitReached(3))
        assertFalse(CrmRunStates.eventLoopLimitReached(4))
        assertTrue(CrmRunStates.eventLoopLimitReached(5))
    }

    @Test
    fun `queued runs move pending to running to a final state and stuck ones are stale`() {
        assertTrue(CrmRunStates.canMove("pending", "running"))
        assertTrue(CrmRunStates.canMove("pending", "skipped"))
        assertFalse(CrmRunStates.canMove("pending", "done"))
        listOf("done", "failed", "missing_data", "skipped").forEach { assertTrue(CrmRunStates.canMove("running", it)) }
        assertFalse(CrmRunStates.canMove("running", "pending"))
        assertFalse(CrmRunStates.canMove("done", "running"))
        assertFalse(CrmRunStates.canMove("warned", "running"))
        val started = Instant.parse("2026-12-01T10:00:00Z")
        assertFalse(CrmRunStates.isStale(started, started.plusSeconds(15 * 60)))
        assertTrue(CrmRunStates.isStale(started, started.plusSeconds(15 * 60 + 1)))
        assertFalse(CrmRunStates.isStale(null, started.plusSeconds(86400)))
    }

    @Test
    fun `the due query binds its parameters in order and skips runs already recorded`() {
        val id = UUID.randomUUID()
        val from = LocalDate.of(2026, 11, 30)
        val to = LocalDate.of(2026, 12, 4)
        val custom = CrmAutomationSchedule.dueQuery("fecha_ingreso", id, from, to, 500)
        assertEquals(custom.sql.count { it == '?' }, custom.params.size)
        assertEquals(listOf<Any>("fecha_ingreso", "fecha_ingreso", from, to, id, "fecha_ingreso", 500), custom.params)
        assertTrue(custom.sql.contains("l.archived_at IS NULL"))
        assertTrue(custom.sql.contains("NOT EXISTS") && custom.sql.contains("'date:' || to_char("))
        assertTrue(custom.sql.contains("ORDER BY d") && custom.sql.endsWith("LIMIT ?"))
        val followUp = CrmAutomationSchedule.dueQuery(CrmAutomationRefs.FollowUpField, id, from, to, 50)
        assertEquals(followUp.sql.count { it == '?' }, followUp.params.size)
        assertEquals(listOf<Any>(from, to, id, 50), followUp.params)
        assertTrue(followUp.sql.contains("l.follow_up_on"))
        assertFalse(followUp.sql.contains("crm_safe_date"))
    }

    @Test
    fun `an earlier action that fills a stay date covers it`() {
        val stay = CrmStayMapping("fecha_ingreso", "fecha_salida")
        val reserve = CrmAction(CrmActionKind.CloudbedsReserve)
        val noDates = CrmLeadFacts("a@b.co", null, null, listOf(LocalDate.of(2026, 12, 1) to LocalDate.of(2026, 12, 5)))
        assertEquals(listOf("fecha de ingreso", "fecha de salida"), CrmAutomationRequirements.missing(listOf(reserve), noDates, CrmStayLabels(), stay))
        val setBoth = listOf(
            CrmAction(CrmActionKind.SetField, field = "fecha_ingreso", value = "2026-12-01"),
            CrmAction(CrmActionKind.SetField, field = "fecha_salida", value = "2026-12-05"),
            reserve,
        )
        assertEquals(emptyList(), CrmAutomationRequirements.missing(setBoth, noDates, CrmStayLabels(), stay))
        // An empty value clears the date, a field that is not mapped gives nothing and order matters.
        assertEquals(1, CrmAutomationRequirements.missing(listOf(CrmAction(CrmActionKind.SetField, field = "fecha_salida", value = ""), reserve), noDates.copy(startDate = LocalDate.of(2026, 12, 1), endDate = LocalDate.of(2026, 12, 5)), CrmStayLabels(), stay).size)
        assertEquals(2, CrmAutomationRequirements.missing(listOf(CrmAction(CrmActionKind.SetField, field = "cf_otro", value = "2026-12-01"), reserve), noDates, CrmStayLabels(), stay).size)
        assertEquals(2, CrmAutomationRequirements.missing(listOf(reserve, setBoth[0], setBoth[1]), noDates, CrmStayLabels(), stay).size)
    }

    @Test
    fun `run statuses match the table`() {
        assertEquals(setOf("pending", "running", "done", "failed", "missing_data", "warned", "skipped"), CrmAutomationStore.statusLabels.keys)
        assertEquals(setOf("done", "failed", "missing_data", "warned", "skipped"), setOf(CrmRunOutcome.Done, CrmRunOutcome.Failed, CrmRunOutcome.MissingData, CrmRunOutcome.Warned, CrmRunOutcome.Skipped))
    }

    // ---- JSON ----

    @Test
    fun `the columns of V021 are written and read back`() {
        val rule = welcome().copy(
            conditions = welcome().conditions + listOf(
                CrmCondition(CrmConditionKind.InterestIn, interestIds = listOf(surf.id), noInterest = true),
                CrmCondition(CrmConditionKind.FieldEquals, field = "cf_idioma", value = "Ingles"),
                CrmCondition(CrmConditionKind.FieldFilled, field = "cf_vip"),
            ),
            actions = listOf(
                CrmAction(CrmActionKind.SendTemplate, templateId = welcomeTemplate.id),
                CrmAction(CrmActionKind.SetStage, stageId = fresh.id),
                CrmAction(CrmActionKind.SetField, field = "cf_idioma", value = "Ingles"),
                CrmAction(CrmActionKind.Notify, who = CrmNotifyWho.Users, userIds = listOf(ana.id), title = "T", text = "X"),
                CrmAction(CrmActionKind.CloudbedsReserve),
                CrmAction(CrmActionKind.CloudbedsCheckIn),
            ),
        )
        val triggerJson = Json.parseToJsonElement(CrmAutomationJson.trigger(rule.trigger).toString()).jsonObject
        assertEquals("fecha_ingreso", (triggerJson["field"] as JsonPrimitive).content)
        assertEquals("-1", (triggerJson["offsetDays"] as JsonPrimitive).content)
        assertEquals("9", (triggerJson["hour"] as JsonPrimitive).content)
        val back = CrmAutomationJson.parseTrigger(rule.trigger.type, triggerJson, null)
        assertEquals(rule.trigger, back)
        val conditions = CrmAutomationJson.parseConditions(Json.parseToJsonElement(CrmAutomationJson.conditions(rule.conditions).toString()), null)
        assertEquals(rule.conditions, conditions)
        val actions = CrmAutomationJson.parseActions(Json.parseToJsonElement(CrmAutomationJson.actions(rule.actions).toString()), null)
        assertEquals(rule.actions, actions)
        val stage = CrmTrigger(CrmTriggerType.StageChanged, stageId = booked.id)
        assertEquals(stage, CrmAutomationJson.parseTrigger(stage.type, CrmAutomationJson.trigger(stage), null))
        assertEquals(JsonObject(emptyMap()), CrmAutomationJson.trigger(CrmTrigger(CrmTriggerType.Manual)))
    }

    @Test
    fun `agents can send names instead of ids`() {
        val actions = Json.parseToJsonElement(
            """[{"kind":"send_template","template":"bienvenida"},{"kind":"set_stage","stage":"booked"},{"kind":"set_field","field":"Idioma","value":"Ingles"},
                {"kind":"notify","who":"users","users":["ANA"],"title":"Hola"}]"""
        )
        val parsed = CrmAutomationJson.parseActions(actions, refs)
        assertEquals(welcomeTemplate.id, parsed[0].templateId)
        assertEquals(booked.id, parsed[1].stageId)
        assertEquals("cf_idioma", parsed[2].field)
        assertEquals(listOf(ana.id), parsed[3].userIds)
        val conditions = CrmAutomationJson.parseConditions(Json.parseToJsonElement("""[{"kind":"stage_in","stages":["Booked","Nuevo"]},{"kind":"interest_in","interests":["surf"],"noInterest":true},{"kind":"has_phone"}]"""), refs)
        assertEquals(listOf(booked.id, fresh.id), conditions[0].stageIds)
        assertEquals(listOf(surf.id), conditions[1].interestIds)
        assertTrue(conditions[1].noInterest)
        val date = CrmAutomationJson.parseTrigger(CrmTriggerType.Date, Json.parseToJsonElement("""{"field":"Fecha de ingreso","offsetDays":-2,"hour":8}""").jsonObject, refs)
        assertEquals(CrmTrigger(CrmTriggerType.Date, field = "fecha_ingreso", offsetDays = -2, hour = 8), date)
        val followUp = CrmAutomationJson.parseTrigger(CrmTriggerType.Date, Json.parseToJsonElement("""{"field":"Fecha de seguimiento"}""").jsonObject, refs)
        assertEquals(CrmAutomationRefs.FollowUpField, followUp.field)
        assertEquals(9, followUp.hour)
    }

    @Test
    fun `wrong json is explained`() {
        fun fails(block: () -> Unit) = assertFailsWith<CrmValidationException> { block() }.message.orEmpty()
        assertTrue(fails { CrmAutomationJson.parseActions(Json.parseToJsonElement("""[{"kind":"volar"}]"""), refs) }.contains("volar"))
        assertTrue(fails { CrmAutomationJson.parseActions(Json.parseToJsonElement("""["send_template"]"""), refs) }.contains("objeto"))
        assertTrue(fails { CrmAutomationJson.parseActions(Json.parseToJsonElement("""{"kind":"notify"}"""), refs) }.contains("lista"))
        assertTrue(fails { CrmAutomationJson.parseActions(Json.parseToJsonElement("""[{"kind":"send_template","template":"Nada"}]"""), refs) }.contains("Nada"))
        assertTrue(fails { CrmAutomationJson.parseActions(Json.parseToJsonElement("""[{"kind":"notify","who":"todos"}]"""), refs) }.contains("who"))
        assertTrue(fails { CrmAutomationJson.parseConditions(Json.parseToJsonElement("""[{"kind":"field_empty"}]"""), refs) }.contains("campo"))
        assertTrue(fails { CrmAutomationJson.parseConditions(Json.parseToJsonElement("""[{"kind":"field_empty","field":"Fecha de seguimiento"}]"""), refs) }.contains("campo"))
        assertTrue(fails { CrmAutomationJson.parseTrigger(CrmTriggerType.Date, Json.parseToJsonElement("""{"field":"fecha_ingreso","offsetDays":"x"}""").jsonObject, refs) }.contains("dias"))
        assertTrue(fails { CrmAutomationJson.parseTrigger(CrmTriggerType.StageChanged, Json.parseToJsonElement("""{"stage":"Nada"}""").jsonObject, refs) }.contains("Nada"))
    }

    @Test
    fun `summaries name the trigger and the actions`() {
        assertEquals("1 dia(s) antes de Fecha de ingreso, a las 09:00", CrmAutomationSummary.trigger(trigger, refs))
        assertEquals("El dia de Fecha de seguimiento, a las 08:00", CrmAutomationSummary.trigger(CrmTrigger(CrmTriggerType.Date, field = CrmAutomationRefs.FollowUpField, hour = 8), refs))
        assertEquals("Cuando un lead pasa a Booked", CrmAutomationSummary.trigger(CrmTrigger(CrmTriggerType.StageChanged, stageId = booked.id), refs))
        assertEquals("Enviar Bienvenida", CrmAutomationSummary.action(CrmAction(CrmActionKind.SendTemplate, templateId = welcomeTemplate.id), refs))
        assertEquals("Mensaje nuevo entrante", CrmAutomationSummary.trigger(CrmTrigger(CrmTriggerType.MessageReceived), refs))
    }

    // ---- The form ----

    @Test
    fun `a rule goes through the form and comes back the same`() {
        val rule = welcome().copy(
            conditions = welcome().conditions + CrmCondition(CrmConditionKind.FieldEquals, field = "cf_idioma", value = "Ingles") +
                CrmCondition(CrmConditionKind.InterestIn, interestIds = listOf(surf.id), noInterest = true),
            actions = listOf(
                CrmAction(CrmActionKind.SendTemplate, templateId = welcomeTemplate.id),
                CrmAction(CrmActionKind.SetField, field = "cf_idioma", value = "Ingles"),
                CrmAction(CrmActionKind.Notify, who = CrmNotifyWho.Users, userIds = listOf(ana.id, beto.id), title = "Aviso", text = "Texto"),
                CrmAction(CrmActionKind.CloudbedsReserve),
            ),
            position = 4,
        )
        val values = CrmAutomationForm.values(rule, refs)
        assertEquals("Antes de la fecha", values["dateWhen"])
        assertEquals("1", values["dateDays"])
        assertEquals("48", values["warnHours"])
        assertEquals("Fecha de ingreso", values["dateField"])
        assertEquals(rule, CrmAutomationForm.parse(values, refs, rule.id, 4))
        // A rule saved by an agent with 90 minutes shows 1.5 hours.
        assertEquals("1.5", CrmAutomationForm.hours(90))
        assertEquals("0", CrmAutomationForm.hours(0))
    }

    @Test
    fun `the form reads days before and after and ignores empty slots`() {
        val values = CrmAutomationForm.values(null, refs) + mapOf(
            "name" to "  Seguimiento  ", "triggerType" to "Fecha", "dateField" to "Fecha de seguimiento", "dateWhen" to "Despues de la fecha", "dateDays" to "3",
            "dateHour" to "14", "warnHours" to "0,5", "act1_kind" to "Avisar a usuarios", "cond1_kind" to "(ninguna)", "cond2_kind" to "",
        )
        val rule = CrmAutomationForm.parse(values, refs, UUID.randomUUID(), 0)
        assertEquals("Seguimiento", rule.name)
        assertFalse(rule.enabled)
        assertEquals(CrmTrigger(CrmTriggerType.Date, field = CrmAutomationRefs.FollowUpField, offsetDays = 3, hour = 14), rule.trigger)
        assertEquals(30, rule.warnBeforeMinutes)
        assertTrue(rule.conditions.isEmpty())
        assertEquals(listOf(CrmAction(CrmActionKind.Notify)), rule.actions)
        val on = CrmAutomationForm.parse(values + ("dateWhen" to "El dia de la fecha"), refs, UUID.randomUUID(), 0)
        assertEquals(0, on.trigger.offsetDays)
        // The warning only exists for date rules.
        val manual = CrmAutomationForm.parse(values + ("triggerType" to "Manual (boton)"), refs, UUID.randomUUID(), 0)
        assertEquals(0, manual.warnBeforeMinutes)
        assertEquals(CrmTriggerType.Manual, manual.trigger.type)
    }

    @Test
    fun `the form explains what is wrong`() {
        val base = CrmAutomationForm.values(null, refs) + mapOf("name" to "X", "act1_kind" to "Cambiar la etapa", "act1_stage" to "Booked")
        CrmAutomationForm.parse(base, refs, UUID.randomUUID(), 0)
        fun fails(extra: Map<String, String>) = assertFailsWith<CrmValidationException> { CrmAutomationForm.parse(base + extra, refs, UUID.randomUUID(), 0) }.message.orEmpty()
        assertTrue(fails(mapOf("triggerType" to "")).contains("disparador"))
        assertTrue(fails(mapOf("triggerType" to "Cambio a una etapa", "triggerStage" to "")).contains("etapa"))
        assertTrue(fails(mapOf("triggerType" to "Fecha", "dateField" to "Fecha de ingreso", "dateDays" to "abc")).contains("dias"))
        assertTrue(fails(mapOf("triggerType" to "Fecha", "dateField" to "")).contains("campo"))
        assertTrue(fails(mapOf("triggerType" to "Fecha", "dateField" to "Fecha de ingreso", "warnHours" to "-2")).contains("aviso"))
        assertTrue(fails(mapOf("act2_kind" to "Enviar una plantilla al correo del lead", "act2_template" to "")).contains("Accion 2"))
        assertTrue(fails(mapOf("cond1_kind" to "Un campo es igual a", "cond1_field" to "")).contains("Condicion 1"))
        assertTrue(fails(mapOf("act1_kind" to "Volar")).contains("Accion 1"))
        assertTrue(fails(mapOf("notifyUsers" to UiFieldValues.options(listOf("nadie")))).contains("nadie"))
    }

    @Test
    fun `slots grow with the rule and stay bounded`() {
        assertEquals(CrmAutomationForm.MinSlots, CrmAutomationForm.slots(0))
        assertEquals(CrmAutomationForm.MinSlots, CrmAutomationForm.slots(2))
        assertEquals(4, CrmAutomationForm.slots(3))
        assertEquals(CrmAutomationForm.MaxSlots, CrmAutomationForm.slots(30))
        assertNotNull(CrmAutomationForm.None)
    }
}
