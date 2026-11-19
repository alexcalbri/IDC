package com.ideasdeveloper.idc.modules.compras

/** Identidad del modulo y ids de sus permisos (los declara ComprasServerModule). */
object ComprasModule {
    const val id = "compras"
    const val displayName = "Compras"

    /** Entra al modulo y ve sus propias solicitudes. */
    const val View = "compras.view"

    /** Crea solicitudes y edita o cancela las suyas. */
    const val Create = "compras.create"

    /** Ve todas las solicitudes y los totales. */
    const val ViewAll = "compras.view_all"

    /** Aprueba o rechaza dentro de su limite (ademas debe estar en la lista de aprobadores). */
    const val Approve = "compras.approve"

    /** Departamentos, aprobadores, moneda; edita y mueve cualquier solicitud. */
    const val Manage = "compras.manage"

    /** Importa solicitudes desde otras herramientas (las fuentes las aportan extensiones). */
    const val Import = "compras.import"
}

/** Permisos que una extension pide sobre el modulo (se declaran en su extension.json). */
object ComprasExtensionPermissions {
    const val RequestsImport = "compras.requests.import"
}
