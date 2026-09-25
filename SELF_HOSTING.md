# IdeasCore --- Self-Hosting Guide

> Status: **Bootstrap guide**
>
> IdeasCore is still evolving. This document intentionally separates
> steps that can be verified from the repository from architecture that
> is planned. Before publishing a release, replace placeholders with
> exact commands tested against that release.

## 1. Deployment Model

### Web application in the initial installation

The installer builds and installs both Ktor and the browser application.
`https://YOUR_DOMAIN/` serves the web login instead of the Ktor greeting.
Nginx serves `/opt/ideascore/web`, with an `index.html` fallback for client
navigation, and forwards `/auth/` to Ktor on loopback without rewriting
the path. The browser login starts with the current origin as its server URL;
use that URL for same-origin authentication. Other server URLs require a
separately configured CORS policy and are not enabled by this installer.

The web build command is:

```bash
./gradlew -PwebOnly=true :app:webApp:jsBrowserDistribution
```

The result is `app/webApp/build/dist/js/productionExecutable/`.
`core/build-web.gradle.kts` and `app/shared/build-web.gradle.kts` reuse
existing sources with JS-only targets so Ubuntu needs no Android/iOS SDK.
Keep their shared dependencies aligned with the normal build profiles.
Gradle downloads Node/Yarn and frontend dependencies automatically.

Publish these build profiles, the client sources, settings and installer
together before installing from GitHub. This change applies to new
installations; an already running installer will not acquire the new logic.
Do not rerun this first-install script against an installed server. Existing
deployments need a separate web build/deployment and Nginx update.

### Initial owner provisioning (local implementation; clean-host test pending)

The installer now asks for a central administration database and one set of
credentials for `server_owner`. It does not create the first company. The
server owner is a PostgreSQL login role with tenant-provisioning privileges,
not a SQL superuser. The
central database holds server-owner users/sessions and an initially empty
company registry with code, name, database, active status and branding fields.
Company users, sessions, the singleton `business_owner` record, the shared
Customer/Prospect schema and the tenant module registry will live inside each
company database after the Empresa core module provisions it. The tenant module
registry seeds `clientes` as an enabled base module.

The installer applies Core authentication migrations and the central registry
migrations to the central database. The database records scripts in
`schema_migrations` by module, version and checksum. Database ownership remains
with the provisioning administrator. The runtime account receives the
table permissions needed for authentication and the company registry, plus
membership in the `server_owner` role so it can provision tenants only after
the backend validates a `server_owner` session token. `DB_URL`
points at the central database; root-only `TENANT_DATABASES_JSON` starts as
`[]`. The Empresa screen can create a company database, apply tenant
migrations, seed its `business_owner` and register the tenant in the running
server so company login works without restart. From the same screen,
`server_owner` can enable or disable optional `hostpot` and `crm` modules per
company; `clientes` stays enabled as the base customer module.

Before reporting success, the installer tests `/auth/login` with the
`server_owner`, revokes the test session, and rejects passwordless
authentication for that account. This verifies login only: administrative
endpoints and their authorization remain pending; the client login is wired but
end-to-end verification against a new Ubuntu installation is pending. Module files
are not yet downloaded selectively; the installer still clones the repository.

Publish the matching backend, installer and migration files to the selected
Git branch before running on Ubuntu. This script is for a fresh installation;
it refuses existing application files/accounts instead of resetting owners.
An interrupted installation after provisioning requires diagnosis, not a
blind rerun. Do not apply these initial scripts manually to an existing server
with the previous central-user layout; data migration is a separate task.

For company login, POST JSON to `/auth/login` with `username`, `password` and
`companyCode` after a company has been created from the app. For server
administration omit `companyCode` or set it to null. Unknown/inactive companies are rejected.
Responses include `scope`, `companyCode`, `role` and the session token.
The client login view consumes this response; administrative actions are not yet exposed.

### Instalador interactivo para Ubuntu

**Implementado; pendiente de prueba integral en un servidor Ubuntu limpio.**
El archivo `scripts/ubuntu/install.sh` prepara una primera instalación en
Ubuntu 24.04 o 26.04 con systemd, sin PostgreSQL ni Java preinstalados.
Instala PostgreSQL y OpenJDK 21 desde APT, descarga este repositorio,
compila el backend con su Gradle Wrapper y crea el servicio `ideascore`.
Ktor se incorpora como dependencia de la aplicación; no se instala como
un paquete independiente del sistema.

No necesitas instalar Nginx ni las herramientas del backend previamente.
El script consulta el estado de los paquetes de Ubuntu para Java 21,
PostgreSQL y su cliente, Nginx, Git, curl, certificados CA, Python 3,
herramientas DNS y OpenSSL. Muestra cuáles están disponibles y cuáles
faltan, instala estos últimos y verifica el resultado. Comprueba Certbot
por separado después de validar el dominio, reutilizando una instalación
existente incluso si no proviene de APT. No actualiza deliberadamente
todos los paquetes ya instalados: el mantenimiento del sistema sigue
siendo una operación independiente.

El punto de partida es Ubuntu Server 24.04/26.04 con sus herramientas
básicas (Bash, APT/dpkg, systemd y utilidades de cuentas), acceso root
mediante sudo, terminal interactiva e Internet. El comando de descarga
de abajo instala curl si la instalación limpia todavía no lo tiene.

Primero publica los cambios del instalador y del backend en la rama que
vas a utilizar. Desde una terminal SSH en Ubuntu:

```bash
sudo apt-get update
sudo apt-get install -y ca-certificates curl
curl --fail --show-error --location \
  https://raw.githubusercontent.com/alexcalbri/IDC/develop/scripts/ubuntu/install.sh \
  --output install-ideascore.sh
less install-ideascore.sh
sudo bash install-ideascore.sh
```

El comando presupone que los archivos ya están publicados en `develop`.
Para otra rama o etiqueta, cambia `develop` en la URL y selecciona esa
misma referencia cuando pregunte el instalador. El repositorio debe poder
clonarse públicamente por HTTPS; este instalador no gestiona credenciales
para repositorios privados. Necesita Internet para APT, GitHub, Gradle y
las dependencias, además de memoria suficiente para compilar (el proyecto
configura un heap Gradle de hasta 4 GiB y otro Kotlin de hasta 3 GiB).
Como requisito mínimo, el servidor debe contar con al menos 2 GiB de RAM
y 10 GiB libres en disco duro antes de iniciar la instalación.

Preguntas del instalador:

- Rama o etiqueta que se instalará (predeterminada: `develop`).
- Dominio público del backend, sin protocolo ni rutas (por ejemplo `api.tuempresa.com`).
- Correo para registrar la cuenta de Let’s Encrypt.
- Base PostgreSQL nueva (predeterminada: `idc`).
- Usuario PostgreSQL nuevo (predeterminado: `idc_app`).
- Contraseña de PostgreSQL y confirmación, sin mostrarla en pantalla.
- Confirmación del resumen antes de instalar paquetes.

La contraseña admite al menos 16 caracteres ASCII imprimibles. Se guarda
en `/etc/ideascore/server.env`, propiedad de root y con permisos `600`.
La aplicación corre con el usuario Linux `ideascore`, sin privilegios
administrativos. El instalador configura Nginx con HTTPS y un certificado
de Let’s Encrypt. Reutiliza Certbot si ya está instalado; si falta,
lo instala mediante APT después de validar el dominio. Instala los servicios en el Ubuntu donde se
ejecuta. Configura automáticamente Ktor en `127.0.0.1:8080` y su conexión
JDBC al PostgreSQL local en `127.0.0.1:5432`, usando la base, usuario y
contraseña introducidos. No solicita IP ni puerto. Si el puerto HTTP
8080 no está disponible, se detiene con un mensaje explicativo.
Para probar desde tu PC, utiliza `https://TU_DOMINIO/`. Nginx publica el
puerto 443 y redirige HTTP a HTTPS; Ktor no escucha conexiones externas.
Antes de ejecutar, crea los registros DNS A y, si utilizas IPv6, AAAA,
apuntando al servidor y permite entrada TCP 80/443 en el firewall y en
el proveedor de hosting/router. El script no modifica DNS ni firewall.
Consulta DNS público a través de `1.1.1.1` y comprueba cada dirección
publicada con un token HTTP temporal servido por este Nginx. Un AAAA
incorrecto también detiene la instalación. Esta comprobación desde el
servidor requiere NAT loopback si está detrás de NAT; la validación final
de accesibilidad desde Internet la realiza Let’s Encrypt al emitir el
certificado. Usa DNS directo para esta instalación, sin un proxy CDN.
La confirmación inicial incluye aceptar los términos enlazados de
Let’s Encrypt. El certificado no se solicita hasta confirmar.

El servidor verifica `SELECT 1` durante el arranque y luego habilita su
ruta `/`. El instalador comprueba PostgreSQL, la respuesta HTTPS local
con validación del certificado y una renovación de prueba con Certbot.
La comprobación HTTPS es obligatoria. Si solo falla la simulación de
renovación, el instalador muestra una advertencia y marca esa prueba como
pendiente; no declara la renovación verificada ni deshace la instalación.
Todavía no crea tablas de usuarios, login, migraciones ni control plane.
La instalación configura una sola base; la resolución multi-tenant es
arquitectura prevista.

```bash
sudo systemctl status ideascore --no-pager
sudo journalctl -u ideascore -f
sudoedit /etc/ideascore/server.env
sudo systemctl restart ideascore
curl --fail http://127.0.0.1:8080/
```

Cambiar `DB_PASSWORD` en el archivo no cambia la contraseña en PostgreSQL:
ambas deben coincidir. Las variables del archivo son leídas por systemd;
no ejecutes ese archivo como un script Bash.

Este instalador es **solo para una primera instalación**. Rechaza
directorios, servicio, cuenta Linux, base o usuario PostgreSQL existentes;
no sobrescribe sus datos ni cambia sus contraseñas. Si falla después de
crear recursos, los conserva para diagnóstico: no es una actualización
automática ni realiza rollback. Revisa el error y el estado antes de
reintentar; no borres una base de datos para forzar su ejecución.

Excepción de recuperación: si solo quedó el sitio Nginx temporal con
`return 503`, puedes ejecutar de nuevo con el mismo dominio. El instalador
exige que el archivo coincida exactamente con su plantilla, que el enlace
habilitado apunte a ese archivo y que todavía no existan los directorios,
cuenta ni servicio de la aplicación. Repite la comprobación HTTP y continúa
con Certbot. No adopta sitios HTTPS ni configuraciones personalizadas.

Para modificarlo, conserva sus bloques: validación y preguntas, paquetes,
compilación, PostgreSQL, configuración del servicio y comprobaciones.
Los valores predeterminados están junto a cada llamada a `ask`.
La dirección y el puerto HTTP están en las constantes `APP_HOST` y
`APP_PORT`; cualquier cambio posterior también debe reflejarse en el
proxy de Nginx. Mantén Ktor en loopback para que el acceso público use HTTPS.
Las rutas de instalación están declaradas al inicio y también aparecen
en el archivo de servicio: deben mantenerse sincronizadas.

La compilación del backend sin SDK Android utiliza:

```bash
./gradlew -PserverOnly=true :server:tasks --all
./gradlew -PserverOnly=true :server:test :server:installDist
```

`settings.gradle.kts` omite las aplicaciones y selecciona
`core/build-server.gradle.kts`, que compila las mismas fuentes comunes
de Core para JVM. El build normal conserva todos los targets.

IdeasCore supports a self-hosted model in which an organization runs the
application on infrastructure it controls.

The intended deployment contains:

``` text
Users
  |
Reverse Proxy / TLS
  |
IdeasCore Ktor Server
  |
  +-------------------+
  |                   |
Platform/Control DB   Tenant PostgreSQL DB(s)
                      |
                      +-- Core structures
                      +-- Installed module structures
```

KMP clients communicate with the Ktor server. They do not connect
directly to PostgreSQL.

------------------------------------------------------------------------

## 2. Prerequisites

Verify the exact requirements in the current repository before
installation.

Expected categories include:

-   Git
-   JDK compatible with the repository Gradle toolchain
-   PostgreSQL
-   Gradle Wrapper from the repository
-   Docker/Compose if the deployment uses containers
-   reverse proxy with TLS for internet-facing production deployments

Do not rely on versions copied from old documentation. Check:

``` bash
./gradlew --version
```

and inspect:

``` text
gradle/libs.versions.toml
gradle.properties
settings.gradle.kts
```

------------------------------------------------------------------------

## 3. Clone

``` bash
git clone <IDEASCORE_REPOSITORY_URL>
cd IDC
```

For a production deployment, use a tagged release when releases are
available instead of an arbitrary development commit.

------------------------------------------------------------------------

## 4. Inspect the Build

Before configuring production infrastructure:

``` bash
git status
git log --oneline -5
./gradlew tasks
```

Run the server tests available in the current repository.

Historically the server task has been:

``` bash
./gradlew :server:test
```

Verify that this task still exists before relying on it.

------------------------------------------------------------------------

## 5. PostgreSQL

IdeasCore's approved architecture uses PostgreSQL.

The intended tenant model is:

-   one tenant/company = one PostgreSQL database;
-   every tenant DB receives Core migrations;
-   only installed modules receive their module migrations.

Example only:

``` text
ideascore_company_a
├── Core
├── CRM-like module
└── Hotel-like module

ideascore_company_b
├── Core
└── Hotspot-like module
```

Exact database names and schemas are deployment choices.

### Production recommendations

-   use dedicated database roles;
-   use strong generated passwords;
-   restrict network access;
-   enable backups;
-   use TLS where database traffic crosses untrusted networks;
-   do not expose PostgreSQL directly to public clients.

------------------------------------------------------------------------

## 6. Control Plane

Managed/multi-tenant deployments are intended to have platform metadata
logically separate from tenant business data.

The exact control-plane provisioning command/schema is **not yet defined
by this guide**.

Do not manually invent control-plane tables from architectural examples.

Once provisioning tooling exists, document the exact tested command
here.

------------------------------------------------------------------------

## 7. Environment Configuration

Secrets must be supplied server-side.

Likely configuration categories include:

``` text
server host/port
database host/port
platform/control database credentials
tenant database credentials or resolver configuration
JWT/authentication secrets
integration credentials
logging configuration
module configuration
```

The exact environment-variable names must come from the current server
implementation.

### Important

Do not copy historical/example variable names into production unless
they are verified in current code.

To find current configuration, inspect the server for environment/config
access, for example:

``` bash
grep -R "System.getenv\|environment.config\|application.conf" -n server
```

Document verified variables in a table here as they are implemented:

| Variable | Required | Purpose / default |
| --- | --- | --- |
| `DB_PASSWORD` | Yes | PostgreSQL password; no fallback |
| `DB_URL` | No | JDBC URL; `jdbc:postgresql://localhost:5432/idc` |
| `DB_USER` | No | PostgreSQL role; `idc` (installer sets `idc_app`) |
| `DB_POOL_SIZE` | No | Hikari pool maximum; `10` |
| `HOST` | No | HTTP bind address; `0.0.0.0` (installer sets `127.0.0.1` behind Nginx) |
| `PORT` | No | HTTP port; `8080` |
| `PROVISIONING_DB_URL` | No | PostgreSQL administration database URL used before `SET ROLE server_owner`; defaults to `DB_URL`. |
| `TENANT_JDBC_URL_PREFIX` | No | Prefix used for newly created tenant JDBC URLs; defaults to the central DB URL up to the last `/`. |
| `MIGRATIONS_ROOT` | No | Repository/source root containing `database/...` migrations; defaults to the server working directory. |

`EngineMain` loads `application.conf` and starts
`com.ideasdeveloper.idc.server.app.ApplicationKt.module`. `DatabaseFactory`
connects and checks PostgreSQL at startup, and closes the pool on
application stop. These variables must also be provided when running the
backend locally.

Never commit `.env` files containing real secrets.

Provide `.env.example` with placeholders when the project adopts
environment-based configuration.

------------------------------------------------------------------------

## 8. Database Migrations

Target architecture:

``` text
Core migrations
      +
Installed module migrations
      =
Tenant database schema
```

The exact migration tool/commands must be documented after they are
implemented and tested.

A future release procedure should support:

1.  backup;
2.  verify target version;
3.  run Core migrations;
4.  run tenant Core migrations for company databases, including shared
    Customer/Prospect and tenant module registry migrations;
5.  run migrations only for installed optional modules;
6.  verify migration status;
7.  start/upgrade server;
8.  health check;
9.  rollback/recovery procedure if needed.

Never tell operators to run unverified SQL copied from architectural
examples.

------------------------------------------------------------------------

## 9. Module Installation

The intended lifecycle is:

``` text
Available → Entitled (when applicable) → Installed → Enabled
```

For self-managed open-source deployments, commercial entitlement may not
apply.

Installing a module should eventually:

1.  validate dependencies;
2.  apply the module's migrations to the tenant DB;
3.  register installed version/state;
4.  enable server functionality;
5.  expose client/UI functionality as appropriate.

Exact CLI/API/UI installation steps must be added here when implemented.

Disabling a module should preserve data by default.

Uninstalling may be destructive and must require explicit administrative
intent.

------------------------------------------------------------------------

## 10. External Integrations

Integration credentials remain server-side.

Example: a Hotel module may use a Cloudbeds adapter implementing a
provider-neutral reservation capability.

Do not put provider secrets in:

-   KMP source;
-   browser JavaScript;
-   mobile app resources;
-   Git;
-   screenshots/logs.

For every implemented provider, document:

-   required credentials;
-   callback/webhook requirements;
-   redirect URLs if applicable;
-   required network access;
-   synchronization behavior;
-   retry behavior;
-   provider-specific setup.

------------------------------------------------------------------------

## 11. Running the Server

Historically the repository has used:

``` bash
./gradlew :server:run
```

Verify this against the current repository.

Before calling a deployment production-ready, confirm:

-   server starts successfully;
-   database connectivity works;
-   migrations are current;
-   authentication works;
-   tenant isolation works;
-   enabled/disabled module enforcement works;
-   health endpoint works if implemented;
-   logs contain no secrets.

------------------------------------------------------------------------

## 12. Docker

Containerized self-hosting is an intended deployment option.

Do not publish a fictional `docker run` command before the repository
contains a tested image/Dockerfile.

When Docker support is implemented, this section should include:

-   image name/tag;
-   required environment variables;
-   ports;
-   volumes;
-   network configuration;
-   database connectivity;
-   healthcheck;
-   upgrade steps;
-   backup requirements.

A `compose.yaml` is recommended for a reproducible self-hosted stack
once the server, database and provisioning workflow are stable.

------------------------------------------------------------------------

## 13. Reverse Proxy and TLS

The Ubuntu installer now creates `/etc/nginx/sites-available/ideascore`
and enables it without replacing other sites. It rejects an existing
IdeasCore site or an existing Nginx configuration mentioning the supplied
domain. It preserves other Certbot certificates and uses the certificate
name `ideascore-DOMAIN` under `/etc/letsencrypt/live/`.

Nginx terminates TLS 1.2/1.3 on port 443, serves the browser application,
and proxies `/auth/`, `/companies` and `/modules` to loopback port 8080.
Port 80 redirects to HTTPS except for the ACME webroot challenge path,
which must stay accessible for renewals. The backend is not publicly
proxied until a certificate is obtained and the database check succeeds.
If issuance fails, the temporary site only serves challenges and a 503
response; no plaintext backend fallback is enabled.

`ideascore-certbot.timer` checks renewal twice daily with randomized delay.
The certificate's deploy hook reloads Nginx after renewal. Existing
Certbot installation methods and renewal timers are left in place.

```bash
sudo systemctl status ideascore-certbot.timer
sudo journalctl -u ideascore-certbot.service
sudo nginx -t
```

End-to-end issuance and renewal against a real domain remain untested in
this workspace. Failed installs preserve their files and certificates;
inspect the failing step before recovery instead of rerunning blindly.

Internet-facing production deployments should place the server behind a
reverse proxy or equivalent ingress with HTTPS.

Document the project's tested proxy configuration when available.

The proxy should preserve the headers required by the server and enforce
appropriate request-size/time-out policies.

Never document a proxy configuration as official until tested.

------------------------------------------------------------------------

## 14. Backups

A production self-host must back up:

1.  platform/control metadata, if used;
2.  every tenant PostgreSQL database;
3.  deployment configuration/secrets through an appropriate secure
    mechanism;
4.  any persistent files not stored in PostgreSQL.

Module installation/uninstallation and upgrades should be preceded by an
appropriate backup when they can modify persistent data.

A restore procedure is as important as a backup procedure. Test
restores.

------------------------------------------------------------------------

## 15. Upgrade Procedure

Until automated tooling exists, treat upgrades conservatively.

Target release process:

``` text
Backup
  ↓
Read release notes
  ↓
Stop/coordinate writes if required
  ↓
Update code/image
  ↓
Core migrations
  ↓
Installed-module migrations
  ↓
Start server
  ↓
Health checks
  ↓
Functional smoke tests
```

Never assume every tenant has the same installed modules.

Migration orchestration must account for each tenant's installed module
set and versions.

### Ubuntu updater

`scripts/ubuntu/update.sh` updates an existing installation created by the
Ubuntu installer. It does not create databases, change secrets or run
module migrations. It rewrites the IdeasCore Nginx site using the current proxy
routes for `/auth/`, `/companies` and `/modules`. It:

1. verifies `/opt/ideascore/source`, `/opt/ideascore/app`,
   `/etc/ideascore/server.env` and `ideascore.service`;
2. refuses to continue if the checked-out source has local changes;
3. fetches the requested branch, tag or commit;
4. builds `:server:test :server:installDist` with `-PserverOnly=true`;
5. builds `:app:webApp:jsBrowserDistribution` with `-PwebOnly=true`;
6. backs up the current runtime app, and backs up web artifacts too when
   `/opt/ideascore/web` already exists;
7. replaces `/opt/ideascore/app` and `/opt/ideascore/web`;
8. restarts `ideascore.service` and checks `http://127.0.0.1:8080/`.

Run it on the server with:

```bash
sudo bash /opt/ideascore/source/scripts/ubuntu/update.sh
```

For the current package reorganization, the updater installs the new Ktor
entry point `com.ideasdeveloper.idc.server.app.ApplicationKt.module` through
the rebuilt server distribution. Existing `/etc/ideascore/server.env`,
PostgreSQL databases and Nginx configuration are preserved.

If the server was installed with an older installer that did not create
`/opt/ideascore/web`, the updater builds the browser app and installs it
there instead of skipping it.

The updater runs Gradle with one worker, disables configuration cache for the
deployment build and sets Kotlin compiler execution through
`-Pkotlin.compiler.execution.strategy=in-process`. If the host has less than
2 GiB available memory and less than 1 GiB swap, it creates a temporary
2 GiB swap file at `/opt/ideascore/update.swap` for the build and removes it
afterward. This avoids depending on the Kotlin daemon socket and gives the
Kotlin/JS production compiler enough memory on small servers.

------------------------------------------------------------------------

## 16. Security Checklist

Before exposing a self-hosted installation:

-   [ ] no default passwords;
-   [ ] no secrets committed to Git;
-   [ ] PostgreSQL not publicly exposed unnecessarily;
-   [ ] HTTPS enabled;
-   [ ] tenant isolation tested;
-   [ ] server-side authorization tested;
-   [ ] disabled modules blocked server-side;
-   [ ] backups configured;
-   [ ] restore tested;
-   [ ] integration secrets stored server-side;
-   [ ] logs reviewed for secret leakage;
-   [ ] dependency/security updates reviewed.

------------------------------------------------------------------------

## 17. Troubleshooting

Si la instalación anterior terminó en `certbot renew --dry-run` con
`rateLimited` / `Service busy; retry later`, no ejecutes de nuevo todo el
instalador. El certificado real puede estar emitido y el backend activo.
Comprueba `systemctl status ideascore --no-pager` y
`curl --fail https://TU_DOMINIO/`. Revisa el temporizador con
`systemctl status ideascore-certbot.timer --no-pager`. La simulación usa
normalmente el entorno staging de Let’s Encrypt; no sustituye el certificado
real. Revisa el log de Certbot y respeta cualquier indicación `Retry-After`.
Después repite solamente `certbot renew --cert-name ideascore-TU_DOMINIO --dry-run`.

Si una versión anterior termina con `No se pudo completar la instalación
de dnsutils` después de que APT seleccione `bind9-dnsutils`, es un falso
negativo del instalador: comprobaba el alias en vez del paquete instalado.
La versión corregida utiliza `bind9-dnsutils` y comprueba que exista `dig`.
En este fallo concreto todavía no se ha creado la configuración de
IdeasCore; descarga el script corregido y ejecútalo de nuevo. Los paquetes
ya instalados se detectan y se conservan, sin borrar bases ni certificados.

Si aparece `ERR: bad trap`, el intérprete utilizado puede no admitir el
trap `ERR` de Bash. Ejecuta el archivo descargado explícitamente con:

```bash
sudo bash install-ideascore.sh
```

La versión actual detecta `sh install.sh` y se relanza con Bash antes de
usar opciones o traps específicos de Bash. Si se recibe por una tubería
en otro intérprete, solicita descargarlo y ejecutarlo con Bash. Evita
`source install.sh`: el instalador debe correr como un proceso separado.

When deployment fails, collect:

``` bash
git rev-parse HEAD
git status
java -version
./gradlew --version
```

Then inspect:

-   server logs;
-   PostgreSQL connectivity;
-   migration state;
-   environment configuration;
-   installed module state;
-   reverse proxy logs.

Do not post secrets publicly when requesting support.

------------------------------------------------------------------------

## 18. Documentation Maintenance

This guide must change with the code.

Whenever a change affects:

-   prerequisites;
-   environment variables;
-   ports;
-   database setup;
-   tenant provisioning;
-   migration commands;
-   Docker;
-   module installation;
-   integration setup;
-   backup/upgrade procedures;

update this document in the same change.

Only document commands that are implemented and tested.

Use labels such as **Implemented**, **Planned**, or **Example** when
necessary to prevent architectural intentions from being mistaken for
working deployment instructions.
