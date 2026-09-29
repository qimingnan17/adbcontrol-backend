package com.adbcontrol.backend.model

import kotlinx.serialization.Serializable

@Serializable
data class CfAccount(
    val id: String,
    val name: String,
    val type: String = "standard"
)

@Serializable
data class CfZone(
    val id: String,
    val name: String,
    val status: String = "active"
)

@Serializable
data class CfTunnel(
    val id: String,
    val name: String,
    val status: String = "inactive",
    val createdAt: String = "",
    val connectionsCount: Int = 0
)

@Serializable
data class CfR2Bucket(
    val name: String,
    val creationDate: String = "",
    val s3Endpoint: String = ""
)

@Serializable
data class CfD1Database(
    val uuid: String,
    val name: String,
    val version: String = "beta",
    val createdAt: String = "",
    val accountId: String = ""
)

@Serializable
data class CfAccessOrg(
    val name: String = "",
    val authDomain: String = ""
)

@Serializable
data class CfTokenVerifyInfo(
    val valid: Boolean,
    val tokenId: String? = null,
    val status: String? = null,
    val message: String? = null
)

@Serializable
data class CfAllResources(
    val valid: Boolean,
    val tokenInfo: CfTokenVerifyInfo? = null,
    val accounts: List<CfAccount> = emptyList(),
    val accessOrg: CfAccessOrg? = null,
    val zones: List<CfZone> = emptyList(),
    val tunnels: List<CfTunnel> = emptyList(),
    val r2Buckets: List<CfR2Bucket> = emptyList(),
    val d1Databases: List<CfD1Database> = emptyList(),
    val message: String? = null
)

@Serializable
data class CfTokenLoginRequest(
    val apiToken: String
)

@Serializable
data class CfAccessStatusResponse(
    val hasAccessHeader: Boolean,
    val email: String? = null,
    val user: AdminUser? = null
)

@Serializable
data class CfApplyR2Request(
    val endpoint: String,
    val bucket: String,
    val accessKey: String? = null,
    val accessSecret: String? = null
)

@Serializable
data class CfApplyTunnelRequest(
    val serverUrl: String
)

@Serializable
data class CfApplyOidcRequest(
    val teamDomain: String,
    val clientId: String,
    val clientSecret: String? = null,
    val redirectUri: String? = null
)

@Serializable
data class CfOidcAuthUrlResponse(
    val configured: Boolean,
    val authUrl: String? = null,
    val message: String? = null
)

@Serializable
data class CfOidcUserInfo(
    val email: String,
    val name: String? = null,
    val sub: String? = null
)

@Serializable
data class CfApplyD1Request(
    val databaseId: String,
    val databaseName: String,
    val accountId: String? = null
)

@Serializable
data class CfAutoBindRequest(
    val apiToken: String? = null
)

@Serializable
data class CfAutoBindResponse(
    val ok: Boolean,
    val boundTunnel: String? = null,
    val boundR2: String? = null,
    val boundD1: String? = null,
    val message: String
)

@Serializable
data class CfManualBindRequest(
    val serverUrl: String? = null,
    val r2Endpoint: String? = null,
    val r2Bucket: String? = null,
    val r2AccessKey: String? = null,
    val r2AccessSecret: String? = null,
    val d1DatabaseId: String? = null,
    val d1DatabaseName: String? = null,
    val d1AccountId: String? = null
)

@Serializable
data class CfCloudBindingsResponse(
    val serverUrl: String = "",
    val isTunnelBound: Boolean = false,
    val r2Endpoint: String = "",
    val r2Bucket: String = "",
    val isR2Bound: Boolean = false,
    val d1DatabaseId: String = "",
    val d1DatabaseName: String = "",
    val d1AccountId: String = "",
    val isD1Bound: Boolean = false,
    val hasApiToken: Boolean = false,
    val isAllBound: Boolean = false
)
