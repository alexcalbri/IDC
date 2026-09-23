package com.ideasdeveloper.idc.server.auth.domain

import java.time.OffsetDateTime
import kotlin.uuid.Uuid

class ApplicationSession(
    val tokenHash: String,
    val userId: Uuid,
    val createdAt: OffsetDateTime,
    val expiresAt: OffsetDateTime,
    val revokedAt: OffsetDateTime?,
)