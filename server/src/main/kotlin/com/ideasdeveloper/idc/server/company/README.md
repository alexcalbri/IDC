# Core Empresa

Este paquete contendrá el módulo core de empresas.

Responsabilidades iniciales:

- creación de empresas desde una sesión `server_owner`;
- registro central de código, nombre, base de datos y estado;
- identidad visual de empresa: logo y colores;
- provisión de la base tenant y migraciones Core necesarias;
- provisión del esquema compartido de clientes y activación del módulo base `clientes`;
- exposición de la identidad de empresa para que login, dashboard y módulos usen la misma marca.

Implementación actual:

- `POST /companies` requiere sesión `server_owner`.
- La ruta crea la base tenant, aplica migraciones Core/tenant, crea el
  `business_owner` y registra la empresa.
- La ruta valida el token `server_owner` y luego asume el rol PostgreSQL del
  `server_owner` para ejecutar el provisioning.
- `GET /companies` lista empresas y estado de modulos.
- `GET /server/modules` lista modulos instalados localmente o disponibles para
  instalacion segun las carpetas validas del repositorio de modulos del
  servidor, indica si estan instalados en el catalogo activo, muestra su
  version y cuantas empresas los tienen activos.
- `GET /server/modules/catalog` y `PUT /server/modules/catalog` permiten al
  `server_owner` consultar y cambiar la URL del repositorio/directorio remoto
  de modulos.
- `POST /server/modules/{moduleId}/install` instala en el catalogo activo un
  modulo disponible para instalacion, descarga su paquete si el catalogo expone
  `packageUrl`, valida `packageSha256` cuando existe y lo persiste en
  `server_modules`.
- `DELETE /server/modules/{moduleId}` elimina un modulo opcional del catalogo
  activo del servidor solo si ninguna empresa lo tiene activo, y elimina su
  fila de `server_modules`.
- `GET /server/modules/{moduleId}/inspect` (solo `server_owner`) descarga el
  paquete del modulo y devuelve su commit, URL, hash SHA-256, si viene firmado y
  el problema de firma si lo hay. `POST /server/modules/{moduleId}/authorize-unsigned`
  (solo `server_owner`) autoriza un modulo SIN firmar fijando su hash y commit
  (igual que las extensiones): antes de compilar, `ModulePackageBuilder.verify`
  exige firma Ed25519 valida o, si no viene firmada, que su hash este autorizado
  aqui. Con `MODULE_SIGNATURE_ENFORCED=true` (recomendado en produccion), sin
  firma ni autorizacion la instalacion se rechaza; con `false` (por defecto,
  dev/pruebas) se permite sin firma con un aviso en el log. La autorizacion de
  modulos es independiente de la de extensiones (se guarda con prefijo
  `module-commit:`).
- `PUT /companies/{code}/modules/{moduleId}` habilita o deshabilita modulos
  opcionales por empresa. Esta accion requiere `server_owner`; `business_owner`
  no puede cambiar disponibilidad de modulos. `clientes` queda bloqueado como
  modulo base.
- El catalogo de modulos activos del servidor se restaura desde `server_modules`
  y se expone a traves de `ModuleRegistry`. Los modulos bloqueados como
  `clientes` siempre se consideran instalados.
- Cada modulo instalado localmente puede exponer metadata y endpoints propios
  bajo `/modules/{moduleId}`.

Los modulos opcionales de negocio,  no pertenecen al
codigo base de Core. Deben venir del repositorio externo de modulos. Cada
carpeta instalable debe contener un `module.json` valido cuyo `id` coincida con
el nombre de la carpeta. Si un modulo se elimina del repositorio remoto antes
de instalarse, deja de ser visible para nuevas instalaciones. Si ya fue
instalado en un servidor, el servidor conserva su paquete local y su fila de
`server_modules` hasta que el `server_owner` decida removerlo.

`module.json` puede declarar `minCoreVersion` y `minAppVersion`. Al instalar o
actualizar (`POST /server/modules/{id}/install`; instalar modulos y cambiar el
catalogo ya no se exponen por MCP, solo desde la app) se rechaza con 409
`MODULE_NEEDS_CORE` si el Core es menor, o
`MODULE_NEEDS_APP` si la app (header `X-App-Version`) es menor; sin header solo
se avisa. El minimo se revisa con el catalogo y otra vez con el `module.json`
del paquete. Los minimos de un modulo instalado se leen del `module.json`
guardado dentro de su jar al arrancar.

Regla de alcance:

- `server_owner` administra cualquier empresa y todos los modulos del servidor.
- `business_owner` administra solo su propia empresa, sus backups y los
  usuarios/permisos dentro de la base tenant de esa empresa.
- Otros roles no administran empresas ni modulos.

El instalador inicial solo crea la plataforma central y el `server_owner`. No crea empresas.
