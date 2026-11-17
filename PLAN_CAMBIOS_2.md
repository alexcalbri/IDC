# Plan de cambios 2

Estado: en armado (se van agregando cambios). Mismo flujo de trabajo que PLAN_CAMBIOS.md.

## 1. La barra superior nunca queda tapada por el contenido (app) — IMPLEMENTADO, compila; falta prueba visual

**Problema:** en ventanas altas y angostas, el título "Panel de control" queda encima de la barra superior. Los botones de notificaciones y del menú quedan debajo y no se pueden tocar.

**Causa:** las pantallas usan `Box { AuthenticatedTopBar(align TopCenter); contenido(align Center) }`. El contenido se dibuja después de la barra y se centra en todo el alto. Cuando es más alto que el espacio libre, sube por encima de la barra.

**Pantallas afectadas (9):** DashboardScreen, SettingsScreen, ServerDrivenModuleScreen (2 usos), CompanyManagementScreen, CompanyProvisioningScreen, ServerLogsScreen, ServerModuleManagementScreen, ServerStaffScreen.

**Solución:**
- Crear un contenedor común en `features/shell/ui` (p. ej. `AuthenticatedScreen`). Primero va la barra y debajo el contenido en `Box(Modifier.weight(1f))`, con un margen superior entre los dos. Así el contenido no puede subir por encima de la barra.
- Pasar las 9 pantallas a este contenedor. El contenido sigue centrado, pero dentro del espacio que queda debajo de la barra.
- Los menús desplegables de la barra (notificaciones, menú, título) siguen en popup y quedan por encima del contenido.

**Archivos:** solo `app/shared` (shell/ui y las pantallas listadas). Versión de la app: parche.

## 2. Actualizar el Core desde la app (server owner)

**Objetivo:** el server owner ve "Actualizaciones disponibles", toca **Actualizar** y el servidor se actualiza solo, sin entrar por SSH a correr `update.sh`.

**Restricción:** el backend corre como `ideascore` con `NoNewPrivileges` y `ProtectSystem=strict`. No puede volverse root, y la actualización necesita root para systemctl, las migraciones como postgres y Nginx. Además, al actualizar se detiene a sí mismo.

**Solución (mismo patrón que `ideascore-domains-sync`):**
1. **Servidor (Core):**
   - Nueva migración de control con la tabla `core_update_requests` (id, versión destino, estado `pending/running/done/error`, solicitado por, fechas y las últimas líneas del log).
   - Rutas solo para el server owner:
     - `POST /server/core-update`: crea la solicitud en `pending`. Solo una a la vez.
     - `GET /server/core-update`: devuelve el estado y el log.
   - La versión destino nunca la escribe el usuario: sale de `latestCoreVersion` del catálogo de versiones.
2. **Ayudante root `ideascore-core-update`:**
   - Lo instalan `install.sh` y `update.sh` en `/usr/local/sbin`, como root. Lo corre un timer de systemd cada minuto.
   - Lee la solicitud `pending`, la valida y la marca `running`.
   - Corre la actualización sin preguntas, como `update.sh --non-interactive --target ambos`, y después escribe `done` o `error` con el log.
3. **`update.sh`:** nuevo modo sin preguntas, que no necesita `/dev/tty`. Mantiene el lock, el respaldo, el build antes de detener el servicio, la revisión de salud y la vuelta atrás a `*.previous`.
4. **App:**
   - En el aviso de versión del dashboard, un botón **Actualizar** con confirmación.
   - Una pantalla de progreso que consulta el estado. Mientras el servicio se reinicia, la app espera, se reconecta y muestra la versión nueva.

**Seguridad (punto clave):**
- Hoy `update.sh` ejecuta como root scripts que están en `/opt/ideascore/source`, que pertenece a `ideascore`. Si el backend se viera comprometido, podría modificarlos y pedir una actualización para volverse root.
- Por eso el ayudante root:
  - usa su propia copia de los scripts, que pertenece a root;
  - antes de ejecutar nada como root, deja el código igual al de `origin`: hace `git fetch` y `reset --hard` al destino, sin hooks, y verifica que la URL del remoto sea la esperada;
  - deja el build de Gradle como ahora, a cargo de `ideascore`.

**Tiempo:** el build en el servidor tarda varios minutos. La app solo queda sin servicio durante el reinicio, de unos 10 a 90 segundos.

**Archivos:** `server` (rutas, migración, pruebas), `scripts/ubuntu` (`update.sh`, nuevo ayudante y su instalación en `install.sh`), `app/shared` (dashboard y pantalla de progreso). Versiones: Core minor y app minor.

### Contrato del cambio 2 (lo comparten los 3 carriles)

**Migración** `database/control/migrations/V010__create_core_update_requests.sql`:
```sql
CREATE TABLE IF NOT EXISTS core_update_requests (
    id BIGSERIAL PRIMARY KEY,
    target_version TEXT NOT NULL CHECK (target_version ~ '^[0-9]+\.[0-9]+\.[0-9]+$'),
    from_version TEXT NOT NULL,
    status TEXT NOT NULL DEFAULT 'pending' CHECK (status IN ('pending', 'running', 'done', 'error')),
    requested_by TEXT NOT NULL,
    requested_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    started_at TIMESTAMPTZ,
    finished_at TIMESTAMPTZ,
    message TEXT,
    log_tail TEXT
);
-- Solo una solicitud activa a la vez.
CREATE UNIQUE INDEX IF NOT EXISTS core_update_requests_one_active
    ON core_update_requests ((true)) WHERE status IN ('pending', 'running');
```
Permisos del usuario del backend: `GRANT SELECT, INSERT ON core_update_requests` y `GRANT USAGE ON SEQUENCE core_update_requests_id_seq`. Solo el ayudante (como postgres) hace UPDATE.

**API (solo el server owner, en CompanyRoutes, rate limit "admin"):**
- `GET /server/core-update` → `CoreUpdateStatusResponse { currentVersion: String, latestVersion: String?, updateAvailable: Boolean, request: CoreUpdateRequestDto? }`. Devuelve la solicitud más reciente o null.
- `POST /server/core-update` (sin body) → 202 con `CoreUpdateRequestDto`. Errores con `CompanyErrorResponse(code, message)`:
  - 403 `SERVER_OWNER_REQUIRED`;
  - 503 `VERSION_CATALOG_UNAVAILABLE`, si no se pudo leer el catálogo;
  - 409 `CORE_UP_TO_DATE`;
  - 409 `CORE_UPDATE_IN_PROGRESS`, si ya hay una solicitud pending o running (índice único).
  - `target_version` sale de `latestCoreVersion` del catálogo y `from_version` de `IdeasCoreVersion`. Se registra en la auditoría.
- `CoreUpdateRequestDto { id: Long, targetVersion, fromVersion, status, requestedAt: String (ISO), startedAt: String?, finishedAt: String?, message: String?, logTail: String?, stalePending: Boolean }`. `stalePending` es true si sigue `pending` después de más de 3 minutos, lo que indica que el ayudante no está instalado.
- La descarga del catálogo de versiones lleva timeouts de 5 s, de conexión y de lectura.

**Ayudante root** `scripts/ubuntu/core-update.sh`:
- `--install` lo copia a `/usr/local/sbin/ideascore-core-update` y crea `ideascore-core-update.service` (oneshot) y un `.timer` que corre cada minuto.
- Usa un clon espejo propio de root en `/var/lib/ideascore/mirror`, con remoto fijo `https://github.com/alexcalbri/IDC.git` y la rama `CORE_UPDATE_REF` de server.env (por defecto `master`).
- Nunca ejecuta git ni scripts como root dentro de `/opt/ideascore/source`.
- Cada corrida:
  1. Si existe una fila `running` y el lock de mantenimiento está libre, la marca `error` ("Actualizacion interrumpida").
  2. Toma la fila `pending` más antigua. Si el lock está ocupado, la deja en `pending`.
  3. Hace fetch del espejo y verifica que `server/build.gradle.kts` del commit traído tenga `version = "<target_version>"`. Si no coincide, marca `error`.
  4. Marca la fila `running` y corre `<espejo>/scripts/ubuntu/update.sh --non-interactive --commit <sha> --target ambos` con `IDEASCORE_TRUSTED_DIR=<espejo>`.
  5. Escribe `done` o `error`, `message` y las últimas 60 líneas del log en `log_tail`.

**update.sh** gana el modo no interactivo con `--non-interactive --commit <sha40> --target server|webapp|ambos`:
- no exige `/dev/tty` ni hace preguntas;
- todo lo que se ejecuta como root (migrations.sh, los SQL, domains-sync.sh) se lee de `IDEASCORE_TRUSTED_DIR`, que por defecto es `/opt/ideascore/source`, así que el modo manual no cambia;
- el build sigue en `/opt/ideascore/source` como `ideascore`, con checkout del mismo sha;
- aplica V010 con sus GRANT.

`install.sh` hace lo mismo: agrega el archivo nuevo a REQUIRED_FILES, aplica V010 con sus GRANT y llama a `core-update.sh --install`.

**App:**
- Cliente en `CompanyApi`: `coreUpdateStatus(token)` y `requestCoreUpdate(token)`.
- Pantalla `server-update` para el server owner, con su ruta en App.kt y una tarjeta en el dashboard: "Actualizar servidor".
- El aviso de versión del dashboard tiene un botón que lleva a esa pantalla.
- La pantalla muestra la versión actual y la última, y un botón Actualizar con confirmación. Mientras la solicitud está `pending` o `running`, consulta el estado cada 5 s y muestra el log en monoespaciado.
- Si falla la red durante la actualización, muestra "El servidor se esta reiniciando..." y sigue consultando.
- Con `stalePending`, muestra un aviso: falta el ayudante y hay que correr `update.sh` una vez por SSH.

## 3. update.sh manual también seguro (scripts)

El modo manual hace lo mismo que el automático, pero preguntando:
- root sincroniza su clon `/var/lib/ideascore/mirror` (URL fija, sin hooks, `clean -ffdx`) con la rama, etiqueta o commit elegido;
- `TRUSTED_DIR` es ese clon;
- compila en `/opt/ideascore/build-tree` a partir de `git archive`;
- `source/` se actualiza como `ideascore` solo como referencia.

Nada de lo que corre como root o postgres se lee de `source/`. La lógica del clon se comparte con `core-update.sh` en un archivo nuevo, `scripts/ubuntu/mirror.sh`, que `update.sh` y `core-update.sh` cargan con `source` desde el directorio de confianza. Si se ejecuta `update.sh` desde `source/` (archivo de `ideascore`), avisa y pide confirmación, porque el propio script podría estar alterado. Lo recomendado es descargarlo de GitHub.

## 4. Autocompletado de @ en las notas del lead (Core + app + CRM)

**Core (`ModuleUi.kt`):**
- `UiSuggest` gana `trigger: String? = null`.
- Con `trigger` (p. ej. `"@"`), el campo, que también puede ser `textarea`, pide sugerencias solo para la palabra que se escribe después del último `trigger` antes del cursor, aunque tenga menos de `minLength`. La consulta va con `q` = el texto después del `@` y `field` = la clave.
- Elegir una sugerencia reemplaza `@<texto>` por `@` + `UiSuggestion.values["insert"]` (o `title` si no viene) + un espacio, y no toca los demás campos.
- Sin `trigger`, todo funciona como hoy. Los clientes viejos ignoran el campo.

**App (`NativeModuleRenderer.kt`):** el campo con sugerencias ya acepta `textarea` y el modo `trigger`, con la lista bajo el campo, navegable con teclado y clic.

**CRM:**
- Nueva ruta `GET /suggest/mentions?q=&lead=`. Devuelve hasta 8 `UiSuggestion(title = nombre, subtitle = correo, values = {"insert": nombre})`, tomadas de los usuarios mencionables del lead (los mismos que `mentionOptions`, sin el propio usuario), filtradas por nombre o correo que contengan `q`.
- La nota (`textarea`) lleva `suggest = UiSuggest(route, minLength = 0, trigger = "@")`.
- Se conserva el dropdown "mentions" para clientes viejos y para el MCP.

## 5. Selección múltiple y acciones por tarjeta en el pipeline (Core + app + CRM)

**Core (`ModuleUi.kt`):**
- Nuevo `UiBoardAction(id: String, label: String, request: UiRequest, options: List<UiFieldOption>? = null, tone: String = UiTone.Neutral)`, donde `UiFieldOption(value, label)` es un tipo nuevo si no existe uno equivalente.
- `UiBoard` gana `actions: List<UiBoardAction> = emptyList()`.
- La app ejecuta `request` y agrega los params `cards` (ids separados por coma) y, si hay `options`, `value` (la opción elegida). `request.confirm` se pide antes.

**App:**
- Con `actions` no vacío, el tablero muestra un botón "Seleccionar". En modo selección, cada tarjeta tiene una casilla y tocarla la marca; la columna tiene "Seleccionar todos" para las tarjetas cargadas.
- Una barra fija muestra "N seleccionados", las acciones (con submenú si tienen `options`) y "Cancelar". El arrastre se desactiva en modo selección.
- Fuera del modo selección, el menú de cada tarjeta (donde está "Mover a...") muestra las mismas acciones aplicadas a esa tarjeta sola.

**CRM:**
- El tablero declara estas acciones, mapeadas a `CrmBulkActions` con la misma regla de alcance y permisos por lead: los leads vistos solo por mención no entran.
  - Mover a etapa, con las etapas como options;
  - Asignarme;
  - Asignar a, con usuarios como options;
  - Quitar asignados;
  - Archivar;
  - Restaurar, solo en la columna de archivados;
  - Eliminar, con confirmación.
- Nueva operación `pipeline op=action`, con `action` = el id de la acción, `cards` y `value`. Responde con el tablero actualizado y un mensaje "N leads actualizados", o con los rechazados.
- Los alias viejos `pipeline` y `bulk` no se tocan.

## 6. Dependabot #80: qs 6.16.0 (HECHO)

`build.gradle.kts` fuerza `resolution("qs", "6.16.1")` y `kotlin-js-store/yarn.lock` quedó regenerado.

**Versiones de los cambios 3 a 6:** Core 0.5.0, app 0.5.0 y CRM 0.21.0.

## 7. Diseño responsive: usar el ancho en vez de centrar una columna angosta (app)

**Problema:** con el arreglo de `contentWidth`, las pantallas se limitan a 880 dp (o 1360 dp en los módulos) y dejan grandes espacios vacíos a los lados. Además, las tarjetas, secciones y elementos de lista siguen apilados uno debajo de otro aunque quepan varios lado a lado, y el pipeline muestra menos etapas de las que entran en la pantalla.

**Objetivo:** en pantallas anchas, el contenido usa el ancho disponible y las tarjetas se acomodan en una cuadrícula adaptable, con el número de columnas según el ancho y un ancho mínimo por tarjeta. En pantallas angostas se mantiene una sola columna, como hoy.

**Solución:**
- **Ancho de las pantallas:**
  - el login y los formularios cortos mantienen su ancho máximo (`Card` 420 y `Form` 640);
  - las pantallas de listas y tarjetas (dashboard, Módulos del servidor, Empresa, personal, errores y los módulos sin interfaz propia) pasan a ocupar todo el ancho, con un margen lateral y un máximo alto, de unos 1600 dp;
  - los módulos con interfaz propia (CRM) ocupan todo el ancho menos el margen, para que el pipeline muestre más etapas.
- **Cuadrícula adaptable común:** un componente nuevo en `core/layout`, por ejemplo `AdaptiveGrid(minItemWidth, gap)`, con `FlowRow` o un cálculo de columnas por ancho y filas de igual alto. Lo usan:
  - las tarjetas del dashboard;
  - las tarjetas de "Módulos del servidor" y las listas de personal y empresas;
  - en el renderizador de módulos, los nodos consecutivos de tipo tarjeta o sección, y los elementos de lista cuando el ancho alcanza para más de una columna.
- **Sin romper:** las tablas, el tablero, los formularios y los gráficos siguen ocupando todo el ancho.

**Ajuste del 7: una sola barra de scroll horizontal, fija abajo.** La barra arriba y abajo no sirve: a mitad del pipeline ninguna de las dos se ve. Queda una sola barra, pegada al borde inferior de la parte visible del panel del módulo, mientras el tablero o la tabla esté en pantalla, sin importar el scroll vertical. Va alineada con el ancho del contenido que desplaza, y si no hay un contenedor que la sostenga, queda debajo del contenido como antes.

## 8. Secciones y campos personalizados del lead, fechas mapeables y calendario (CRM + app)

**Pedido:**
- "Lo que busca" deja de ser fijo: cada empresa arma sus secciones con nombre propio (p. ej. "Datos de la reserva") y pone en ellas sus campos (p. ej. "Fecha de ingreso" y "Fecha de salida").
- Los campos de fecha tienen un selector de calendario.
- Cloudbeds y el hotspot leen las fechas desde campos que se pueden elegir.
- El campo "age", que creó la empresa en el CRM, se mueve a Clientes con sus valores.

**Decisiones del usuario:**
- Las fechas pasan a ser campos personalizados, y Cloudbeds y el hotspot permiten elegirlas.
- Se agrega la acción "Mover a Clientes".
- "age" se mueve tal cual, sin convertirlo a fecha de nacimiento.

**CRM (migración V019):**
- **Secciones:** tabla nueva `crm_field_sections` (id, nombre, posición), y `crm_field_definitions` gana `section_id`, que puede ir vacío.
  - En Configuración > Campos se crean, renombran, ordenan y borran secciones. Borrar una sección deja sus campos sin sección, sin borrarlos.
  - El formulario y el detalle del lead muestran cada sección con sus campos, en orden. Los campos sin sección van en "Campos personalizados".
  - La sección fija "Lo que busca" desaparece. "Valor estimado" queda como campo fijo en la sección principal del lead.
- **Fechas como campos personalizados:**
  - La migración, en cada empresa que tenga leads con `target_start` o `target_end`, crea la sección "Datos de la reserva" con dos campos de fecha, `fecha_ingreso` ("Fecha de ingreso") y `fecha_salida` ("Fecha de salida"), copia los valores a `custom_fields` y deja hecho el mapeo de abajo. Las empresas sin fechas no reciben nada.
  - `crm_settings` gana `stay_start_field` y `stay_end_field`: las claves de los campos de fecha que valen como ingreso y salida.
  - Se configuran en Configuración > Integraciones, bloque "Fechas de estadía". El mismo mapeo se muestra en la configuración de Cloudbeds y lo explica la sección del hotspot.
  - `target_start` y `target_end` quedan como copia interna: el repositorio las llena desde los campos mapeados cada vez que se guarda un lead (y quedan vacías sin mapeo). Así siguen funcionando sin cambios:
    - el hotspot (`HotspotCrmPipeline`);
    - la salida automática (`CrmExit`);
    - `restoreSurvives`;
    - la fecha por defecto de Cloudbeds;
    - las variables `checkin` y `checkout` de las plantillas.
  - Validación: si las dos fechas mapeadas existen, la de salida debe ser igual o posterior a la de ingreso.
  - La tarjeta del pipeline "Fechas: X a Y" usa los campos mapeados, con sus etiquetas.
  - La importación (las palabras check-in, entrada, salida...) escribe en los campos mapeados. Sin mapeo, ignora esas columnas y avisa.
  - En MCP, `targetStart` y `targetEnd` quedan como alias, aceptados y documentados, que escriben en los campos mapeados. `customFields` sigue igual.
- **"Mover a Clientes":** acción en la configuración de cada campo personalizado del CRM, con confirmación.
  1. Crea el campo en Clientes (`customer_field_definitions`) con la misma clave, etiqueta y un tipo equivalente. Si la clave ya existe con otro tipo, se cancela con un mensaje.
  2. Copia cada valor del lead a `customers.flexible_attributes` de su cliente, sin pisar valores que el cliente ya tenga. Si un cliente tiene varios leads, usa el más reciente.
  3. Borra el campo del CRM y quita la clave de `custom_fields` de los leads.
  - Todo va en una transacción entre esquemas, como ya hace la importación (`CrmImportTargets`). Requiere `crm.settings` y `clientes.fields.manage`.

**App:**
- **Selector de calendario:** los campos `date` muestran un botón con calendario (DatePicker de Material 3 en un diálogo), mantienen el valor `AAAA-MM-DD` y se pueden vaciar. Los campos `datetime` agregan la hora con TimePicker.
- **Campos dinámicos de Clientes** (`ServerDrivenModuleScreen`): al crear un campo se puede elegir el tipo (texto, número, fecha, sí/no, lista) y no solo texto.

**Versiones:** CRM 0.22.0. La app pasa a 0.6.0 e incluye el cambio 7, que todavía no tiene commit. El Core sigue en 0.5.0 porque el modelo de UI no cambia. Clientes no cambia, salvo que haga falta para los tipos.

**Ajuste del 8 (CRM 0.23.0, migración V020), pedido del usuario:**
- **Se quita "Mover a Clientes"** por completo: acción, botón, código, pruebas y README. `age` sigue siendo un campo personalizado del CRM.
- **Campos en cualquier sección:** un campo personalizado se puede poner en las secciones fijas del lead ("Datos del cliente", la de contacto con nombre, teléfono y correo; "Seguimiento"; "Mensaje y detalles"; "Documentos") o en una sección creada por la empresa. Si la sección no existe, se crea con su nombre y se le agregan campos.
- **Orden de las secciones:** todas se ordenan a gusto, fijas y personalizadas, salvo "Datos del cliente", que siempre va primero.
- **Cómo:** V020 agrega `system_key` a `crm_field_sections` y crea en cada empresa una fila por sección fija, en el orden actual. Las secciones fijas no se borran; renombrarlas no se pide, así que se dejan con su nombre. `position` controla el orden y "Datos del cliente" se fija en 0. El formulario y el detalle se arman recorriendo las secciones en orden: en cada una van primero sus campos fijos y después los personalizados asignados a ella.
