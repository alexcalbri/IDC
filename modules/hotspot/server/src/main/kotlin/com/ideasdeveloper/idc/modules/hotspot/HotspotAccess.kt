package com.ideasdeveloper.idc.modules.hotspot

import com.ideasdeveloper.idc.server.company.application.ServerWorkspace
import com.ideasdeveloper.idc.server.modules.ModuleAccessService
import com.ideasdeveloper.idc.server.modules.ModulePermissions
import com.ideasdeveloper.idc.ui.UiButton
import com.ideasdeveloper.idc.ui.UiCard
import com.ideasdeveloper.idc.ui.UiField
import com.ideasdeveloper.idc.ui.UiForm
import com.ideasdeveloper.idc.ui.UiGrid
import com.ideasdeveloper.idc.ui.UiMetric
import com.ideasdeveloper.idc.ui.UiNode
import com.ideasdeveloper.idc.ui.UiRequest
import com.ideasdeveloper.idc.ui.UiStatus
import com.ideasdeveloper.idc.ui.UiTable
import com.ideasdeveloper.idc.ui.UiTableColumn
import com.ideasdeveloper.idc.ui.UiText
import com.ideasdeveloper.idc.ui.UiTextStyle
import com.ideasdeveloper.idc.ui.UiTone
import java.sql.Connection
import java.util.UUID

/**
 * "Accesos WiFi" and "Integraciones" sections. Every user sees their own WiFi code; the business
 * owner (and server owners in the server company) see every user's code; server owners also choose
 * which users of the server company may join user-code networks of other companies.
 */
class HotspotAccess(private val access: ModuleAccessService) {
    private val separator = " · "

    fun isServerCompanyOwner(permissions: ModulePermissions) =
        permissions.serverOwner && permissions.companyCode == ServerWorkspace.Code

    // ---- Accesos WiFi ----

    fun accessBody(tenant: Connection, permissions: ModulePermissions): List<UiNode> {
        val usersEnabled = HotspotIntegrations.isEnabled(tenant, HotspotIntegrations.Users)
        val own = permissions.userId?.let { HotspotUserCodes.codeFor(tenant, it, permissions.serverOwner) }
        val mine = UiCard(
            title = "Mi acceso WiFi",
            children = listOfNotNull(
                own?.let { UiMetric("Tu codigo de usuario", it, "Escribelo en las redes con acceso \"Codigo de usuario\".") }
                    ?: UiText("No se pudo identificar tu usuario.", UiTextStyle.Caption),
                if (usersEnabled) null else UiStatus("El acceso con codigo de usuario aun no esta activo en esta empresa (Integraciones > Usuarios).", UiTone.Warning),
            ),
        )
        if (!permissions.businessOwner) return listOf(mine)

        val users = if (isServerCompanyOwner(permissions)) {
            access.centralConnection().use { central -> HotspotUserCodes.users(tenant, central) }
        } else {
            HotspotUserCodes.users(tenant, null)
        }
        val table = UiCard(
            title = "Codigos de los usuarios",
            children = listOf(
                UiTable(
                    listOf(UiTableColumn("user", "Usuario"), UiTableColumn("code", "Codigo"), UiTableColumn("kind", "Tipo"), UiTableColumn("state", "Estado")),
                    users.map {
                        mapOf(
                            "user" to it.username,
                            "code" to it.code,
                            "kind" to if (it.serverOwner) "Dueno del servidor" else "Usuario",
                            "state" to if (it.active) "Activo" else "Inactivo",
                        )
                    },
                    "Aun no hay usuarios.",
                ),
            ),
        )
        if (!isServerCompanyOwner(permissions)) return listOf(mine, table)
        return listOf(mine, table) + grantsBody(tenant, users)
    }

    private fun grantsBody(tenant: Connection, users: List<HotspotUser>): List<UiNode> {
        val names = users.associate { it.id to userLabel(it) }
        val grants = HotspotUserCodes.grants(tenant)
        val networks = otherCompanyNetworks()
        val list = UiCard(
            title = "Acceso a redes de otras empresas",
            children = listOf(
                UiText(
                    "Usuarios de la empresa del servidor que pueden conectarse con su codigo a redes \"Codigo de usuario\" de otras empresas. " +
                        "Solo los duenos del servidor lo cambian.",
                    UiTextStyle.Caption,
                ),
                if (grants.isEmpty()) UiText("Aun no hay accesos.", UiTextStyle.Caption) else UiGrid(
                    minItemWidth = 260,
                    children = grants.map { grant ->
                        UiCard(
                            title = "${grant.companyCode}$separator${grant.ssid}",
                            children = listOf(
                                UiText(names[grant.userId] ?: "Usuario eliminado", UiTextStyle.Caption),
                                UiButton(
                                    "Quitar",
                                    UiRequest("${HotspotScreens.UiRoute}/access", params = mapOf("grant" to "delete", "id" to grant.id.toString()), confirm = "Quitar este acceso?"),
                                    style = "danger",
                                ),
                            ),
                        )
                    },
                ),
            ),
        )
        val form = UiCard(
            title = "Dar acceso",
            children = listOf(
                if (networks.isEmpty()) {
                    UiText("Ninguna otra empresa tiene redes con acceso \"Codigo de usuario\".", UiTextStyle.Caption)
                } else {
                    UiForm(
                        id = "grant",
                        fields = listOf(
                            UiField("network", "Red", "dropdown", required = true, options = networks),
                            UiField("user", "Usuario", "dropdown", required = true, options = users.filter { it.active }.map(::userLabel)),
                        ),
                        submit = UiButton("Dar acceso", UiRequest("${HotspotScreens.UiRoute}/access", params = mapOf("grant" to "add"), formId = "grant")),
                    )
                },
            ),
        )
        return listOf(list, form)
    }

    /** Adds or removes a grant; returns the message to show. */
    fun grantAction(tenant: Connection, step: String, id: String?, values: Map<String, String>): Pair<String, String> {
        return when (step) {
            "add" -> {
                val (companyCode, ssid) = values["network"].orEmpty().split(separator, limit = 2).takeIf { it.size == 2 }?.let { it[0] to it[1] }
                    ?: return "Elige la red." to UiTone.Danger
                if ("$companyCode$separator$ssid" !in otherCompanyNetworks()) return "Esa red ya no tiene acceso por codigo de usuario." to UiTone.Danger
                val user = access.centralConnection().use { central -> HotspotUserCodes.users(tenant, central) }
                    .firstOrNull { it.active && userLabel(it) == values["user"] }
                    ?: return "Elige el usuario." to UiTone.Danger
                HotspotUserCodes.addGrant(tenant, companyCode, ssid, user.id)
                "${user.username} puede conectarse a $ssid de $companyCode." to UiTone.Success
            }
            "delete" -> {
                val grantId = id?.let { runCatching { UUID.fromString(it) }.getOrNull() }
                if (grantId != null && HotspotUserCodes.deleteGrant(tenant, grantId)) "Acceso quitado." to UiTone.Success
                else "Ese acceso ya no existe." to UiTone.Warning
            }
            else -> "Accion desconocida." to UiTone.Danger
        }
    }

    private fun userLabel(user: HotspotUser) = if (user.serverOwner) "${user.username} (dueno del servidor)" else user.username

    // "COMPANY · SSID" of every user-code network of the other active companies.
    private fun otherCompanyNetworks(): List<String> {
        val companies = access.centralConnection().use { central ->
            central.prepareStatement("SELECT code FROM companies WHERE is_active AND NOT is_server_workspace ORDER BY code").use { query ->
                query.executeQuery().use { rows -> buildList { while (rows.next()) add(rows.getString(1)) } }
            }
        }
        return companies.flatMap { code ->
            val tenant = access.tenantConnectionForCompany(code) ?: return@flatMap emptyList()
            tenant.use { connection ->
                // Companies without the hotspot have no networks table.
                runCatching {
                    connection.prepareStatement("SELECT ssid FROM hotspot_networks WHERE access_mode = ? ORDER BY ssid").use { query ->
                        query.setString(1, HotspotNetworks.UserCode)
                        query.executeQuery().use { rows -> buildList { while (rows.next()) add("$code$separator${rows.getString(1)}") } }
                    }
                }.getOrDefault(emptyList())
            }
        }
    }

    // ---- Integraciones ----

    fun integrationsBody(tenant: Connection): List<UiNode> {
        val enabled = HotspotIntegrations.enabled(tenant)
        val catalog = HotspotIntegrations.catalog(tenant)
        fun card(integration: HotspotIntegration): UiNode {
            val on = integration.id in enabled
            return UiCard(
                title = integration.title,
                children = listOf(
                    UiText(integration.description, UiTextStyle.Caption),
                    UiStatus(if (on) "Activa" else "Inactiva", if (on) UiTone.Success else UiTone.Neutral),
                    UiButton(
                        if (on) "Desactivar" else "Activar",
                        UiRequest(
                            "${HotspotScreens.UiRoute}/integrations",
                            params = mapOf("integration" to integration.id, "enabled" to (!on).toString()),
                            confirm = if (on && integration.kind == HotspotIntegrations.Network) "Sin controlador de red el portal deja de autorizar visitantes. Continuar?" else null,
                        ),
                        style = if (on) "secondary" else "primary",
                    ),
                ),
            )
        }
        return listOf(
            UiCard(
                title = "Controlador de red",
                children = listOf(
                    UiText("Ecosistema de red donde el portal autoriza a los visitantes. Solo uno puede estar activo.", UiTextStyle.Caption),
                    UiGrid(minItemWidth = 260, children = catalog.filter { it.kind == HotspotIntegrations.Network }.map(::card)),
                ),
            ),
            UiCard(
                title = "Modulos",
                children = listOf(
                    UiText(
                        "Conecta el hotspot con otros modulos. Los modulos que dan acceso a sus clientes (hotel, membresia...) " +
                            "aparecen aqui al instalarse. Solo el dueno de la empresa o del servidor las cambia.",
                        UiTextStyle.Caption,
                    ),
                    UiGrid(minItemWidth = 260, children = catalog.filter { it.kind == HotspotIntegrations.Module }.map(::card)),
                ),
            ),
        )
    }
}
