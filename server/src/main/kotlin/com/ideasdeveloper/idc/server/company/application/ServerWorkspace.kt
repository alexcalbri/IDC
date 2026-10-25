package com.ideasdeveloper.idc.server.company.application

/**
 * Company-like tenant where server owners and their staff use modules without creating a company.
 * It is a row in `companies` flagged `is_server_workspace`, with its own tenant database, hidden
 * from company administration. Staff sign in with the company code [Code].
 */
object ServerWorkspace {
    const val Code = "servidor"
    const val Name = "Espacio del servidor"
    const val DatabaseName = "idc_servidor"
}
