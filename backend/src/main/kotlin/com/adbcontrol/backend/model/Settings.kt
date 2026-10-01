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
    val hasPmToken: Boolean,
    /** CI 部署触发令牌(仅 /api/admin/upgrade)。与 pmToken 分离,避免 OTA 发布权限外泄。 */
    val ciUpgradeToken: String = "",
    val hasCiUpgradeToken: Boolean = false,
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

/**
 * CI 自动部署接入信息(主机无公网,CI 经 Cloudflare 命名隧道回调本机)。
 *
 * [token] 仅在轮换当次返回明文,供运维复制进 GitHub Secrets;之后无法再读回。
 */
@Serializable
data class CiAccessResponse(
    /** 隧道对外服务地址(https://<你的域名>) */
    val serverUrl: String,
    /** 完整的 CI 回调 URL;tunnel 未绑定时为空串 */
    val upgradeUrl: String,
    val isTunnelBound: Boolean,
    /** 是否已配置 ci.upgrade_token */
    val hasToken: Boolean,
    /** 仅轮换时非空 */
    val token: String? = null,
)
