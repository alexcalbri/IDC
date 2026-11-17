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
