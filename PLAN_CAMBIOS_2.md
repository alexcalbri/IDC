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

## 9. Automatizaciones, plantillas con traducción, botones y avisos (CRM 0.24.0, V021)

**Origen:** la revisión de los tableros de leads de La Jolla en monday. Los puntos 6, 7 y 8 de esa revisión (salida a la etapa Old Guest, reparto por asignado y webhook) quedan fuera.

### 9.1 Plantillas de correo por contenido
- Siguen las plantillas actuales (`crm_email_templates`, HTML o texto). Cada empresa crea las que quiera (bienvenida, confirmación, recordatorio...), sin una plantilla por idioma.
- **Datos:** además de las variables actuales (`{nombre}`, `{codigo_cliente}`, `{checkin}`, `{checkout}`, `{etapa}`, `{interes}`, `{valor}`, `{responsable}`, `{habitaciones}`, `{reserva}`), se puede cargar cualquier campo personalizado del lead con `{campo:clave}` y cualquier campo dinámico del cliente con `{cliente:clave}`. El editor muestra la lista de variables disponibles y una vista previa con un lead de ejemplo.
- **Traducción automática** (opcional por plantilla, "Traducir al idioma del huésped"):
  - **Configuración > Integraciones > "Traducción":** proveedor Google Cloud Translation (clave de API) o IA por API (Claude: clave de API y modelo). La clave se guarda como las demás credenciales de integraciones.
  - **Idioma del huésped:** sale de un campo mapeado ("Campo de idioma", como el mapeo de fechas) y de una tabla de equivalencias (Español → es, Inglés → en...). La plantilla tiene un idioma base. Sin idioma o con el idioma base, no se traduce.
  - **Lo que se respeta:** se traduce el asunto y el cuerpo antes de cargar los datos. Las variables `{...}` y las etiquetas HTML se protegen (Google con `format=html` y marcadores; la IA con instrucciones y una verificación de que las variables y las etiquetas siguen iguales). Así, nombres, códigos y fechas nunca pasan por el traductor.
  - **Caché y fallos:** las traducciones se guardan en caché (`crm_template_translations`: plantilla, idioma y hash del contenido) y se invalidan al editar. Si la traducción falla, se envía el original y queda anotado en la actividad del lead.

### 9.2 Motor de automatizaciones (`crm_automations`, `crm_automation_runs`)
Configuración > Automatizaciones (permiso `crm.settings`). Cada regla tiene un disparador, condiciones opcionales y una o más acciones en orden.
- **Disparadores:**
  - lead creado;
  - cambio a una etapa;
  - cambio de un campo;
  - nuevo mensaje entrante;
  - fecha: N días antes o después de la fecha de ingreso o salida mapeada, del seguimiento o de cualquier campo de fecha, a una hora del día en la zona horaria de la empresa;
  - manual: solo desde un botón (9.4).
- **Condiciones:** etapa o interés en una lista, campo igual o distinto a un valor, campo vacío o lleno, y tiene correo o teléfono.
- **Acciones:**
  - enviar una plantilla al correo del lead (traducida si corresponde);
  - cambiar la etapa;
  - fijar un campo;
  - avisar a usuarios: asignados, usuarios elegidos o los que ven todos los leads, con aviso en la app y correo;
  - Cloudbeds, solo si la integración está activa: crear la reserva y hacer el check-in.
- **Datos incompletos:** cada acción sabe lo que necesita (el correo para enviar, las fechas y la habitación para reservar, la reserva para el check-in).
  - **Aviso anticipado (reglas de fecha):** quien crea la regla define con cuánto tiempo de anticipación revisar los datos (por ejemplo "avisar 2 días antes"). Al llegar ese momento, si al lead le falta algo para la ejecución que viene, se avisa (app y correo) a los asignados o a los usuarios elegidos en la regla: qué falta, en qué lead y cuándo se ejecuta. Así hay tiempo de completarlo.
  - **Al ejecutar:** si todavía falta algo, la regla no se ejecuta y se avisa de nuevo, una sola vez. Las reglas sin fecha (lead creado, cambio de etapa o campo, mensaje, botón) solo pueden avisar en el momento: no se ejecutan y avisan qué falta.
- **Ejecución:**
  - un job revisa cada 5 minutos los disparadores de fecha;
  - los demás se disparan al guardar el lead o al llegar el mensaje;
  - cada ejecución queda en `crm_automation_runs` (lead, regla, resultado, detalle) y en la actividad del lead;
  - una regla de fecha corre una sola vez por lead para cada valor de la fecha: si la fecha cambia, vuelve a correr.
- **Bienvenida antes del check-in:** no es una regla especial. Es una automatización normal de fecha ("N días antes de la fecha del campo elegido, hacer esto") que cada empresa crea si la necesita: por ejemplo, 1 día antes de "Fecha de ingreso", enviar la plantilla "Bienvenida". Las empresas sin check-in simplemente no la crean. Si Cloudbeds está activo con su bienvenida encendida, al guardar una regla que envía correo por una fecha de estadía se avisa que pueden salir dos correos, y se ofrece apagar la bienvenida de Cloudbeds.

### 9.3 Avisos (app y correo)
- **Mensaje nuevo de un lead** (inbox: correo o Meta): aviso en la app a los asignados, y correo según su preferencia en Equipo.
- **Fecha de seguimiento:** aviso en la app y correo a los asignados a la hora del seguimiento (o a la hora del resumen si no tiene hora), además del resumen diario.
- **Preferencias:** las dos opciones nuevas se agregan a las preferencias de notificación de cada usuario.

### 9.4 Botones como campos personalizados
- **Tipo nuevo de campo, "Botón":** se pone en cualquier sección, como los demás campos, y su acción puede ser:
  - enviar una plantilla;
  - ejecutar una o más automatizaciones con disparador manual, en orden, por ejemplo "Confirmar reserva": enviar la confirmación, crear la reserva en Cloudbeds y avisar a recepción.
- Se muestra en el detalle del lead (no en el formulario de edición), pide confirmación y devuelve el resultado de cada acción o los datos que faltan. Respeta los permisos de quien lo pulsa.

### Fuera de esta versión
Salida a la etapa "Old Guest", reparto por asignado y webhooks.

### Automatizaciones para las pruebas del cambio 9 (no son código)
No se programan: son datos de configuración. Al probar el cambio 9, se crean desde la app (o por MCP) en el servidor, con la configuración de La Jolla (las fechas mapeadas como "Fecha de ingreso" y "Fecha de salida", y el campo de idioma mapeado):
1. **Bienvenida antes del check-in:**
   - disparador: 1 día antes de "Fecha de ingreso", a la hora configurada;
   - condiciones: etapa Booked/Converted y tiene correo;
   - acción: enviar la plantilla "Bienvenida" traducida al idioma del huésped;
   - aviso anticipado: 2 días antes, si falta el correo.
2. **Confirmación de reserva** (botón "Enviar confirmación" en el lead): disparador manual; enviar la plantilla "Confirmación", traducida.
3. **Confirmar y reservar** (botón):
   - disparador manual;
   - acciones: enviar la confirmación, crear la reserva en Cloudbeds y avisar a recepción;
   - si faltan las fechas o la habitación, avisa qué falta.
4. **Booked sin fechas:**
   - disparador: cambio a la etapa Booked/Converted;
   - acción: avisar a los asignados (o al usuario elegido, como magrock en monday);
   - si faltan la fecha de ingreso o la de salida, avisa qué falta.
5. **Mensaje nuevo de un lead:** aviso en la app y por correo a los asignados. Lo cubre 9.3; se revisa que llegue.
6. **Seguimiento que vence:** aviso en la app y por correo a los asignados a la hora del seguimiento. Lo cubre 9.3.
7. **Check-in en Cloudbeds** (botón, con la integración activa): disparador manual; acción: hacer el check-in, o avisar si no hay reserva.

## 10. Versión mínima de Core y app por módulo (Core 0.6.0, app 0.6.1, todos los módulos)

**Pedido:** al instalar o actualizar un módulo, verificar que el Core y la app tengan la versión mínima que el módulo necesita para funcionar.

**Contrato:**
- **`module.json`:** cada módulo declara `"minCoreVersion": "X.Y.Z"` y `"minAppVersion": "X.Y.Z"`. Se comparan con `ModuleVersion` (core `ModuleVersion.kt`). Si no vienen, no hay mínimo.
  - Valores iniciales: CRM `0.5.0`/`0.5.0`; hotspot y agentes `0.5.0`/`0.5.0`.
  - Los módulos base (clientes, empresa) van con el Core y no se revisan.
- **Servidor:**
  - `RemoteModuleDefinition` y `ModuleDefinition` llevan `minCoreVersion: String? = null` y `minAppVersion: String? = null`, leídos del `module.json` del catálogo y del paquete instalado.
  - `ServerModuleResponse` expone los mínimos de la versión instalada y de la disponible: `minCoreVersion`, `minAppVersion`, `latestMinCoreVersion`, `latestMinAppVersion`, `installBlockedReason: String?`.
- **Verificación al instalar o actualizar** (`installServerModule`, ruta POST `/server/modules/{id}/install` y MCP `install_module`), antes de descargar y otra vez al validar el paquete en `ModulePackages.validate` (por si el catálogo y el paquete no coinciden):
  - si el Core instalado (`IdeasCoreVersion`) es menor que `minCoreVersion`: 409 `MODULE_NEEDS_CORE`, "Este modulo necesita el Core X o superior; el servidor tiene Y. Actualiza el servidor primero.";
  - si la app de quien instala, que manda el header `X-App-Version`, es menor que `minAppVersion`: 409 `MODULE_NEEDS_APP`, "Este modulo necesita la app X o superior; tu app es Y. Actualizala antes de instalar.";
  - si no llega el header (app vieja o MCP), no se bloquea por la app, pero la respuesta lleva un aviso.
- **App:**
  - Todas las peticiones (`CompanyApi`, `ModuleApi`, `VersionApi` y demás clientes HTTP) mandan `X-App-Version: <ClientVersion>`.
  - En "Módulos del servidor", cada tarjeta muestra "Requiere Core ≥ X · App ≥ Y". Si `installBlockedReason` viene, o si la app es más vieja que el mínimo, el botón Instalar o Actualizar se desactiva y se muestra el motivo.
  - Al abrir un módulo cuyo `minAppVersion` es mayor que `ClientVersion`, la app muestra "Este modulo necesita la app X o superior. Actualiza la app." en lugar de la pantalla del módulo.
- **Revisar:** `VersionResponse` usa `IdeasCoreVersion` también como `appVersion` (VersionModels.kt:10). Hay que corregirlo si es un error.

**Versiones:** Core 0.6.0 y app 0.6.1. Los módulos sin cambios de código no suben de versión; solo cambia su `module.json`.
