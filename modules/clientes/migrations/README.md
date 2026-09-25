# Clientes Migrations

Customer identity tables are currently created by tenant Core migrations under
`database/tenant/migrations` because Customer/Prospect identity is shared by
all modules.

Use this folder later only for Clientes-specific behavior that is not part of
the shared customer identity.
