# Hostpot Module

Status: planned module scaffold.

Hostpot owns hostpot-specific server behavior and future database migrations.
Module-specific migrations belong in:

```text
modules/hostpot/migrations/
```

Core capabilities such as tenants, sessions, customers, permissions and module
lifecycle remain outside this module. Hostpot is registered in the server module
catalog and can be enabled/disabled, but functional screens and database tables
are not implemented yet.
