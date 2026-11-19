# Migraciones de Compras

Se aplican en orden a la base de datos de cada empresa. Nunca se edita una migracion ya publicada: un cambio nuevo es un archivo `V0xx__descripcion.sql` con el siguiente numero libre, registrado donde se registran las demas.

| Archivo | Contenido |
| --- | --- |
| `V001__create_compras.sql` | Ajustes (`compras_settings`), departamentos (`compras_departments`, con 5 de ejemplo), aprobadores con limite (`compras_approvers`), solicitudes (`compras_requests`), cotizaciones (`compras_quotes`), adjuntos (`compras_attachments`) e historial (`compras_events`). |

Los montos son `numeric(14,2)` con su moneda de 3 letras. `compras_approvers` no tiene clave foranea hacia `application_users`: el codigo valida que el usuario exista.
