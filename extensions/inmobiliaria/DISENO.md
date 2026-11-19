# Extensión `inmobiliaria` 1.0.0 — diseño

Convierte el CRM en una herramienta de corretaje (inventario de propiedades, captaciones, transacciones con partes y
comisiones, vistas por agente y herramientas MCP). Reemplaza en monday.com: "All Real Estate Listings", "Deals",
"Initial leads"/"New listing"/"Sellers | New upcoming listings" y los tableros por agente.

## Investigación (resumen)

- Follow Up Boss: fuerte en enrutar leads y en un pipeline de "deals" simple por etapas; no hace back office. Adoptar:
  pipeline de deals separado del pipeline de leads, vista "lo mío" por agente.
- Brokermint / Lone Wolf BrokerWolf (back office): reparto de comisión por participante con plantillas, referidos,
  auditoría. Adoptar: partes de la transacción con rol y % propio, y el monto calculado. Evitar: contabilidad, caps,
  1099/facturación (fuera de alcance).
- Propertybase (sobre Salesforce): contactos, listados y transacciones en un mismo sistema; publica listados en
  portales. Adoptar: un inventario maestro con enlaces por portal. Evitar: la configuración pesada (campos fijos y
  claros en vez de un constructor genérico).
- Patrón común: CRM (leads) + back office (comisiones) en dos productos unidos por Zapier. Aquí van juntos y ligados
  al lead y al cliente sin integración externa.
- Historial de precios y "rebajada" automática: lo hacen los portales (Zillow, Encuentra24); conviene adoptarlo en vez
  de un estado manual.

## Decisiones de producto (usuario, 2026-11-18)

1. Comisión: la transacción tiene un % de comisión sobre el precio acordado; **cada participante tiene su propio %**.
   Modelo elegido: el % de cada parte es **sobre la comisión total** (la suma debería dar 100 %; la pantalla avisa si
   no). Monto de cada parte = comisión total × % / 100. Se puede fijar un monto total a mano (sobrescribe el cálculo).
2. Etapas de la transacción: plantilla ERE ampliada, editable por empresa: Oferta → Esperando CNO → Due diligence →
   Cierre programado → Cerrada (ganada) / Cancelada (perdida).
3. Estados de la propiedad: Captación → En venta → En negociación → Vendida / Retirada. "Rebajada" no es un estado: se
   muestra cuando el precio actual es menor que el anterior (historial de precios). El estado manual Vendida o Retirada se
   rechaza si la propiedad tiene transacciones abiertas ("Cierra o cancela primero sus transacciones abiertas.").
   Máquina de estados de la venta (`InmobiliariaRules.propertyStatusAfter`): entrar en Ganada → Vendida; salir de Ganada →
   sigue Vendida si otra transacción está ganada, si no En negociación (hay otra abierta o la transacción pasa a Abierta) o
   En venta; entrar en Perdida → En venta si estaba En negociación y no queda otra abierta; entrar en Abierta → En
   negociación si estaba En venta. Retirada y Captación solo cambian al entrar en Ganada.
   Una propiedad tiene a lo sumo UNA transacción ganada: entrar en Ganada con otra ya ganada se rechaza ("Esa propiedad ya
   tiene una transacción cerrada."). Una etapa con transacciones no puede cambiar de resultado. Al salir de Ganada con
   listado de WordPress se avisa que el sitio sigue marcándola vendida.
4. Visibilidad: todos los usuarios con `crm.view` ven el inventario completo; editan precio/estado solo el agente del
   listado, quien la creó, y quien tiene `crm.leads.all` (gerente). Transacciones: cada agente ve las suyas (es parte
   con su usuario, la creó, o es el agente del listado de la propiedad); `crm.leads.all` ve todas.
   Efectos laterales: al crear o mover una transacción, el estado de la propiedad (En negociación, Vendida, En venta, fecha
   de venta) y la marca de vendida en WordPress solo se aplican si el usuario puede editar esa propiedad (agente del
   listado, quien la creó o gerente); si no, se mueve solo la transacción y se avisa "La propiedad no se actualizó". El item
   del lead comprador se actualiza solo si el lead es visible para el usuario. En las herramientas MCP, el lead y el cliente
   comprador (y el cliente de una parte) salen solo si están al alcance del usuario.

## Punto de enganche nuevo en el CRM (CRM 0.28.0, aditivo)

`modules/crm/server/.../CrmScreenProvider.kt`:

```kotlin
data class CrmExtensionSection(val id: String, val label: String, val permission: String, val inMenu: Boolean = true)
data class CrmExtensionActionResult(val screen: String, val notice: UiNotice?, val params: Map<String, String> = emptyMap(), val submitted: Map<String, String>? = null)

interface CrmScreenProvider {
    val extensionId: String
    val sections: List<CrmExtensionSection>          // ids con prefijo "<extensionId>_"
    fun screen(context: CrmScreens.Context, screen: String, params: Map<String, String>, submitted: Map<String, String>?): List<UiNode>
    fun action(context: CrmScreens.Context, actor: CrmActor, screen: String, op: String, params: Map<String, String>, values: Map<String, String>): CrmExtensionActionResult
    val mcpTools: List<McpTool> get() = emptyList()
    fun leadPanel(context: CrmScreens.Context, lead: CrmLead): List<UiNode> = emptyList()
}
```

El CRM: lista los proveedores con `CrmModuleExtensions.screenProviders()`; muestra sus secciones en el menú solo si la
extensión está activa en la empresa y el usuario tiene el permiso; abre/ejecuta sus pantallas y acciones con el mismo
control de permiso que las propias; agrega sus `mcpTools` al MCP del CRM; añade `leadPanel` al final de la ficha del
lead. Un fallo del proveedor se muestra como aviso y nunca rompe el CRM. Ids que choquen con pantallas del CRM se ignoran.

## Datos (`migrations/V001__inmobiliaria.sql`, tablas `ext_inmobiliaria__*`)

Sin FK a tablas del CRM ni de clientes (el validador lo prohíbe): `lead_id`, `customer_id`, `user_id` son UUID sin FK y
el código comprueba que existan y que el usuario los vea. Dinero en `numeric(14,2)`, porcentajes `numeric(6,3)`.

- `ext_inmobiliaria__settings` (id=1): `currency char(3) default 'USD'`, `default_commission_pct`, `updated_at`.
- `ext_inmobiliaria__properties`: id, `code` (referencia única, p. ej. ERE-0123), `title`, `kind`
  (land, house, condo, development_lot, commercial, other), `zone` (barrio/zona), `development`, `status`
  (intake, for_sale, negotiation, sold, withdrawn), `listing_agent_id`, `owner_customer_id` (vendedor en clientes),
  `lot_area_m2`, `built_area_m2`, `bedrooms smallint`, `bathrooms numeric(4,1)`, `price`, `previous_price`,
  `currency`, `description`, `notes`, `wordpress_listing_id bigint` (opcional), `published_on date`, `sold_on date`,
  `created_by`, `created_at`, `updated_at`. Precio por m² (lote y construcción) se calcula en código.
- `ext_inmobiliaria__property_links`: property_id (FK propia, cascade), `label` (Sitio web, Sitio ES, Craigslist,
  Encuentra24, Google Drive u otra), `url` CHECK `^https?://`, `position`.
- `ext_inmobiliaria__price_changes`: property_id (FK propia, cascade), `old_price`, `new_price`, `changed_by`,
  `changed_at`, `note`.
- `ext_inmobiliaria__deal_stages`: id, `name`, `position`, `outcome` (open, won, lost). La plantilla se inserta desde
  código en `onEnable` si la tabla está vacía (sin INSERT en la migración).
- `ext_inmobiliaria__deals`: id, `property_id` (FK propia, restrict), `buyer_lead_id`, `buyer_customer_id`,
  `stage_id` (FK propia), `agreed_price`, `commission_pct`, `commission_amount` (nulo = calculado), `currency`,
  `estimated_close_on`, `closed_on`, `comments`, `created_by`, `created_at`, `updated_at`.
- `ext_inmobiliaria__deal_parties`: deal_id (FK propia, cascade), `role` (seller, buyer, listing_agent, buyer_agent,
  broker, seller_lawyer, buyer_lawyer, referral, other), `user_id` (agente interno) o `customer_id` o `name` (externo),
  `share_pct` (de la comisión), `notes`.

- `import_key varchar(120)` (nula) en `properties` y `deals`, con índice único parcial `WHERE import_key IS NOT NULL`:
  clave estable de la fuente de una importación.

## Importación (`CrmImportTarget`, CRM 0.28.0)

`InmobiliariaServerExtension` implementa `CrmImportTarget` y delega en `InmobiliariaImport` (tipos `property` y `deal`; campos y
reglas en el README y en `extensions/inmobiliaria_monday/DISENO.md`). Decisiones: la interfaz solo da `tenant` y `actor`, así que
se arma una `InmobiliariaSession` con permisos de importación (`crm.view`, `crm.leads.manage`, `crm.leads.all`, `crm.settings`) y la
extensión llamante DEBE comprobar `crm.settings` del usuario. Un lote corre en una transacción y cada registro en un savepoint
(un fallo va a `problems` y cuenta como omitido). Upsert por `import_key`: crea con el servicio o actualiza
(`updateProperty`, `changePrice`, `replaceLinks` por etiqueta, `changeStatus` solo si no lo lleva el motor de transacciones;
`updateDeal` y `moveDeal` solo si cambia la etapa). Una transacción sobre una propiedad vendida o retirada la reabre un instante
(el servicio no abre transacciones sobre ellas) y la restaura salvo que la venta se cierre.

## Pantallas (secciones del CRM)

- `inmobiliaria_inventory` "Propiedades" (menú, `crm.view`): filtros texto, zona, tipo, estado, agente, precio mín/máx,
  "Mis listados"; tabla código, título, zona, tipo, estado (+ insignia Rebajada), precio, $/m², agente. Botón
  "Nueva captación" (`crm.leads.manage`).
- `inmobiliaria_property` (oculta): ficha y formulario, enlaces por portal, historial de precios, transacciones de la
  propiedad, "Vincular a un lead" (crea un item `source=inmobiliaria` en el lead) y, si WordPress está activo,
  "Traer datos del sitio" por id de listado.
- `inmobiliaria_intake` "Captaciones" (menú, `crm.view`): propiedades en Captación (pendientes de publicar),
  filtro "Mis próximos listados", acción "Publicar" (pasa a En venta).
- `inmobiliaria_deals` "Cierres" (menú, `crm.view`): transacciones visibles, filtros etapa/agente/"Mis cierres", y
  resumen de cierres estimados por mes (cantidad, precio y comisión; métrica y gráfico de barras).
- `inmobiliaria_deal` (oculta): ficha, partes con % y monto, aviso si los % no suman 100, mover de etapa; al pasar a
  una etapa ganada la propiedad queda Vendida (fecha de cierre real) y, si hay lead comprador, su item pasa a `sold`.
- `inmobiliaria_stages` (oculta, `crm.settings`): editar etapas.
- `leadPanel`: en la ficha del lead, sus transacciones como comprador y las propiedades vinculadas.

## MCP (nombres `crm_inmobiliaria_*` ≤ 64)

`inmobiliaria_search_properties` (view, solo lectura), `inmobiliaria_get_property` (view, solo lectura),
`inmobiliaria_update_property` (manage: precio, estado; misma regla de edición), `inmobiliaria_list_deals` (view,
solo lectura; respeta visibilidad), `inmobiliaria_create_deal` (manage), `inmobiliaria_advance_deal` (manage; mover
etapa o fijar fechas), `inmobiliaria_closings_by_month` (view, solo lectura). Todas comprueban que la extensión esté
activa (`CrmHost.extensionInactiveReason`).

## Relación con otras extensiones

No depende de `wordpress` ni de `encuentra24`: si WordPress está activo se puede importar un listado y marcarlo vendido
en el sitio al cerrar (`CrmModuleExtensions.wordpress()`); Encuentra24 queda como un enlace de portal. Los items de
lead con `source=wordpress` no se duplican: la propiedad guarda `wordpress_listing_id`.

## Fuera de la 1.0.0

Subelementos/tareas por propiedad (módulo `tareas`), documentos (Google Drive queda como enlace), publicación
automática en portales, contabilidad de comisiones pagadas.
