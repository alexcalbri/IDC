package com.ideasdeveloper.idc.modules.propietarios

import com.ideasdeveloper.idc.ui.UiButton
import com.ideasdeveloper.idc.ui.UiCard
import com.ideasdeveloper.idc.ui.UiFieldValues
import com.ideasdeveloper.idc.ui.UiFileRef
import com.ideasdeveloper.idc.ui.UiForm
import com.ideasdeveloper.idc.ui.UiJson
import com.ideasdeveloper.idc.ui.UiNode
import com.ideasdeveloper.idc.ui.UiOpenModule
import com.ideasdeveloper.idc.ui.UiOpenUrl
import com.ideasdeveloper.idc.ui.UiRequest
import com.ideasdeveloper.idc.ui.UiRow
import com.ideasdeveloper.idc.ui.UiScreen
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Pantallas puras y lectura de formularios: permisos visibles, forma del contrato de interfaz y permisos de cada accion. */
class PropietariosScreensTest {
    private val today = LocalDate.of(2026, 11, 18)

    private fun context(manage: Boolean = false, admin: Boolean = false, import: Boolean = false, customers: Boolean = false) =
        PropietariosScreens.Context(canManage = manage, canAdmin = admin, importEnabled = import, canSeeCustomers = customers, today = today)

    /** Todos los botones de una pantalla, bajando por tarjetas, filas y formularios. */
    private fun buttons(nodes: List<UiNode>): List<UiButton> = nodes.flatMap { node ->
        when (node) {
            is UiButton -> listOf(node)
            is UiRow -> buttons(node.children)
            is UiCard -> buttons(node.children) + buttons(node.actions)
            is UiForm -> listOfNotNull(node.submit)
            else -> emptyList()
        }
    }

    private fun forms(nodes: List<UiNode>): List<UiForm> = nodes.flatMap { node ->
        when (node) {
            is UiForm -> listOf(node)
            is UiCard -> forms(node.children)
            is UiRow -> forms(node.children)
            else -> emptyList()
        }
    }

    private fun summary(development: Development = PropietariosFixtures.development()) = DevelopmentSummary(development, 3, BigDecimal("750.50"), 2)

    private fun detail(lot: Lot = PropietariosFixtures.lot(), ownerships: List<Ownership> = emptyList()) =
        PropietariosScreens.LotDetail(lot, ownerships, emptyList(), emptyList())

    // ---- navegacion ----

    @Test
    fun navigationHasTheFourSectionsAndImportOnlyWhenEnabled() {
        val without = PropietariosScreens.navigation(context(), "desarrollos")
        assertEquals(listOf("desarrollos", "propietarios", "buscar"), without.items.map { it.id })
        val with = PropietariosScreens.navigation(context(import = true), "desarrollos")
        assertEquals(listOf("desarrollos", "propietarios", "buscar", "importar"), with.items.map { it.id })
        assertEquals("desarrollos", with.selected)
    }

    // ---- desarrollos ----

    @Test
    fun developmentsShowMetricsAndOnlyAdminsGetTheCreateForm() {
        val list = listOf(summary(), summary(PropietariosFixtures.development("Bosque")))
        val reader = PropietariosScreens.developments(context(), list)
        assertTrue(forms(reader.body).isEmpty())
        val admin = PropietariosScreens.developments(context(admin = true), list)
        assertEquals(listOf("development"), forms(admin.body).map { it.id })
        assertEquals("Desarrollos", admin.title)
    }

    @Test
    fun developmentScreenHidesWriteButtonsFromReaders() {
        val dev = PropietariosFixtures.development()
        val data = PropietariosScreens.DevelopmentData(dev, emptyMap(), listOf(PropietariosFixtures.lot()))
        val reader = PropietariosScreens.development(context(), data, emptyMap())
        assertFalse(buttons(reader.body).any { it.label == "Nuevo lote" })
        assertTrue(forms(reader.body).none { it.id == "development" })
        val manager = PropietariosScreens.development(context(manage = true), data, emptyMap())
        assertTrue(buttons(manager.body).any { it.label == "Nuevo lote" })
        val admin = PropietariosScreens.development(context(manage = true, admin = true), data, emptyMap())
        assertTrue(forms(admin.body).any { it.id == "development" })
        assertTrue(buttons(admin.body).any { it.label == "Archivar desarrollo" })
    }

    @Test
    fun developmentFilterListsTheStatesAndAll() {
        val data = PropietariosScreens.DevelopmentData(PropietariosFixtures.development(), emptyMap(), emptyList())
        val form = forms(PropietariosScreens.development(context(), data, mapOf("status" to "Vendido", "q" to "24")).body).first { it.id == "filters" }
        val status = form.fields.first { it.key == "status" }
        assertEquals(listOf("Todos") + LotStatus.entries.map { it.label }, status.options)
        assertEquals("Vendido", form.values["status"])
        assertEquals("24", form.values["q"])
    }

    // ---- lote ----

    @Test
    fun lotMapButtonUsesTheHttpsUrlOrTheCoordinatesAndIsAbsentWithoutBoth() {
        fun mapUrl(lot: Lot): String? =
            buttons(PropietariosScreens.lot(context(), detail(lot)).body).firstOrNull { it.label == "Ver en mapa" }?.let { (it.action as UiOpenUrl).url }
        assertEquals("https://maps.example.com/l24", mapUrl(PropietariosFixtures.lot(mapUrl = "https://maps.example.com/l24")))
        assertEquals("https://www.google.com/maps?q=9.9,-84.1", mapUrl(PropietariosFixtures.lot(latitude = BigDecimal("9.9"), longitude = BigDecimal("-84.1"))))
        assertNull(mapUrl(PropietariosFixtures.lot()))
        // Un enlace que no es https no se ofrece.
        assertNull(mapUrl(PropietariosFixtures.lot(mapUrl = "javascript:alert(1)")))
    }

    @Test
    fun lotEditingNeedsManageAndTransferButtonToo() {
        val reader = PropietariosScreens.lot(context(), detail())
        assertTrue(forms(reader.body).first { it.id == "lot" }.fields.all { it.readOnly })
        assertNull(forms(reader.body).first { it.id == "lot" }.submit)
        assertFalse(buttons(reader.body).any { it.label == "Cambiar titularidad" })
        val manager = PropietariosScreens.lot(context(manage = true), detail())
        assertNotNull(forms(manager.body).first { it.id == "lot" }.submit)
        assertTrue(buttons(manager.body).any { it.label == "Cambiar titularidad" })
        assertFalse(buttons(manager.body).any { it.label == "Archivar lote" })
        assertTrue(buttons(PropietariosScreens.lot(context(manage = true, admin = true), detail()).body).any { it.label == "Archivar lote" })
    }

    @Test
    fun anArchivedLotCannotBeEditedNorTransferred() {
        val screen = PropietariosScreens.lot(context(manage = true, admin = true), detail(PropietariosFixtures.lot(archived = true)))
        assertFalse(buttons(screen.body).any { it.label == "Cambiar titularidad" || it.label == "Archivar lote" })
        assertNull(forms(screen.body).first { it.id == "lot" }.submit)
    }

    @Test
    fun coOwnerFormNeedsManageAndTheRightToSeeCustomers() {
        fun has(context: PropietariosScreens.Context) = forms(PropietariosScreens.lot(context, detail()).body).any { it.id == "co-owner" }
        assertFalse(has(context(manage = true)))
        assertFalse(has(context(customers = true)))
        assertTrue(has(context(manage = true, customers = true)))
    }

    @Test
    fun lotShowsCurrentAndPastOwnersWithShares() {
        val current = PropietariosFixtures.ownership("Ana", share = BigDecimal("60"), startsOn = LocalDate.of(2025, 1, 1))
        val past = PropietariosFixtures.ownership("Luis", endsOn = LocalDate.of(2025, 1, 1), current = false)
        val json = UiJson.encodeToString(UiScreen.serializer(), PropietariosScreens.lot(context(), detail(ownerships = listOf(current, past))))
        assertTrue("Ana" in json)
        assertTrue("60 %" in json)
        assertTrue("Titulares anteriores" in json)
        assertTrue("Luis" in json)
    }

    @Test
    fun attachmentsFormUsesTheModuleRoutesAndIsReadOnlyForReaders() {
        val attachment = LotAttachment(UUID.randomUUID(), UUID.randomUUID(), "escritura.pdf", "", "application/pdf", 10, null, Instant.EPOCH)
        val screen = PropietariosScreens.lot(context(), PropietariosScreens.LotDetail(PropietariosFixtures.lot(), emptyList(), emptyList(), listOf(attachment)))
        val form = forms(screen.body).first { it.id == "attachments" }
        val field = form.fields.single()
        assertTrue(field.readOnly)
        assertEquals("/modules/propietarios/ui/files", field.files?.upload)
        assertEquals("/modules/propietarios/ui/files", field.files?.download)
        assertEquals(listOf(attachment.id.toString()), UiFieldValues.files(form.values["files"].orEmpty()).map { it.id })
    }

    // ---- traspaso ----

    @Test
    fun transferFormHasFourSuggestingOwnersADateAndNotes() {
        val lot = PropietariosFixtures.lot()
        val screen = PropietariosScreens.transfer(context(manage = true, customers = true), lot, emptyList())
        val form = forms(screen.body).single { it.id == "transfer" }
        val owners = form.fields.filter { it.key.startsWith("owner") }
        assertEquals(listOf("owner1", "owner2", "owner3", "owner4"), owners.map { it.key })
        assertTrue(owners.all { it.suggest?.route == "/modules/propietarios/ui/suggest/customers" })
        assertEquals(today.toString(), form.values["date"])
        assertTrue(form.fields.any { it.key == "notes" })
        val submit = form.submit!!.action as UiRequest
        assertEquals(mapOf("op" to "transfer", "lot" to lot.id.toString()), submit.params)
    }

    @Test
    fun transferWithoutTheRightToSeeCustomersShowsNoForm() {
        val screen = PropietariosScreens.transfer(context(manage = true), PropietariosFixtures.lot(), emptyList())
        assertTrue(forms(screen.body).isEmpty())
    }

    // ---- propietarios y busqueda ----

    @Test
    fun ownerScreenOffersClientesOnlyToWhoCanSeeCustomers() {
        val customer = CustomerRef(UUID.randomUUID(), "Ana Pérez", "ana@example.com")
        val owned = listOf(OwnedLot(PropietariosFixtures.ownership("Ana Pérez", customerId = customer.id), PropietariosFixtures.lot()))
        val without = PropietariosScreens.owner(context(), customer, owned)
        assertNull(buttons(without.body).firstOrNull { it.action is UiOpenModule })
        val with = PropietariosScreens.owner(context(customers = true), customer, owned)
        val open = buttons(with.body).first { it.action is UiOpenModule }.action as UiOpenModule
        assertEquals("clientes", open.moduleId)
        assertEquals("customer", open.screen)
        assertEquals(mapOf("id" to customer.id.toString()), open.params)
    }

    @Test
    fun searchScreenShowsAHintWithoutQueryAndResultsWithOne() {
        val empty = UiJson.encodeToString(UiScreen.serializer(), PropietariosScreens.search(context(), "", emptyList()))
        assertTrue("todos los desarrollos" in empty)
        val found = UiJson.encodeToString(UiScreen.serializer(), PropietariosScreens.search(context(), "24", listOf(PropietariosFixtures.lot())))
        assertTrue("Lote 24" in found)
        assertTrue("1-234567" in found)
    }

    @Test
    fun everyScreenSerializesToTheUiContract() {
        val lot = PropietariosFixtures.lot()
        val ownership = PropietariosFixtures.ownership()
        val customer = CustomerRef(UUID.randomUUID(), "Ana", "")
        val all = context(manage = true, admin = true, import = true, customers = true)
        listOf(
            PropietariosScreens.developments(all, listOf(summary())),
            PropietariosScreens.development(all, PropietariosScreens.DevelopmentData(PropietariosFixtures.development(), emptyMap(), listOf(lot)), emptyMap()),
            PropietariosScreens.newLot(all, PropietariosFixtures.development()),
            PropietariosScreens.lot(all, detail(lot, listOf(ownership))),
            PropietariosScreens.transfer(all, lot, listOf(ownership)),
            PropietariosScreens.ownership(all, ownership, lot),
            PropietariosScreens.owners(all, listOf(OwnerSummary(customer.id, "Ana", "", 1, BigDecimal("100"))), ""),
            PropietariosScreens.owner(all, customer, emptyList()),
            PropietariosScreens.search(all, "x", listOf(lot)),
        ).forEach { screen ->
            val json = UiJson.encodeToString(UiScreen.serializer(), screen)
            assertEquals(screen, UiJson.decodeFromString(UiScreen.serializer(), json), screen.title)
        }
    }

    // ---- lectura de formularios ----

    @Test
    fun newOwnersReadsUpToFourAndIgnoresEmptyRows() {
        val a = UUID.randomUUID()
        val c = UUID.randomUUID()
        val owners = PropietariosActions.newOwners(
            mapOf("owner1" to "Ana", "owner1_id" to a.toString(), "share1" to "60,5", "owner2" to " ", "owner3" to "Carla", "owner3_id" to c.toString()),
        )
        assertEquals(listOf(a, c), owners.map { it.customerId })
        assertEquals(0, BigDecimal("60.5").compareTo(owners[0].sharePercent))
        assertNull(owners[1].sharePercent)
        assertTrue(PropietariosActions.newOwners(emptyMap()).isEmpty())
    }

    @Test
    fun anOwnerNameWithoutAChosenCustomerIsAnError() {
        assertFailsWith<PropietariosValidationException> { PropietariosActions.newOwners(mapOf("owner1" to "Ana")) }
        assertFailsWith<PropietariosValidationException> { PropietariosActions.newOwners(mapOf("owner1" to "Ana", "owner1_id" to "no-id")) }
        assertFailsWith<PropietariosValidationException> { PropietariosActions.coOwner(mapOf("owner" to "Ana")) }
    }

    @Test
    fun decimalsAndDatesAreReadStrictly() {
        assertEquals(0, BigDecimal("1234.5").compareTo(PropietariosActions.decimal(" 1234,5 ", "El área")))
        assertNull(PropietariosActions.decimal("", "El área"))
        assertFailsWith<PropietariosValidationException> { PropietariosActions.decimal("abc", "El área") }
        assertEquals(today, PropietariosActions.date("2026-11-18"))
        assertNull(PropietariosActions.date(" "))
        assertFailsWith<PropietariosValidationException> { PropietariosActions.date("18/11/2026") }
    }

    @Test
    fun lotFormBecomesAValidatedInput() {
        val dev = UUID.randomUUID()
        val input = PropietariosActions.lotInput(
            dev,
            mapOf("lot_number" to " 24 ", "status" to "En construcción", "area_m2" to "300,25", "register_number" to "1-2"),
        )
        assertEquals("24", input.lotNumber)
        assertEquals(LotStatus.EnConstruccion, input.status)
        assertEquals(0, BigDecimal("300.25").compareTo(input.areaM2))
        assertFailsWith<PropietariosValidationException> { PropietariosActions.lotInput(dev, mapOf("lot_number" to "")) }
        assertFailsWith<PropietariosValidationException> { PropietariosActions.lotInput(dev, mapOf("lot_number" to "1", "status" to "otro")) }
        assertFailsWith<PropietariosValidationException> { PropietariosActions.lotInput(dev, mapOf("lot_number" to "1", "map_url" to "http://x.example.com")) }
    }

    @Test
    fun statusFilterTreatsAllAndUnknownAsNoFilter() {
        assertNull(PropietariosActions.statusFilter("Todos"))
        assertNull(PropietariosActions.statusFilter(null))
        assertNull(PropietariosActions.statusFilter("nada"))
        assertEquals(LotStatus.Reservado, PropietariosActions.statusFilter("Reservado"))
    }

    @Test
    fun fileChangesComparesTheFormWithTheLinkedFiles() {
        val kept = LotAttachment(UUID.randomUUID(), UUID.randomUUID(), "a.pdf", "", "application/pdf", 1, null, Instant.EPOCH)
        val removed = kept.copy(id = UUID.randomUUID(), name = "b.pdf")
        val fresh = UiFileRef(UUID.randomUUID().toString(), "c.pdf", "", 3)
        val values = mapOf("files" to UiFieldValues.files(listOf(UiFileRef(kept.id.toString(), "a.pdf", "", 1), fresh)))
        val changes = PropietariosActions.fileChanges(values, listOf(kept, removed))
        assertEquals(listOf(fresh.id), changes.added.map { it.id })
        assertEquals(listOf(removed.id), changes.removed)
        // Sin la clave "files" (formulario de solo lectura) no cambia nada.
        val none = PropietariosActions.fileChanges(emptyMap(), listOf(kept))
        assertTrue(none.added.isEmpty() && none.removed.isEmpty())
    }

    @Test
    fun formBodiesMustBeJsonObjects() {
        val values = PropietariosActions.formValues("""{"lot_number":"24","status":["x","y"],"n":null}""")
        assertEquals("24", values["lot_number"])
        assertEquals(listOf("x", "y"), UiFieldValues.options(values.getValue("status")))
        assertFailsWith<PropietariosValidationException> { PropietariosActions.formValues("[1,2]") }
        assertFailsWith<PropietariosValidationException> { PropietariosActions.formValues("no es json") }
    }

    // ---- permisos de cada operacion ----

    @Test
    fun everyWriteOperationAsksForTheRightPermission() {
        val required = PropietariosActions::requiredPermission
        assertEquals(PropietariosModule.View, required("desarrollos", null))
        assertEquals(PropietariosModule.Admin, required("desarrollos", "dev-save"))
        assertEquals(PropietariosModule.View, required("desarrollo", null))
        assertEquals(PropietariosModule.Admin, required("desarrollo", "dev-save"))
        assertEquals(PropietariosModule.Admin, required("desarrollo", "dev-archive"))
        assertEquals(PropietariosModule.View, required("lote", null))
        assertEquals(PropietariosModule.Manage, required("lote", "save"))
        assertEquals(PropietariosModule.Manage, required("lote", "files"))
        assertEquals(PropietariosModule.Manage, required("lote", "add-owner"))
        assertEquals(PropietariosModule.Admin, required("lote", "archive"))
        assertEquals(PropietariosModule.Manage, required("traspaso", "transfer"))
        assertEquals(PropietariosModule.Manage, required("titularidad", "save"))
        assertEquals(PropietariosModule.Import, required("importar", "x"))
        listOf("propietarios", "propietario", "buscar").forEach { assertEquals(PropietariosModule.View, required(it, null), it) }
    }
}
