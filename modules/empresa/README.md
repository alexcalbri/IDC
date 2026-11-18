# Empresa Module

Status: implemented core module foundation.

Empresa owns company administration, company identity, tenant provisioning
and company-level configuration. It is part of Core and is always active for
server-owner and business-owner dashboards.

Company SMTP (Core 0.8.0): the outgoing mail server of a company lives in the
Core, not in any optional module. Table `company_mail_settings` in each tenant
database (tenant migration V007, a single row `id = 1`; the password is stored
sealed as `password_enc` with the company `SecretBox` and is never returned).
Routes for the business owner (or the server owner in the server workspace):

- `GET /companies/me/mail` returns `{host, port, username, from, security, passwordSet}`.
- `PUT /companies/me/mail` receives `{host, port, username, from, security, password}`
  (`password`: absent/null keeps the stored one, `""` deletes it, a value replaces it).
  It rejects ports outside `MailHostGuard.AllowedPorts` and private hosts with 400
  before saving. An empty `host` clears the configuration.

Modules read it with `CompanyMail.read(tenantConnection, secretBox)`, which returns
`SmtpSettings?` (always `trusted = false`; `MailSender` repeats the anti-SSRF check
when sending). `CompanyMail.load` / `CompanyMail.save` serve the routes.

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
