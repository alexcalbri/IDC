# Clientes Module

Status: locked base module scaffold.

Clientes exposes the shared IdeasCore Customer/Prospect identity to the UI and
other modules. Because every company needs the customer surface, new tenant
databases install this module automatically and seed it as enabled in
`tenant_modules`.

The customer schema is owned by this module and lives in:

```text
modules/clientes/migrations/V001__create_customers.sql
```

Implemented baseline:

- `GET /modules/clientes/customers` lists customers for users with
  `clientes.view`.
- `POST /modules/clientes/customers` creates customers for users with
  `clientes.create`.
- Creating a customer requires Nombre, Correo and Telefono.
- Extra Customer/Core fields created from the Clientes administration surface
  are backed by `customer_field_definitions` and stored in
  `customers.flexible_attributes`.
- Module-specific fields must be stored by the owning module, not in the shared
  Clientes field table, unless they are intentionally promoted to the shared
  customer record.
