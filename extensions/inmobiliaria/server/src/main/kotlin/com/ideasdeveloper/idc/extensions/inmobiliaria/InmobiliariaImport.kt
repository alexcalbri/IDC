package com.ideasdeveloper.idc.extensions.inmobiliaria

import com.ideasdeveloper.idc.modules.crm.CrmActor
import com.ideasdeveloper.idc.modules.crm.CrmHost
import com.ideasdeveloper.idc.modules.crm.CrmImportField
import com.ideasdeveloper.idc.modules.crm.CrmImportKind
import com.ideasdeveloper.idc.modules.crm.CrmImportOutcome
import com.ideasdeveloper.idc.modules.crm.CrmImportRecord
import com.ideasdeveloper.idc.modules.crm.CrmRepository
import com.ideasdeveloper.idc.modules.crm.CrmScreens
import com.ideasdeveloper.idc.modules.crm.CrmUser
import com.ideasdeveloper.idc.server.modules.ModulePermissions
import java.math.BigDecimal
import java.sql.Connection
import java.text.Normalizer
import java.time.DateTimeException
import java.time.LocalDate
import java.util.UUID

/** Reglas puras de la importacion (sin base de datos): estados, etapas, fechas, nombres, booleanos y enlaces. */
object InmobiliariaImportRules {
    /** Campos de enlace del registro de propiedad y la etiqueta con la que se guardan. */
    val LinkFields = listOf(
        "link_website" to "Sitio web",
        "link_website_es" to "Sitio ES",
        "link_craigslist" to "Craigslist",
        "link_encuentra24" to "Encuentra24",
        "link_drive" to "Google Drive",
        "link_other" to "Otro",
    )

    /** Campos de partes de la transaccion y el rol de cada uno. */
    val PartyFields = listOf(
        "seller" to PartyRole.Seller,
        "seller_agent" to PartyRole.ListingAgent,
        "seller_lawyer" to PartyRole.SellerLawyer,
        "seller_referral" to PartyRole.Referral,
        "buyer" to PartyRole.Buyer,
        "buyer_agent" to PartyRole.BuyerAgent,
        "buyer_lawyer" to PartyRole.BuyerLawyer,
        "agents" to PartyRole.ListingAgent,
        "brokers" to PartyRole.Broker,
    )

    private val accents = Regex("\\p{M}+")
    private val nonAlphanumeric = Regex("[^a-z0-9]+")
    private val isoDate = Regex("^\\d{4}-\\d{2}-\\d{2}$")
    private val usDate = Regex("^\\d{1,2}/\\d{1,2}/\\d{4}$")
    private val trueValues = setOf("true", "si", "yes", "y", "1", "x", "verdadero")

    /** Minusculas, sin tildes y con cualquier separador convertido en un espacio ("For_Sale" -> "for sale"). */
    fun normalize(text: String?): String =
        Normalizer.normalize(text.orEmpty().trim().lowercase(), Normalizer.Form.NFD).replace(accents, "").replace(nonAlphanumeric, " ").trim()

    /** "true", "si", "yes", "1"... (sin distinguir mayusculas ni tildes); cualquier otra cosa es falso. */
    fun isTrue(text: String?): Boolean = normalize(text) in trueValues

    /**
     * Estado de la propiedad desde el texto de la fuente: FOR SALE y REDUCED -> En venta, SOLD -> Vendida; vacio -> En venta
     * (Captacion si [intake]). Acepta tambien los ids y etiquetas de la extension. Lanza [InmobiliariaException] si no lo reconoce.
     */
    fun statusFor(raw: String?, intake: Boolean): PropertyStatus {
        val text = normalize(raw)
        if (text.isEmpty()) return if (intake) PropertyStatus.Intake else PropertyStatus.ForSale
        return when (text) {
            "for sale", "reduced", "price reduced", "rebajada", "active", "activa" -> PropertyStatus.ForSale
            "sold", "vendida", "vendido" -> PropertyStatus.Sold
            "negotiation", "en negociacion", "under contract", "pending" -> PropertyStatus.Negotiation
            "withdrawn", "retirada", "off market" -> PropertyStatus.Withdrawn
            "intake", "captacion", "upcoming", "new listing" -> PropertyStatus.Intake
            else -> PropertyStatus.parse(raw) ?: throw InmobiliariaException("No se reconoce el estado '${raw.orEmpty().trim()}'.")
        }
    }

    /** Resultado de una etapa por su nombre: Closed/Cerrada/Won -> ganada; Canceled/Cancelada/Lost -> perdida; el resto, abierta. */
    fun stageOutcome(name: String?): DealOutcome = when (normalize(name)) {
        "closed", "cerrada", "cerrado", "won", "ganada" -> DealOutcome.Won
        "canceled", "cancelled", "cancelada", "cancelado", "lost", "perdida" -> DealOutcome.Lost
        else -> DealOutcome.Open
    }

    /** Fecha AAAA-MM-DD o MM/DD/AAAA (se ignora una hora que venga detras). Vacio = nulo; lanza [InmobiliariaException] si no se entiende. */
    fun parseDate(raw: String?, label: String = "La fecha"): LocalDate? {
        val text = raw.orEmpty().trim().takeWhile { !it.isWhitespace() && it != 'T' }
        if (text.isEmpty()) return null
        val invalid = InmobiliariaException("$label debe tener el formato AAAA-MM-DD o MM/DD/AAAA.")
        return try {
            when {
                isoDate.matches(text) -> LocalDate.parse(text)
                usDate.matches(text) -> {
                    val (month, day, year) = text.split('/')
                    LocalDate.of(year.toInt(), month.toInt(), day.toInt())
                }
                else -> throw invalid
            }
        } catch (failure: DateTimeException) {
            throw invalid
        }
    }

    /** Nombres separados por coma, sin vacios ni repetidos (sin distinguir mayusculas). */
    fun splitNames(raw: String?): List<String> =
        raw.orEmpty().split(',').map { it.trim() }.filter { it.isNotEmpty() }.distinctBy { it.lowercase() }

    /** Los enlaces importados reemplazan a los actuales con la misma etiqueta y los demas se conservan; los nuevos van al final. */
    fun mergeLinks(existing: List<LinkInput>, imported: List<LinkInput>): List<LinkInput> {
        val byLabel = imported.associateBy { it.label.lowercase() }
        val kept = existing.map { byLabel[it.label.lowercase()] ?: it }
        val keptLabels = kept.map { it.label.lowercase() }.toSet()
        return kept + imported.filter { it.label.lowercase() !in keptLabels }
    }

    /** Las notas de la fuente seguidas de lo que no se pudo enlazar (agente o vendedor desconocidos), una linea cada cosa. */
    fun composeNotes(notes: String, extra: List<String>): String = (listOf(notes.trim()) + extra).filter { it.isNotEmpty() }.joinToString("\n")

    /** Tope de registros por lote y de caracteres por valor. */
    const val MaxBatch = 500
    const val MaxValue = 5000
    const val MaxStageName = 60

    /** El valor sin espacios en los bordes y recortado a [MaxValue] caracteres. */
    fun clip(text: String?): String = text.orEmpty().trim().take(MaxValue)

    /** Estado leido de la fuente: [unknown] si no se reconocio (entonces [status] es el de por defecto). */
    data class StatusReading(val status: PropertyStatus, val unknown: Boolean)

    /** Como [statusFor] pero sin lanzar: un estado desconocido da el de por defecto (En venta o Captacion) y [StatusReading.unknown]. */
    fun readStatus(raw: String?, intake: Boolean): StatusReading =
        try {
            StatusReading(statusFor(raw, intake), false)
        } catch (failure: InmobiliariaException) {
            StatusReading(if (intake) PropertyStatus.Intake else PropertyStatus.ForSale, true)
        }

    /** Propiedad candidata a ser la misma que un registro importado (para no duplicar entre tableros). */
    data class DedupeCandidate(val id: UUID, val code: String, val title: String, val zone: String)

    /**
     * La propiedad que ya existe y es la misma que un registro sin clave conocida. Con [code]: solo la de ese codigo ([byCode],
     * buscada sin distinguir mayusculas). Sin codigo: la que tenga el mismo titulo normalizado y la misma zona normalizada, solo si es UNA.
     */
    fun pickDuplicate(code: String?, title: String, zone: String, byCode: DedupeCandidate?, titleMatches: List<DedupeCandidate>): DedupeCandidate? {
        if (!code.isNullOrEmpty()) return byCode?.takeIf { it.code.equals(code, ignoreCase = true) }
        val wantedTitle = normalize(title)
        if (wantedTitle.isEmpty()) return null
        val wantedZone = normalize(zone)
        return titleMatches.filter { normalize(it.title) == wantedTitle && normalize(it.zone) == wantedZone }.singleOrNull()
    }

    /** Etapa a usar para un nombre: una existente o una que hay que crear. */
    sealed interface StagePick {
        data class Use(val stage: DealStage, val warning: String? = null) : StagePick
        data class Create(val name: String, val outcome: DealOutcome) : StagePick
    }

    /**
     * La etapa para [rawName] (no vacio): la del mismo nombre (recortado a 60, sin distinguir mayusculas). Si no existe, una abierta
     * usa la primera abierta con un aviso, y una ganada o perdida usa la primera con ese resultado; solo se crea si no hay ninguna.
     */
    fun pickStage(stages: List<DealStage>, rawName: String): StagePick {
        val name = rawName.trim().take(MaxStageName)
        stages.firstOrNull { it.name.equals(name, ignoreCase = true) }?.let { return StagePick.Use(it) }
        val outcome = stageOutcome(name)
        val first = stages.firstOrNull { it.outcome == outcome } ?: return StagePick.Create(name, outcome)
        return StagePick.Use(first, if (outcome == DealOutcome.Open) "La etapa '$name' no existe: se uso '${first.name}'." else null)
    }

    /** Que hacer con la propiedad al crear una transaccion importada. */
    enum class DealPlan { Normal, WithoutPropertyEffects, RejectWon }

    /**
     * Una Ganada sobre una propiedad que ya tiene una ganada se rechaza. Sobre una Vendida o Retirada la transaccion se crea sin efectos
     * sobre la propiedad (no se reabre). En cualquier otro caso se aplican los efectos de la etapa.
     */
    fun planDeal(propertyStatus: PropertyStatus, outcome: DealOutcome, hasWonDeal: Boolean): DealPlan = when {
        outcome == DealOutcome.Won && hasWonDeal -> DealPlan.RejectWon
        propertyStatus == PropertyStatus.Sold || propertyStatus == PropertyStatus.Withdrawn -> DealPlan.WithoutPropertyEffects
        else -> DealPlan.Normal
    }

    /** Fecha de cierre de una venta ganada: la pedida, si no la de venta de la propiedad y, si no hay ninguna, hoy ([defaulted]). */
    data class ClosedOn(val date: LocalDate, val defaulted: Boolean)

    fun closedOnFor(requested: LocalDate?, soldOn: LocalDate?, today: LocalDate = LocalDate.now()): ClosedOn =
        (requested ?: soldOn)?.let { ClosedOn(it, false) } ?: ClosedOn(today, true)

    /** Recorta los enlaces a [max] (los primeros se conservan). */
    fun capLinks(links: List<LinkInput>, max: Int = InmobiliariaService.MaxLinks): List<LinkInput> = links.take(max)

    /**
     * Si al reimportar hay que cambiar el estado de la propiedad: no si ya es el mismo, no si el motor de transacciones lo lleva
     * (En negociacion no vuelve a En venta), nunca a Captacion (solo se aplica al crear) y no si esta vendida con una transaccion ganada.
     */
    fun shouldChangeStatus(current: PropertyStatus, wanted: PropertyStatus, hasWonDeal: Boolean): Boolean = when {
        current == wanted -> false
        wanted == PropertyStatus.Intake -> false
        current == PropertyStatus.Negotiation && wanted == PropertyStatus.ForSale -> false
        current == PropertyStatus.Sold && hasWonDeal -> false
        else -> true
    }
}

/**
 * Destino de importacion de la extension: crea o actualiza propiedades y transacciones desde otra fuente (por ejemplo monday.com),
 * con una clave estable por registro (`import_key`): reimportar actualiza, nunca duplica. Usa [InmobiliariaService], asi que valida igual
 * que las pantallas y deja historial de precios y efectos de etapa.
 *
 * La importacion corre con permisos de importacion (`crm.view`, `crm.leads.manage`, `crm.leads.all` y `crm.settings`): la LLAMANTE DEBE
 * comprobar antes que el usuario tiene `crm.settings` (cualquier extension del CRM podria llamar a este destino: esta dentro del modelo
 * de confianza de extensiones). Los registros se aplican en una transaccion por lote y cada uno en su savepoint: uno invalido va a
 * `problems` ("<importKey>: <mensaje>") y a `failedKeys` y no aborta el lote; los avisos de los guardados van a `warnings`.
 * No llama a WordPress ni a ningun servicio externo durante el lote.
 */
object InmobiliariaImport {
    const val KindProperty = "property"
    const val KindDeal = "deal"
    private const val MaxKey = 120

    /** Tipos que se pueden importar, con los campos que aceptan (los de nombre `*` son obligatorios). */
    val kinds: List<CrmImportKind> = listOf(
        CrmImportKind(
            KindProperty,
            "Propiedades",
            listOf(
                CrmImportField("title", "Titulo", required = true),
                CrmImportField("code", "Codigo", hint = "Referencia unica (ERE-0123); vacio genera uno"),
                CrmImportField("kind", "Tipo", hint = "Terreno, Casa, Condominio, Lote en desarrollo, Comercial"),
                CrmImportField("zone", "Zona"),
                CrmImportField("development", "Desarrollo"),
                CrmImportField("status", "Estado", hint = "FOR SALE, REDUCED, SOLD; vacio = En venta"),
                CrmImportField("agent", "Agente", hint = "Nombre o correo de un usuario del CRM"),
                CrmImportField("owner_name", "Vendedor (nombre)"),
                CrmImportField("owner_email", "Vendedor (correo)", hint = "Enlaza al cliente que tenga ese correo"),
                CrmImportField("owner_phone", "Vendedor (telefono)"),
                CrmImportField("lot_area", "Area del lote (m2)"),
                CrmImportField("built_area", "Area construida (m2)"),
                CrmImportField("bedrooms", "Habitaciones"),
                CrmImportField("bathrooms", "Banos"),
                CrmImportField("price", "Precio"),
                CrmImportField("previous_price", "Precio anterior"),
                CrmImportField("currency", "Moneda", hint = "Codigo de 3 letras (USD)"),
                CrmImportField("description", "Descripcion"),
                CrmImportField("notes", "Notas"),
                CrmImportField("link_website", "Enlace sitio web", hint = "Solo http o https"),
                CrmImportField("link_website_es", "Enlace sitio ES"),
                CrmImportField("link_craigslist", "Enlace Craigslist"),
                CrmImportField("link_encuentra24", "Enlace Encuentra24"),
                CrmImportField("link_drive", "Enlace Google Drive"),
                CrmImportField("link_other", "Otro enlace"),
                CrmImportField("intake", "Es captacion", hint = "true/si/yes/1 deja en Captacion si no hay estado (solo al crear)"),
            ),
        ),
        CrmImportKind(
            KindDeal,
            "Transacciones",
            listOf(
                CrmImportField("property", "Propiedad", required = true, hint = "Clave de importacion, codigo o titulo exacto"),
                CrmImportField("buyer_name", "Comprador (nombre)"),
                CrmImportField("buyer_email", "Comprador (correo)", hint = "Enlaza al lead abierto con ese correo"),
                CrmImportField("stage", "Etapa", hint = "Una abierta desconocida usa la primera abierta; Closed/Cerrada = ganada, Canceled = perdida (solo se crea si no hay ninguna)"),
                CrmImportField("agreed_price", "Precio acordado"),
                CrmImportField("commission_pct", "Comision (%)"),
                CrmImportField("commission_amount", "Comision (monto)"),
                CrmImportField("estimated_close_on", "Cierre estimado", hint = "AAAA-MM-DD o MM/DD/AAAA"),
                CrmImportField("closed_on", "Cierre real", hint = "AAAA-MM-DD o MM/DD/AAAA"),
                CrmImportField("comments", "Comentarios"),
                CrmImportField("seller", "Vendedor", hint = "Nombres separados por coma"),
                CrmImportField("seller_agent", "Agente del vendedor"),
                CrmImportField("seller_lawyer", "Abogado del vendedor"),
                CrmImportField("seller_referral", "Referido del vendedor"),
                CrmImportField("buyer", "Comprador"),
                CrmImportField("buyer_agent", "Agente del comprador"),
                CrmImportField("buyer_lawyer", "Abogado del comprador"),
                CrmImportField("agents", "Agentes", hint = "Un nombre igual al de un usuario del CRM queda como agente interno"),
                CrmImportField("brokers", "Corredores"),
            ),
        ),
    )

    private class Context(val session: InmobiliariaSession) {
        val tenant: Connection get() = session.tenant
        val users: List<CrmUser> = CrmRepository.users(session.tenant)

        /** El usuario del CRM por nombre o correo (sin distinguir mayusculas). */
        fun findUser(text: String): CrmUser? =
            users.firstOrNull { it.name.equals(text, ignoreCase = true) || (it.email.isNotEmpty() && it.email.equals(text, ignoreCase = true)) }
    }

    // Permisos de importacion: la llamante ya comprobo crm.settings. Sin filtrar por la visibilidad del usuario (crm.leads.all).
    private fun sessionFor(tenant: Connection, actor: CrmActor): InmobiliariaSession {
        val permissions = ModulePermissions(
            companyCode = "",
            accessToken = "",
            businessOwner = false,
            granted = setOf(CrmScreens.View, CrmScreens.Manage, CrmScreens.SeeAll, CrmScreens.Settings),
            userId = actor.userId,
            serverOwner = false,
        )
        return InmobiliariaSession.of(tenant, permissions, CrmRepository.scope(tenant, permissions), actor)
    }


    /**
     * Aplica un lote de [records] de tipo [kind] ([KindProperty] o [KindDeal]; las transacciones despues de las propiedades que citan).
     * Un lote de mas de [InmobiliariaImportRules.MaxBatch] registros se rechaza entero. Un registro que falla cuenta como omitido: su
     * mensaje va a `problems` y su clave a `failedKeys`. Un aviso que no impide guardarlo va a `warnings` y el registro cuenta como
     * creado o actualizado.
     */
    fun importRecords(tenant: Connection, actor: CrmActor, kind: String, records: List<CrmImportRecord>): CrmImportOutcome {
        if (kind != KindProperty && kind != KindDeal) {
            return CrmImportOutcome(0, 0, records.size, listOf("Tipo de importacion desconocido: '$kind'."), records.map { it.importKey })
        }
        if (records.size > InmobiliariaImportRules.MaxBatch) {
            return CrmImportOutcome(0, 0, records.size, listOf("Lote demasiado grande."), records.map { it.importKey })
        }
        var created = 0
        var updated = 0
        var skipped = 0
        val problems = mutableListOf<String>()
        val failedKeys = mutableListOf<String>()
        val allWarnings = mutableListOf<String>()
        InmobiliariaStore.inTransaction(tenant) {
            if (kind == KindDeal) InmobiliariaStore.seedTemplate(tenant)
            val context = Context(sessionFor(tenant, actor))
            for (record in records) {
                val key = record.importKey.trim()
                if (key.isEmpty() || key.length > MaxKey) {
                    skipped++
                    failedKeys += record.importKey
                    problems += "${key.take(40).ifEmpty { "(sin clave)" }}: la clave de importacion debe tener entre 1 y $MaxKey caracteres."
                    continue
                }
                val warnings = mutableListOf<String>()
                val values = record.values.mapValues { (_, value) -> InmobiliariaImportRules.clip(value) }
                val savepoint = tenant.setSavepoint()
                try {
                    val wasCreated = if (kind == KindProperty) importProperty(context, key, values, warnings) else importDeal(context, key, values, warnings)
                    tenant.releaseSavepoint(savepoint)
                    if (wasCreated) created++ else updated++
                    warnings.forEach { allWarnings += "$key: $it" }
                } catch (failure: Exception) {
                    runCatching { tenant.rollback(savepoint) }
                    skipped++
                    failedKeys += key
                    problems += "$key: " + (if (failure is InmobiliariaException) failure.message.orEmpty() else "No se pudo guardar el registro.")
                }
            }
        }
        return CrmImportOutcome(created, updated, skipped, problems, failedKeys, allWarnings)
    }

    // ---- Propiedades ----

    /** Los campos de una propiedad leidos y validados de un registro (sin tocar la base de datos). */
    private class ParsedProperty(
        val title: String,
        val code: String?,
        val kind: PropertyKind?,
        val zone: String,
        val development: String,
        val lot: BigDecimal?,
        val built: BigDecimal?,
        val bedrooms: Int?,
        val bathrooms: BigDecimal?,
        val price: BigDecimal?,
        val previous: BigDecimal?,
        val currency: String?,
        val description: String,
        val intake: Boolean,
        val statusText: String,
        val links: List<LinkInput>,
    )

    private fun parsePropertyFields(values: Map<String, String>, warnings: MutableList<String>): ParsedProperty {
        fun text(field: String) = values[field].orEmpty().trim()
        val title = text("title")
        if (title.isEmpty()) throw InmobiliariaException("Falta el titulo.")
        val kindText = text("kind")
        val kind = PropertyKind.parse(kindText)
        if (kindText.isNotEmpty() && kind == null) warnings += "No se reconoce el tipo '$kindText'."
        val links = mutableListOf<LinkInput>()
        for ((field, label) in InmobiliariaImportRules.LinkFields) {
            val url = text(field)
            if (url.isEmpty()) continue
            if (InmobiliariaInput.isValidUrl(url)) links += LinkInput(label, url) else warnings += "El enlace '$label' no es una direccion http o https valida y se omitio."
        }
        return ParsedProperty(
            title = title,
            code = text("code").ifEmpty { null },
            kind = kind,
            zone = text("zone"),
            development = text("development"),
            lot = InmobiliariaInput.parseArea(text("lot_area"), "El area del lote"),
            built = InmobiliariaInput.parseArea(text("built_area"), "El area construida"),
            bedrooms = InmobiliariaInput.parseBedrooms(text("bedrooms")),
            bathrooms = InmobiliariaInput.parseBathrooms(text("bathrooms")),
            price = InmobiliariaInput.parseMoney(text("price"), "El precio"),
            previous = InmobiliariaInput.parseMoney(text("previous_price"), "El precio anterior"),
            currency = text("currency").uppercase().ifEmpty { null },
            description = text("description"),
            intake = InmobiliariaImportRules.isTrue(text("intake")),
            statusText = text("status"),
            links = links,
        )
    }

    // El vendedor: el cliente con ese correo o telefono; si no hay, lo que se sepa de el queda en [extraNotes].
    private fun resolveOwner(c: Context, values: Map<String, String>, extraNotes: MutableList<String>): UUID? {
        fun text(field: String) = values[field].orEmpty().trim()
        val name = text("owner_name")
        val email = text("owner_email")
        val phone = text("owner_phone")
        val owner = if (email.isNotEmpty() || phone.isNotEmpty()) CrmRepository.matchCustomer(c.tenant, email, phone) else null
        if (owner == null) {
            val contact = listOf(name, email, phone).filter { it.isNotEmpty() }
            if (contact.isNotEmpty()) extraNotes += "Vendedor: " + contact.joinToString(" - ")
        }
        return owner
    }

    private fun candidateOf(property: Property) = InmobiliariaImportRules.DedupeCandidate(property.id, property.code, property.title, property.zone)

    // Una propiedad existente que sea la misma que el registro (por codigo o por titulo y zona), para no duplicar entre tableros.
    private fun findDuplicate(c: Context, parsed: ParsedProperty): Property? {
        val byCode = parsed.code?.let { InmobiliariaStore.findPropertyByCode(c.tenant, it) }
        val titleMatches = if (parsed.code == null) InmobiliariaStore.searchProperties(c.tenant, PropertyFilter(text = parsed.title), 50, 0).items else emptyList()
        val pick = InmobiliariaImportRules.pickDuplicate(parsed.code, parsed.title, parsed.zone, byCode?.let(::candidateOf), titleMatches.map(::candidateOf)) ?: return null
        return byCode?.takeIf { it.id == pick.id } ?: titleMatches.firstOrNull { it.id == pick.id }
    }

    private fun importProperty(c: Context, key: String, values: Map<String, String>, warnings: MutableList<String>): Boolean {
        val s = c.session
        val parsed = parsePropertyFields(values, warnings)
        var existing = InmobiliariaStore.findPropertyByImportKey(c.tenant, key)
        if (existing == null) {
            existing = findDuplicate(c, parsed)
            if (existing != null && !InmobiliariaStore.setImportKey(c.tenant, existing.id, key)) {
                warnings += "Fusionada con ${existing.code.ifEmpty { existing.title }}."
            }
        }

        val agentText = values["agent"].orEmpty().trim()
        val agent = agentText.takeIf { it.isNotEmpty() }?.let { c.findUser(it) }
        val extraNotes = mutableListOf<String>()
        if (agentText.isNotEmpty() && agent == null) extraNotes += "Agente: $agentText"
        val owner = resolveOwner(c, values, extraNotes)
        val notes = InmobiliariaImportRules.composeNotes(values["notes"].orEmpty().trim(), extraNotes)

        if (existing == null) return createImportedProperty(c, key, parsed, agent?.id, owner, notes, warnings)

        if (parsed.code != null && !parsed.code.equals(existing.code, ignoreCase = true) && InmobiliariaStore.codeTaken(c.tenant, parsed.code, existing.id)) {
            throw InmobiliariaException("El codigo ${parsed.code} ya lo tiene otra propiedad.")
        }
        // Lo que la fuente trae vacio no borra lo que ya hay.
        val fields = PropertyFields(
            code = parsed.code, title = parsed.title, kind = parsed.kind ?: existing.kind, zone = parsed.zone.ifEmpty { existing.zone },
            development = parsed.development.ifEmpty { existing.development }, listingAgentId = agent?.id ?: existing.listingAgentId,
            ownerCustomerId = owner ?: existing.ownerCustomerId, lotAreaM2 = parsed.lot ?: existing.lotAreaM2, builtAreaM2 = parsed.built ?: existing.builtAreaM2,
            bedrooms = parsed.bedrooms ?: existing.bedrooms, bathrooms = parsed.bathrooms ?: existing.bathrooms, currency = parsed.currency,
            description = parsed.description.ifEmpty { existing.description }, notes = notes.ifEmpty { existing.notes },
            wordpressListingId = existing.wordpressListingId, publishedOn = existing.publishedOn,
        )
        InmobiliariaService.updateProperty(s, existing.id, fields)
        if (parsed.price != null) InmobiliariaService.changePrice(s, existing.id, parsed.price, "Importado")
        updateImportedStatus(c, existing, parsed, warnings)
        if (parsed.links.isNotEmpty()) {
            val current = InmobiliariaStore.links(c.tenant, existing.id).map { LinkInput(it.label, it.url) }
            val merged = InmobiliariaImportRules.mergeLinks(current, parsed.links)
            val capped = InmobiliariaImportRules.capLinks(merged)
            if (capped.size < merged.size) warnings += "Una propiedad admite hasta ${InmobiliariaService.MaxLinks} enlaces: se omitieron ${merged.size - capped.size}."
            InmobiliariaService.replaceLinks(s, existing.id, capped)
        }
        return false
    }

    // El estado solo lo manda la fuente si lo trae; "Es captacion" solo vale al crear y nunca baja una propiedad a Captacion.
    private fun updateImportedStatus(c: Context, existing: Property, parsed: ParsedProperty, warnings: MutableList<String>) {
        if (parsed.statusText.isEmpty()) return
        val reading = InmobiliariaImportRules.readStatus(parsed.statusText, false)
        if (reading.unknown) {
            warnings += "No se reconoce el estado '${parsed.statusText}': se dejo el que tenia."
            return
        }
        val hasWon = InmobiliariaStore.wonDealCount(c.tenant, existing.id, null) > 0
        val current = InmobiliariaStore.findProperty(c.tenant, existing.id)?.status ?: existing.status
        if (!InmobiliariaImportRules.shouldChangeStatus(current, reading.status, hasWon)) return
        try {
            InmobiliariaService.changeStatus(c.session, existing.id, reading.status)
        } catch (failure: InmobiliariaException) {
            warnings += "No se cambio el estado: ${failure.message.orEmpty()}"
        }
    }

    private fun createImportedProperty(c: Context, key: String, parsed: ParsedProperty, agentId: UUID?, owner: UUID?, notes: String, warnings: MutableList<String>): Boolean {
        val s = c.session
        val reading = InmobiliariaImportRules.readStatus(parsed.statusText, parsed.intake)
        if (reading.unknown) warnings += "No se reconoce el estado '${parsed.statusText}': se uso ${reading.status.label}."
        if (parsed.code != null && InmobiliariaStore.codeTaken(c.tenant, parsed.code)) throw InmobiliariaException("El codigo ${parsed.code} ya lo tiene otra propiedad.")
        val fields = PropertyFields(
            code = parsed.code, title = parsed.title, kind = parsed.kind ?: PropertyKind.Other, zone = parsed.zone, development = parsed.development,
            listingAgentId = agentId, ownerCustomerId = owner, lotAreaM2 = parsed.lot, builtAreaM2 = parsed.built, bedrooms = parsed.bedrooms,
            bathrooms = parsed.bathrooms, currency = parsed.currency, description = parsed.description, notes = notes,
        )
        // Con precio anterior el historial queda anterior -> actual y la propiedad se ve Rebajada.
        val startPrice = if (parsed.previous != null && parsed.price != null && parsed.previous.compareTo(parsed.price) != 0) parsed.previous else parsed.price
        val property = InmobiliariaService.createProperty(s, fields, startPrice, reading.status, key)
        if (startPrice != parsed.price && parsed.price != null) InmobiliariaService.changePrice(s, property.id, parsed.price, "Importado")
        if (parsed.links.isNotEmpty()) InmobiliariaService.replaceLinks(s, property.id, InmobiliariaImportRules.capLinks(parsed.links))
        return true
    }

    // ---- Transacciones ----

    // La propiedad por su clave de importacion, su codigo o su titulo exacto (si es unico).
    private fun resolveProperty(c: Context, ref: String): Property {
        InmobiliariaStore.findPropertyByImportKey(c.tenant, ref)?.let { return it }
        InmobiliariaStore.findPropertyByCode(c.tenant, ref)?.let { return it }
        val matches = InmobiliariaStore.searchProperties(c.tenant, PropertyFilter(text = ref), 50, 0).items.filter { it.title.equals(ref, ignoreCase = true) }
        return when (matches.size) {
            1 -> matches.first()
            0 -> throw InmobiliariaException("No se encontro la propiedad '$ref'.")
            else -> throw InmobiliariaException("Hay varias propiedades con el titulo '$ref': usa su clave de importacion o su codigo.")
        }
    }

    // La etapa por nombre (ver InmobiliariaImportRules.pickStage); solo se crea si no hay ninguna con ese resultado.
    private fun resolveStage(c: Context, rawName: String, warnings: MutableList<String>): DealStage? {
        if (rawName.trim().isEmpty()) return null
        val stages = InmobiliariaStore.stages(c.tenant)
        when (val pick = InmobiliariaImportRules.pickStage(stages, rawName)) {
            is InmobiliariaImportRules.StagePick.Use -> {
                pick.warning?.let { warnings += it }
                return pick.stage
            }
            is InmobiliariaImportRules.StagePick.Create -> {
                InmobiliariaService.saveStages(c.session, stages.map { StageInput(it.id, it.name, it.outcome) } + StageInput(null, pick.name, pick.outcome))
                return InmobiliariaStore.stages(c.tenant).first { it.name.equals(pick.name, ignoreCase = true) }
            }
        }
    }

    private fun partiesFrom(c: Context, values: Map<String, String>): List<PartyInput> =
        InmobiliariaImportRules.PartyFields.flatMap { (field, role) ->
            InmobiliariaImportRules.splitNames(values[field]).map { name ->
                val user = c.findUser(name)
                PartyInput(role, userId = user?.id, name = if (user == null) name else "")
            }
        }.distinctBy { Triple(it.role, it.userId, it.name.lowercase()) }

    private class Buyer(val leadId: UUID?, val customerId: UUID?, val parties: List<PartyInput>)

    // Comprador: el lead abierto con ese correo si el usuario lo ve; si no, el cliente por correo o su nombre como parte.
    private fun resolveBuyer(c: Context, values: Map<String, String>): Buyer {
        val email = values["buyer_email"].orEmpty().trim()
        val name = values["buyer_name"].orEmpty().trim()
        val leadId = email.takeIf { it.isNotEmpty() }?.let { CrmHost.openLeadIdByEmail(c.tenant, it) }?.takeIf { id ->
            CrmHost.lead(c.tenant, id)?.let { lead -> c.session.scope.canSee(lead.interestId, lead.ownerUserIds) } == true
        }
        val customer = if (leadId == null && email.isNotEmpty()) CrmRepository.matchCustomer(c.tenant, email, "") else null
        val named = if (leadId == null && customer == null && name.isNotEmpty()) listOf(PartyInput(PartyRole.Buyer, name = name)) else emptyList()
        val parties = (partiesFrom(c, values) + named).distinctBy { Triple(it.role, it.userId, it.name.lowercase()) }
        return Buyer(leadId, customer, parties)
    }

    private fun importDeal(c: Context, key: String, values: Map<String, String>, warnings: MutableList<String>): Boolean {
        fun text(field: String) = values[field].orEmpty().trim()
        val s = c.session
        val existing = InmobiliariaStore.findDealByImportKey(c.tenant, key)

        val agreedPrice = InmobiliariaInput.parseMoney(text("agreed_price"), "El precio acordado")
        val commissionPct = InmobiliariaInput.parsePercent(text("commission_pct"), "El porcentaje de comision")
        val commissionAmount = InmobiliariaInput.parseMoney(text("commission_amount"), "La comision")
        val estimatedClose = InmobiliariaImportRules.parseDate(text("estimated_close_on"), "El cierre estimado")
        val closedOn = InmobiliariaImportRules.parseDate(text("closed_on"), "El cierre real")
        val stage = resolveStage(c, text("stage"), warnings)
        val buyer = resolveBuyer(c, values)

        if (existing == null) return createImportedDeal(c, key, values, stage, buyer, agreedPrice, commissionPct, commissionAmount, estimatedClose, closedOn, warnings)

        val edit = DealEdit(
            buyerLeadId = buyer.leadId ?: existing.buyerLeadId,
            buyerCustomerId = buyer.customerId ?: existing.buyerCustomerId,
            agreedPrice = agreedPrice ?: existing.agreedPrice,
            commissionPct = commissionPct ?: existing.commissionPct,
            commissionAmount = commissionAmount ?: existing.commissionAmount,
            currency = existing.currency,
            estimatedCloseOn = estimatedClose ?: existing.estimatedCloseOn,
            closedOn = existing.closedOn,
            comments = text("comments").ifEmpty { existing.comments },
        )
        InmobiliariaService.updateDeal(s, existing.id, edit, buyer.parties.ifEmpty { null })
        // Solo si cambia la etapa se aplican sus efectos; la misma etapa ganada solo ajusta la fecha de cierre.
        val sameStageNewClose = stage != null && stage.id == existing.stageId && stage.outcome == DealOutcome.Won && closedOn != null && closedOn != existing.closedOn
        if (stage != null && (stage.id != existing.stageId || sameStageNewClose)) {
            var close = closedOn
            if (stage.outcome == DealOutcome.Won && stage.id != existing.stageId) {
                val soldOn = InmobiliariaStore.findProperty(c.tenant, existing.propertyId)?.soldOn
                val resolved = InmobiliariaImportRules.closedOnFor(closedOn, soldOn)
                if (resolved.defaulted) warnings += "La venta no trae fecha de cierre: se uso hoy."
                close = resolved.date
            }
            warnings += InmobiliariaService.moveDeal(s, existing.id, stage.id, close, external = false).warnings
        }
        return false
    }

    private fun createImportedDeal(
        c: Context,
        key: String,
        values: Map<String, String>,
        stage: DealStage?,
        buyer: Buyer,
        agreedPrice: BigDecimal?,
        commissionPct: BigDecimal?,
        commissionAmount: BigDecimal?,
        estimatedClose: LocalDate?,
        closedOn: LocalDate?,
        warnings: MutableList<String>,
    ): Boolean {
        val s = c.session
        val ref = values["property"].orEmpty().trim()
        if (ref.isEmpty()) throw InmobiliariaException("Falta la propiedad.")
        val property = resolveProperty(c, ref)
        // Sin etapa el servicio usa la primera abierta.
        val outcome = stage?.outcome ?: DealOutcome.Open
        val hasWon = InmobiliariaStore.wonDealCount(c.tenant, property.id, null) > 0
        val plan = InmobiliariaImportRules.planDeal(property.status, outcome, hasWon)
        if (plan == InmobiliariaImportRules.DealPlan.RejectWon) throw InmobiliariaException("La propiedad ${property.code} ya tiene una transaccion ganada.")
        val effects = plan == InmobiliariaImportRules.DealPlan.Normal
        if (!effects) warnings += "La propiedad esta ${property.status.label.lowercase()}: la transaccion se creo sin cambiar la propiedad."
        val result = InmobiliariaService.createDeal(
            s,
            DealDraft(
                propertyId = property.id, buyerLeadId = buyer.leadId, buyerCustomerId = buyer.customerId, stageId = stage?.id, agreedPrice = agreedPrice,
                commissionPct = commissionPct, commissionAmount = commissionAmount, estimatedCloseOn = estimatedClose,
                comments = values["comments"].orEmpty().trim(), parties = buyer.parties, importKey = key,
            ),
            applyPropertyEffects = effects,
            external = false,
        )
        warnings += result.warnings
        if (outcome == DealOutcome.Won) {
            val resolved = InmobiliariaImportRules.closedOnFor(closedOn, property.soldOn)
            if (resolved.defaulted) warnings += "La venta no trae fecha de cierre: se uso hoy."
            warnings += InmobiliariaService.moveDeal(s, result.deal.id, result.deal.stageId, resolved.date, applyPropertyEffects = effects, external = false).warnings
        }
        return true
    }
}
