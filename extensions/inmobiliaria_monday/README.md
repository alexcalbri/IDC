# Extensión `inmobiliaria_monday` (CRM) — 1.0.0

Migración de un solo uso desde monday.com hacia la extensión [`inmobiliaria`](../inmobiliaria/README.md): reemplaza los
tableros de listados ("All Real Estate Listings", "New listing", "Sellers | New upcoming listings") y el de transacciones
("Deals"). Se instala, se importa y se desinstala; lo importado queda en `inmobiliaria`. Diseño: [DISENO.md](DISENO.md).

- Módulo: `crm`, versión mínima **0.28.0**. Requiere la extensión `inmobiliaria` activa en la empresa (si no, la pantalla lo
  avisa y no importa nada).
- Permisos del paquete: `network.outbound:api.monday.com`, `secrets.write`, `crm.items.write`.
- Migración: `migrations/V001__inmobiliaria_monday.sql` (`ext_inmobiliaria_monday__settings`, `__jobs`, `__rows`).
- Los leads ("Initial leads", tableros por agente) los importa la extensión `monday`; las tareas por agente son del módulo `tareas`.

## Flujo

1. **Importar > monday - Inmobiliaria** (o la pantalla oculta `inmobiliaria_monday_import`, permiso `crm.settings`).
2. Guardar el **token** de la API de monday (se guarda sellado y no se vuelve a mostrar).
3. **Cargar tableros**, elegir tablero y qué contiene (propiedades o transacciones). En tableros de nuevos listados,
   marcar la casilla para que las propiedades entren como Captación.
4. El tablero se **lee en segundo plano** (por páginas de 100, hasta 20 000 filas) a `__rows`. El nombre del elemento y su
   grupo se ofrecen como columnas **Nombre** y **Grupo** (Nombre se sugiere como título de la propiedad o propiedad de la
   transacción). Cada valor se recorta a 5000 caracteres y el nombre a 300.
5. **Revisar**: mapeo columna → campo (sugerido por el título, en inglés o español) y vista previa de 10 filas. Un mapeo
   enviado por un agente que no coincide con las columnas reales se reemplaza por el sugerido y la importación queda lista
   con un aviso.
6. **Aplicar**: lotes de 100 con el contrato de importación de Inmobiliaria, como el usuario que **pulsó Aplicar**. En cada
   corrida se vuelve a comprobar que ese usuario sigue activo y es dueño del negocio o tiene `crm.settings`; si no, la
   importación falla ("El usuario que aplicó la importación ya no tiene permiso."). Termina con creadas / actualizadas /
   omitidas / con problema (se guardan hasta 200 problemas y avisos). Solo fallan los registros que el destino no guardó;
   los avisos de registros guardados quedan como texto de su fila.

Cada elemento entra con la clave `monday:<id del elemento>`: reimportar el mismo tablero actualiza, no duplica.

- **Orden:** aplicar transacciones exige antes una importación de propiedades terminada (o propiedades de monday ya
  importadas); si no, "Importa primero las propiedades.".
- **Una a la vez:** solo una importación leyendo o aplicando (índice único parcial por tablero y comprobación global).
- **Errores pasajeros** de monday (red, 429, 5xx, límite de complejidad): la importación sigue leyendo y reintenta en la
  siguiente corrida; tras 5 intentos seguidos falla. Un token rechazado falla de inmediato.
- **Presupuesto:** cada corrida usa 50 s; no se lanza otra consulta con menos de 3 s y el plazo HTTP de cada consulta no
  pasa del tiempo restante. Lo que no alcanza sigue desde el cursor guardado.
- `rows_read` cuenta filas realmente nuevas (monday puede repetir un elemento entre páginas; reemplaza su fila).

Herramientas MCP (`crm_inmobiliaria_monday_*`, permiso `crm.settings`; nunca reciben ni devuelven el token):
`list_boards`, `start_import`, `status` (problemas limitados a 50 de 300 caracteres y vista previa recortada), `apply`.

## Desinstalar

Borra siempre el token. La opción **Historial de importaciones** (por defecto, borrar) elimina las importaciones y sus
filas leídas; si se conserva, el aviso recuerda que contiene datos de clientes (nombres, correos y teléfonos). Las
propiedades y transacciones ya importadas se conservan en `inmobiliaria`.
