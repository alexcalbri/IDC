# CRM Module

Status: planned module scaffold.

CRM will own sales and customer-relationship behavior around the shared
IdeasCore Customer/Prospect identity. Module-specific migrations belong in:

```text
modules/crm/migrations/
```

CRM is not included in the default server build. A future server-owner module
installation flow will install it from a trusted GitHub package or a validated
ZIP upload before it can be enabled for companies.
