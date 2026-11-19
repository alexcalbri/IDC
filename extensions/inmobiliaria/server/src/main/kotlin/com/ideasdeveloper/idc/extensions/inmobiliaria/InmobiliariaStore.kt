package com.ideasdeveloper.idc.extensions.inmobiliaria

import java.math.BigDecimal
import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.sql.SQLException
import java.sql.Types
import java.time.LocalDate
import java.util.UUID

/** Filtros de la busqueda de propiedades (todos opcionales; el inventario completo lo ve todo usuario con crm.view). */
data class PropertyFilter(
    // Texto en codigo, titulo, zona o desarrollo.
    val text: String = "",
    val zone: String = "",
    val kind: PropertyKind? = null,
    val status: PropertyStatus? = null,
    // Agente del listado.
    val agentId: UUID? = null,
    val minPrice: BigDecimal? = null,
    val maxPrice: BigDecimal? = null,
    // "Mis listados": propiedades de este usuario (agente del listado o quien las creo).
    val mineOf: UUID? = null,
)

data class PropertyPage(val total: Int, val items: List<Property>)

data class DealFilter(
    val stageId: UUID? = null,
    // Agente: es parte de la transaccion o agente del listado de la propiedad.
    val agentId: UUID? = null,
    val propertyId: UUID? = null,
    val buyerLeadId: UUID? = null,
    val openOnly: Boolean = false,
    val withEstimatedClose: Boolean = false,
)

data class DealPage(val total: Int, val items: List<Deal>)

/** Campos editables de una transaccion (el cambio de etapa va aparte). */
data class DealEdit(
    val buyerLeadId: UUID?,
    val buyerCustomerId: UUID?,
    val agreedPrice: BigDecimal?,
    val commissionPct: BigDecimal,
    val commissionAmount: BigDecimal?,
    val currency: String,
    val estimatedCloseOn: LocalDate?,
    val closedOn: LocalDate?,
    val comments: String,
) {
    companion object {
        fun of(deal: Deal) = DealEdit(
            deal.buyerLeadId, deal.buyerCustomerId, deal.agreedPrice, deal.commissionPct, deal.commissionAmount,
            deal.currency, deal.estimatedCloseOn, deal.closedOn, deal.comments,
        )
    }
}

data class PartyInput(
    val role: PartyRole,
    val userId: UUID? = null,
    val customerId: UUID? = null,
    val name: String = "",
    val sharePct: BigDecimal = BigDecimal.ZERO,
    val notes: String = "",
    // Id de una parte actual de la transaccion cuyo cliente se conserva cuando el formulario no lo trae (cliente fuera de la vista); lo resuelve el servicio.
    val keepPartyId: UUID? = null,
)

data class LinkInput(val label: String, val url: String)

/** Etapa que se guarda: con id es una existente (se renombra o cambia de resultado); sin id es nueva. */
data class StageInput(val id: UUID?, val name: String, val outcome: DealOutcome)

/**
 * Todo el SQL de la extension, siempre con PreparedStatement parametrizado sobre la [Connection] de la empresa.
 * Los nombres de tabla son constantes de este archivo, nunca entrada del usuario.
 *
 * Corre en el hilo IO que ya usan las pantallas y los manejadores MCP del CRM: son llamadas bloqueantes. El almacen
 * no revisa permisos de las personas (eso lo hace [InmobiliariaService] con [InmobiliariaAccess]); la unica excepcion es
 * la visibilidad de las transacciones, que va en el SQL de las busquedas para que ninguna lista traiga filas ajenas.
 */
object InmobiliariaStore {
    const val SettingsTable = "ext_inmobiliaria__settings"
    const val PropertiesTable = "ext_inmobiliaria__properties"
    const val LinksTable = "ext_inmobiliaria__property_links"
    const val PriceChangesTable = "ext_inmobiliaria__price_changes"
    const val StagesTable = "ext_inmobiliaria__deal_stages"
    const val DealsTable = "ext_inmobiliaria__deals"
    const val PartiesTable = "ext_inmobiliaria__deal_parties"

    const val MaxPageSize = 200

    /** Etapas de la plantilla (ERE ampliada) que se crean al activar la extension si la empresa no tiene ninguna. */
    val TemplateStages = listOf(
        StageInput(null, "Oferta", DealOutcome.Open),
        StageInput(null, "Esperando CNO", DealOutcome.Open),
        StageInput(null, "Due diligence", DealOutcome.Open),
        StageInput(null, "Cierre programado", DealOutcome.Open),
        StageInput(null, "Cerrada", DealOutcome.Won),
        StageInput(null, "Cancelada", DealOutcome.Lost),
    )

    private const val PropertyColumns =
        "id, code, title, kind, zone, development, status, listing_agent_id, owner_customer_id, lot_area_m2, built_area_m2, bedrooms, " +
            "bathrooms, price, previous_price, currency, description, notes, wordpress_listing_id, published_on, sold_on, created_by, created_at, updated_at"

    private const val DealColumns =
        "d.id, d.property_id, d.buyer_lead_id, d.buyer_customer_id, d.stage_id, d.agreed_price, d.commission_pct, d.commission_amount, d.currency, " +
            "d.estimated_close_on, d.closed_on, d.comments, d.created_by, d.created_at, d.updated_at, " +
            "s.name AS stage_name, s.outcome AS stage_outcome, p.code AS property_code, p.title AS property_title, p.listing_agent_id AS property_agent"

    private const val DealFrom =
        "FROM $DealsTable d JOIN $PropertiesTable p ON p.id = d.property_id JOIN $StagesTable s ON s.id = d.stage_id"

    // ---- Transacciones de base de datos ----

    /**
     * Corre [block] de forma atomica: si la conexion esta en autocommit abre y cierra la transaccion aqui; si el llamador
     * ya abrio una (la desinstalacion, otra operacion mayor) solo corre [block] y deja que el llamador confirme o deshaga.
     */
    fun <T> inTransaction(tenant: Connection, block: () -> T): T {
        if (!tenant.autoCommit) return block()
        tenant.autoCommit = false
        try {
            val result = block()
            tenant.commit()
            return result
        } catch (failure: Throwable) {
            runCatching { tenant.rollback() }
            throw failure
        } finally {
            tenant.autoCommit = true
        }
    }

    // ---- Auxiliares de SQL ----

    private fun PreparedStatement.uuid(index: Int, value: UUID?) = if (value == null) setNull(index, Types.OTHER) else setObject(index, value)

    private fun PreparedStatement.decimal(index: Int, value: BigDecimal?) = if (value == null) setNull(index, Types.NUMERIC) else setBigDecimal(index, value)

    private fun PreparedStatement.date(index: Int, value: LocalDate?) = if (value == null) setNull(index, Types.DATE) else setObject(index, value)

    private fun PreparedStatement.int(index: Int, value: Int?) = if (value == null) setNull(index, Types.INTEGER) else setInt(index, value)

    private fun PreparedStatement.long(index: Int, value: Long?) = if (value == null) setNull(index, Types.BIGINT) else setLong(index, value)

    // Parametros de las busquedas dinamicas: solo valores no nulos.
    private fun PreparedStatement.bindAll(params: List<Any>) {
        params.forEachIndexed { offset, value ->
            val index = offset + 1
            when (value) {
                is UUID -> setObject(index, value)
                is String -> setString(index, value)
                is BigDecimal -> setBigDecimal(index, value)
                is LocalDate -> setObject(index, value)
                is Int -> setInt(index, value)
                else -> throw IllegalArgumentException("Tipo de parametro no soportado: ${value.javaClass.name}")
            }
        }
    }

    private fun likePattern(text: String): String =
        "%" + text.trim().replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%"

    private fun uuidArray(tenant: Connection, ids: Collection<UUID>) = tenant.createArrayOf("uuid", ids.toTypedArray())

    private fun ResultSet.uuidOrNull(column: String): UUID? = getObject(column, UUID::class.java)

    private fun ResultSet.dateOrNull(column: String): LocalDate? = getObject(column, LocalDate::class.java)

    // ---- Ajustes ----

    /** Los ajustes; sin fila (extension recien instalada) son los de por defecto. */
    fun loadSettings(tenant: Connection): Settings =
        tenant.prepareStatement("SELECT currency, default_commission_pct FROM $SettingsTable WHERE id = 1").use { query ->
            query.executeQuery().use { rows ->
                if (!rows.next()) Settings() else Settings(rows.getString(1).trim(), rows.getBigDecimal(2))
            }
        }

    fun saveSettings(tenant: Connection, settings: Settings) {
        tenant.prepareStatement(
            """
            INSERT INTO $SettingsTable (id, currency, default_commission_pct, updated_at) VALUES (1, ?, ?, CURRENT_TIMESTAMP)
            ON CONFLICT (id) DO UPDATE SET currency = EXCLUDED.currency, default_commission_pct = EXCLUDED.default_commission_pct, updated_at = CURRENT_TIMESTAMP
            """.trimIndent()
        ).use { upsert ->
            upsert.setString(1, settings.currency)
            upsert.setBigDecimal(2, settings.defaultCommissionPct)
            upsert.executeUpdate()
        }
    }

    // ---- Propiedades ----

    private fun ResultSet.property() = Property(
        id = getObject("id", UUID::class.java),
        code = getString("code"),
        title = getString("title"),
        kind = PropertyKind.fromId(getString("kind")) ?: PropertyKind.Other,
        zone = getString("zone"),
        development = getString("development"),
        status = PropertyStatus.fromId(getString("status")) ?: PropertyStatus.Intake,
        listingAgentId = uuidOrNull("listing_agent_id"),
        ownerCustomerId = uuidOrNull("owner_customer_id"),
        lotAreaM2 = getBigDecimal("lot_area_m2"),
        builtAreaM2 = getBigDecimal("built_area_m2"),
        bedrooms = getInt("bedrooms").takeUnless { wasNull() },
        bathrooms = getBigDecimal("bathrooms"),
        price = getBigDecimal("price"),
        previousPrice = getBigDecimal("previous_price"),
        currency = getString("currency").trim(),
        description = getString("description"),
        notes = getString("notes"),
        wordpressListingId = getLong("wordpress_listing_id").takeUnless { wasNull() },
        publishedOn = dateOrNull("published_on"),
        soldOn = dateOrNull("sold_on"),
        createdBy = uuidOrNull("created_by"),
        createdAt = getTimestamp("created_at").toInstant(),
        updatedAt = getTimestamp("updated_at").toInstant(),
    )

    fun findProperty(tenant: Connection, id: UUID): Property? =
        tenant.prepareStatement("SELECT $PropertyColumns FROM $PropertiesTable WHERE id = ?").use { query ->
            query.setObject(1, id)
            query.executeQuery().use { rows -> if (rows.next()) rows.property() else null }
        }

    /** La propiedad con ese codigo (sin distinguir mayusculas). */
    fun findPropertyByCode(tenant: Connection, code: String): Property? =
        tenant.prepareStatement("SELECT $PropertyColumns FROM $PropertiesTable WHERE lower(code) = lower(?)").use { query ->
            query.setString(1, code)
            query.executeQuery().use { rows -> if (rows.next()) rows.property() else null }
        }

    fun findPropertyByWordPressListing(tenant: Connection, listingId: Long): Property? =
        tenant.prepareStatement("SELECT $PropertyColumns FROM $PropertiesTable WHERE wordpress_listing_id = ? ORDER BY created_at LIMIT 1").use { query ->
            query.setLong(1, listingId)
            query.executeQuery().use { rows -> if (rows.next()) rows.property() else null }
        }

    fun codeTaken(tenant: Connection, code: String, exceptId: UUID? = null): Boolean =
        tenant.prepareStatement("SELECT 1 FROM $PropertiesTable WHERE lower(code) = lower(?) AND (CAST(? AS uuid) IS NULL OR id <> CAST(? AS uuid))").use { query ->
            query.setString(1, code)
            query.uuid(2, exceptId)
            query.uuid(3, exceptId)
            query.executeQuery().use { it.next() }
        }

    /** Un codigo libre con la forma P-0001 (para las propiedades que se captan sin codigo). */
    fun nextCode(tenant: Connection): String = inTransaction(tenant) {
        // El bloqueo dura hasta el final de la transaccion: dos captaciones a la vez no toman el mismo numero.
        advisoryLock(tenant, CodeLock)
        val highest = tenant.prepareStatement("SELECT COALESCE(max(CAST(substr(code, 3) AS BIGINT)), 0) FROM $PropertiesTable WHERE upper(code) ~ '^P-[0-9]{1,9}$'").use { query ->
            query.executeQuery().use { rows -> rows.next(); rows.getLong(1) }
        }
        // Un codigo escrito a mano (P-01 contra P-0001) no choca por numero pero si por texto: se salta.
        var number = highest + 1
        var code = "P-" + number.toString().padStart(4, '0')
        while (codeTaken(tenant, code)) {
            number++
            code = "P-" + number.toString().padStart(4, '0')
        }
        code
    }

    // Constantes de los bloqueos de la extension (pg_advisory_xact_lock): codigos de propiedad y plantilla de etapas.
    private const val CodeLock = 7_281_001L
    private const val SeedLock = 7_281_002L

    private fun advisoryLock(tenant: Connection, key: Long) {
        tenant.prepareStatement("SELECT pg_advisory_xact_lock(?)").use { query ->
            query.setLong(1, key)
            query.execute()
        }
    }

    /** Bloquea la fila de la propiedad hasta el final de la transaccion (no falla si no existe: el llamador lo comprueba). */
    fun lockProperty(tenant: Connection, id: UUID) {
        tenant.prepareStatement("SELECT 1 FROM $PropertiesTable WHERE id = ? FOR UPDATE").use { query ->
            query.setObject(1, id)
            query.executeQuery().use { it.next() }
        }
    }

    /** Bloquea la transaccion y su propiedad (en ese orden, siempre el mismo) hasta el final de la transaccion de base de datos. */
    fun lockDealAndProperty(tenant: Connection, dealId: UUID) {
        val propertyId = tenant.prepareStatement("SELECT property_id FROM $DealsTable WHERE id = ? FOR UPDATE").use { query ->
            query.setObject(1, dealId)
            query.executeQuery().use { rows -> if (rows.next()) rows.getObject(1, UUID::class.java) else null }
        } ?: return
        lockProperty(tenant, propertyId)
    }

    private fun <T> uniqueCode(code: String, block: () -> T): T = try {
        block()
    } catch (failure: SQLException) {
        if (failure.sqlState == "23505") {
            // El mensaje de PostgreSQL trae el nombre del indice que choco.
            val text = failure.message.orEmpty()
            if (text.contains("properties_wordpress_uq")) throw InmobiliariaException("Ese listado del sitio web ya esta vinculado a otra propiedad.")
            if (text.contains("properties_code_uq")) throw InmobiliariaException("Ya existe una propiedad con el codigo $code.")
        }
        throw failure
    }

    /**
     * Crea la propiedad. [code] y [currency] ya vienen resueltos. Si hay [price] queda en el historial de precios (sin precio
     * anterior). Con estado En venta la fecha de publicacion es hoy si no se dio; con Vendida, la de venta es hoy.
     */
    fun createProperty(tenant: Connection, fields: PropertyFields, code: String, currency: String, price: BigDecimal?, status: PropertyStatus, createdBy: UUID?): Property {
        val id = UUID.randomUUID()
        uniqueCode(code) {
            tenant.prepareStatement(
                """
                INSERT INTO $PropertiesTable (id, code, title, kind, zone, development, status, listing_agent_id, owner_customer_id, lot_area_m2,
                    built_area_m2, bedrooms, bathrooms, price, currency, description, notes, wordpress_listing_id, published_on, sold_on, created_by)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """.trimIndent()
            ).use { insert ->
                insert.setObject(1, id)
                insert.setString(2, code)
                insert.setString(3, fields.title)
                insert.setString(4, fields.kind.id)
                insert.setString(5, fields.zone)
                insert.setString(6, fields.development)
                insert.setString(7, status.id)
                insert.uuid(8, fields.listingAgentId)
                insert.uuid(9, fields.ownerCustomerId)
                insert.decimal(10, fields.lotAreaM2)
                insert.decimal(11, fields.builtAreaM2)
                insert.int(12, fields.bedrooms)
                insert.decimal(13, fields.bathrooms)
                insert.decimal(14, price)
                insert.setString(15, currency)
                insert.setString(16, fields.description)
                insert.setString(17, fields.notes)
                insert.long(18, fields.wordpressListingId)
                insert.date(19, fields.publishedOn ?: if (status == PropertyStatus.ForSale) LocalDate.now() else null)
                insert.date(20, if (status == PropertyStatus.Sold) LocalDate.now() else null)
                insert.uuid(21, createdBy)
                insert.executeUpdate()
            }
        }
        if (price != null) recordPriceChange(tenant, id, null, price, createdBy, "Precio inicial")
        return requireNotNull(findProperty(tenant, id)) { "La propiedad recien creada no existe" }
    }

    /** Actualiza los campos descriptivos (no el precio ni el estado). [code] y [currency] ya vienen resueltos. */
    fun updateProperty(tenant: Connection, id: UUID, fields: PropertyFields, code: String, currency: String) {
        uniqueCode(code) {
            tenant.prepareStatement(
                """
                UPDATE $PropertiesTable SET code = ?, title = ?, kind = ?, zone = ?, development = ?, listing_agent_id = ?, owner_customer_id = ?,
                    lot_area_m2 = ?, built_area_m2 = ?, bedrooms = ?, bathrooms = ?, currency = ?, description = ?, notes = ?,
                    wordpress_listing_id = ?, published_on = COALESCE(?, published_on), updated_at = CURRENT_TIMESTAMP
                WHERE id = ?
                """.trimIndent()
            ).use { update ->
                update.setString(1, code)
                update.setString(2, fields.title)
                update.setString(3, fields.kind.id)
                update.setString(4, fields.zone)
                update.setString(5, fields.development)
                update.uuid(6, fields.listingAgentId)
                update.uuid(7, fields.ownerCustomerId)
                update.decimal(8, fields.lotAreaM2)
                update.decimal(9, fields.builtAreaM2)
                update.int(10, fields.bedrooms)
                update.decimal(11, fields.bathrooms)
                update.setString(12, currency)
                update.setString(13, fields.description)
                update.setString(14, fields.notes)
                update.long(15, fields.wordpressListingId)
                update.date(16, fields.publishedOn)
                update.setObject(17, id)
                update.executeUpdate()
            }
        }
    }

    private fun recordPriceChange(tenant: Connection, propertyId: UUID, oldPrice: BigDecimal?, newPrice: BigDecimal?, changedBy: UUID?, note: String) {
        tenant.prepareStatement("INSERT INTO $PriceChangesTable (id, property_id, old_price, new_price, changed_by, note) VALUES (?, ?, ?, ?, ?, ?)").use { insert ->
            insert.setObject(1, UUID.randomUUID())
            insert.setObject(2, propertyId)
            insert.decimal(3, oldPrice)
            insert.decimal(4, newPrice)
            insert.uuid(5, changedBy)
            insert.setString(6, note.take(500))
            insert.executeUpdate()
        }
    }

    /**
     * Cambia el precio: guarda el anterior en previous_price (de ahi sale "Rebajada") y deja una fila en el historial.
     * Devuelve false si el precio es el mismo (no cambia nada).
     */
    fun changePrice(tenant: Connection, id: UUID, newPrice: BigDecimal, changedBy: UUID?, note: String): Boolean = inTransaction(tenant) {
        val old = tenant.prepareStatement("SELECT price FROM $PropertiesTable WHERE id = ? FOR UPDATE").use { query ->
            query.setObject(1, id)
            query.executeQuery().use { rows ->
                if (!rows.next()) throw InmobiliariaException("La propiedad ya no existe.")
                rows.getBigDecimal(1)
            }
        }
        if (old != null && old.compareTo(newPrice) == 0) return@inTransaction false
        tenant.prepareStatement("UPDATE $PropertiesTable SET price = ?, previous_price = ?, updated_at = CURRENT_TIMESTAMP WHERE id = ?").use { update ->
            update.setBigDecimal(1, newPrice)
            update.decimal(2, old)
            update.setObject(3, id)
            update.executeUpdate()
        }
        recordPriceChange(tenant, id, old, newPrice, changedBy, note)
        true
    }

    /**
     * Cambia el estado. Vendida guarda la fecha de venta ([soldOn] o hoy); cualquier otro estado la borra. Pasar a En venta
     * fija la fecha de publicacion si aun no tenia.
     */
    fun changeStatus(tenant: Connection, id: UUID, status: PropertyStatus, soldOn: LocalDate? = null) {
        tenant.prepareStatement(
            "UPDATE $PropertiesTable SET status = ?, sold_on = ?, published_on = COALESCE(published_on, ?), updated_at = CURRENT_TIMESTAMP WHERE id = ?"
        ).use { update ->
            update.setString(1, status.id)
            update.date(2, if (status == PropertyStatus.Sold) soldOn ?: LocalDate.now() else null)
            update.date(3, if (status == PropertyStatus.ForSale) LocalDate.now() else null)
            update.setObject(4, id)
            update.executeUpdate()
        }
    }

    private fun propertyWhere(filter: PropertyFilter): Pair<String, List<Any>> {
        val conditions = mutableListOf<String>()
        val params = mutableListOf<Any>()
        if (filter.text.isNotBlank()) {
            val pattern = likePattern(filter.text)
            conditions += "(code ILIKE ? ESCAPE '\\' OR title ILIKE ? ESCAPE '\\' OR zone ILIKE ? ESCAPE '\\' OR development ILIKE ? ESCAPE '\\')"
            repeat(4) { params += pattern }
        }
        if (filter.zone.isNotBlank()) {
            conditions += "zone ILIKE ? ESCAPE '\\'"
            params += likePattern(filter.zone)
        }
        filter.kind?.let { conditions += "kind = ?"; params += it.id }
        filter.status?.let { conditions += "status = ?"; params += it.id }
        filter.agentId?.let { conditions += "listing_agent_id = ?"; params += it }
        filter.minPrice?.let { conditions += "price >= ?"; params += it }
        filter.maxPrice?.let { conditions += "price <= ?"; params += it }
        filter.mineOf?.let { conditions += "(listing_agent_id = ? OR created_by = ?)"; params += it; params += it }
        return (if (conditions.isEmpty()) "" else "WHERE " + conditions.joinToString(" AND ")) to params
    }

    /** Busca propiedades con los filtros dados, de la mas reciente a la mas antigua; [limit] se acota a 1..[MaxPageSize]. */
    fun searchProperties(tenant: Connection, filter: PropertyFilter, limit: Int, offset: Int): PropertyPage {
        val (where, params) = propertyWhere(filter)
        val total = tenant.prepareStatement("SELECT count(*) FROM $PropertiesTable $where").use { query ->
            query.bindAll(params)
            query.executeQuery().use { rows -> rows.next(); rows.getInt(1) }
        }
        val items = tenant.prepareStatement("SELECT $PropertyColumns FROM $PropertiesTable $where ORDER BY updated_at DESC, code, id LIMIT ? OFFSET ?").use { query ->
            query.bindAll(params + limit.coerceIn(1, MaxPageSize) + offset.coerceAtLeast(0))
            query.executeQuery().use { rows -> buildList { while (rows.next()) add(rows.property()) } }
        }
        return PropertyPage(total, items)
    }

    /** Zonas que ya usan las propiedades, para el filtro de la pantalla. */
    fun zones(tenant: Connection): List<String> =
        tenant.prepareStatement("SELECT DISTINCT zone FROM $PropertiesTable WHERE zone <> '' ORDER BY zone").use { query ->
            query.executeQuery().use { rows -> buildList { while (rows.next()) add(rows.getString(1)) } }
        }

    // ---- Enlaces e historial de precios ----

    fun links(tenant: Connection, propertyId: UUID): List<PropertyLink> =
        tenant.prepareStatement("SELECT id, property_id, label, url, position FROM $LinksTable WHERE property_id = ? ORDER BY position, label").use { query ->
            query.setObject(1, propertyId)
            query.executeQuery().use { rows ->
                buildList { while (rows.next()) add(PropertyLink(rows.getObject(1, UUID::class.java), rows.getObject(2, UUID::class.java), rows.getString(3), rows.getString(4), rows.getInt(5))) }
            }
        }

    /** Reemplaza la lista de enlaces de la propiedad (ya validados por el servicio), en el orden dado. */
    fun replaceLinks(tenant: Connection, propertyId: UUID, links: List<LinkInput>) = inTransaction(tenant) {
        tenant.prepareStatement("DELETE FROM $LinksTable WHERE property_id = ?").use { delete ->
            delete.setObject(1, propertyId)
            delete.executeUpdate()
        }
        tenant.prepareStatement("INSERT INTO $LinksTable (id, property_id, label, url, position) VALUES (?, ?, ?, ?, ?)").use { insert ->
            links.forEachIndexed { index, link ->
                insert.setObject(1, UUID.randomUUID())
                insert.setObject(2, propertyId)
                insert.setString(3, link.label)
                insert.setString(4, link.url)
                insert.setInt(5, index)
                insert.addBatch()
            }
            insert.executeBatch()
        }
        tenant.prepareStatement("UPDATE $PropertiesTable SET updated_at = CURRENT_TIMESTAMP WHERE id = ?").use { touch ->
            touch.setObject(1, propertyId)
            touch.executeUpdate()
        }
        Unit
    }

    /** Historial de precios, del cambio mas reciente al mas antiguo. */
    fun priceChanges(tenant: Connection, propertyId: UUID, limit: Int = 50): List<PriceChange> =
        tenant.prepareStatement("SELECT id, property_id, old_price, new_price, changed_by, changed_at, note FROM $PriceChangesTable WHERE property_id = ? ORDER BY changed_at DESC LIMIT ?").use { query ->
            query.setObject(1, propertyId)
            query.setInt(2, limit.coerceIn(1, MaxPageSize))
            query.executeQuery().use { rows ->
                buildList {
                    while (rows.next()) add(
                        PriceChange(
                            rows.getObject(1, UUID::class.java), rows.getObject(2, UUID::class.java), rows.getBigDecimal(3), rows.getBigDecimal(4),
                            rows.getObject(5, UUID::class.java), rows.getTimestamp(6).toInstant(), rows.getString(7),
                        )
                    )
                }
            }
        }

    // ---- Etapas de las transacciones ----

    fun stages(tenant: Connection): List<DealStage> =
        tenant.prepareStatement("SELECT id, name, position, outcome FROM $StagesTable ORDER BY position, name").use { query ->
            query.executeQuery().use { rows ->
                buildList { while (rows.next()) add(DealStage(rows.getObject(1, UUID::class.java), rows.getString(2), rows.getInt(3), DealOutcome.fromId(rows.getString(4)) ?: DealOutcome.Open)) }
            }
        }

    private fun stageInUse(tenant: Connection, stageId: UUID): Boolean =
        tenant.prepareStatement("SELECT 1 FROM $DealsTable WHERE stage_id = ? LIMIT 1").use { query ->
            query.setObject(1, stageId)
            query.executeQuery().use { it.next() }
        }

    /**
     * Deja las etapas exactamente como [inputs] (en ese orden): las que traen id se actualizan, las nuevas se crean y las
     * que faltan se borran. No se puede borrar una etapa que tiene transacciones.
     */
    fun saveStages(tenant: Connection, inputs: List<StageInput>) = inTransaction(tenant) {
        val existing = stages(tenant).associateBy { it.id }
        val kept = inputs.mapNotNull { it.id }.toSet()
        existing.values.filter { it.id !in kept }.forEach { stage ->
            if (stageInUse(tenant, stage.id)) throw InmobiliariaException("La etapa '${stage.name}' tiene transacciones: muevelas a otra etapa antes de borrarla.")
            tenant.prepareStatement("DELETE FROM $StagesTable WHERE id = ?").use { delete ->
                delete.setObject(1, stage.id)
                delete.executeUpdate()
            }
        }
        inputs.forEachIndexed { index, input ->
            if (input.id != null && input.id in existing) {
                val before = existing.getValue(input.id)
                if (before.outcome != input.outcome && stageInUse(tenant, before.id)) {
                    throw InmobiliariaException("La etapa '${before.name}' tiene transacciones: no se puede cambiar su resultado.")
                }
                tenant.prepareStatement("UPDATE $StagesTable SET name = ?, position = ?, outcome = ? WHERE id = ?").use { update ->
                    update.setString(1, input.name)
                    update.setInt(2, index)
                    update.setString(3, input.outcome.id)
                    update.setObject(4, input.id)
                    update.executeUpdate()
                }
            } else {
                tenant.prepareStatement("INSERT INTO $StagesTable (id, name, position, outcome) VALUES (?, ?, ?, ?)").use { insert ->
                    insert.setObject(1, input.id ?: UUID.randomUUID())
                    insert.setString(2, input.name)
                    insert.setInt(3, index)
                    insert.setString(4, input.outcome.id)
                    insert.executeUpdate()
                }
            }
        }
    }

    /** Crea las etapas de la plantilla si la empresa no tiene ninguna; devuelve true si las creo. */
    fun seedTemplate(tenant: Connection): Boolean = inTransaction(tenant) {
        // Dos primeras transacciones a la vez no deben sembrar la plantilla dos veces.
        advisoryLock(tenant, SeedLock)
        if (stages(tenant).isNotEmpty()) return@inTransaction false
        saveStages(tenant, TemplateStages)
        true
    }

    // ---- Transacciones ----

    private fun ResultSet.deal() = Deal(
        id = getObject("id", UUID::class.java),
        propertyId = getObject("property_id", UUID::class.java),
        buyerLeadId = uuidOrNull("buyer_lead_id"),
        buyerCustomerId = uuidOrNull("buyer_customer_id"),
        stageId = getObject("stage_id", UUID::class.java),
        agreedPrice = getBigDecimal("agreed_price"),
        commissionPct = getBigDecimal("commission_pct"),
        commissionAmount = getBigDecimal("commission_amount"),
        currency = getString("currency").trim(),
        estimatedCloseOn = dateOrNull("estimated_close_on"),
        closedOn = dateOrNull("closed_on"),
        comments = getString("comments"),
        createdBy = uuidOrNull("created_by"),
        createdAt = getTimestamp("created_at").toInstant(),
        updatedAt = getTimestamp("updated_at").toInstant(),
        stageName = getString("stage_name"),
        stageOutcome = DealOutcome.fromId(getString("stage_outcome")) ?: DealOutcome.Open,
        propertyCode = getString("property_code"),
        propertyTitle = getString("property_title"),
        propertyListingAgentId = uuidOrNull("property_agent"),
    )

    private fun withParties(tenant: Connection, deals: List<Deal>): List<Deal> {
        if (deals.isEmpty()) return deals
        val parties = partiesOf(tenant, deals.map { it.id }).groupBy { it.dealId }
        return deals.map { it.copy(parties = parties[it.id].orEmpty()) }
    }

    /** Una transaccion con sus partes, sin filtrar por visibilidad (el servicio la revisa con InmobiliariaAccess.canSeeDeal). */
    fun findDeal(tenant: Connection, id: UUID): Deal? {
        val deal = tenant.prepareStatement("SELECT $DealColumns $DealFrom WHERE d.id = ?").use { query ->
            query.setObject(1, id)
            query.executeQuery().use { rows -> if (rows.next()) rows.deal() else null }
        }
        return deal?.let { withParties(tenant, listOf(it)).first() }
    }

    private fun partyOfUser(alias: String) = "EXISTS (SELECT 1 FROM $PartiesTable x WHERE x.deal_id = $alias.id AND x.user_id = ?)"

    // La visibilidad va en el SQL: quien no ve todo, ve las transacciones que creo, en las que es parte o cuya propiedad lista.
    private fun dealWhere(access: InmobiliariaAccess, filter: DealFilter): Pair<String, List<Any>> {
        val conditions = mutableListOf<String>()
        val params = mutableListOf<Any>()
        if (!access.seesAll) {
            val user = access.userId
            if (user == null) {
                conditions += "FALSE"
            } else {
                conditions += "(d.created_by = ? OR ${partyOfUser("d")} OR p.listing_agent_id = ?)"
                params += user
                params += user
                params += user
            }
        }
        filter.stageId?.let { conditions += "d.stage_id = ?"; params += it }
        filter.agentId?.let { conditions += "(${partyOfUser("d")} OR p.listing_agent_id = ?)"; params += it; params += it }
        filter.propertyId?.let { conditions += "d.property_id = ?"; params += it }
        filter.buyerLeadId?.let { conditions += "d.buyer_lead_id = ?"; params += it }
        if (filter.openOnly) conditions += "s.outcome = 'open'"
        if (filter.withEstimatedClose) conditions += "d.estimated_close_on IS NOT NULL"
        return (if (conditions.isEmpty()) "" else "WHERE " + conditions.joinToString(" AND ")) to params
    }

    /** Las transacciones que el usuario ve segun [access], abiertas primero y por fecha de cierre estimada, con sus partes. */
    fun listDeals(tenant: Connection, access: InmobiliariaAccess, filter: DealFilter, limit: Int, offset: Int): DealPage {
        val (where, params) = dealWhere(access, filter)
        val total = tenant.prepareStatement("SELECT count(*) $DealFrom $where").use { query ->
            query.bindAll(params)
            query.executeQuery().use { rows -> rows.next(); rows.getInt(1) }
        }
        val items = tenant.prepareStatement(
            "SELECT $DealColumns $DealFrom $where ORDER BY (s.outcome = 'open') DESC, d.estimated_close_on ASC NULLS LAST, d.created_at DESC, d.id LIMIT ? OFFSET ?"
        ).use { query ->
            query.bindAll(params + limit.coerceIn(1, MaxPageSize) + offset.coerceAtLeast(0))
            query.executeQuery().use { rows -> buildList { while (rows.next()) add(rows.deal()) } }
        }
        return DealPage(total, withParties(tenant, items))
    }

    /**
     * Cierres estimados por mes de las transacciones abiertas que [access] ve ([agentId]: solo las de ese agente), sumados en SQL
     * sobre la misma condicion de visibilidad que [listDeals] y sin limite de filas. De menor a mayor mes.
     */
    fun closingsByMonth(tenant: Connection, access: InmobiliariaAccess, agentId: UUID?): List<ClosingMonth> {
        val (where, params) = dealWhere(access, DealFilter(agentId = agentId, openOnly = true, withEstimatedClose = true))
        return tenant.prepareStatement(
            """
            SELECT to_char(d.estimated_close_on, 'YYYY-MM') AS month, d.currency, count(*) AS total,
                COALESCE(sum(COALESCE(d.agreed_price, 0)), 0) AS total_price,
                COALESCE(sum(COALESCE(d.commission_amount, round(d.agreed_price * d.commission_pct / 100, 2))), 0) AS total_commission
            $DealFrom $where
            GROUP BY 1, 2 ORDER BY 1, 2
            """.trimIndent()
        ).use { query ->
            query.bindAll(params)
            query.executeQuery().use { rows ->
                buildList {
                    while (rows.next()) {
                        add(
                            ClosingMonth(
                                month = java.time.YearMonth.parse(rows.getString("month")),
                                currency = rows.getString("currency").trim(),
                                count = rows.getInt("total"),
                                totalPrice = rows.getBigDecimal("total_price"),
                                totalCommission = rows.getBigDecimal("total_commission"),
                            )
                        )
                    }
                }
            }
        }
    }

    fun createDeal(tenant: Connection, propertyId: UUID, stageId: UUID, edit: DealEdit, createdBy: UUID?): UUID {
        val id = UUID.randomUUID()
        tenant.prepareStatement(
            """
            INSERT INTO $DealsTable (id, property_id, buyer_lead_id, buyer_customer_id, stage_id, agreed_price, commission_pct, commission_amount, currency,
                estimated_close_on, closed_on, comments, created_by)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """.trimIndent()
        ).use { insert ->
            insert.setObject(1, id)
            insert.setObject(2, propertyId)
            insert.uuid(3, edit.buyerLeadId)
            insert.uuid(4, edit.buyerCustomerId)
            insert.setObject(5, stageId)
            insert.decimal(6, edit.agreedPrice)
            insert.setBigDecimal(7, edit.commissionPct)
            insert.decimal(8, edit.commissionAmount)
            insert.setString(9, edit.currency)
            insert.date(10, edit.estimatedCloseOn)
            insert.date(11, edit.closedOn)
            insert.setString(12, edit.comments)
            insert.uuid(13, createdBy)
            insert.executeUpdate()
        }
        return id
    }

    fun updateDeal(tenant: Connection, id: UUID, edit: DealEdit) {
        tenant.prepareStatement(
            """
            UPDATE $DealsTable SET buyer_lead_id = ?, buyer_customer_id = ?, agreed_price = ?, commission_pct = ?, commission_amount = ?, currency = ?,
                estimated_close_on = ?, closed_on = ?, comments = ?, updated_at = CURRENT_TIMESTAMP
            WHERE id = ?
            """.trimIndent()
        ).use { update ->
            update.uuid(1, edit.buyerLeadId)
            update.uuid(2, edit.buyerCustomerId)
            update.decimal(3, edit.agreedPrice)
            update.setBigDecimal(4, edit.commissionPct)
            update.decimal(5, edit.commissionAmount)
            update.setString(6, edit.currency)
            update.date(7, edit.estimatedCloseOn)
            update.date(8, edit.closedOn)
            update.setString(9, edit.comments)
            update.setObject(10, id)
            update.executeUpdate()
        }
    }

    /** Cambia la etapa; los efectos sobre la propiedad y el lead los aplica el servicio. */
    fun moveDeal(tenant: Connection, id: UUID, stageId: UUID) {
        tenant.prepareStatement("UPDATE $DealsTable SET stage_id = ?, updated_at = CURRENT_TIMESTAMP WHERE id = ?").use { update ->
            update.setObject(1, stageId)
            update.setObject(2, id)
            update.executeUpdate()
        }
    }

    fun setClosedOn(tenant: Connection, id: UUID, closedOn: LocalDate?) {
        tenant.prepareStatement("UPDATE $DealsTable SET closed_on = ?, updated_at = CURRENT_TIMESTAMP WHERE id = ?").use { update ->
            update.date(1, closedOn)
            update.setObject(2, id)
            update.executeUpdate()
        }
    }

    /** Transacciones abiertas de la propiedad, sin contar [excluding] (para decidir si la propiedad sigue en negociacion). */
    fun openDealCount(tenant: Connection, propertyId: UUID, excluding: UUID?): Int =
        tenant.prepareStatement(
            "SELECT count(*) FROM $DealsTable d JOIN $StagesTable s ON s.id = d.stage_id WHERE d.property_id = ? AND s.outcome = 'open' AND (CAST(? AS uuid) IS NULL OR d.id <> CAST(? AS uuid))"
        ).use { query ->
            query.setObject(1, propertyId)
            query.uuid(2, excluding)
            query.uuid(3, excluding)
            query.executeQuery().use { rows -> rows.next(); rows.getInt(1) }
        }

    /** Transacciones ganadas de la propiedad, sin contar [excluding] (una propiedad solo tiene una venta cerrada). */
    fun wonDealCount(tenant: Connection, propertyId: UUID, excluding: UUID?): Int =
        tenant.prepareStatement(
            "SELECT count(*) FROM $DealsTable d JOIN $StagesTable s ON s.id = d.stage_id WHERE d.property_id = ? AND s.outcome = 'won' AND (CAST(? AS uuid) IS NULL OR d.id <> CAST(? AS uuid))"
        ).use { query ->
            query.setObject(1, propertyId)
            query.uuid(2, excluding)
            query.uuid(3, excluding)
            query.executeQuery().use { rows -> rows.next(); rows.getInt(1) }
        }

    // ---- Partes ----

    fun partiesOf(tenant: Connection, dealIds: Collection<UUID>): List<DealParty> {
        if (dealIds.isEmpty()) return emptyList()
        val array = uuidArray(tenant, dealIds)
        try {
            return tenant.prepareStatement(
                "SELECT id, deal_id, role, user_id, customer_id, name, share_pct, notes FROM $PartiesTable WHERE deal_id = ANY(?) ORDER BY deal_id, position, id"
            ).use { query ->
                query.setArray(1, array)
                query.executeQuery().use { rows ->
                    buildList {
                        while (rows.next()) add(
                            DealParty(
                                rows.getObject(1, UUID::class.java), rows.getObject(2, UUID::class.java), PartyRole.fromId(rows.getString(3)) ?: PartyRole.Other,
                                rows.getObject(4, UUID::class.java), rows.getObject(5, UUID::class.java), rows.getString(6), rows.getBigDecimal(7), rows.getString(8),
                            )
                        )
                    }
                }
            }
        } finally {
            array.free()
        }
    }

    /** Reemplaza las partes de la transaccion (ya validadas por el servicio), en el orden dado. */
    fun replaceParties(tenant: Connection, dealId: UUID, parties: List<PartyInput>) = inTransaction(tenant) {
        tenant.prepareStatement("DELETE FROM $PartiesTable WHERE deal_id = ?").use { delete ->
            delete.setObject(1, dealId)
            delete.executeUpdate()
        }
        tenant.prepareStatement("INSERT INTO $PartiesTable (id, deal_id, role, user_id, customer_id, name, share_pct, notes, position) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)").use { insert ->
            parties.forEachIndexed { index, party ->
                insert.setObject(1, UUID.randomUUID())
                insert.setObject(2, dealId)
                insert.setString(3, party.role.id)
                insert.uuid(4, party.userId)
                insert.uuid(5, party.customerId)
                insert.setString(6, party.name)
                insert.setBigDecimal(7, party.sharePct)
                insert.setString(8, party.notes)
                insert.setInt(9, index)
                insert.addBatch()
            }
            insert.executeBatch()
        }
        tenant.prepareStatement("UPDATE $DealsTable SET updated_at = CURRENT_TIMESTAMP WHERE id = ?").use { touch ->
            touch.setObject(1, dealId)
            touch.executeUpdate()
        }
        Unit
    }
}
