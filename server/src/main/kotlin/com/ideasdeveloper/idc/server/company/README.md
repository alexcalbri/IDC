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
- `PUT /companies/{code}/modules/{moduleId}` habilita o deshabilita modulos
  opcionales por empresa. `clientes` queda bloqueado como modulo base.

El instalador inicial solo crea la plataforma central y el `server_owner`. No crea empresas.
