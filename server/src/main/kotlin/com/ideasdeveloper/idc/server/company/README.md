# Core Empresa

Este paquete contendrá el módulo core de empresas.

Responsabilidades iniciales:

- creación de empresas desde una sesión `server_owner`;
- registro central de código, nombre, base de datos y estado;
- identidad visual de empresa: logo y colores;
- provisión de la base tenant y migraciones Core necesarias;
- exposición de la identidad de empresa para que login, dashboard y módulos usen la misma marca.

El instalador inicial solo crea la plataforma central y el `server_owner`. No crea empresas.
