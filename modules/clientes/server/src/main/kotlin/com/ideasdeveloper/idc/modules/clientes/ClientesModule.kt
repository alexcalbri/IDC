package com.ideasdeveloper.idc.modules.clientes

object ClientesModule {
    const val id: String = "clientes"
    const val displayName: String = "Clientes"
    // Types of custom customer fields. Here and not in ClientesServerModule: the agent tools read it
    // while ClientesServerModule is still initializing.
    val fieldTypes: Set<String> = setOf("text", "textarea", "number", "money", "date", "datetime", "email", "phone", "boolean", "select", "multi_select")
}
