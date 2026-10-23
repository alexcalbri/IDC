# Clientes Migrations

This folder contains SQL migrations owned by the locked `clientes` module.

These migrations are applied to each new tenant database because `clientes` is
the base customer module. Other modules should reference these shared customer
records instead of creating duplicate customer identities.
