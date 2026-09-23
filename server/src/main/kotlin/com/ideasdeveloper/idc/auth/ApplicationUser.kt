package com.ideasdeveloper.idc.auth

import kotlin.uuid.Uuid

data class ApplicationUser(
    val id: Uuid,
    val postgresRole: String,
    val isActive: Boolean,
)