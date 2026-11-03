package com.ideasdeveloper.idc.app.features.company.data

import com.ideasdeveloper.idc.app.core.company.CompanyIdentity
import com.ideasdeveloper.idc.app.core.company.CompanyIdentityStore
import com.ideasdeveloper.idc.app.core.config.ClientConfiguration
import com.ideasdeveloper.idc.app.core.config.ClientConfigurationStore
import com.ideasdeveloper.idc.app.core.session.PersistentSessionStore
import com.ideasdeveloper.idc.app.core.session.SessionStore
import com.ideasdeveloper.idc.app.features.auth.data.LoginResponse

/** Cambia la sesion a otra empresa del mismo business owner sin volver a iniciar sesion. */
object CompanySwitch {
    /** Devuelve null si cambio, o el mensaje de error para mostrar. */
    suspend fun switchTo(serverUrl: String, session: LoginResponse, target: OwnedCompany): String? {
        val companyCode = session.companyCode ?: return "Solo un business owner puede cambiar de empresa."
        return try {
            CompanyApi(serverUrl).use { api ->
                val next = api.switchCompany(session.accessToken, companyCode, target.code)
                // La nueva sesion se guarda igual que la anterior: recordada solo si el usuario lo pidio al entrar.
                SessionStore.save(next, keepSignedIn = PersistentSessionStore.load() != null)
                ClientConfigurationStore.save(ClientConfiguration(serverUrl = serverUrl, companyCode = target.code))
                val identity = runCatching { api.companyProfile(next.accessToken, target.code) }.getOrNull()?.let { profile ->
                    CompanyIdentity(profile.code, profile.code, profile.name, profile.logoUrl, profile.primaryColor, profile.secondaryColor, profile.accentColor)
                } ?: CompanyIdentityStore.current().copy(id = target.code, code = target.code, name = target.name)
                CompanyIdentityStore.save(identity)
            }
            null
        } catch (exception: CompanyException) {
            exception.message ?: "No se pudo cambiar de empresa."
        }
    }
}

private suspend inline fun <T> CompanyApi.use(block: (CompanyApi) -> T): T = try {
    block(this)
} finally {
    close()
}
