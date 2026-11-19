package com.ideasdeveloper.idc.modules.membresia

import com.ideasdeveloper.idc.server.modules.ModuleAccessService
import com.ideasdeveloper.idc.server.modules.ModulePermissions
import java.sql.Connection
import java.time.LocalDate
import java.util.UUID

/** Resultado de registrar la visita de un miembro desde su cliente. */
sealed class CheckInOutcome {
    /** El cliente no existe o no es visible. */
    object NoMember : CheckInOutcome()

    /** El cliente existe pero nunca tuvo una membresia. */
    object NoMembership : CheckInOutcome()

    data class Done(val membership: Membership, val result: VisitResult) : CheckInOutcome()
}

/** Resultado de usar un pase de invitado desde el cliente. */
sealed class GuestPassOutcome {
    object NoMember : GuestPassOutcome()

    object NoMembership : GuestPassOutcome()

    data class Done(val membership: Membership, val result: GuestPassResult) : GuestPassOutcome()
}

/**
 * Orquesta cada cambio: el repositorio escribe solo en las tablas del modulo; aqui se agregan el pase del hotspot y el aviso a Clientes. JDBC sincrono: el llamador usa withContext(Dispatchers.IO).
 * Los fallos del hotspot se registran y nunca rompen la operacion (ver MembresiaHotspot). El historial del cliente no se escribe: lo calcula MembresiaCustomerTimeline al leer.
 */
object MembresiaService {
    /** Nombre de quien hace el cambio, para el historial y las visitas; vacio si no se conoce. */
    fun authorName(access: ModuleAccessService, tenant: Connection, permissions: ModulePermissions): String =
        access.userIdentity(tenant, permissions)?.name ?: ""

    /**
     * Alta de membresia. Con [newCustomer] se reutiliza el cliente visible con el mismo correo o se crea uno
     * (y se avisa a Clientes); si no, se usa [MembershipInput.customerId], que debe ser visible.
     */
    fun create(
        access: ModuleAccessService,
        tenant: Connection,
        input: MembershipInput,
        newCustomer: NewCustomerInput?,
        author: String,
        today: LocalDate,
    ): Membership {
        val plan = MembresiaRepository.plan(tenant, input.planId) ?: throw MembresiaValidationException(listOf("El plan no existe."))
        // Una sola transaccion: valida, busca o crea el cliente y rechaza solapes; nada se crea antes de validar.
        val (membership, customerCreated) = MembresiaRepository.createMembershipWithCustomer(tenant, input, newCustomer, plan, today, author)
        // Ya hizo commit: ahora si se avisa a Clientes.
        if (customerCreated) access.customerChanged(tenant, membership.customerId)
        MembresiaHotspot.syncPass(tenant, membership, MembresiaRepository.planWifi(tenant, membership.planId))
        return membership
    }

    /** Renueva; null si la membresia no existe o su cliente no es visible (sin historial ni hotspot). */
    fun renew(tenant: Connection, membershipId: UUID, input: RenewalInput, author: String, today: LocalDate): Membership? {
        val membership = MembresiaRepository.renew(tenant, membershipId, input, today, author) ?: return null
        MembresiaHotspot.syncPass(tenant, membership, MembresiaRepository.planWifi(tenant, membership.planId))
        return membership
    }

    /** Edita la membresia (null si no existe) y vuelve a sincronizar el pase por si cambio el fin. */
    fun edit(tenant: Connection, membershipId: UUID, edit: MembershipEdit, today: LocalDate): Membership? {
        val membership = MembresiaRepository.edit(tenant, membershipId, edit, today) ?: return null
        MembresiaHotspot.syncPass(tenant, membership, MembresiaRepository.planWifi(tenant, membership.planId))
        return membership
    }

    /** Cancela (null si no existe o ya estaba cancelada) y quita el pase WiFi. */
    fun cancel(tenant: Connection, membershipId: UUID, reason: String): Membership? {
        val membership = MembresiaRepository.cancel(tenant, membershipId, reason) ?: return null
        MembresiaHotspot.removePass(tenant, membership.id)
        return membership
    }

    /** Registra la visita de hoy del cliente en su membresia actual (la vigente, o si no la mas reciente). */
    fun checkIn(tenant: Connection, customerId: UUID, override: Boolean, author: String, today: LocalDate, note: String = ""): CheckInOutcome {
        val expiringDays = MembresiaRepository.settings(tenant).expiringDays
        val detail = MembresiaRepository.member(tenant, customerId, today, expiringDays) ?: return CheckInOutcome.NoMember
        val current = detail.summary.current ?: return CheckInOutcome.NoMembership
        val result = MembresiaRepository.recordMemberVisit(tenant, current.id, today, override, author, note)
        val updated = if (result is VisitResult.Recorded) MembresiaRepository.membership(tenant, current.id) ?: current else current
        return CheckInOutcome.Done(updated, result)
    }

    /** Descuenta un pase de invitado de la membresia actual del cliente. */
    fun guestPass(tenant: Connection, customerId: UUID, guestName: String, author: String, today: LocalDate): GuestPassOutcome {
        val expiringDays = MembresiaRepository.settings(tenant).expiringDays
        val detail = MembresiaRepository.member(tenant, customerId, today, expiringDays) ?: return GuestPassOutcome.NoMember
        val current = detail.summary.current ?: return GuestPassOutcome.NoMembership
        val result = MembresiaRepository.useGuestPass(tenant, current.id, guestName, today, author)
        val updated = if (result is GuestPassResult.Recorded) MembresiaRepository.membership(tenant, current.id) ?: current else current
        return GuestPassOutcome.Done(updated, result)
    }
}
