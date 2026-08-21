package com.adbcontrol.backend.model

import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.Serializable

@Serializable
data class AdminUser(
    val id: Int,
    val username: String,
    @EncodeDefault val passwordHash: String = "",
    val role: String = "admin",
    val createdAt: Long = 0L,
    val lastLoginAt: Long = 0L,
    val totpSecret: String? = null
)

@Serializable
data class LoginRequest(val username: String, val password: String)

@Serializable
data class LoginResponse(val ok: Boolean, val user: AdminUser? = null, val message: String? = null)
