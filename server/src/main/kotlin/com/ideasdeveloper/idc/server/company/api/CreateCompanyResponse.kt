package com.ideasdeveloper.idc.server.company.api

import kotlinx.serialization.Serializable

@Serializable
data class CreateCompanyResponse(
    val id: String,
    val code: String,
    val name: String,
    val databaseName: String,
    val businessOwnerUsername: String,
    val enabledModules: List<String>,
    // Duplicating: modules whose settings could not be copied (the company exists anyway).
    val settingsNotCopied: List<String> = emptyList(),
)

// A company of the same business owner, for the company switcher.
@Serializable
data class OwnedCompanyResponse(val code: String, val name: String)

@Serializable
data class OwnedCompaniesResponse(val current: String, val companies: List<OwnedCompanyResponse>)

// Without newOwner the copy keeps the business owner of the source company.
@Serializable
data class DuplicateCompanyRequest(val code: String, val newOwner: DuplicateOwnerRequest? = null)

@Serializable
data class DuplicateOwnerRequest(val username: String, val password: String, val name: String, val email: String)

// targetCode null takes the company out of its owner group.
@Serializable
data class LinkCompanyRequest(val targetCode: String? = null)
