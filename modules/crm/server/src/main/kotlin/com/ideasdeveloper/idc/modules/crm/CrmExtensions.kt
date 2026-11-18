package com.ideasdeveloper.idc.modules.crm

import com.ideasdeveloper.idc.server.extensions.ExtensionAccess
import com.ideasdeveloper.idc.server.extensions.ExtensionManifest
import com.ideasdeveloper.idc.server.extensions.ExtensionSource
import com.ideasdeveloper.idc.server.extensions.ExtensionStatus
import com.ideasdeveloper.idc.server.extensions.ExtensionUninstallDefinition
import com.ideasdeveloper.idc.server.extensions.ExtensionUninstallOption
import com.ideasdeveloper.idc.server.extensions.UninstallItem
import com.ideasdeveloper.idc.ui.UiCard
import com.ideasdeveloper.idc.ui.UiNode
import com.ideasdeveloper.idc.ui.UiText
import com.ideasdeveloper.idc.ui.UiTone
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.plugins.origin
import io.ktor.server.request.receiveChannel
import io.ktor.server.response.respond
import io.ktor.utils.io.jvm.javaio.toInputStream
import java.sql.Connection
import java.sql.SQLException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Ids de las extensiones incluidas en el CRM (EXTENSIONS.md). */
object CrmExtensionIds {
    const val Meta = "meta"
    const val MondayImport = "monday_import"
    const val WordPress = "wordpress"
    const val Encuentra24 = "encuentra24"
    const val Cloudbeds = "cloudbeds"

    val all = listOf(Meta, MondayImport, WordPress, Encuentra24, Cloudbeds)
}

/** Permisos propios del CRM que sus extensiones pueden declarar (ModuleDefinition.extensionPermissions). */
object CrmExtensionPermissions {
    const val LeadsCreate = "crm.leads.create"
    const val LeadsUpdate = "crm.leads.update"
    const val FieldsCreate = "crm.fields.create"
    const val MessagesWrite = "crm.messages.write"
    const val ItemsWrite = "crm.items.write"
    const val ReservationsWrite = "crm.reservations.write"

    val all = listOf(LeadsCreate, LeadsUpdate, FieldsCreate, MessagesWrite, ItemsWrite, ReservationsWrite)
}

/** Utilidades comunes de las extensiones del CRM: estado en la empresa, manifiestos y rutas publicas. */
object CrmExtensions {
    /** Version de los manifiestos de las extensiones incluidas; sube cuando cambian sus permisos u opciones. */
    const val ManifestVersion = "1.0.0"

    /** Version del CRM que trae las extensiones (igual a la del modulo; una prueba lo comprueba). */
    const val MinModuleVersion = "0.26.1"

    private val names = mapOf(
        CrmExtensionIds.Meta to "Meta (WhatsApp, Messenger e Instagram)",
        CrmExtensionIds.MondayImport to "Importacion desde monday",
        CrmExtensionIds.WordPress to "WordPress (Real Estate 7)",
        CrmExtensionIds.Encuentra24 to "Encuentra24",
        CrmExtensionIds.Cloudbeds to "Cloudbeds",
    )

    fun displayName(id: String): String = names[id] ?: id

    fun manifest(
        id: String,
        description: String,
        permissions: List<String>,
        options: List<ExtensionUninstallOption> = emptyList(),
    ) = ExtensionManifest(
        id = id,
        module = CrmModule.id,
        displayName = displayName(id),
        description = description,
        version = ManifestVersion,
        minModuleVersion = MinModuleVersion,
        source = ExtensionSource(),
        permissions = permissions,
        // La configuracion vive en las pantallas y tablas del CRM: ningun ajuste es obligatorio para activarla.
        settings = emptyList(),
        uninstall = ExtensionUninstallDefinition(options),
    )

    // ---- Estado en la empresa ----

    /** tenant_extensions.status de la extension, o nulo si no esta instalada (o la tabla aun no existe). */
    fun status(tenant: Connection, id: String): String? = try {
        tenant.prepareStatement("SELECT status FROM tenant_extensions WHERE extension_id = ?").use { query ->
            query.setString(1, id)
            query.executeQuery().use { rows -> if (rows.next()) rows.getString(1) else null }
        }
    } catch (failure: SQLException) {
        null
    }

    fun isEnabled(tenant: Connection, id: String): Boolean = status(tenant, id) == ExtensionStatus.Enabled

    /** Mensaje de las acciones y trabajos cuando la extension no esta activa en la empresa. */
    fun inactiveMessage(id: String) = "La extension ${displayName(id)} no esta activa en esta empresa."

    /** El mensaje de [inactiveMessage] si la extension no esta activa, o nulo. */
    fun inactive(tenant: Connection, id: String): String? = if (isEnabled(tenant, id)) null else inactiveMessage(id)

    /** Lo que muestran las pantallas de integraciones cuando la extension no esta activa; nulo si lo esta. */
    fun missingNodes(tenant: Connection, id: String): List<UiNode>? = when (status(tenant, id)) {
        ExtensionStatus.Enabled -> null
        ExtensionStatus.Installed, ExtensionStatus.Disabled -> listOf(notice(id, "La extension ${displayName(id)} esta desactivada. Activala desde Empresa > Extensiones."))
        ExtensionStatus.Incompatible -> listOf(notice(id, "La extension ${displayName(id)} es incompatible con esta version del CRM. Revisala desde Empresa > Extensiones."))
        else -> listOf(notice(id, "Instala la extension ${displayName(id)} desde Empresa > Extensiones."))
    }

    private fun notice(id: String, text: String): UiNode = UiCard(listOf(UiText(text, tone = UiTone.Warning)), title = displayName(id))

    // ---- Conteos para la vista previa de desinstalacion ----

    fun count(tenant: Connection, sql: String, vararg binds: String): Int = try {
        tenant.prepareStatement(sql).use { query ->
            binds.forEachIndexed { index, value -> query.setString(index + 1, value) }
            query.executeQuery().use { rows -> if (rows.next()) rows.getInt(1) else 0 }
        }
    } catch (failure: SQLException) {
        0
    }

    fun update(tenant: Connection, sql: String, vararg binds: String): Int =
        tenant.prepareStatement(sql).use { statement ->
            binds.forEachIndexed { index, value -> statement.setString(index + 1, value) }
            statement.executeUpdate()
        }

    fun item(optionId: String?, label: String, count: Int, action: String) = UninstallItem(optionId, label, count, action)

    // ---- Rutas publicas ----

    /** Maximo del cuerpo de un webhook entrante. */
    const val MaxWebhookBytes = 1024 * 1024

    /** Cuerpo del webhook, a lo sumo [MaxWebhookBytes]; si es mayor responde 413 y devuelve nulo (nunca lo lee entero en memoria). */
    suspend fun readBody(call: ApplicationCall): ByteArray? {
        val length = call.request.headers[io.ktor.http.HttpHeaders.ContentLength]?.toLongOrNull()
        if (length != null && length > MaxWebhookBytes) {
            call.respond(HttpStatusCode.PayloadTooLarge)
            return null
        }
        val body = withContext(Dispatchers.IO) { call.receiveChannel().toInputStream().use { it.readNBytes(MaxWebhookBytes + 1) } }
        if (body.size > MaxWebhookBytes) {
            call.respond(HttpStatusCode.PayloadTooLarge)
            return null
        }
        return body
    }

    // https://host de este servidor como lo vio el cliente (detras del proxy inverso si lo hay).
    fun publicBase(call: ApplicationCall): String {
        val scheme = call.request.headers["X-Forwarded-Proto"]?.substringBefore(',')?.trim() ?: call.request.origin.scheme
        val host = call.request.headers["X-Forwarded-Host"]?.substringBefore(',')?.trim() ?: call.request.headers["Host"] ?: call.request.origin.serverHost
        return "$scheme://$host"
    }

    /**
     * Abre la base de la empresa de un webhook y corre [block]; responde 404 (y devuelve nulo) cuando la empresa no existe o la
     * extension no esta activa en ella. [block] devuelve lo que responde la ruta.
     */
    suspend fun <T> withEnabledTenant(
        call: ApplicationCall,
        access: ExtensionAccess,
        company: String,
        // Los webhooks publicos responden igual (403, sin cuerpo) cuando la empresa no existe y cuando la firma es invalida.
        missing: HttpStatusCode = HttpStatusCode.NotFound,
        block: (Connection) -> T,
    ): T? {
        val connection = withContext(Dispatchers.IO) { access.enabledTenant(company) }
        if (connection == null) {
            call.respond(missing)
            return null
        }
        return try {
            connection.use { tenant -> withContext(Dispatchers.IO) { block(tenant) } }
        } catch (failure: SQLException) {
            call.respond(HttpStatusCode.InternalServerError)
            null
        }
    }

    // ---- Trabajo de arranque: integraciones activas -> extensiones, y secretos cifrados ----

    /** Integraciones ya configuradas en la empresa, por extension (token, pagina, clave o la integracion activa). */
    fun configuredIntegrations(tenant: Connection): Set<String> {
        fun has(sql: String) = count(tenant, sql) > 0
        return buildSet {
            if (has(
                    "SELECT count(*) FROM crm_meta_settings WHERE enabled OR coalesce(system_token, '') <> '' OR coalesce(page_id, '') <> '' " +
                        "OR coalesce(waba_id, '') <> '' OR coalesce(oauth_token, '') <> '' OR coalesce(messenger_token, '') <> '' OR coalesce(instagram_token, '') <> ''",
                )
            ) add(CrmExtensionIds.Meta)
            if (has("SELECT count(*) FROM crm_settings WHERE coalesce(monday_token, '') <> ''")) add(CrmExtensionIds.MondayImport)
            if (has("SELECT count(*) FROM crm_wordpress_settings WHERE enabled OR coalesce(secret, '') <> '' OR coalesce(site_url, '') <> ''")) add(CrmExtensionIds.WordPress)
            if (has("SELECT count(*) FROM crm_encuentra24_settings WHERE enabled OR coalesce(api_key, '') <> ''")) add(CrmExtensionIds.Encuentra24)
            if (has("SELECT count(*) FROM crm_cloudbeds_settings WHERE enabled OR coalesce(api_key, '') <> ''")) add(CrmExtensionIds.Cloudbeds)
        }
    }

    /** Registra [ids] como extensiones instaladas y activas (installed_by "migration"); lo que ya esta registrado no se toca. */
    fun registerEnabled(tenant: Connection, ids: Collection<String>): Int = ids.sumOf { id ->
        tenant.prepareStatement(
            """
            INSERT INTO tenant_extensions (extension_id, module_id, version, status, installed_by, enabled_at)
            VALUES (?, ?, ?, 'enabled', 'migration', CURRENT_TIMESTAMP)
            ON CONFLICT (extension_id) DO NOTHING
            """.trimIndent()
        ).use { insert ->
            insert.setString(1, id)
            insert.setString(2, CrmModule.id)
            insert.setString(3, ManifestVersion)
            insert.executeUpdate()
        }
    }

    /**
     * Una pasada del trabajo de arranque en una empresa: registra las integraciones configuradas como extensiones activas y cifra
     * los secretos que sigan en claro. Idempotente. Sin llave maestra solo registra y la marca queda pendiente, para cifrar despues.
     * Devuelve cuantas extensiones registro y cuantos secretos cifro.
     */
    fun migrateTenant(tenant: Connection, secrets: com.ideasdeveloper.idc.server.security.SecretBox, includeServerApp: Boolean = false): Pair<Int, Int> {
        val done = tenant.prepareStatement("SELECT extensions_migrated_at IS NOT NULL FROM crm_settings WHERE id = 1").use { query ->
            query.executeQuery().use { rows -> rows.next() && rows.getBoolean(1) }
        }
        if (done) return 0 to 0
        val registered = registerEnabled(tenant, configuredIntegrations(tenant))
        if (!secrets.available) return registered to 0
        // La app de Meta del dueno del servidor vive en el espacio del servidor.
        val sealed = CrmSecrets.sealAll(tenant, secrets, CrmSecrets.tenantColumns + if (includeServerApp) CrmSecrets.serverColumns else emptyList())
        tenant.prepareStatement(
            "INSERT INTO crm_settings (id, extensions_migrated_at) VALUES (1, CURRENT_TIMESTAMP) ON CONFLICT (id) DO UPDATE SET extensions_migrated_at = CURRENT_TIMESTAMP"
        ).use { it.executeUpdate() }
        return registered to sealed
    }
}
