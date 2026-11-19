package com.ideasdeveloper.idc.modules.eventos

import com.ideasdeveloper.idc.ui.UiButton
import com.ideasdeveloper.idc.ui.UiCard
import com.ideasdeveloper.idc.ui.UiColumn
import com.ideasdeveloper.idc.ui.UiForm
import com.ideasdeveloper.idc.ui.UiGrid
import com.ideasdeveloper.idc.ui.UiJson
import com.ideasdeveloper.idc.ui.UiList
import com.ideasdeveloper.idc.ui.UiMetric
import com.ideasdeveloper.idc.ui.UiNode
import com.ideasdeveloper.idc.ui.UiOpenModule
import com.ideasdeveloper.idc.ui.UiRequest
import com.ideasdeveloper.idc.ui.UiRow
import com.ideasdeveloper.idc.ui.UiScreen
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Pure builders of the native screens: navigation by permission, buttons by permission and state, agenda by week, labels and parsing. */
class EventosScreensTest {
    private val utc = ZoneId.of("UTC")

    // A Wednesday: its ISO week starts on Monday 2026-11-16.
    private val today = LocalDate.of(2026, 11, 18)
    private val now = Instant.parse("2026-11-18T12:00:00Z")
    private val settings = EventosSettings("USD", listOf("Sala"), listOf("Taller"))
    private val viewer = EventosScreens.Caps(register = false, manage = false)
    private val registrar = EventosScreens.Caps(register = true, manage = false)
    private val manager = EventosScreens.Caps(register = true, manage = true, import = true)

    private fun context(caps: EventosScreens.Context? = null, perms: EventosScreens.Caps = viewer, import: Boolean = false) =
        caps ?: EventosScreens.Context(perms, emptyList(), settings, today, now, importEnabled = import, zone = utc)

    private fun event(
        kind: EventKind = EventKind.Public,
        status: EventStatus = EventStatus.Published,
        startsAt: String = "2026-11-20T18:00:00Z",
        capacity: Int? = 10,
        title: String = "Taller",
        clientId: UUID? = null,
    ) = Event(
        id = UUID.randomUUID(), kind = kind, title = title, description = "", category = "", series = "", space = "Sala",
        startsAt = Instant.parse(startsAt), endsAt = null, capacity = if (kind == EventKind.Public) capacity else null, hostName = "",
        responsibleUserId = null, status = status, priceMember = null, pricePublic = null, currency = "USD",
        clientCustomerId = clientId, clientName = if (clientId != null) "Marta" else "", agreedAmount = null, guests = null, notes = "",
        importKey = null, cancelledAt = null, cancelReason = "", createdBy = "", createdAt = Instant.EPOCH, updatedAt = Instant.EPOCH,
    )

    private fun item(event: Event, occupied: Int = 0, waiting: Int = 0) = EventWithStats(event, EventStats(occupied = occupied, waiting = waiting))

    private fun registration(
        event: Event,
        status: RegistrationStatus = RegistrationStatus.Registered,
        paid: Boolean = false,
        attended: Boolean = false,
        name: String = "Ana",
    ) = Registration(
        id = UUID.randomUUID(), eventId = event.id, customerId = UUID.randomUUID(), customerName = name, status = status, seats = 1,
        priceKind = PriceKind.General, amount = BigDecimal("10.00"), paid = paid, paidAt = null, paymentNote = "",
        attendedAt = if (attended) now else null, attendedBy = "", waitlistedAt = null, note = "", cancelledAt = null, createdBy = "",
        createdAt = Instant.EPOCH, eventTitle = event.title, eventStartsAt = event.startsAt, eventCurrency = "USD",
    )

    private fun nodes(list: List<UiNode>): List<UiNode> = list.flatMap { node ->
        listOf(node) + when (node) {
            is UiCard -> nodes(node.children + node.actions)
            is UiRow -> nodes(node.children)
            is UiColumn -> nodes(node.children)
            is UiGrid -> nodes(node.children)
            is UiForm -> listOfNotNull(node.submit)
            else -> emptyList()
        }
    }

    private fun buttons(screen: UiScreen) = nodes(screen.body).filterIsInstance<UiButton>()

    private fun formIds(screen: UiScreen) = nodes(screen.body).filterIsInstance<UiForm>().map { it.id }

    // ---- navigation ----

    @Test
    fun navigationFollowsThePermissions() {
        assertEquals(listOf("proximos", "pasados"), EventosScreens.navigation(viewer, "proximos").items.map { it.id })
        assertEquals(listOf("proximos", "pasados"), EventosScreens.navigation(registrar, "proximos").items.map { it.id })
        assertEquals(
            listOf("proximos", "pasados", "nuevo", "nuevo-privado", "ajustes"),
            EventosScreens.navigation(EventosScreens.Caps(register = true, manage = true), "proximos", import = true).items.map { it.id },
        )
        // Importar needs the permission and an active source.
        assertTrue(EventosScreens.navigation(manager, "proximos", import = true).items.any { it.id == "importar" })
        assertFalse(EventosScreens.navigation(manager, "proximos", import = false).items.any { it.id == "importar" })
        assertFalse(EventosScreens.navigation(EventosScreens.Caps(register = true, manage = true), "proximos", import = true).items.any { it.id == "importar" })
    }

    @Test
    fun everyRouteAsksForTheExactPermission() {
        assertEquals(EventosModule.View, EventosActions.viewPermission("proximos"))
        assertEquals(EventosModule.View, EventosActions.viewPermission("evento"))
        assertEquals(EventosModule.Register, EventosActions.viewPermission("asistencia"))
        assertEquals(EventosModule.Manage, EventosActions.viewPermission("editar"))
        assertEquals(EventosModule.Manage, EventosActions.viewPermission("ajustes"))
        assertEquals(EventosModule.Import, EventosActions.viewPermission("importar"))
        assertEquals(EventosModule.Register, EventosActions.requiredPermission("evento", "register"))
        assertEquals(EventosModule.Manage, EventosActions.requiredPermission("evento", "status"))
        assertEquals(EventosModule.Manage, EventosActions.requiredPermission("evento", "payment-delete"))
        assertEquals(EventosModule.Register, EventosActions.requiredPermission("inscripcion", "cancel"))
        assertEquals(EventosModule.Register, EventosActions.requiredPermission("asistencia", "attend"))
        assertEquals(EventosModule.Manage, EventosActions.requiredPermission("nuevo", "create"))
        assertEquals(EventosModule.Import, EventosActions.requiredPermission("importar", "x"))
    }

    // ---- agenda ----

    @Test
    fun weekBucketsGroupByIsoWeekAndCutTheEnds() {
        val events = listOf(
            item(event(startsAt = "2026-11-15T10:00:00Z")),
            item(event(startsAt = "2026-11-23T09:00:00Z")),
            item(event(startsAt = "2026-11-22T20:00:00Z")),
            item(event(startsAt = "2026-11-16T08:00:00Z")),
            item(event(startsAt = "2026-11-18T19:00:00Z")),
            item(event(startsAt = "2027-02-07T10:00:00Z")),
            item(event(startsAt = "2027-02-08T00:00:00Z")),
        )
        val weeks = EventosScreens.weekBuckets(events, today, utc)
        // Sunday 15 is the week before; 16 to 22 share a week; Monday 23 starts the next; Feb 7 is the last day of week 12.
        assertEquals(listOf(LocalDate.of(2026, 11, 16), LocalDate.of(2026, 11, 23), LocalDate.of(2027, 2, 1)), weeks.map { it.monday })
        assertEquals(listOf(3, 1, 1), weeks.map { it.events.size })
        assertTrue(weeks[0].title.endsWith("2026-11-16 a 2026-11-22"), weeks[0].title)
        assertEquals(weeks[0].events.map { it.event.startsAt }.sorted(), weeks[0].events.map { it.event.startsAt })
    }

    @Test
    fun noEventsMakeNoWeeks() {
        assertTrue(EventosScreens.weekBuckets(emptyList(), today, utc).isEmpty())
    }

    @Test
    fun proximosShowsOneCardPerWeekAndTheFilters() {
        val events = listOf(item(event(), occupied = 3), item(event(startsAt = "2026-12-10T18:00:00Z"), occupied = 1))
        val screen = EventosScreens.proximos(context(perms = viewer), events, EventosOverview(2, 4, 1), mapOf("tipo" to "privado"))
        val weekCards = screen.body.filterIsInstance<UiCard>().filter { it.title?.startsWith("Semana") == true }
        assertEquals(2, weekCards.size)
        val form = nodes(screen.body).filterIsInstance<UiForm>().single { it.id == "filters" }
        assertEquals("Privados", form.values["tipo"])
        assertEquals(listOf("Próximos", "Pasados"), screen.navigation?.items?.map { it.label })
        assertNull(buttons(screen).firstOrNull { it.label == "Nuevo evento" })
    }

    // ---- labels ----

    @Test
    fun privateEventsUseTheirOwnStateLabelsAndShowNoCapacity() {
        val confirmed = item(event(kind = EventKind.Private, status = EventStatus.Published))
        val tentative = item(event(kind = EventKind.Private, status = EventStatus.Draft))
        assertEquals("Confirmado", EventosScreens.eventBadges(confirmed).first().label)
        assertEquals("Tentativo", EventosScreens.eventBadges(tentative).first().label)
        assertEquals("Privado", EventosScreens.occupancyLabel(confirmed))
        val public = item(event(status = EventStatus.Published), occupied = 4, waiting = 2)
        assertEquals("Publicado", EventosScreens.eventBadges(public).first().label)
        assertEquals("4/10", EventosScreens.occupancyLabel(public))
        assertTrue(EventosScreens.eventBadges(public).any { it.label == "2 en espera" })
        assertEquals("3 inscritos", EventosScreens.occupancyLabel(item(event(capacity = null), occupied = 3)))
    }

    // ---- buttons by permission and state ----

    @Test
    fun statusButtonsDependOnManageAndState() {
        assertTrue(EventosScreens.statusActions(registrar, event(status = EventStatus.Draft)).isEmpty())
        assertEquals(listOf("Publicar"), EventosScreens.statusActions(manager, event(status = EventStatus.Draft)).map { it.label })
        assertEquals(listOf("Confirmar"), EventosScreens.statusActions(manager, event(kind = EventKind.Private, status = EventStatus.Draft)).map { it.label })
        assertEquals(listOf("Marcar realizado"), EventosScreens.statusActions(manager, event(status = EventStatus.Full)).map { it.label })
        assertEquals(listOf("Reabrir"), EventosScreens.statusActions(manager, event(status = EventStatus.Cancelled)).map { it.label })
        assertTrue(EventosScreens.statusActions(manager, event(status = EventStatus.Done)).isEmpty())
        assertTrue(EventosScreens.canCancel(manager, event(status = EventStatus.Published)))
        assertFalse(EventosScreens.canCancel(manager, event(status = EventStatus.Draft)))
        assertFalse(EventosScreens.canCancel(registrar, event(status = EventStatus.Published)))
    }

    @Test
    fun registrationButtonsDependOnPermissionStateAndDate() {
        val started = event(startsAt = "2026-11-18T09:00:00Z")
        val later = event(startsAt = "2026-11-25T09:00:00Z")
        val inside = registration(started)
        assertTrue(EventosScreens.registrationActions(viewer, inside, started, now, utc).isEmpty())
        assertEquals(
            listOf("Marcar pagado", "Registrar asistencia", "Cancelar inscripción"),
            EventosScreens.registrationActions(registrar, inside, started, now, utc).map { it.label },
        )
        // Before the event day attendance cannot be marked.
        assertEquals(
            listOf("Marcar pagado", "Cancelar inscripción"),
            EventosScreens.registrationActions(registrar, registration(later), later, now, utc).map { it.label },
        )
        assertEquals(
            listOf("Marcar no pagado", "Quitar asistencia", "Cancelar inscripción"),
            EventosScreens.registrationActions(registrar, registration(started, paid = true, attended = true), started, now, utc).map { it.label },
        )
        val waiting = EventosScreens.registrationActions(registrar, registration(started, RegistrationStatus.Waitlist), started, now, utc)
        assertEquals("Pasar a inscrito", waiting.first().label)
        assertFalse(waiting.any { it.op == "attend" })
        assertTrue(EventosScreens.registrationActions(registrar, registration(started, RegistrationStatus.Cancelled), started, now, utc).isEmpty())
        // The irreversible one asks first.
        assertTrue(EventosScreens.registrationActions(registrar, inside, started, now, utc).single { it.op == "cancel" }.confirm != null)
    }

    @Test
    fun eventCardShowsTheRegistrationFormOnlyToWhoRegisters() {
        val open = event(status = EventStatus.Published)
        val detail = EventosScreens.EventDetail(item(open, occupied = 1), listOf(registration(open), registration(open, RegistrationStatus.Waitlist, name = "Luis")), emptyList())
        assertFalse("register" in formIds(EventosScreens.evento(context(perms = viewer), detail)))
        assertTrue("register" in formIds(EventosScreens.evento(context(perms = registrar), detail)))
        val form = nodes(EventosScreens.evento(context(perms = registrar), detail).body).filterIsInstance<UiForm>().single { it.id == "register" }
        assertEquals("customer", form.fields.first().key)
        assertEquals("/modules/eventos/ui/suggest/customers", form.fields.first().suggest?.route)
        assertEquals("Automático", form.values["price_kind"])
        // A draft admits no registrations, even for a manager.
        val draft = EventosScreens.EventDetail(item(event(status = EventStatus.Draft)), emptyList(), emptyList())
        assertFalse("register" in formIds(EventosScreens.evento(context(perms = manager), draft)))
        // Cancelling has its own form (reason) only for published or full events and manage.
        assertTrue("cancel" in formIds(EventosScreens.evento(context(perms = manager), detail)))
        assertFalse("cancel" in formIds(EventosScreens.evento(context(perms = registrar), detail)))
        val cancel = buttons(EventosScreens.evento(context(perms = manager), detail)).single { it.label == "Cancelar evento" }
        assertTrue((cancel.action as UiRequest).confirm != null)
        // Editing buttons need manage.
        assertTrue(buttons(EventosScreens.evento(context(perms = registrar), detail)).none { it.label == "Editar" })
        assertTrue(buttons(EventosScreens.evento(context(perms = manager), detail)).any { it.label == "Editar" })
    }

    @Test
    fun attendanceButtonAppearsWhenTheEventStarted() {
        val started = event(startsAt = "2026-11-18T09:00:00Z")
        val detail = EventosScreens.EventDetail(item(started), emptyList(), emptyList())
        assertTrue(buttons(EventosScreens.evento(context(perms = registrar), detail)).any { it.label == "Asistencia" })
        assertTrue(buttons(EventosScreens.evento(context(perms = viewer), detail)).none { it.label == "Asistencia" })
        val future = EventosScreens.EventDetail(item(event(startsAt = "2026-12-01T09:00:00Z")), emptyList(), emptyList())
        assertTrue(buttons(EventosScreens.evento(context(perms = registrar), future)).none { it.label == "Asistencia" })
    }

    @Test
    fun privateEventCardShowsClientAndPaymentsWithoutRegistrations() {
        val clientId = UUID.randomUUID()
        val base = event(kind = EventKind.Private, status = EventStatus.Published, clientId = clientId)
        val private = base.copy(agreedAmount = BigDecimal("1000"), guests = 50)
        val payment = EventPayment(UUID.randomUUID(), private.id, LocalDate.of(2026, 11, 1), BigDecimal("400"), "Efectivo", "", "", Instant.EPOCH)
        val detail = EventosScreens.EventDetail(EventWithStats(private, EventStats(paymentsTotal = BigDecimal("400"))), emptyList(), listOf(payment))
        val screen = EventosScreens.evento(context(perms = manager), detail)
        val open = buttons(screen).single { it.label == "Abrir ficha del cliente" }
        assertEquals(UiOpenModule("clientes", "customer", mapOf("id" to clientId.toString())), open.action)
        assertTrue("register" !in formIds(screen))
        assertTrue("payment" in formIds(screen))
        val metrics = nodes(screen.body).filterIsInstance<UiMetric>()
        assertEquals("600.00 USD", metrics.single { it.label == "Saldo" }.value)
        val delete = buttons(screen).single { it.label.startsWith("Borrar abono") }
        assertTrue((delete.action as UiRequest).confirm != null)
        assertEquals("payment-delete", (delete.action as UiRequest).params["op"])
        // Who only registers cannot add or delete payments.
        val registrarScreen = EventosScreens.evento(context(perms = registrar), detail)
        assertTrue("payment" !in formIds(registrarScreen))
        assertTrue(buttons(registrarScreen).none { it.label.startsWith("Borrar abono") })
    }

    @Test
    fun overPaidPrivateEventShowsTheExcess() {
        val private = event(kind = EventKind.Private, clientId = UUID.randomUUID()).copy(agreedAmount = BigDecimal("100"))
        val detail = EventosScreens.EventDetail(EventWithStats(private, EventStats(paymentsTotal = BigDecimal("130"))), emptyList(), emptyList())
        val texts = nodes(EventosScreens.evento(context(perms = viewer), detail).body).filterIsInstance<com.ideasdeveloper.idc.ui.UiText>().map { it.text }
        assertTrue(texts.any { it == "Pagado de más: 30.00 USD" }, texts.toString())
    }

    @Test
    fun registrationCardHidesTheEmailFromWhoCannotRegister() {
        val open = event(startsAt = "2026-11-18T09:00:00Z")
        val detail = EventosScreens.RegistrationDetail(registration(open), open, "ana@example.com", ActiveMembership("Mensual", LocalDate.of(2026, 12, 31)))
        val withRegister = nodes(EventosScreens.inscripcion(context(perms = registrar), detail).body).filterIsInstance<com.ideasdeveloper.idc.ui.UiText>().map { it.text }
        assertTrue(withRegister.any { it == "Correo: ana@example.com" })
        assertTrue(withRegister.any { it == "Miembro vigente: Mensual hasta 2026-12-31" })
        val withoutRegister = nodes(EventosScreens.inscripcion(context(perms = viewer), detail).body).filterIsInstance<com.ideasdeveloper.idc.ui.UiText>().map { it.text }
        assertTrue(withoutRegister.none { it.contains("ana@example.com") })
    }

    // ---- attendance ----

    @Test
    fun attendanceScreenCountsAndOffersOneButtonPerPerson() {
        val started = event(startsAt = "2026-11-18T09:00:00Z")
        val people = listOf(registration(started, attended = true, name = "Ana"), registration(started, name = "Beto"), registration(started, RegistrationStatus.Waitlist, name = "Cris"))
        val screen = EventosScreens.asistencia(context(perms = registrar), started, people)
        assertEquals("1/2", nodes(screen.body).filterIsInstance<UiMetric>().single().value)
        val cards = nodes(screen.body).filterIsInstance<UiCard>().filter { it.title != null }
        assertEquals(listOf("Ana", "Beto"), cards.map { it.title })
        assertEquals("Quitar", (cards[0].actions.single() as UiButton).label)
        assertEquals("Llegó", (cards[1].actions.single() as UiButton).label)
        assertEquals("attend", ((cards[1].actions.single() as UiButton).action as UiRequest).params["op"])
        // The event has not started: no buttons, only the reason.
        val future = event(startsAt = "2026-12-01T09:00:00Z")
        val later = EventosScreens.asistencia(context(perms = registrar), future, people)
        assertTrue(nodes(later.body).filterIsInstance<UiCard>().all { it.actions.isEmpty() })
    }

    // ---- notices ----

    @Test
    fun registeredNoticeTellsWaitlistMembershipAndFull() {
        val open = event()
        val registered = registration(open)
        val ok = EventosScreens.registeredNotice(RegistrationResult(registered, false, true, ActiveMembership("Mensual", LocalDate.of(2026, 12, 31)), "USD"))
        assertTrue(ok.first.contains("Miembro vigente: Mensual hasta 2026-12-31"))
        assertTrue(ok.first.contains("Evento lleno"))
        val waiting = EventosScreens.registeredNotice(RegistrationResult(registration(open, RegistrationStatus.Waitlist), true, false, null, "USD"))
        assertTrue(waiting.first.contains("lista de espera"))
    }

    // ---- parsing ----

    @Test
    fun scheduleParsesDateAndHoursInTheZone() {
        val (start, end) = EventosActions.schedule("2026-11-20", "18:30", "20:00", utc)
        assertEquals(Instant.parse("2026-11-20T18:30:00Z"), start)
        assertEquals(Instant.parse("2026-11-20T20:00:00Z"), end)
        assertNull(EventosActions.schedule("2026-11-20", "9:05", "", utc).second)
        assertFailsWith<EventosValidationException> { EventosActions.schedule("", "18:30", "", utc) }
        assertFailsWith<EventosValidationException> { EventosActions.schedule("2026-11-20", "", "", utc) }
        assertFailsWith<EventosValidationException> { EventosActions.schedule("2026-11-20", "25:99", "", utc) }
        assertFailsWith<EventosValidationException> { EventosActions.schedule("20/11/2026", "18:00", "", utc) }
    }

    @Test
    fun eventInputKeepsOnlyWhatBelongsToTheKind() {
        val boss = EventosUser(UUID.randomUUID(), "Laura", "laura@example.com")
        val values = mapOf(
            "title" to " Taller ", "date" to "2026-11-20", "start" to "18:00", "category" to "Sin categoría", "space" to "Sala",
            "capacity" to "20", "price_member" to "5", "price_public" to "10,50", "agreed_amount" to "900", "guests" to "30",
            "responsible" to "Laura", "customer" to "Marta", "customer_id" to UUID.randomUUID().toString(),
        )
        val public = EventosActions.eventInput(EventKind.Public, values, listOf(boss), utc)
        assertEquals("Taller", public.title)
        assertEquals("", public.category)
        assertEquals(20, public.capacity)
        assertEquals(BigDecimal("10.50"), public.pricePublic)
        assertNull(public.clientCustomerId)
        assertNull(public.agreedAmount)
        assertEquals(boss.id, public.responsibleUserId)
        val private = EventosActions.eventInput(EventKind.Private, values, listOf(boss), utc)
        assertNull(private.capacity)
        assertNull(private.priceMember)
        assertEquals(BigDecimal("900"), private.agreedAmount)
        assertEquals(30, private.guests)
        assertEquals(values["customer_id"], private.clientCustomerId.toString())
        // A client typed without choosing a suggestion is an error, and so are wrong numbers or people.
        assertFailsWith<EventosValidationException> { EventosActions.eventInput(EventKind.Private, values - "customer_id", listOf(boss), utc) }
        assertFailsWith<EventosValidationException> { EventosActions.eventInput(EventKind.Public, values + ("capacity" to "veinte"), listOf(boss), utc) }
        assertFailsWith<EventosValidationException> { EventosActions.eventInput(EventKind.Public, values + ("responsible" to "Nadie"), listOf(boss), utc) }
    }

    @Test
    fun registrationFormNeedsASuggestedCustomerOrANewOne() {
        val eventId = UUID.randomUUID()
        val customer = UUID.randomUUID()
        val (existing, none) = EventosActions.registration(
            eventId, mapOf("customer" to "Ana", "customer_id" to customer.toString(), "seats" to "2", "price_kind" to "Cortesía", "paid" to "true"),
        )
        assertEquals(customer, existing.customerId)
        assertEquals(2, existing.seats)
        assertEquals(PriceKind.Courtesy, existing.priceKind)
        assertTrue(existing.paid)
        assertNull(none)
        val (fresh, created) = EventosActions.registration(eventId, mapOf("new_name" to "Luis", "new_email" to "luis@example.com", "price_kind" to "Automático"))
        assertNull(fresh.customerId)
        assertNull(fresh.priceKind)
        assertEquals(NewCustomerInput("Luis", "luis@example.com", ""), created)
        assertFailsWith<EventosValidationException> { EventosActions.registration(eventId, emptyMap()) }
        assertFailsWith<EventosValidationException> { EventosActions.registration(eventId, mapOf("customer" to "Ana")) }
        assertFailsWith<EventosValidationException> { EventosActions.registration(eventId, mapOf("new_name" to "Luis", "seats" to "dos")) }
    }

    @Test
    fun filtersAcceptLabelsAndCodes() {
        val params = mapOf("tipo" to "Privados", "categoria" to "Todos", "espacio" to "Sala", "serie" to " Escape ", "q" to "  jazz ")
        val filter = EventosActions.filter(params, null, null, listOf(EventStatus.Published), newestFirst = false, limit = 201)
        assertEquals(EventKind.Private, filter.kind)
        assertEquals("", filter.category)
        assertEquals("Sala", filter.space)
        assertEquals("Escape", filter.series)
        assertEquals("jazz", filter.query)
        assertEquals(EventKind.Public, EventosActions.filter(mapOf("tipo" to "publico"), null, null, emptyList(), false, 10).kind)
        assertNull(EventosActions.filter(mapOf("tipo" to "Todos"), null, null, emptyList(), false, 10).kind)
    }

    @Test
    fun formBodyMustBeAJsonObject() {
        assertEquals(mapOf("a" to "1", "b" to "true"), EventosActions.formValues("""{"a":"1","b":true}"""))
        assertFailsWith<EventosValidationException> { EventosActions.formValues("no es json") }
    }

    // ---- forms and contract ----

    @Test
    fun eventFormHasTheFieldsOfItsKindAndRefillsWhatWasTyped() {
        val users = listOf(EventosUser(UUID.randomUUID(), "Laura", ""))
        val ctx = EventosScreens.Context(manager, users, settings, today, now, zone = utc)
        val publicKeys = EventosForms.eventFields(ctx, EventKind.Public, creating = true).map { it.key }
        assertTrue("capacity" in publicKeys && "price_member" in publicKeys && "customer" !in publicKeys && "publish" in publicKeys)
        val privateKeys = EventosForms.eventFields(ctx, EventKind.Private, creating = false).map { it.key }
        assertTrue("customer" in privateKeys && "agreed_amount" in privateKeys && "capacity" !in privateKeys && "publish" !in privateKeys)
        val screen = EventosForms.eventForm(ctx, EventKind.Private, null, UiNoticeForTest, mapOf("title" to "Boda"))
        val form = nodes(screen.body).filterIsInstance<UiForm>().single()
        assertEquals("Boda", form.values["title"])
        assertEquals("nuevo-privado", screen.navigation?.selected)
        assertEquals("responsible", form.fields.first { it.key == "responsible" }.key)
        assertEquals(listOf("Sin responsable", "Laura"), form.fields.first { it.key == "responsible" }.options)
    }

    @Test
    fun savedEventKeepsItsDateHoursAndClientInTheForm() {
        val clientId = UUID.randomUUID()
        val saved = event(kind = EventKind.Private, clientId = clientId, startsAt = "2026-11-20T18:05:00Z")
            .copy(endsAt = Instant.parse("2026-11-20T21:00:00Z"), space = "Terraza")
        val ctx = EventosScreens.Context(manager, emptyList(), settings, today, now, zone = utc)
        val values = EventosForms.eventValues(ctx, EventKind.Private, saved)
        assertEquals("2026-11-20", values["date"])
        assertEquals("18:05", values["start"])
        assertEquals("21:00", values["end"])
        assertEquals(clientId.toString(), values["customer_id"])
        assertEquals("Marta", values["customer"])
        // A space that left the settings is still offered, so editing does not lose it.
        assertTrue("Terraza" in EventosForms.eventFields(ctx, EventKind.Private, creating = false, current = saved).first { it.key == "space" }.options)
    }

    @Test
    fun screensSerializeWithTheUiContract() {
        val open = event()
        val detail = EventosScreens.EventDetail(item(open, occupied = 1), listOf(registration(open)), emptyList())
        val screen = EventosScreens.evento(context(perms = manager), detail)
        val json = UiJson.encodeToString(UiScreen.serializer(), screen)
        val back = UiJson.decodeFromString(UiScreen.serializer(), json)
        assertEquals(screen.title, back.title)
        assertIs<UiRow>(back.body.first())
        assertTrue(nodes(back.body).filterIsInstance<UiList>().isNotEmpty())
    }

    private val UiNoticeForTest = com.ideasdeveloper.idc.ui.UiNotice("Prueba")
}
