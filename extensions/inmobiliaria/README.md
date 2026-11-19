# Extensión `inmobiliaria` (CRM) — 1.0.0

Convierte el CRM en una herramienta de corretaje inmobiliario: inventario maestro de propiedades, captaciones,
transacciones (deals) con sus partes y comisiones, vistas por agente y herramientas MCP. Pensada para reemplazar los
tableros de monday.com "All Real Estate Listings", "Deals", "New listing", "Sellers | New upcoming listings" y los
tableros por agente. Diseño y decisiones de producto: [DISENO.md](DISENO.md).

- Módulo: `crm`, versión mínima **0.28.0** (usa el punto de enganche `CrmScreenProvider`).
- Permisos del paquete: `crm.items.write`, `crm.leads.update`. Sin red ni secretos.
- Migración: `migrations/V001__inmobiliaria.sql` (tablas `ext_inmobiliaria__*`, sin FK a tablas ajenas).
- No depende de `wordpress` ni de `encuentra24`. Si WordPress está activo se puede traer un listado del sitio y, al
  cerrar la venta, marcarlo vendido allí.

## Qué añade

| Pantalla (menú del CRM) | Permiso | Qué hace |
| --- | --- | --- |
| Propiedades (`inmobiliaria_inventory`) | `crm.view` | Inventario filtrable (texto, zona, tipo, estado, agente, precio, "Mis listados"), con "Rebajada" y precio por m². |
| Captaciones (`inmobiliaria_intake`) | `crm.view` | Propiedades pendientes de publicar ("Mis próximos listados") y botón Publicar. |
| Cierres (`inmobiliaria_deals`) | `crm.view` | Transacciones visibles, filtros, "Mis cierres" y cierres estimados por mes (cantidad, precio, comisión). |
| Ficha de propiedad (`inmobiliaria_property`) | `crm.view` | Datos, enlaces por portal (solo http/https), historial de precios, sus transacciones, vincular a un lead. |
| Ficha de transacción (`inmobiliaria_deal`) | `crm.view` | Partes con % y monto, etapa, cierre estimado y real, comentarios. |
| Etapas (`inmobiliaria_stages`) | `crm.settings` | Etapas de la transacción (plantilla ERE) y ajustes (moneda, % de comisión por defecto). |

En la ficha del lead aparece un bloque con sus transacciones como comprador y las propiedades vinculadas.

### Reglas

- **Comisión:** % sobre el precio acordado (o monto total a mano). Cada parte tiene su % **de la comisión total**; la
  ficha avisa si no suman 100 %. Los montos por parte se redondean a 2 decimales.
- **Estados de la propiedad:** Captación → En venta → En negociación → Vendida / Retirada. "Rebajada" se muestra sola
  cuando el precio baja. Una propiedad tiene a lo sumo una transacción ganada; mover transacciones actualiza el estado
  de la propiedad según `InmobiliariaRules.propertyStatusAfter`.
- **Visibilidad:** con `crm.view` se ve todo el inventario; precio y estado los editan el agente del listado, quien la
  creó y quien tiene `crm.leads.all`. Cada agente ve sus transacciones (creadas por él, donde es parte, o de sus
  listados); `crm.leads.all` ve todas. Leads y clientes enlazados solo se muestran o aceptan si el usuario los ve.

## Herramientas MCP

Se publican como `crm_<nombre>` y heredan los permisos vivos del usuario:
`inmobiliaria_search_properties`, `inmobiliaria_get_property`, `inmobiliaria_update_property` (precio y estado),
`inmobiliaria_list_deals`, `inmobiliaria_create_deal`, `inmobiliaria_advance_deal`, `inmobiliaria_closings_by_month`.

## Importar desde otras fuentes

La extensión implementa `CrmImportTarget` (CRM 0.28.0): otra extensión de un solo uso (p. ej. `inmobiliaria_monday`) le
entrega lotes de registros con una clave estable (`import_key`, hasta 120 caracteres, p. ej. `monday:<itemId>`) y
reimportar **actualiza, nunca duplica**. Dos tipos:

- `property`: `title`*, `code`, `kind`, `zone`, `development`, `status`, `agent` (nombre o correo de un usuario del CRM),
  `owner_name`/`owner_email`/`owner_phone`, `lot_area`, `built_area`, `bedrooms`, `bathrooms`, `price`, `previous_price`,
  `currency`, `description`, `notes`, enlaces `link_website`, `link_website_es`, `link_craigslist`, `link_encuentra24`,
  `link_drive`, `link_other` (solo http/https) e `intake`.
- `deal`: `property`* (clave de importación, código o título exacto), `buyer_name`/`buyer_email`, `stage`, `agreed_price`,
  `commission_pct`, `commission_amount`, `estimated_close_on`, `closed_on`, `comments` y partes por nombre separadas por coma
  (`seller`, `seller_agent`, `seller_lawyer`, `seller_referral`, `buyer`, `buyer_agent`, `buyer_lawyer`, `agents`, `brokers`).

Reglas: estados FOR SALE/REDUCED → En venta, SOLD → Vendida, vacío → En venta (Captación con `intake`); fechas
`AAAA-MM-DD` y `MM/DD/AAAA`. `intake` solo vale **al crear**: al actualizar nunca se baja una propiedad a Captación. Un
estado o un tipo desconocido no omite la fila: se usa el de por defecto (al actualizar se deja el que tenía) y queda un aviso.
Un agente o vendedor que no se encuentra queda escrito en las notas; el comprador se enlaza al lead abierto con ese correo (si
es visible) o queda como parte. Al reimportar, lo que la fuente trae vacío no borra lo que ya hay, el precio cambia con
`changePrice` (con historial) y los efectos de etapa solo se aplican si la etapa cambia.

- **Deduplicación entre tableros:** si no hay propiedad con esa `import_key`, se busca por `code` (sin distinguir
  mayúsculas) y, si el registro no trae código, por título normalizado + zona cuando hay UNA sola coincidencia. Si se encuentra
  se actualiza esa propiedad y, si no tenía `import_key`, se le asigna la de este registro; si ya tenía otra, se deja y el
  aviso dice "Fusionada con <código o título>".
- **Etapas:** el nombre se recorta a 60 caracteres. Una etapa abierta desconocida usa la primera abierta (con aviso); una
  ganada o perdida usa la primera con ese resultado y solo se crea si no existe ninguna.
- **Transacciones sobre propiedades Vendidas/Retiradas:** no se reabren; la transacción se crea sin efectos sobre la propiedad
  (con aviso). Una Ganada sobre una propiedad que ya tiene una ganada se omite con error. Sin `closed_on` se usa la fecha de
  venta de la propiedad y, si no hay, hoy (con aviso).
- **Sin efectos externos:** la importación no llama a WordPress ni marca nada vendido allí.
- **Topes:** hasta 500 registros por lote (si no, se rechaza entero con `failedKeys` de todos) y 5000 caracteres por valor;
  los enlaces por encima de 20 se recortan con aviso.
- **Resultado:** `problems` solo lleva errores (registros no guardados, cuya clave va a `failedKeys`); los avisos de registros
  guardados van a `warnings` ("<importKey>: aviso").
- **Permisos y confianza:** el importador ve todo (actúa como gerente con `crm.settings`). `CrmImportTarget` está dentro del
  modelo de confianza de extensiones: cualquier extensión del CRM podría llamarlo, así que la llamante debe comprobar
  `crm.settings` antes. La clave `import_key` queda en los datos aunque la extensión de origen se desinstale.

## Desinstalar

Los ajustes y las etapas sin transacciones se borran siempre. Opciones: `properties` (inventario, enlaces, historial y
los items de leads con `source=inmobiliaria`) y `deals` (transacciones y sus partes), ambas `keep` por defecto. Los
leads y los clientes nunca se borran.

## Código y pruebas

`server/src/main/kotlin/com/ideasdeveloper/idc/extensions/inmobiliaria/`: `InmobiliariaServerExtension` (+ proveedor),
`InmobiliariaModel` (tipos y cálculos puros), `InmobiliariaAccess` (permisos), `InmobiliariaStore` (SQL parametrizado),
`InmobiliariaService` (operaciones), `InmobiliariaImport` (destino de importación), `InmobiliariaMcpTools`, `InmobiliariaScreens`/`*PropertyScreens`/`*DealScreens`/`Ui`.

El build central no compila `extensions/**`. Para compilar y probar este paquete junto con el servidor sin tocar el
build, se usa un init script de Gradle que añade sus fuentes al sourceSet de pruebas de `:server`:

```groovy
// verify-ext.init.gradle
allprojects {
    if (path == ':server') {
        afterEvaluate {
            def id = findProperty('verifyExtension')
            if (id == null) return
            def dir = new File(rootDir, "extensions/${id}/server/src")
            kotlin.sourceSets.test.kotlin.srcDir(new File(dir, 'main/kotlin'))
            kotlin.sourceSets.test.kotlin.srcDir(new File(dir, 'test/kotlin'))
            sourceSets.test.resources.srcDir(new File(dir, 'main/resources'))
        }
    }
}
```

```sh
./gradlew -I verify-ext.init.gradle -PverifyExtension=inmobiliaria :server:test --tests '*inmobiliaria*'
```

Las pruebas son puras (sin base de datos): cálculos, permisos, máquina de estados de la venta, formularios, y que la
migración pasa `MigrationTableValidator`. El SQL del almacén aún no se ha probado contra PostgreSQL real.

## Versiones

- **1.0.0** — primera versión: inventario, captaciones, transacciones con partes y comisiones, etapas configurables
  (plantilla ERE), vistas por agente, cierres por mes, panel en la ficha del lead y 7 herramientas MCP.
