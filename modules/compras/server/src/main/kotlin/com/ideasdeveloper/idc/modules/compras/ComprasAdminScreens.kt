package com.ideasdeveloper.idc.modules.compras

import com.ideasdeveloper.idc.ui.UiButton
import com.ideasdeveloper.idc.ui.UiCard
import com.ideasdeveloper.idc.ui.UiField
import com.ideasdeveloper.idc.ui.UiForm
import com.ideasdeveloper.idc.ui.UiNode
import com.ideasdeveloper.idc.ui.UiNotice
import com.ideasdeveloper.idc.ui.UiRequest
import com.ideasdeveloper.idc.ui.UiRow
import com.ideasdeveloper.idc.ui.UiScreen
import com.ideasdeveloper.idc.ui.UiText
import com.ideasdeveloper.idc.ui.UiTextStyle
import com.ideasdeveloper.idc.ui.UiTone
import java.sql.Connection
import java.util.UUID

/** Ajustes de Compras (solo con compras.manage): moneda por defecto, departamentos y aprobadores. */
object ComprasAdminScreens {
    private const val Route = ComprasScreens.UiRoute + "/settings"

    /** Datos ya cargados de la pantalla de ajustes. */
    data class SettingsData(
        val defaultCurrency: String,
        val departments: List<Department>,
        val approvers: List<Approver>,
        val users: List<CompanyUser>,
        /** Aprobadores designados que no tienen el permiso compras.approve (no podran aprobar). */
        val withoutPermission: Set<UUID>,
    )

    /**
     * Etiqueta con la que se elige a cada usuario en el desplegable: "Nombre (correo)"; si dos usuarios
     * quedaran con la misma etiqueta se les agrega " [n]". La misma funcion resuelve la eleccion al guardar.
     */
    fun userLabels(users: List<CompanyUser>): Map<String, CompanyUser> {
        val result = LinkedHashMap<String, CompanyUser>()
        users.forEach { user ->
            val base = if (user.email.isBlank()) user.name else "${user.name} (${user.email})"
            var label = base
            var n = 2
            while (label in result) label = "$base [${n++}]"
            result[label] = user
        }
        return result
    }

    fun build(tenant: Connection, actor: Actor, notice: UiNotice?, submitted: Map<String, String>?): UiScreen {
        val data = SettingsData(
            defaultCurrency = ComprasRepository.settings(tenant).defaultCurrency,
            departments = ComprasRepository.departments(tenant),
            approvers = ComprasRepository.approvers(tenant),
            users = ComprasRepository.companyUsers(tenant),
            withoutPermission = ComprasRepository.approversWithoutPermission(tenant),
        )
        return settingsScreen(actor, data, notice, submitted)
    }

    private fun approverFields(withUser: UiField?): List<UiField> = listOfNotNull(
        withUser,
        UiField("maxAmount", "Limite de monto", type = "number", help = "Vacio = sin limite."),
        UiField("limitCurrency", "Moneda del limite", help = "Codigo de 3 letras, por ejemplo USD."),
        UiField("active", "Activo", type = "boolean"),
    )

    fun settingsScreen(actor: Actor, data: SettingsData, notice: UiNotice? = null, submitted: Map<String, String>? = null): UiScreen {
        val currency = UiCard(
            title = "Moneda por defecto",
            children = listOf(
                UiForm(
                    "currency", listOf(UiField("currency", "Moneda", required = true, help = "Moneda de las solicitudes nuevas (3 letras).")),
                    mapOf("currency" to (submitted?.get("currency") ?: data.defaultCurrency)),
                    submit = UiButton("Guardar moneda", UiRequest(Route, params = mapOf("op" to "currency"), formId = "currency")),
                ),
            ),
        )

        val newDepartment = UiForm(
            "dept-new", listOf(UiField("name", "Nuevo departamento", required = true)),
            mapOf("name" to (submitted?.get("name") ?: "")),
            submit = UiButton("Agregar departamento", UiRequest(Route, params = mapOf("op" to "deptSave"), formId = "dept-new"), style = "secondary"),
        )
        val departments = UiCard(
            title = "Departamentos",
            children = listOf<UiNode>(newDepartment) + data.departments.map { department ->
                val params = mapOf("id" to department.id.toString())
                UiCard(
                    title = if (department.active) department.name else "${department.name} (inactivo)",
                    children = listOf(
                        UiForm(
                            "dept-${department.id}", listOf(UiField("name", "Nombre", required = true)), mapOf("name" to department.name),
                            submit = UiButton("Renombrar", UiRequest(Route, params = params + ("op" to "deptSave"), formId = "dept-${department.id}"), style = "secondary"),
                        ),
                        UiRow(
                            listOf(
                                UiButton(
                                    if (department.active) "Desactivar" else "Activar",
                                    UiRequest(Route, params = params + mapOf("op" to "deptActive", "active" to (!department.active).toString())),
                                    style = if (department.active) "danger" else "primary",
                                ),
                            ),
                        ),
                    ),
                )
            },
        )

        val labels = userLabels(data.users)
        val newApprover = UiForm(
            "approver-new",
            approverFields(UiField("user", "Usuario", type = "dropdown", required = true, options = labels.keys.toList())),
            submitted?.takeIf { it.containsKey("user") } ?: mapOf("limitCurrency" to data.defaultCurrency, "active" to "true"),
            submit = UiButton("Designar aprobador", UiRequest(Route, params = mapOf("op" to "approverSave"), formId = "approver-new"), style = "secondary"),
        )
        val approvers = UiCard(
            title = "Aprobadores",
            children = buildList<UiNode> {
                add(UiText("Basta una aprobacion de alguien cuyo limite cubra el monto. El dueno del negocio aprueba sin limite. Cada aprobador necesita ademas el permiso compras.approve.", UiTextStyle.Caption))
                add(newApprover)
                data.approvers.forEach { approver ->
                    val form = "approver-${approver.userId}"
                    val params = mapOf("user" to approver.userId.toString())
                    val limit = approver.maxAmount?.let { "Limite: " + ComprasMoney.format(it, approver.limitCurrency) } ?: "Sin limite"
                    add(
                        UiCard(
                            title = approver.name + if (approver.active) "" else " (inactivo)",
                            children = buildList<UiNode> {
                                add(UiText(limit))
                                if (approver.userId in data.withoutPermission) {
                                    add(UiText("No tiene el permiso compras.approve: no podra aprobar hasta que se lo den en Usuarios.", tone = UiTone.Warning))
                                }
                                add(
                                    UiForm(
                                        form, approverFields(null),
                                        mapOf(
                                            "maxAmount" to (approver.maxAmount?.toPlainString() ?: ""),
                                            "limitCurrency" to approver.limitCurrency,
                                            "active" to approver.active.toString(),
                                        ),
                                        submit = UiButton("Guardar", UiRequest(Route, params = params + ("op" to "approverSave"), formId = form), style = "secondary"),
                                    ),
                                )
                                add(
                                    UiRow(
                                        listOf(
                                            UiButton(
                                                "Quitar",
                                                UiRequest(Route, params = params + ("op" to "approverRemove"), confirm = "Se quita a ${approver.name} de los aprobadores. Continuar?"),
                                                style = "danger",
                                            ),
                                        ),
                                    ),
                                )
                            },
                        ),
                    )
                }
            },
        )
        return ComprasScreens.frame(actor, "settings", ComprasScreens.title("settings"), notice, listOf(currency, departments, approvers))
    }
}
