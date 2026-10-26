package com.ideasdeveloper.idc.modules.hotspot

import com.ideasdeveloper.idc.modules.hotspot.HotspotPortalPages.BasePath
import com.ideasdeveloper.idc.modules.hotspot.HotspotPortalPages.FormValues
import com.ideasdeveloper.idc.modules.hotspot.HotspotPortalPages.GuestContext
import com.ideasdeveloper.idc.server.company.application.ServerWorkspace
import com.ideasdeveloper.idc.server.modules.MailException
import com.ideasdeveloper.idc.server.modules.MailSender
import com.ideasdeveloper.idc.server.modules.ModuleAccessService
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.Parameters
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.host
import io.ktor.server.request.receiveParameters
import io.ktor.server.response.header
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import java.security.MessageDigest
import java.security.SecureRandom
import java.sql.Connection
import java.sql.Timestamp
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.util.HexFormat
import java.util.UUID

/**
 * Public captive portal served on each company's configured domain.
 *
 * Flow: UniFi redirects the guest to /guest/s/default/?id=<mac>&ssid=<network>. On networks with
 * form access (and SSIDs not configured) the guest registers, receives a one-time code by email and
 * enters it; on networks with customer-code access the guest types the code shown in Clientes. The
 * backend then authorizes the MAC through UniFi. An email can connect again only after the
 * reconnection interval (the network's, or the company's).
 */
class HotspotPortal(private val access: ModuleAccessService) {
    private val log = LoggerFactory.getLogger(HotspotPortal::class.java)
    private val random = SecureRandom()
    private val macPattern = Regex("^[0-9a-f]{2}(:[0-9a-f]{2}){5}$")
    private val emailPattern = Regex("^[^\\s@<>\"]+@[^\\s@<>\"]+\\.[^\\s@<>\"]{2,}$")
    private val phonePattern = Regex("^[+0-9][0-9 ()-]{5,19}$")
    private val maxCodeAttempts = 5
    // A new code is not emailed while the previous one for the same address is younger than this.
    private val resendInterval = Duration.ofSeconds(60)
    // Wrong customer codes: per device within the window, and for the whole company per minute.
    private val maxCodeFailuresPerDevice = 5
    private val codeFailureWindow = Duration.ofMinutes(15)
    private val maxCodeFailuresPerMinute = 30

    private class PortalTarget(
        val companyCode: String,
        val connection: Connection,
        val settings: HotspotSettings,
        val controller: NetworkController,
    )

    fun install(route: Route) {
        route.get(BasePath) { call.showForm() }
        route.get("$BasePath/") { call.showForm() }
        route.post("$BasePath/register") { call.register() }
        route.post("$BasePath/verify") { call.verify() }
        route.post("$BasePath/code") { call.customerCode() }
    }

    private suspend fun ApplicationCall.showForm() {
        val guest = guestContext(request.queryParameters)
        val html = withPortal { target ->
            val network = codeNetwork(target, guest)
            if (network != null) {
                HotspotPortalPages.accessCode(target.settings, guest, byUser = network.byUserCode)
            } else {
                HotspotPortalPages.form(target.settings, guest)
            }
        } ?: return
        respondHtml(html)
    }

    private suspend fun ApplicationCall.register() {
        val form = receiveParameters()
        val guest = guestContext(form)
        val values = FormValues(
            name = form["name"].orEmpty().trim(),
            email = form["email"].orEmpty().trim().lowercase(),
            phone = form["phone"].orEmpty().trim(),
            birthday = form["birthday"].orEmpty().trim(),
        )
        val html = withPortal { target -> register(target, guest, values, form["terms"] == "si") } ?: return
        respondHtml(html)
    }

    private suspend fun ApplicationCall.verify() {
        val form = receiveParameters()
        val pendingId = form["pending"]?.let { runCatching { UUID.fromString(it) }.getOrNull() }
        val code = form["code"].orEmpty().filter { it.isDigit() }
        val continueUrl = safeUrl(form["url"])
        val html = withPortal { target ->
            if (pendingId == null) {
                HotspotPortalPages.message(target.settings, "Solicitud invalida", "Vuelve a empezar el registro.")
            } else {
                verify(target, pendingId, code, continueUrl)
            }
        } ?: return
        respondHtml(html)
    }

    private suspend fun ApplicationCall.customerCode() {
        val form = receiveParameters()
        val guest = guestContext(form)
        val code = HotspotNetworks.normalizeCustomerCode(form["code"].orEmpty().take(40))
        val html = withPortal { target -> accessByCode(target, guest, code) } ?: return
        respondHtml(html)
    }

    private fun register(target: PortalTarget, guest: GuestContext, values: FormValues, acceptedTerms: Boolean): String {
        val network = HotspotNetworks.findBySsid(target.connection, guest.ssid)
        // A network that asks for a code cannot be entered through the form.
        if (network?.byCode == true) return HotspotPortalPages.accessCode(network.applyTo(target.settings), guest, byUser = network.byUserCode)
        val settings = network?.applyTo(target.settings) ?: target.settings
        val connection = target.connection
        fun retry(message: String) = HotspotPortalPages.form(settings, guest, values, message)

        if (!macPattern.matches(guest.mac)) return deviceNotIdentified(settings)
        if (values.name.isBlank() || values.name.length > 60) return retry("Escribe tu nombre.")
        if (values.email.length > 120 || !emailPattern.matches(values.email)) return retry("Escribe un correo valido.")
        if (!phonePattern.matches(values.phone)) return retry("Escribe un numero de celular valido.")
        val birthday = values.birthday.takeIf { it.isNotBlank() }?.let {
            runCatching { LocalDate.parse(it) }.getOrNull()?.takeIf { date -> date.isBefore(LocalDate.now()) && date.year > 1900 }
                ?: return retry("La fecha de cumpleanos no es valida.")
        }
        if (!acceptedTerms) return retry("Debes aceptar los terminos para continuar.")

        val smtp = settings.smtp(access.serverMail)
            ?: return HotspotPortalPages.message(settings, "Portal no disponible", "El envio de codigos no esta configurado. Avisa al personal del local.")

        minutesUntilReconnect(connection, values.email, settings.reconnectMinutes)?.let { remaining ->
            return HotspotPortalPages.wait(settings, remaining)
        }

        recentPendingCode(connection, values.email)?.let { pendingId ->
            return HotspotPortalPages.code(settings, pendingId.toString(), values.email, guest.continueUrl, "Ya te enviamos un codigo hace un momento. Revisa tu correo.")
        }

        val code = (0 until 6).joinToString("") { random.nextInt(10).toString() }
        val codeId = UUID.randomUUID()
        transaction(connection) {
            val customerId = findOrCreateCustomer(connection, values)
            val registrationId = UUID.randomUUID()
            insertRegistration(connection, registrationId, Owner.Customer(customerId), values, birthday, guest, HotspotNetworks.Form)
            connection.prepareStatement(
                "INSERT INTO hotspot_codes (id, registration_id, code_hash, expires_at) VALUES (?, ?, ?, ?)"
            ).use { insert ->
                insert.setObject(1, codeId)
                insert.setObject(2, registrationId)
                insert.setString(3, codeHash(codeId, code))
                insert.setTimestamp(4, Timestamp.from(Instant.now().plus(Duration.ofMinutes(settings.codeTtlMinutes.toLong()))))
                insert.executeUpdate()
            }
        }

        try {
            MailSender.sendText(
                settings = smtp,
                to = values.email,
                subject = settings.emailSubject,
                body = """
                    Hola ${values.name},

                    Tu codigo de acceso a ${settings.networkName} es: $code

                    Vence en ${settings.codeTtlMinutes} minutos y solo puede usarse una vez.
                    Si no lo solicitaste, ignora este correo.
                """.trimIndent(),
            )
        } catch (failure: MailException) {
            log.warn("Hotspot {}: no se pudo enviar el codigo a {}: {}", target.companyCode, values.email, failure.message)
            connection.prepareStatement("DELETE FROM hotspot_codes WHERE id = ?").use { delete ->
                delete.setObject(1, codeId)
                delete.executeUpdate()
            }
            return retry("No pudimos enviar el codigo a tu correo. Verificalo e intenta de nuevo.")
        }
        return HotspotPortalPages.code(settings, codeId.toString(), values.email, guest.continueUrl)
    }

    private fun verify(target: PortalTarget, codeId: UUID, code: String, continueUrl: String): String {
        val connection = target.connection
        val pending = connection.prepareStatement(
            """
            SELECT codes.code_hash, codes.expires_at, codes.attempts, codes.used_at,
                   registrations.id AS registration_id, registrations.name, registrations.email, registrations.mac,
                   registrations.ssid
            FROM hotspot_codes codes
            JOIN hotspot_registrations registrations ON registrations.id = codes.registration_id
            WHERE codes.id = ?
            """.trimIndent()
        ).use { query ->
            query.setObject(1, codeId)
            query.executeQuery().use { rows ->
                if (!rows.next()) null else PendingCode(
                    hash = rows.getString("code_hash"),
                    expiresAt = rows.getTimestamp("expires_at").toInstant(),
                    attempts = rows.getInt("attempts"),
                    used = rows.getTimestamp("used_at") != null,
                    registrationId = rows.getObject("registration_id", UUID::class.java),
                    name = rows.getString("name"),
                    email = rows.getString("email"),
                    mac = rows.getString("mac"),
                    ssid = rows.getString("ssid").orEmpty(),
                )
            }
        } ?: return HotspotPortalPages.message(target.settings, "Codigo no encontrado", "Vuelve a registrarte para recibir un codigo nuevo.")
        // Times of the network the guest registered from, when it is configured.
        val settings = HotspotNetworks.findBySsid(connection, pending.ssid)?.applyTo(target.settings) ?: target.settings

        if (pending.used) return HotspotPortalPages.message(settings, "Codigo ya utilizado", "Cada codigo se usa una sola vez.")
        if (Instant.now().isAfter(pending.expiresAt)) return HotspotPortalPages.message(settings, "Codigo vencido", "Vuelve a registrarte para recibir un codigo nuevo.")
        if (pending.attempts >= maxCodeAttempts) return HotspotPortalPages.message(settings, "Demasiados intentos", "Vuelve a registrarte para recibir un codigo nuevo.")

        if (code.length != 6 || !MessageDigest.isEqual(codeHash(codeId, code).toByteArray(), pending.hash.toByteArray())) {
            connection.prepareStatement("UPDATE hotspot_codes SET attempts = attempts + 1 WHERE id = ?").use { update ->
                update.setObject(1, codeId)
                update.executeUpdate()
            }
            val left = maxCodeAttempts - pending.attempts - 1
            if (left <= 0) return HotspotPortalPages.message(settings, "Demasiados intentos", "Vuelve a registrarte para recibir un codigo nuevo.")
            return HotspotPortalPages.code(settings, codeId.toString(), pending.email, continueUrl, "Codigo incorrecto. Te quedan $left intentos.")
        }

        // The interval may have been consumed by another device with the same email meanwhile.
        minutesUntilReconnect(connection, pending.email, settings.reconnectMinutes)?.let { remaining ->
            return HotspotPortalPages.wait(settings, remaining)
        }

        // Consume the code before calling UniFi so two concurrent submissions cannot both succeed.
        val claimed = connection.prepareStatement(
            "UPDATE hotspot_codes SET used_at = CURRENT_TIMESTAMP WHERE id = ? AND used_at IS NULL"
        ).use { update ->
            update.setObject(1, codeId)
            update.executeUpdate() == 1
        }
        if (!claimed) return HotspotPortalPages.message(settings, "Codigo ya utilizado", "Cada codigo se usa una sola vez.")

        val result = target.controller.authorizeGuest(settings, pending.mac)
        recordAccess(connection, pending.registrationId, Owner.Customer(null), pending.email, pending.mac, result, settings.accessMinutes)
        if (!result.authorized) {
            log.warn("Hotspot {}: UniFi rechazo {}: {}", target.companyCode, pending.mac, result.message)
            // Give the code back so the guest can retry once the problem is fixed.
            connection.prepareStatement("UPDATE hotspot_codes SET used_at = NULL WHERE id = ?").use { update ->
                update.setObject(1, codeId)
                update.executeUpdate()
            }
            return HotspotPortalPages.code(settings, codeId.toString(), pending.email, continueUrl, "No pudimos activar tu acceso en este momento. Intenta de nuevo en unos segundos.")
        }
        // One of the configured pages at random; without any, the page the guest was opening.
        return HotspotPortalPages.success(settings, pending.name, settings.successUrls.randomOrNull() ?: continueUrl.takeIf { it.isNotBlank() })
    }

    /**
     * Access by customer code on networks configured for it: the code must belong to a customer of
     * this company (or of the server workspace, when the portal runs there) that is not inactive or
     * archived and whose type the network allows. Every access is kept as a registration with the
     * customer's contact data, so reports and the reconnection interval work as with the form.
     */
    private fun accessByCode(target: PortalTarget, guest: GuestContext, code: String): String {
        val connection = target.connection
        // The network may have changed to form access since the page was opened.
        val network = codeNetwork(target, guest) ?: return HotspotPortalPages.form(target.settings, guest)
        val settings = network.applyTo(target.settings)
        fun retry(message: String) = HotspotPortalPages.accessCode(settings, guest, network.byUserCode, message)

        if (!macPattern.matches(guest.mac)) return deviceNotIdentified(settings)
        if (tooManyCodeFailures(connection, guest.mac)) {
            return retry("Demasiados intentos. Espera unos minutos y vuelve a intentarlo.")
        }
        if (network.byUserCode) return userCode(target, guest, network, settings, code, ::retry)

        val customer = if (HotspotNetworks.isCustomerCode(code)) findCustomerByCode(connection, code) else null
        if (customer == null || (network.customerTypes.isNotEmpty() && customer.type !in network.customerTypes)) {
            // The same answer for unknown codes and for types the network does not allow.
            recordCodeFailure(connection, guest.mac)
            return retry("El codigo no es valido para esta red. Revisalo e intenta de nuevo.")
        }

        minutesUntilReconnect(connection, customer.email, settings.reconnectMinutes)?.let { remaining ->
            return HotspotPortalPages.wait(settings, remaining, byCode = true)
        }

        return authorizeByCode(
            target, guest, settings, Owner.Customer(customer.id),
            FormValues(name = customer.name, email = customer.email, phone = customer.phone),
            HotspotNetworks.CustomerCode, ::retry,
        )
    }

    /**
     * Access by user code (Usuarios integration): users of this company, server owners when the
     * portal belongs to the server company, and users of the server company that a server owner
     * authorized for this network. Reconnection is counted per user.
     */
    private fun userCode(
        target: PortalTarget,
        guest: GuestContext,
        network: HotspotNetwork,
        settings: HotspotSettings,
        code: String,
        retry: (String) -> String,
    ): String {
        val connection = target.connection
        val invalid = "El codigo no es valido para esta red. Revisalo e intenta de nuevo."
        if (!HotspotIntegrations.isEnabled(connection, HotspotIntegrations.Users)) {
            return HotspotPortalPages.message(settings, "Red no disponible", "El acceso con codigo de usuario no esta activo. Avisa al personal del local.")
        }
        if (!HotspotNetworks.isCustomerCode(code)) {
            recordCodeFailure(connection, guest.mac)
            return retry(invalid)
        }
        val found = access.centralConnection().use { central ->
            HotspotUserCodes.findActive(connection, central, code)?.let { it to target.companyCode }
                ?: grantedServerUser(central, target.companyCode, network.ssid, code)?.let { it to ServerWorkspace.Code }
        }
        if (found == null) {
            recordCodeFailure(connection, guest.mac)
            return retry(invalid)
        }
        val (user, userCompany) = found
        val owner = Owner.User(user.id, userCompany)
        minutesUntilUserReconnect(connection, owner, settings.reconnectMinutes)?.let { remaining ->
            return HotspotPortalPages.wait(settings, remaining, byCode = true)
        }
        return authorizeByCode(target, guest, settings, owner, FormValues(name = user.username), HotspotNetworks.UserCode, retry)
    }

    // A user of the server company with [code] that a server owner authorized for [ssid] of [companyCode].
    private fun grantedServerUser(central: Connection, companyCode: String, ssid: String, code: String): HotspotUser? {
        if (companyCode == ServerWorkspace.Code) return null
        val workspace = access.tenantConnectionForCompany(ServerWorkspace.Code) ?: return null
        return workspace.use { serverCompany ->
            // The server company may not use the hotspot (no tables there).
            runCatching {
                HotspotUserCodes.findActive(serverCompany, central, code)
                    ?.takeIf { HotspotUserCodes.hasGrant(serverCompany, companyCode, ssid, it.id) }
            }.getOrNull()
        }
    }

    // Keeps the access as a registration, asks the network controller and shows the result.
    private fun authorizeByCode(
        target: PortalTarget,
        guest: GuestContext,
        settings: HotspotSettings,
        owner: Owner,
        values: FormValues,
        accessMode: String,
        retry: (String) -> String,
    ): String {
        val connection = target.connection
        val registrationId = UUID.randomUUID()
        insertRegistration(connection, registrationId, owner, values, birthday = null, guest = guest, accessMode = accessMode)
        val result = target.controller.authorizeGuest(settings, guest.mac)
        recordAccess(connection, registrationId, owner, values.email.ifBlank { null }, guest.mac, result, settings.accessMinutes)
        if (!result.authorized) {
            log.warn("Hotspot {}: {} rechazo {}: {}", target.companyCode, target.controller.name, guest.mac, result.message)
            return retry("No pudimos activar tu acceso en este momento. Intenta de nuevo en unos segundos.")
        }
        return HotspotPortalPages.success(settings, values.name, settings.successUrls.randomOrNull() ?: guest.continueUrl.takeIf { it.isNotBlank() })
    }

    private fun minutesUntilUserReconnect(connection: Connection, owner: Owner.User, reconnectMinutes: Int): Long? {
        if (reconnectMinutes <= 0) return null
        val lastAccess = connection.prepareStatement(
            "SELECT max(created_at) FROM hotspot_accesses WHERE user_id = ? AND user_company = ? AND authorized"
        ).use { query ->
            query.setObject(1, owner.id)
            query.setString(2, owner.company)
            query.executeQuery().use { rows -> if (rows.next()) rows.getTimestamp(1)?.toInstant() else null }
        } ?: return null
        val remaining = Duration.between(Instant.now(), lastAccess.plus(Duration.ofMinutes(reconnectMinutes.toLong())))
        return if (remaining.isNegative || remaining.isZero) null else (remaining.toSeconds() + 59) / 60
    }

    // The configured network the guest joined when it asks for a code; null otherwise.
    private fun codeNetwork(target: PortalTarget, guest: GuestContext): HotspotNetwork? =
        HotspotNetworks.findBySsid(target.connection, guest.ssid)?.takeIf { it.byCode }

    private fun deviceNotIdentified(settings: HotspotSettings) = HotspotPortalPages.message(
        settings,
        "Abre el portal desde la red WiFi",
        "No se pudo identificar tu dispositivo. Conectate a la red ${settings.networkName} y vuelve a abrir el navegador.",
    )

    private fun findCustomerByCode(connection: Connection, code: String): CodeCustomer? = connection.prepareStatement(
        """
        SELECT id, display_name, primary_email, primary_phone, customer_type
        FROM customers
        WHERE customer_code = ? AND status NOT IN ('inactive', 'archived')
        """.trimIndent()
    ).use { query ->
        query.setString(1, code)
        query.executeQuery().use { rows ->
            if (!rows.next()) null else CodeCustomer(
                id = rows.getObject("id", UUID::class.java),
                name = rows.getString("display_name"),
                email = rows.getString("primary_email"),
                phone = rows.getString("primary_phone"),
                type = rows.getString("customer_type"),
            )
        }
    }

    private fun tooManyCodeFailures(connection: Connection, mac: String): Boolean = connection.prepareStatement(
        """
        SELECT count(*) FILTER (WHERE mac = ?),
               count(*) FILTER (WHERE created_at > ?)
        FROM hotspot_code_attempts
        WHERE created_at > ?
        """.trimIndent()
    ).use { query ->
        val now = Instant.now()
        query.setString(1, mac)
        query.setTimestamp(2, Timestamp.from(now.minus(Duration.ofMinutes(1))))
        query.setTimestamp(3, Timestamp.from(now.minus(codeFailureWindow)))
        query.executeQuery().use { rows ->
            rows.next()
            rows.getInt(1) >= maxCodeFailuresPerDevice || rows.getInt(2) >= maxCodeFailuresPerMinute
        }
    }

    private fun recordCodeFailure(connection: Connection, mac: String) {
        connection.prepareStatement("INSERT INTO hotspot_code_attempts (id, mac) VALUES (?, ?)").use { insert ->
            insert.setObject(1, UUID.randomUUID())
            insert.setString(2, mac)
            insert.executeUpdate()
        }
        // Older attempts no longer count toward any limit.
        connection.prepareStatement("DELETE FROM hotspot_code_attempts WHERE created_at < ?").use { delete ->
            delete.setTimestamp(1, Timestamp.from(Instant.now().minus(Duration.ofDays(1))))
            delete.executeUpdate()
        }
    }

    private fun insertRegistration(
        connection: Connection,
        id: UUID,
        owner: Owner,
        values: FormValues,
        birthday: LocalDate?,
        guest: GuestContext,
        accessMode: String,
    ) {
        connection.prepareStatement(
            """
            INSERT INTO hotspot_registrations (id, customer_id, name, email, phone, birthday, mac, ap_mac, ssid, access_mode, user_id, user_company)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """.trimIndent()
        ).use { insert ->
            insert.setObject(1, id)
            insert.setObject(2, (owner as? Owner.Customer)?.id)
            insert.setString(3, values.name)
            insert.setString(4, values.email.ifBlank { null })
            insert.setString(5, values.phone.ifBlank { null })
            insert.setObject(6, birthday)
            insert.setString(7, guest.mac)
            insert.setString(8, guest.apMac.takeIf { macPattern.matches(it) })
            insert.setString(9, guest.ssid.take(64).takeIf { it.isNotBlank() })
            insert.setString(10, accessMode)
            insert.setObject(11, (owner as? Owner.User)?.id)
            insert.setString(12, (owner as? Owner.User)?.company)
            insert.executeUpdate()
        }
    }

    private fun recordAccess(
        connection: Connection,
        registrationId: UUID,
        owner: Owner,
        email: String?,
        mac: String,
        result: GuestAuthorization,
        minutes: Int,
    ) {
        connection.prepareStatement(
            """
            INSERT INTO hotspot_accesses (id, registration_id, email, mac, authorized, minutes, unifi_message, user_id, user_company)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
            """.trimIndent()
        ).use { insert ->
            insert.setObject(1, UUID.randomUUID())
            insert.setObject(2, registrationId)
            insert.setString(3, email)
            insert.setString(4, mac)
            insert.setBoolean(5, result.authorized)
            insert.setInt(6, minutes)
            insert.setString(7, result.message.take(1000))
            insert.setObject(8, (owner as? Owner.User)?.id)
            insert.setString(9, (owner as? Owner.User)?.company)
            insert.executeUpdate()
        }
    }

    private fun minutesUntilReconnect(connection: Connection, email: String, reconnectMinutes: Int): Long? {
        if (reconnectMinutes <= 0) return null
        val lastAccess = connection.prepareStatement(
            "SELECT max(created_at) FROM hotspot_accesses WHERE lower(email) = lower(?) AND authorized"
        ).use { query ->
            query.setString(1, email)
            query.executeQuery().use { rows -> if (rows.next()) rows.getTimestamp(1)?.toInstant() else null }
        } ?: return null
        val nextAllowed = lastAccess.plus(Duration.ofMinutes(reconnectMinutes.toLong()))
        val remaining = Duration.between(Instant.now(), nextAllowed)
        return if (remaining.isNegative || remaining.isZero) null else (remaining.toSeconds() + 59) / 60
    }

    private fun recentPendingCode(connection: Connection, email: String): UUID? = connection.prepareStatement(
        """
        SELECT codes.id
        FROM hotspot_codes codes
        JOIN hotspot_registrations registrations ON registrations.id = codes.registration_id
        WHERE lower(registrations.email) = lower(?)
          AND codes.used_at IS NULL
          AND codes.expires_at > CURRENT_TIMESTAMP
          AND codes.created_at > ?
        ORDER BY codes.created_at DESC
        LIMIT 1
        """.trimIndent()
    ).use { query ->
        query.setString(1, email)
        query.setTimestamp(2, Timestamp.from(Instant.now().minus(resendInterval)))
        query.executeQuery().use { rows -> if (rows.next()) rows.getObject(1, UUID::class.java) else null }
    }

    // Guests are kept in Clientes. Existing customers are linked without changing their data or type.
    private fun findOrCreateCustomer(connection: Connection, values: FormValues): UUID {
        connection.prepareStatement(
            "SELECT id FROM customers WHERE lower(primary_email) = lower(?) AND status <> 'archived' ORDER BY created_at LIMIT 1"
        ).use { query ->
            query.setString(1, values.email)
            query.executeQuery().use { rows -> if (rows.next()) return rows.getObject(1, UUID::class.java) }
        }
        val id = UUID.randomUUID()
        connection.prepareStatement(
            """
            INSERT INTO customers (id, display_name, primary_email, primary_phone, status, customer_type)
            VALUES (?, ?, ?, ?, 'active', 'hotspot')
            """.trimIndent()
        ).use { insert ->
            insert.setObject(1, id)
            insert.setString(2, values.name)
            insert.setString(3, values.email)
            insert.setString(4, values.phone)
            insert.executeUpdate()
        }
        return id
    }

    /** Resolves Host -> company -> tenant, checks the module is enabled and runs [block]; responds itself on failure. */
    private suspend fun ApplicationCall.withPortal(block: (PortalTarget) -> String): String? {
        // Nginx forwards the original Host header for every hotspot domain.
        val host = request.host().lowercase().removeSuffix(".")
        val html = try {
            withContext(Dispatchers.IO) {
                // The portal stops answering as soon as the server owner removes the module.
                if (!access.isInstalled(HotspotModule.id)) return@withContext null
                val companyCode = access.centralConnection().use { central ->
                    central.prepareStatement(
                        "SELECT company_code FROM public_domains WHERE domain = ? AND module_id = ? AND status <> 'removing'"
                    ).use { query ->
                        query.setString(1, host)
                        query.setString(2, HotspotModule.id)
                        query.executeQuery().use { rows -> if (rows.next()) rows.getString(1) else null }
                    }
                } ?: return@withContext null
                val tenant = access.tenantConnectionForCompany(companyCode) ?: return@withContext null
                tenant.use { connection ->
                    if (!moduleEnabled(connection)) return@withContext null
                    val settings = HotspotSettingsRepository.load(connection)
                    if (settings.domain != host) return@withContext null
                    val controller = HotspotIntegrations.controller(connection)?.takeIf { it.isConfigured(settings) }
                    if (controller == null) {
                        HotspotPortalPages.message(settings, "Portal en configuracion", "Este portal WiFi aun no esta listo. Avisa al personal del local.", retry = false)
                    } else {
                        block(PortalTarget(companyCode, connection, settings, controller))
                    }
                }
            }
        } catch (failure: Exception) {
            log.error("Hotspot portal error for host {}", host, failure)
            respondHtml(HotspotPortalPages.message(null, "Ocurrio un error", "Intenta de nuevo en unos segundos."), HttpStatusCode.InternalServerError)
            return null
        }
        if (html == null) {
            respondHtml(HotspotPortalPages.message(null, "Portal no encontrado", "Este dominio no tiene un portal WiFi activo.", retry = false), HttpStatusCode.NotFound)
        }
        return html
    }

    private fun moduleEnabled(connection: Connection): Boolean = connection.prepareStatement(
        "SELECT 1 FROM tenant_modules WHERE module_id = ? AND status = 'enabled'"
    ).use { query ->
        query.setString(1, HotspotModule.id)
        query.executeQuery().use { rows -> rows.next() }
    }

    private fun guestContext(parameters: Parameters) = GuestContext(
        mac = normalizeMac(parameters["id"]),
        apMac = normalizeMac(parameters["ap"]),
        ssid = parameters["ssid"].orEmpty().take(64),
        continueUrl = safeUrl(parameters["url"]),
    )

    private fun normalizeMac(value: String?): String = value.orEmpty().trim().lowercase().replace('-', ':')

    private fun safeUrl(value: String?): String = value.orEmpty().trim().takeIf {
        it.length <= 500 && (it.startsWith("http://") || it.startsWith("https://"))
    }.orEmpty()

    private fun codeHash(codeId: UUID, code: String): String = HexFormat.of().formatHex(
        MessageDigest.getInstance("SHA-256").digest("$codeId:$code".toByteArray(Charsets.UTF_8))
    )

    private fun transaction(connection: Connection, block: () -> Unit) {
        connection.autoCommit = false
        try {
            block()
            connection.commit()
        } catch (failure: Exception) {
            connection.rollback()
            throw failure
        } finally {
            connection.autoCommit = true
        }
    }

    private suspend fun ApplicationCall.respondHtml(html: String, status: HttpStatusCode = HttpStatusCode.OK) {
        response.header("Cache-Control", "no-store")
        response.header("X-Frame-Options", "DENY")
        response.header("Referrer-Policy", "no-referrer")
        respondText(html, ContentType.Text.Html.withParameter("charset", "utf-8"), status)
    }

    private data class PendingCode(
        val hash: String,
        val expiresAt: Instant,
        val attempts: Int,
        val used: Boolean,
        val registrationId: UUID,
        val name: String,
        val email: String,
        val mac: String,
        val ssid: String,
    )

    // Who a registration and its accesses belong to: a customer (form or customer code) or a user.
    private sealed interface Owner {
        data class Customer(val id: UUID?) : Owner
        data class User(val id: UUID, val company: String) : Owner
    }

    private data class CodeCustomer(val id: UUID, val name: String, val email: String, val phone: String, val type: String)
}
