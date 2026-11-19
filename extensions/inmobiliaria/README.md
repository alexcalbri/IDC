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

## Desinstalar

Los ajustes y las etapas sin transacciones se borran siempre. Opciones: `properties` (inventario, enlaces, historial y
los items de leads con `source=inmobiliaria`) y `deals` (transacciones y sus partes), ambas `keep` por defecto. Los
leads y los clientes nunca se borran.

## Código y pruebas

`server/src/main/kotlin/com/ideasdeveloper/idc/extensions/inmobiliaria/`: `InmobiliariaServerExtension` (+ proveedor),
`InmobiliariaModel` (tipos y cálculos puros), `InmobiliariaAccess` (permisos), `InmobiliariaStore` (SQL parametrizado),
`InmobiliariaService` (operaciones), `InmobiliariaMcpTools`, `InmobiliariaScreens`/`*PropertyScreens`/`*DealScreens`/`Ui`.

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
