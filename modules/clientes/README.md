# Clientes Module

Status: core module scaffold.

Clientes exposes the shared IdeasCore Customer/Prospect identity to the UI and
other modules. Customer storage lives in tenant Core migrations because CRM,
Hotspot, Memberships, Hotel and future modules should reference the same
customer record instead of creating separate customer copies.

New company tenant databases seed this module as enabled through the tenant
module registry migration. Functional screens, server routes and customer
creation services remain pending.
