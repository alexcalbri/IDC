package com.ideasdeveloper.idc.modules.membresia

import com.ideasdeveloper.idc.server.modules.McpSchema
import com.ideasdeveloper.idc.server.modules.McpTool
import com.ideasdeveloper.idc.server.modules.McpToolCall
import com.ideasdeveloper.idc.server.modules.McpToolException
import com.ideasdeveloper.idc.server.modules.ModuleMcp
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.time.DateTimeException
import java.time.LocalDate

/**
 * Herramientas de agentes del modulo Membresias: los mismos permisos y reglas que las pantallas.
 * Nunca devuelven customer_code ni campos personalizados del cliente (documento, nacimiento, emergencia):
 * esos datos viven en Clientes. Ninguna recibe credenciales.
 */
object MembresiaMcpTools {
    // Lo que este modulo le dice a los agentes sobre si mismo (ver ModuleMcp).
    const val Instructions =
        "Membresias lleva los miembros de un espacio de coworking o club: planes, membresias (activa, por vencer, vencida, cancelada, programada), " +
            "visitas e invitados. Los miembros son clientes de Clientes: usa search_members para encontrarlos y sus ids en las demas herramientas. " +
            "record_visit avisa sin registrar cuando la membresia esta vencida, aun no empieza o ya uso todas sus visitas; solo registra en ese caso " +
            "con override=true y despues de confirmarlo con el usuario. Una membresia cancelada nunca registra visitas. Con guest_name usa un pase de invitado " +
            "(sin override). Renovar crea una membresia nueva enlazada; no cobra nada: el monto es solo un registro. " +
            "Los datos sensibles del miembro (documento, fecha de nacimiento, contacto de emergencia) viven en Clientes y no se exponen aqui."

    val tools: List<McpTool> = listOf(
        McpTool(
            name = "search_members",
            title = "Buscar miembros",
            description = "Busca clientes que ya tienen membresia por nombre, correo o telefono y devuelve su membresia actual y su estado.",
            permission = MembresiaModule.View,
            readOnly = true,
            inputSchema = McpSchema.obj(
                mapOf("query" to McpSchema.string("Texto a buscar en nombre, correo o telefono.")),
                required = listOf("query"),
            ),
        ) { call -> guarded(call, MembresiaModule.View) { searchMembers(call) } },
        McpTool(
            name = "get_member_status",
            title = "Estado de un miembro",
            description = "Estado de un miembro: membresia actual, fechas, dias restantes, visitas y pases de invitado restantes, y sus membresias anteriores.",
            permission = MembresiaModule.View,
            readOnly = true,
            inputSchema = McpSchema.obj(
                mapOf("customer_id" to McpSchema.uuid("Id del cliente (de search_members).")),
                required = listOf("customer_id"),
            ),
        ) { call -> guarded(call, MembresiaModule.View) { memberStatus(call) } },
        McpTool(
            name = "list_plans",
            title = "Planes de membresia",
            description = "Lista los planes activos con precio, duracion en dias, visitas incluidas (vacio = ilimitadas), pases de invitado y WiFi.",
            permission = MembresiaModule.View,
            readOnly = true,
        ) { call -> guarded(call, MembresiaModule.View) { listPlans(call) } },
        McpTool(
            name = "list_expiring",
            title = "Membresias por vencer",
            description = "Membresias vigentes que vencen en los proximos dias (por defecto los dias de aviso del modulo), de la mas cercana a la mas lejana.",
            permission = MembresiaModule.View,
            readOnly = true,
            inputSchema = McpSchema.obj(mapOf("days" to McpSchema.integer("Dias hacia adelante (1 a 60); opcional.", 1, MembresiaRules.MaxExpiringDays))),
        ) { call -> guarded(call, MembresiaModule.View) { listExpiring(call) } },
        McpTool(
            name = "record_visit",
            title = "Registrar visita",
            description = "Registra la visita de hoy de un miembro. Si la membresia esta vencida, no ha empezado o ya uso sus visitas, NO registra y avisa; " +
                "repite con override=true solo si el usuario lo confirma. Con guest_name descuenta un pase de invitado.",
            permission = MembresiaModule.Visits,
            inputSchema = McpSchema.obj(
                mapOf(
                    "customer_id" to McpSchema.uuid("Id del cliente miembro."),
                    "guest_name" to McpSchema.string("Nombre del invitado, para registrar un pase de invitado en vez de la visita del miembro."),
                    "override" to McpSchema.boolean("Registrar igual pese al aviso (vencida o sin visitas). Solo tras confirmarlo con el usuario."),
                ),
                required = listOf("customer_id"),
            ),
        ) { call -> guarded(call, MembresiaModule.Visits) { recordVisit(call) } },
        McpTool(
            name = "create_membership",
            title = "Crear membresia",
            description = "Crea una membresia para un cliente existente con un plan activo. Sin starts_on empieza hoy; sin amount usa el precio del plan. No cobra.",
            permission = MembresiaModule.Manage,
            inputSchema = McpSchema.obj(
                mapOf(
                    "customer_id" to McpSchema.uuid("Id del cliente (de search_members o de Clientes)."),
                    "plan_id" to McpSchema.uuid("Id del plan (de list_plans)."),
                    "starts_on" to McpSchema.date("Inicio; opcional, por defecto hoy."),
                    "amount" to McpSchema.number("Monto registrado; opcional, por defecto el precio del plan."),
                    "source" to McpSchema.string("Fuente (por ejemplo Referido); debe ser una de las configuradas."),
                    "unit" to McpSchema.string("Unidad u oficina asignada."),
                    "notes" to McpSchema.string("Notas internas."),
                ),
                required = listOf("customer_id", "plan_id"),
            ),
        ) { call -> guarded(call, MembresiaModule.Manage) { createMembership(call) } },
        McpTool(
            name = "renew_membership",
            title = "Renovar membresia",
            description = "Renueva una membresia: crea una nueva enlazada que empieza al dia siguiente del fin (o hoy si ya vencio). Opcionalmente cambia de plan, inicio o monto. No cobra.",
            permission = MembresiaModule.Manage,
            inputSchema = McpSchema.obj(
                mapOf(
                    "membership_id" to McpSchema.uuid("Id de la membresia a renovar."),
                    "plan_id" to McpSchema.uuid("Plan nuevo; opcional, por defecto el mismo."),
                    "starts_on" to McpSchema.date("Inicio; opcional."),
                    "amount" to McpSchema.number("Monto registrado; opcional, por defecto el precio del plan."),
                ),
                required = listOf("membership_id"),
            ),
        ) { call -> guarded(call, MembresiaModule.Manage) { renewMembership(call) } },
        McpTool(
            name = "revenue_summary",
            title = "Ingresos por membresias",
            description = "Ingresos registrados por mes de inicio y por plan en los ultimos meses (sin contar las canceladas).",
            permission = MembresiaModule.Manage,
            readOnly = true,
            inputSchema = McpSchema.obj(mapOf("months" to McpSchema.integer("Cantidad de meses (1 a 24); por defecto 12.", 1, 24))),
        ) { call -> guarded(call, MembresiaModule.Manage) { revenueSummary(call) } },
    )

    val mcp: ModuleMcp = ModuleMcp(Instructions, tools)

    private const val DefaultMonths = 12

    // Comprueba el permiso vivo del usuario y convierte los errores de validacion en errores que el agente puede corregir.
    private fun guarded(call: McpToolCall, permission: String, block: () -> JsonElement): JsonElement {
        if (!call.permissions.has(permission)) throw McpToolException("No tienes permiso para esta herramienta.")
        try {
            return block()
        } catch (failure: MembresiaValidationException) {
            throw McpToolException(failure.errors.joinToString(" "))
        } catch (failure: DateTimeException) {
            throw McpToolException("Una fecha no es válida: ${failure.message}")
        } catch (failure: IllegalArgumentException) {
            throw McpToolException("Un argumento no es válido: ${failure.message}")
        }
    }

    private fun expiringDays(call: McpToolCall): Int = MembresiaRepository.settings(call.tenant).expiringDays

    private fun searchMembers(call: McpToolCall): JsonElement {
        val query = call.requiredString("query", 100)
        val today = LocalDate.now()
        val days = expiringDays(call)
        val members = MembresiaRepository.searchMembers(call.tenant, query, today, days, limit = 20)
        return buildJsonObject {
            put("count", members.size)
            put("members", JsonArray(members.map { memberJson(it, today, days) }))
        }
    }

    private fun memberStatus(call: McpToolCall): JsonElement {
        val today = LocalDate.now()
        val days = expiringDays(call)
        val detail = MembresiaRepository.member(call.tenant, call.requiredUuid("customer_id"), today, days)
            ?: throw McpToolException("Cliente no encontrado.")
        return buildJsonObject {
            put("member", memberJson(detail.summary, today, days))
            put("memberships", JsonArray(detail.memberships.take(10).map { membershipJson(it, today, days) }))
            put(
                "recent_visits",
                JsonArray(
                    detail.visits.take(10).map { visit ->
                        buildJsonObject {
                            put("date", visit.visitOn.toString())
                            put("kind", visit.kind)
                            visit.guestName?.let { put("guest_name", it) }
                            put("override", visit.override)
                        }
                    },
                ),
            )
        }
    }

    private fun listPlans(call: McpToolCall): JsonElement {
        val plans = MembresiaRepository.plans(call.tenant)
        val currency = MembresiaRepository.settings(call.tenant).currency
        return buildJsonObject {
            put("currency", currency)
            put(
                "plans",
                JsonArray(
                    plans.map { plan ->
                        buildJsonObject {
                            put("plan_id", plan.id.toString())
                            put("name", plan.name)
                            put("description", plan.description)
                            put("price", plan.price.toPlainString())
                            put("duration_days", plan.durationDays)
                            put("included_visits", plan.includedVisits)
                            put("unlimited_visits", plan.includedVisits == null)
                            put("guest_passes", plan.guestPasses)
                            put("wifi_access", plan.wifiAccess)
                        }
                    },
                ),
            )
        }
    }

    private fun listExpiring(call: McpToolCall): JsonElement {
        val today = LocalDate.now()
        val days = call.int("days", expiringDays(call), 1..MembresiaRules.MaxExpiringDays)
        val memberships = MembresiaRepository.memberships(call.tenant, DisplayStatus.POR_VENCER, today, days, limit = 100)
        return buildJsonObject {
            put("days", days)
            put("count", memberships.size)
            put("memberships", JsonArray(memberships.map { membershipJson(it, today, days) }))
        }
    }

    private fun recordVisit(call: McpToolCall): JsonElement {
        val customerId = call.requiredUuid("customer_id")
        val guestName = call.string("guest_name", 160)
        val override = call.boolean("override")
        val today = LocalDate.now()
        val days = expiringDays(call)
        val author = MembresiaService.authorName(call.access, call.tenant, call.permissions)
        if (guestName.isNotEmpty()) {
            return when (val outcome = MembresiaService.guestPass(call.tenant, customerId, guestName, author, today)) {
                GuestPassOutcome.NoMember -> throw McpToolException("Cliente no encontrado.")
                GuestPassOutcome.NoMembership -> throw McpToolException("El cliente no tiene membresia.")
                is GuestPassOutcome.Done -> when (val result = outcome.result) {
                    GuestPassResult.Recorded -> buildJsonObject {
                        put("result", "guest_recorded")
                        put("guest_name", guestName)
                        put("membership", membershipJson(outcome.membership, today, days))
                    }
                    is GuestPassResult.Refused -> buildJsonObject {
                        put("result", "refused")
                        put("registered", false)
                        put("reason", result.reason)
                    }
                }
            }
        }
        return when (val outcome = MembresiaService.checkIn(call.tenant, customerId, override, author, today)) {
            CheckInOutcome.NoMember -> throw McpToolException("Cliente no encontrado.")
            CheckInOutcome.NoMembership -> throw McpToolException("El cliente no tiene membresia.")
            is CheckInOutcome.Done -> when (val result = outcome.result) {
                is VisitResult.Recorded -> buildJsonObject {
                    put("result", if (result.overridden) "recorded_override" else "recorded")
                    put("membership", membershipJson(outcome.membership, today, days))
                }
                VisitResult.AlreadyToday -> buildJsonObject {
                    put("result", "already_today")
                    put("registered", false)
                    put("reason", "Ya hay una visita de este miembro hoy; no se cuenta dos veces.")
                    put("membership", membershipJson(outcome.membership, today, days))
                }
                is VisitResult.NeedsOverride -> buildJsonObject {
                    put("result", "needs_override")
                    put("registered", false)
                    put("reason", result.reason)
                    put("hint", "Si el usuario confirma, repite con override=true para registrar igual.")
                    put("membership", membershipJson(outcome.membership, today, days))
                }
                is VisitResult.Refused -> buildJsonObject {
                    put("result", "refused")
                    put("registered", false)
                    put("reason", result.reason)
                }
            }
        }
    }

    private fun createMembership(call: McpToolCall): JsonElement {
        val today = LocalDate.now()
        val days = expiringDays(call)
        val input = MembershipInput(
            customerId = call.requiredUuid("customer_id"),
            planId = call.requiredUuid("plan_id"),
            startsOn = call.date("starts_on"),
            amount = call.decimal("amount"),
            source = call.string("source", MembresiaRules.MaxSourceLength),
            unit = call.string("unit", MembresiaRules.MaxUnitLength),
            notes = call.string("notes", MembresiaRules.MaxNotesLength),
        )
        val author = MembresiaService.authorName(call.access, call.tenant, call.permissions)
        val membership = MembresiaService.create(call.access, call.tenant, input, null, author, today)
        return buildJsonObject {
            put("result", "created")
            put("membership", membershipJson(membership, today, days, withAmount = true))
        }
    }

    private fun renewMembership(call: McpToolCall): JsonElement {
        val today = LocalDate.now()
        val days = expiringDays(call)
        val input = RenewalInput(call.uuid("plan_id"), call.date("starts_on"), call.decimal("amount"))
        val author = MembresiaService.authorName(call.access, call.tenant, call.permissions)
        val membership = MembresiaService.renew(call.access, call.tenant, call.requiredUuid("membership_id"), input, author, today)
            ?: throw McpToolException("Membresía no encontrada.")
        return buildJsonObject {
            put("result", "renewed")
            put("membership", membershipJson(membership, today, days, withAmount = true))
        }
    }

    private fun revenueSummary(call: McpToolCall): JsonElement {
        val today = LocalDate.now()
        val months = call.int("months", DefaultMonths, 1..24)
        val rows = MembresiaRepository.revenueRows(call.tenant, today, months)
        val currency = MembresiaRepository.settings(call.tenant).currency
        return buildJsonObject {
            put("currency", currency)
            put("months", months)
            put(
                "by_month",
                JsonArray(
                    MembresiaRules.revenueByMonth(rows, today, months, currency).map {
                        buildJsonObject {
                            put("month", it.month.toString())
                            put("amount", it.amount.toPlainString())
                            put("memberships", it.count)
                        }
                    },
                ),
            )
            put(
                "by_plan",
                JsonArray(
                    MembresiaRules.revenueByPlan(rows, today, months, currency).map {
                        buildJsonObject {
                            put("plan", it.planName)
                            put("amount", it.amount.toPlainString())
                            put("memberships", it.count)
                        }
                    },
                ),
            )
        }
    }

    // Solo nombre, correo y telefono del cliente (nunca customer_code ni campos personalizados) y su membresia actual.
    private fun memberJson(member: MemberSummary, today: LocalDate, expiringDays: Int) = buildJsonObject {
        put("customer_id", member.customerId.toString())
        put("name", member.customerName)
        put("email", member.email)
        put("phone", member.phone)
        val current = member.current
        if (current == null) put("membership", null as String?) else put("membership", membershipJson(current, today, expiringDays))
    }

    private fun membershipJson(membership: Membership, today: LocalDate, expiringDays: Int, withAmount: Boolean = false) = buildJsonObject {
        val status = MembresiaRules.displayStatus(membership, today, expiringDays)
        put("membership_id", membership.id.toString())
        put("customer_id", membership.customerId.toString())
        put("customer_name", membership.customerName)
        put("plan", membership.planName)
        put("starts_on", membership.startsOn.toString())
        put("ends_on", membership.endsOn.toString())
        put("status", status.key)
        put("status_label", status.label)
        put("days_left", MembresiaRules.daysLeft(membership.endsOn, today))
        put("visits_used", membership.visitsUsed)
        put("visits_included", membership.includedVisits)
        put("visits_left", MembresiaRules.visitsLeft(membership))
        put("guest_passes_left", MembresiaRules.guestPassesLeft(membership))
        put("unit", membership.unit)
        if (withAmount) {
            put("amount", membership.amount.toPlainString())
            put("currency", membership.currency)
        }
    }
}
