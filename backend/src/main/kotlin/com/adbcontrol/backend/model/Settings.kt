package com.adbcontrol.backend.model

import kotlinx.serialization.Serializable

@Serializable
data class NetworkStatusResponse(
    val isTailscale: Boolean,
    val clientIp: String,
    val tip: String
)

@Serializable
data class R2Secrets(
    val endpoint: String,
    val bucket: String,
    val accessKey: String,
    val accessSecret: String,
    val hasSecret: Boolean
)

@Serializable
data class EmqxSecrets(
    val host: String,
    val port: String,
    val appId: String,
    val restEndpoint: String,
    val appSecret: String,
    val hasAppSecret: Boolean,
    val ingestUsername: String,
    val ingestPassword: String,
    val hasIngestPassword: Boolean
)

@Serializable
data class ServerSecrets(
    val url: String,
    val pmToken: String,
    val hasPmToken: Boolean
)

@Serializable
data class DbSecrets(
    val host: String,
    val port: String,
    val name: String,
    val user: String,
    val password: String,
    val hasPassword: Boolean
)

@Serializable
data class CfSecrets(
    val apiToken: String = "",
    val hasApiToken: Boolean = false,
    val teamDomain: String = "",
    val oidcClientId: String = "",
    val oidcClientSecret: String = "",
    val hasOidcClientSecret: Boolean = false,
    val oidcRedirectUri: String = ""
)

@Serializable
data class D1Secrets(
    val databaseId: String = "",
    val databaseName: String = "",
    val accountId: String = "",
    val hasConfig: Boolean = false
)

@Serializable
data class SecretsResponse(
    val filePath: String,
    val exists: Boolean,
    val r2: R2Secrets,
    val emqx: EmqxSecrets,
    val server: ServerSecrets,
    val db: DbSecrets,
    val cf: CfSecrets = CfSecrets(),
    val d1: D1Secrets = D1Secrets()
)

@Serializable
data class SettingsOperationResponse(
    val ok: Boolean,
    val message: String,
    val code: String? = null
)
