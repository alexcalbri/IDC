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

Functional screens, server routes and customer creation services remain pending.
