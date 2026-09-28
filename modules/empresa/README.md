# Empresa Module

Status: implemented core module foundation.

Empresa owns company administration, company identity, tenant provisioning
and company-level configuration. It is part of Core and is always active for
server-owner and business-owner dashboards.

Initial visibility rule:

- `server_owner` can see Empresa from the dashboard.
- `business_owner` can see Empresa from the dashboard.
- Other roles must not see Empresa in the dashboard.

Scope rules:

- `server_owner` can administer any company, install/remove server modules and
  enable/disable installed modules for any company.
- `business_owner` can administer only its own company profile, backups and
  company users/permissions inside its own tenant database.
- `business_owner` cannot install modules on the server or enable/disable
  modules for its company.

Implemented screens and routes cover company provisioning for `server_owner`,
company self-management and tenant users/permissions for `business_owner`, and
server module administration for `server_owner`.
