package com.ideasdeveloper.idc.modules.propietarios

import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/** Constructores compartidos por las pruebas puras de Propietarios (sin base de datos). */
object PropietariosFixtures {
    fun development(name: String = "Valle Verde", archived: Boolean = false, id: UUID = UUID.randomUUID()) =
        Development(id, name, "Cartago", "", null, null, "", archived)

    fun lot(
        status: LotStatus = LotStatus.Disponible,
        area: BigDecimal? = BigDecimal("250.50"),
        lotNumber: String = "24",
        mapUrl: String = "",
        latitude: BigDecimal? = null,
        longitude: BigDecimal? = null,
        archived: Boolean = false,
        id: UUID = UUID.randomUUID(),
        developmentId: UUID = UUID.randomUUID(),
    ) = Lot(id, developmentId, "Valle Verde", lotNumber, "1-234567", "Casa del lago", "Residencial", status, area, latitude, longitude, mapUrl, "", archived)

    fun ownership(
        name: String = "Ana Pérez",
        customerId: UUID = UUID.randomUUID(),
        share: BigDecimal? = null,
        startsOn: LocalDate? = null,
        endsOn: LocalDate? = null,
        current: Boolean = true,
        lotId: UUID = UUID.randomUUID(),
    ) = Ownership(UUID.randomUUID(), lotId, customerId, name, "", share, startsOn, endsOn, "", Instant.EPOCH, null, current)
}
