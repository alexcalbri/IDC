# IdeasCore (IDC)

IdeasCore es una plataforma empresarial modular y open source construida con
Kotlin Multiplatform, Compose Multiplatform, Ktor y PostgreSQL.

El proyecto gira alrededor de una identidad compartida de Cliente/Prospecto y
permite activar modulos funcionales por empresa sin duplicar la informacion
central del cliente. La logica de negocio, autenticacion, autorizacion,
aislamiento por empresa, migraciones y acceso a base de datos viven en el
servidor; los clientes son interfaces delgadas que consumen APIs y metadatos
del backend.

## Estado del proyecto

IDC esta en evolucion activa. El repositorio ya contiene:

- servidor Ktor con autenticacion, sesiones, provisioning de empresas y APIs de
  modulos;
- cliente Compose Multiplatform compartido para Android, Desktop, Web e iOS;
- estructura de modulos bajo `modules/`, incluyendo `clientes` y `empresa`;
- migraciones y scripts de provisioning bajo `database/`;
- instalador interactivo para Ubuntu bajo `scripts/ubuntu/install.sh`;
- guia de self-hosting y arquitectura del proyecto.

La arquitectura aprobada apunta a una base PostgreSQL central para la
administracion del servidor y una base PostgreSQL por empresa/tenant. Cada
empresa recibe Core y solo las estructuras de los modulos instalados para esa
empresa.

## Estructura

- `app/shared`: UI, navegacion, ViewModels, clientes API y modelos compartidos.
- `app/androidApp`: entrada Android.
- `app/desktopApp`: entrada Desktop JVM.
- `app/webApp`: entrada Web Compose.
- `app/iosApp`: entrada iOS/Xcode.
- `core`: codigo compartido de Core.
- `server`: aplicacion Ktor y APIs backend.
- `modules`: modulos funcionales.
- `database`: migraciones de Core y modulos.
- `scripts/ubuntu`: instalacion y actualizacion para servidores Ubuntu.
- `ARCHITECTURE.md`: guia de arquitectura y decisiones actuales.
- `SELF_HOSTING.md`: instrucciones operativas para instalaciones propias.

## Requisitos locales

- JDK 21.
- Gradle Wrapper incluido en el repositorio.
- Android SDK para compilar Android.
- Xcode para ejecutar iOS.
- PostgreSQL para ejecutar el backend con base de datos real.

El servidor requiere `DB_PASSWORD`. Otras variables de entorno tienen valores
por defecto documentados en `SELF_HOSTING.md`.

## Comandos utiles

Compilar o ejecutar el servidor:

```bash
./gradlew :server:run
./gradlew :server:test
./gradlew :server:installDist -PserverOnly=true
```

Ejecutar clientes:

```bash
./gradlew :app:desktopApp:run
./gradlew :app:webApp:jsBrowserDevelopmentRun
./gradlew :app:webApp:wasmJsBrowserDevelopmentRun
./gradlew :app:androidApp:assembleDebug
```

Build web de produccion:

```bash
./gradlew :app:webApp:jsBrowserDistribution -PwebOnly=true
```

Para iOS, abre `app/iosApp` en Xcode y ejecuta la app desde ahi.

## Instalacion en Ubuntu

Para una primera instalacion en servidor Ubuntu, usa el instalador interactivo:

```bash
sudo bash scripts/ubuntu/install.sh
```

El instalador configura PostgreSQL, Java, el build del servidor, Nginx, HTTPS
con Let's Encrypt cuando se elige modo `https`, variables del backend y un
servicio systemd.

Lee los detalles y advertencias operativas en
[`SELF_HOSTING.md`](SELF_HOSTING.md#instalador-interactivo-para-ubuntu).

## Documentacion

- [`ARCHITECTURE.md`](ARCHITECTURE.md): arquitectura, Core, modulos, tenant
  databases, control plane, permisos y decisiones implementadas o planeadas.
- [`SELF_HOSTING.md`](SELF_HOSTING.md): instalacion, variables de entorno,
  PostgreSQL, proxy, backups, upgrades y troubleshooting.
- [`COMPANY_PROVISIONING_FLOW.md`](COMPANY_PROVISIONING_FLOW.md): flujo de
  provisioning de empresas.
- [`PROGRAM_FLOW.md`](PROGRAM_FLOW.md): flujo general del programa.

## Principios de desarrollo

- El cliente no se conecta directamente a PostgreSQL.
- Los secretos y credenciales pertenecen al servidor.
- La visibilidad en UI no es autorizacion.
- Los modulos opcionales no deben duplicar la identidad compartida de cliente.
- Deshabilitar un modulo conserva datos; desinstalarlo es un flujo
  administrativo separado y potencialmente destructivo.
- La documentacion debe actualizarse junto con cambios de arquitectura,
  instalacion, configuracion o comportamiento observable.
