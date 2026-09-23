package com.ideasdeveloper.idc.server.auth.domain

import kotlin.uuid.Uuid

data class ApplicationUser(
    val id: Uuid,
    val postgresRole: String,
    val isActive: Boolean,
)