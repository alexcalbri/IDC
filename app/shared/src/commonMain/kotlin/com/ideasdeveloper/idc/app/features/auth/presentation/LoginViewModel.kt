package com.ideasdeveloper.idc.app.features.auth.presentation

import com.ideasdeveloper.idc.app.features.company.data.CompanyApi
import com.ideasdeveloper.idc.app.features.auth.data.LoginResponse
import com.ideasdeveloper.idc.app.core.company.serverIdentity
import com.ideasdeveloper.idc.app.core.company.CompanyIdentityStore
import com.ideasdeveloper.idc.app.core.company.CompanyIdentity
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ideasdeveloper.idc.app.core.config.ClientConfiguration
import com.ideasdeveloper.idc.app.core.config.ClientConfigurationStore
import com.ideasdeveloper.idc.app.core.session.SessionStore
import com.ideasdeveloper.idc.app.features.auth.data.LoginApi
import com.ideasdeveloper.idc.app.features.auth.data.LoginException
import com.ideasdeveloper.idc.app.features.auth.data.LoginRequest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class LoginUiState(
    val isLoading: Boolean = false,
    val error: String? = null,
    val succeeded: Boolean = false,
)

// Coordina validacion, llamada de login y persistencia de sesion/configuracion.
class LoginViewModel : ViewModel() {

    // Estado observable por la pantalla de login.
    private val _state = MutableStateFlow(LoginUiState())
    val state = _state.asStateFlow()

    // Ejecuta el flujo completo de autenticacion contra el servidor configurado.
    // Marca de la sesion recien iniciada: el servidor, o la empresa (su perfil cuando lo puede leer).
    private suspend fun refreshIdentity(serverUrl: String, response: LoginResponse, companyCode: String?) {
        if (companyCode == null) {
            CompanyIdentityStore.save(serverIdentity(response.serverName))
            return
        }
        val profile = if (response.role == "business_owner") {
            runCatching {
                val api = CompanyApi(serverUrl)
                try { api.companyProfile(response.accessToken, companyCode) } finally { api.close() }
            }.getOrNull()
        } else null
        val identity = profile?.let { CompanyIdentity(it.code, it.code, it.name, it.logoUrl, it.primaryColor, it.secondaryColor, it.accentColor) }
            ?: CompanyIdentityStore.current().takeIf { it.code == companyCode }
            ?: serverIdentity(null).copy(id = companyCode, code = companyCode, name = companyCode)
        CompanyIdentityStore.save(identity)
    }

    fun login(
        serverUrl: String,
        username: String,
        password: String,
        companyCode: String?,
        keepSignedIn: Boolean,
    ) {
        if (_state.value.isLoading) return

        // Valida campos basicos antes de abrir una conexion HTTP.
        if (username.isBlank() || password.isEmpty()) {
            _state.value = LoginUiState(
                error = "Introduce usuario y contraseña."
            )
            return
        }

        val code = companyCode?.trim()
        // Asegura que el codigo tenant use el mismo formato esperado por el servidor.
        if (code != null && !code.matches(Regex("[a-z][a-z0-9_]{0,62}"))) {
            _state.value = LoginUiState(
                error = "Introduce un código de empresa válido."
            )
            return
        }

        _state.value = LoginUiState(isLoading = true)

        viewModelScope.launch {
            var api: LoginApi? = null

            try {
                api = LoginApi(serverUrl)

                // Solicita al servidor token, rol y modulos habilitados para esta sesion.
                val response = api.login(
                    LoginRequest(
                        username = username.trim(),
                        password = password,
                        companyCode = code,
                    )
                )

                // Guarda sesion y configuracion para que el resto de la app pueda navegar.
                SessionStore.save(response, keepSignedIn = keepSignedIn)
                ClientConfigurationStore.save(
                    ClientConfiguration(
                        serverUrl = serverUrl.trim().trimEnd('/'),
                        companyCode = code,
                    )
                )
                refreshIdentity(serverUrl.trim().trimEnd('/'), response, code)
                _state.value = LoginUiState(succeeded = true)
            } catch (exception: CancellationException) {
                throw exception
            } catch (exception: LoginException) {
                _state.value = LoginUiState(error = exception.message)
            } catch (_: IllegalArgumentException) {
                _state.value = LoginUiState(
                    error = "Revisa la URL del servidor. Debe iniciar con http:// o https:// y no llevar rutas."
                )
            } catch (_: Exception) {
                _state.value = LoginUiState(
                    error = "No se pudo completar el inicio de sesión."
                )
            } finally {
                api?.close()
            }
        }
    }
}
