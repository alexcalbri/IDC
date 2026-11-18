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
- `business_owner` can install, enable, disable and remove extensions for its
  own company, only for modules the server owner has already installed on the
  server. Extensions can also be applied to linked companies that share the same
  business owner (see `EXTENSIONS.md`).
- `server_owner` can install extensions in any company that has the required
  module, and can create or remove linked groups.

Implemented screens and routes cover company provisioning for `server_owner`,
company self-management and tenant users/permissions for `business_owner`, and
server module administration for `server_owner`.
