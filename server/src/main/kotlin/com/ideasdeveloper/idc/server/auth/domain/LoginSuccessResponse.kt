package com.ideasdeveloper.idc.server.auth.domain

import kotlinx.serialization.Serializable

@Serializable
class LoginSuccessResponse(
    val userId: String,
    val username: String,
    val accessToken: String,
    val expiresInSeconds: Long,
    val scope: String = "company",
    val companyCode: String? = null,
    val role: String = "user",
    val enabledModules: List<String> = emptyList(),
    // Server owners: company code of the server workspace where they use modules without a company.
    val workspaceCompanyCode: String? = null,
    val displayName: String? = null,
    val email: String? = null,
    // False until the user has a valid name and email; the app asks for them before continuing.
    val profileComplete: Boolean = true,
    // Server sessions: the name of the server, shown instead of a company name.
    val serverName: String? = null,
    // A server owner opened this company session as its business owner (company switcher).
    val serverOwnerAccess: Boolean = false,
)

// A company the session can move to.
@Serializable
class SwitchTarget(val code: String, val name: String)

// serverName is set when the session may go back to (or stay on) the server: server owners only.
// current is the company of the session, null on the server.
@Serializable
class SwitchTargetsResponse(val serverName: String?, val current: String?, val companies: List<SwitchTarget>)
