# Hostpot Module

Status: planned module scaffold.

Hostpot owns hostpot-specific server behavior and future database migrations.
Module-specific migrations belong in:

```text
modules/hostpot/migrations/
```

Core capabilities such as tenants, sessions, customers, permissions and module
lifecycle remain outside this module. Hostpot is not included in the default
server build. A future server-owner module installation flow will install it
from a trusted GitHub package or a validated ZIP upload before it can be enabled
for companies.
