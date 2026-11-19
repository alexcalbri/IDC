package com.ideasdeveloper.idc.modules.crm

import com.ideasdeveloper.idc.server.extensions.ServerExtension
import java.sql.Connection
import java.time.LocalDate
import java.math.BigDecimal
import java.time.LocalDateTime
import java.util.UUID

/** Una accion de automatizacion que aporta un proveedor: su id (el que se guarda en la regla) y su texto. */
data class CrmActionSpec(val id: String, val label: String)

/** Lo que una accion dejo hecho: el texto para la nota de la ejecucion. */
data class CrmActionResult(val message: String, val warning: Boolean = false)

/**
 * Lo que un proveedor necesita para ejecutar una accion sobre un lead.
 * [values] va vacio en las acciones que no piden datos (p. ej. las de fecha).
 */
class CrmActionContext(
    val tenant: Connection,
    val leadId: UUID,
    val lead: CrmLead,
    val stages: List<CrmStage>,
    val actor: CrmActor,
    val values: Map<String, String> = emptyMap(),
)

/**
 * Implementado por extensiones del CRM (p. ej. Cloudbeds) para aportar la ejecucion de acciones de
 * automatizacion sin que el CRM dependa de su codigo.
 */
interface CrmActionProvider {
    fun actionSpecs(): List<CrmActionSpec>
    fun execute(kind: String, context: CrmActionContext): CrmActionResult
}

/**
 * Capacidades de Cloudbeds que el CRM invoca sin depender de su código; implementada por la extensión Cloudbeds.
 * Todas reciben el [CrmHost] y la conexión de la empresa: la extensión usa la base del CRM solo por el facade.
 */
interface CloudbedsProvider : CrmActionProvider {
    /** Cloudbeds configurado y con su extensión activa en la empresa. */
    fun active(host: CrmHost, tenant: Connection): Boolean

    /** Prueba la conexión; devuelve un resumen o lanza [CrmCloudbedsException]. */
    fun test(host: CrmHost, tenant: Connection): String

    fun availability(host: CrmHost, tenant: Connection, start: LocalDate, end: LocalDate, adults: Int, children: Int): List<CbRoom>

    /** Crea la reserva de las habitaciones elegidas del lead. No mueve el lead de etapa: eso lo decide quien llama. */
    fun reserveForLead(host: CrmHost, tenant: Connection, lead: CrmLead, values: Map<String, String>, actor: CrmActor): CrmLeadItemActions.Reservation

    /** Asigna una habitación libre al lead; [today] resuelve las fechas por defecto de la estadía. */
    fun assignRoom(host: CrmHost, tenant: Connection, lead: CrmLead, params: Map<String, String>, actor: CrmActor, today: LocalDate): CrmLeadItemActions.Reservation

    /** Check-in de la primera reserva pendiente del lead; devuelve el texto del resultado. */
    fun checkInForLead(host: CrmHost, tenant: Connection, leadId: UUID, actor: CrmActor): String

    fun saveSettings(host: CrmHost, tenant: Connection, values: Map<String, String>, templates: List<CrmTemplate>)

    fun settings(host: CrmHost, tenant: Connection): CrmCloudbedsSettings

    /** Guarda el último error de la sincronización (vacío = sin error) y la hora de la última sincronización. */
    fun recordError(host: CrmHost, tenant: Connection, error: String)

    /** Enciende o apaga el correo de bienvenida de Cloudbeds (una automatización del CRM puede reemplazarlo). */
    fun setWelcomeEnabled(host: CrmHost, tenant: Connection, enabled: Boolean)

    /** Envía los correos de bienvenida que tocan; devuelve cuántos puso en cola. */
    fun sendWelcomes(host: CrmHost, tenant: Connection, now: LocalDateTime): Int

    /** Sincroniza el estado de las reservas abiertas; devuelve cuántas cambiaron. */
    fun syncStatuses(host: CrmHost, tenant: Connection, today: LocalDate): Int
}

/**
 * Capacidades de Encuentra24 que el CRM invoca sin depender de su código; implementada por la extensión Encuentra24.
 */
interface Encuentra24Provider {
    /** Encuentra24 activo en la empresa y con API key y slug: lo que decide si el trabajo periódico importa. */
    fun active(host: CrmHost, tenant: Connection): Boolean

    fun settings(host: CrmHost, tenant: Connection): CrmEncuentra24Settings

    /** Guarda el formulario de Integraciones; lanza [CrmValidationException] si algo no es válido. */
    fun saveForm(host: CrmHost, tenant: Connection, values: Map<String, String>)

    /** Importa los leads desde [from] (null = desde la última sincronización) o lanza [CrmEncuentra24Exception]. */
    fun sync(host: CrmHost, tenant: Connection, from: LocalDate?): CrmEncuentra24SyncResult

    /** Si [text] trae el enlace de un anuncio de Encuentra24, lo agrega a los intereses del lead (si la extensión está activa). */
    fun detectAd(host: CrmHost, tenant: Connection, leadId: UUID, text: String)
}

/**
 * Capacidades de WordPress (sitio con Real Estate 7 y el plugin IDC Connector) que el CRM invoca sin depender de su código;
 * implementada por la extensión WordPress. Todas reciben el [CrmHost] y la conexión de la empresa.
 */
interface WordPressProvider {
    /** WordPress listo (conectado, con secreto) y con su extensión activa en la empresa. */
    fun active(host: CrmHost, tenant: Connection): Boolean

    /** Los ajustes con el secreto ya abierto; sin fila, los de por defecto con WordPress apagado. */
    fun settings(host: CrmHost, tenant: Connection): CrmWordPressSettings

    /** Guarda el formulario de Integraciones; lanza [CrmValidationException] si algo no es válido. */
    fun saveForm(host: CrmHost, tenant: Connection, values: Map<String, String>)

    /** Crea un secreto nuevo y lo devuelve para pegarlo en el plugin. */
    fun newSecret(host: CrmHost, tenant: Connection): String

    /** Prueba la conexión; devuelve un resumen o lanza [CrmWordPressException]. */
    fun ping(host: CrmHost, tenant: Connection): String

    fun search(host: CrmHost, tenant: Connection, text: String, filters: Map<String, String>, minPrice: String, maxPrice: String, page: Int): WpSearchResult

    fun listing(host: CrmHost, tenant: Connection, id: Long): WpListing

    fun taxonomies(host: CrmHost, tenant: Connection): Map<String, List<String>>

    /** Marca la propiedad como vendida en el sitio. */
    fun markSold(host: CrmHost, tenant: Connection, id: Long, price: BigDecimal?, date: LocalDate)

    /** Lee los agentes del sitio y vincula a los nuevos por correo; devuelve cuántos leyó. */
    fun syncAgents(host: CrmHost, tenant: Connection, users: List<CrmUser>, userEmails: Map<UUID, String>): Int

    fun agents(host: CrmHost, tenant: Connection): List<WpAgent>

    fun mapAgent(host: CrmHost, tenant: Connection, wpUserId: Long, userId: UUID?)

    /** Vincula la propiedad al lead como interés; [activity] es el texto de la actividad (null = el de por defecto). */
    fun linkListing(host: CrmHost, tenant: Connection, leadId: UUID, listing: WpListing, activity: String?)

    /** Guarda el último error del sitio (vacío = sin error) y la hora del último evento. */
    fun recordError(host: CrmHost, tenant: Connection, error: String)
}

/**
 * Capacidades de Meta (WhatsApp, Messenger e Instagram) que el CRM invoca sin depender de su codigo; implementada por la extension Meta.
 * Todas reciben el [CrmHost] y la conexion de la empresa. Los canales de respuesta (CrmWhatsAppChannel, CrmPageChannel) siguen en el CRM.
 */
interface MetaProvider {
    /** Meta activado en la empresa y con su extension activa. */
    fun active(host: CrmHost, tenant: Connection): Boolean

    /** Los ajustes con los secretos ya abiertos; sin fila, los de por defecto con Meta apagado. */
    fun settings(host: CrmHost, tenant: Connection): CrmMetaSettings

    /** Guarda los ajustes (upsert: sin fila, la crea); los secretos se sellan. */
    fun save(host: CrmHost, tenant: Connection, settings: CrmMetaSettings)

    /** Aplica el formulario de Meta; los secretos en blanco o enmascarados conservan los guardados. Lanza [CrmValidationException]. */
    fun merge(current: CrmMetaSettings, values: Map<String, String>): CrmMetaSettings

    fun toFormValues(settings: CrmMetaSettings): Map<String, String>

    /** Conecta lo que permiten los ajustes (pagina, Instagram, WhatsApp) y guarda el resultado; lanza [CrmMetaException] si la extension no esta activa. */
    fun setup(host: CrmHost, tenant: Connection, settings: CrmMetaSettings, page: Boolean, whatsApp: Boolean): MetaSetup

    fun log(host: CrmHost, tenant: Connection, event: String, detail: String)

    fun recent(host: CrmHost, tenant: Connection): List<MetaLogEntry>

    /** Pide a Meta el nombre de los contactos de Messenger e Instagram que siguen sin uno; lanza [CrmMetaException]. */
    fun fillNames(host: CrmHost, tenant: Connection, settings: CrmMetaSettings, limit: Int): MetaNameFill

    /** Envia un texto por WhatsApp y devuelve el wamid; lanza [CrmMetaException]. */
    fun sendWhatsApp(host: CrmHost, settings: CrmMetaSettings, toDigits: String, text: String): String

    /** Envia un texto por Messenger o Instagram ([platform]) y devuelve el id del mensaje; lanza [CrmMetaException]. */
    fun sendPage(host: CrmHost, settings: CrmMetaSettings, recipient: String, text: String, platform: String): String

    /** Olvida los tokens y activos de la empresa y deja de enrutar sus webhooks (app del servidor). */
    fun disconnect(host: CrmHost, tenant: Connection, company: String)

    // ---- App de Meta del servidor ----

    /** La app del servidor, o null si el servidor no tiene una. */
    fun serverApp(host: CrmHost): CrmMetaServerApp?

    /** Codigo de la empresa que es el espacio del servidor, o null. */
    fun serverWorkspace(host: CrmHost): String?

    fun loadApp(host: CrmHost, tenant: Connection): CrmMetaServerApp

    fun saveApp(host: CrmHost, tenant: Connection, app: CrmMetaServerApp)

    fun mergeApp(current: CrmMetaServerApp, values: Map<String, String>): CrmMetaServerApp

    fun appFormValues(app: CrmMetaServerApp): Map<String, String>

    /** Enlace de un solo uso (30 minutos) para la pagina de conexion de la empresa. */
    fun newTicket(host: CrmHost, tenant: Connection, createdBy: String): String
}

/** Registro de los proveedores de acciones entre las extensiones del CRM (las entrega el Core al arrancar). */
object CrmModuleExtensions {
    @Volatile
    private var providers: List<CrmActionProvider> = emptyList()

    @Volatile
    private var linked: List<ServerExtension> = emptyList()

    // Se recalcula solo en link(): la referencia cambia con cada enlace y asi se invalida la cache de CrmScreenProviders.
    @Volatile
    private var screens: List<CrmScreenProvider> = emptyList()

    @Volatile
    private var targets: List<CrmImportTarget> = emptyList()

    fun link(extensions: List<ServerExtension>) {
        linked = extensions
        providers = extensions.filterIsInstance<CrmActionProvider>()
        screens = extensions.filterIsInstance<CrmScreenProvider>()
        targets = extensions.filterIsInstance<CrmImportTarget>()
    }

    /** Extensiones que reciben datos importados por otras extensiones (ver [CrmImportTarget]). */
    fun importTargets(): List<CrmImportTarget> = targets

    fun actionProviders(): List<CrmActionProvider> = providers

    fun providerFor(kind: String): CrmActionProvider? = providers.firstOrNull { p -> p.actionSpecs().any { it.id == kind } }

    fun hasAction(kind: String): Boolean = providerFor(kind) != null

    /** El proveedor de Cloudbeds, o null si la extensión no está instalada. */
    fun cloudbeds(): CloudbedsProvider? = actionProviders().filterIsInstance<CloudbedsProvider>().firstOrNull()

    /** El proveedor de Encuentra24, o null si la extensión no está instalada. */
    fun encuentra24(): Encuentra24Provider? = linked.filterIsInstance<Encuentra24Provider>().firstOrNull()

    /** El proveedor de WordPress, o null si la extensión no está instalada. */
    fun wordpress(): WordPressProvider? = linked.filterIsInstance<WordPressProvider>().firstOrNull()

    /** El proveedor de Meta, o null si la extensión no está instalada. */
    fun meta(): MetaProvider? = linked.filterIsInstance<MetaProvider>().firstOrNull()

    /** Fuentes externas de importacion que traen las extensiones instaladas (ver [CrmImportSources]). */
    fun importSources(): List<CrmImportSource> = linked.filterIsInstance<CrmImportSource>()

    /** Extensiones que aportan pantallas, acciones, herramientas MCP y un bloque en la ficha del lead (ver [CrmScreenProvider]). */
    fun screenProviders(): List<CrmScreenProvider> = screens

    /** Cloudbeds instalado, configurado y activo en la empresa; false si la extensión no está instalada. */
    fun cloudbedsActive(tenant: Connection): Boolean = cloudbeds()?.active(CrmHost, tenant) ?: false
}
