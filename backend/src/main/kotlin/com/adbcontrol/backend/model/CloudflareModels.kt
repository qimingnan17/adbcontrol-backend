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
    val status: String = "active",
    /**
     * 该域名所属的 Cloudflare 账户。
     *
     * 必须带上:一个 API Token 常可见多个账户,而建隧道 / 绑 DNS 只能在**域名所属账户**下做。
     * 拿“第一个账户”去绑第二个账户下的域名,必然失败。
     */
    val accountId: String = "",
    val accountName: String = "",
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
data class CfTokenVerifyInfo(
    val valid: Boolean,
    val tokenId: String? = null,
    val status: String? = null,
    val message: String? = null
)

/** 各类 Cloudflare 资源读取失败的真实原因（空表示该类资源读取成功） */
@Serializable
data class CfResourceErrors(
    val accounts: String? = null,
    val zones: String? = null,
    val tunnels: String? = null,
    val r2: String? = null,
    val d1: String? = null
)

@Serializable
data class CfAllResources(
    val valid: Boolean,
    val tokenInfo: CfTokenVerifyInfo? = null,
    val accounts: List<CfAccount> = emptyList(),
    val zones: List<CfZone> = emptyList(),
    val tunnels: List<CfTunnel> = emptyList(),
    val r2Buckets: List<CfR2Bucket> = emptyList(),
    val d1Databases: List<CfD1Database> = emptyList(),
    val errors: CfResourceErrors? = null,
    val message: String? = null
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
data class CfProvisionTunnelRequest(
    val subdomain: String,
    val zoneId: String? = null,
    val zoneName: String? = null,
    val tunnelId: String? = null,
    val tunnelName: String? = null,
    val localPort: Int = 8080,
    /** 是否同时开启 MQTT over WSS 双栈通道（追加入口规则 /mqtt → EMQX:8084） */
    val enableMqttWss: Boolean = false,
    val apiToken: String? = null
)

@Serializable
data class CfCreateTunnelRequest(
    val name: String,
    val apiToken: String? = null
)

@Serializable
data class CfCreateTunnelResponse(
    val ok: Boolean,
    val tunnelId: String? = null,
    val tunnelName: String? = null,
    val message: String
)

@Serializable
data class CfProvisionStep(
    val step: String,
    val ok: Boolean,
    val detail: String? = null
)

@Serializable
data class CfProvisionTunnelResponse(
    val ok: Boolean,
    val hostname: String = "",
    val serverUrl: String = "",
    val tunnelId: String? = null,
    val tunnelName: String? = null,
    val connectorCommand: String? = null,
    val steps: List<CfProvisionStep> = emptyList(),
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

/** Cloudflare 账户 API Token 权限组（创建 token 时按 id 引用，id 不透明不可写死）。 */
data class CfPermissionGroup(
    val id: String,
    val name: String,
    val scopes: List<String> = emptyList(),
)

/** 新建的账户级 API Token。[value] 仅在创建响应中出现一次。 */
data class CfCreatedToken(
    val id: String,
    val value: String,
)

/** R2 桶自定义域的绑定结果/就绪状态。 */
@Serializable
data class CfR2CustomDomain(
    val domain: String,
    /** 公共访问是否在该域上启用。 */
    val enabled: Boolean = true,
    /** 域名归属校验：pending / active / deactivated / blocked / error / unknown。 */
    val ownership: String = "unknown",
    /** SSL 证书状态：initializing / pending / active / error / unknown。 */
    val ssl: String = "unknown",
) {
    /** 归属与证书均已就绪，可直接作为公共访问基址。 */
    val ready: Boolean get() = ownership == "active" && ssl == "active"
}

/** R2 全自动配置请求。 */
@Serializable
data class CfR2ProvisionRequest(
    /** 存储桶名；留空则自动生成。 */
    val bucketName: String? = null,
    /** 是否同时开启 r2.dev 公共读域名并以之作为公共访问基地址。 */
    val enablePublic: Boolean = true,
    /**
     * 带「Account API Tokens:Edit」的 bootstrap token。
     * OAuth 票据没有 API Tokens:Write scope，生成 R2 的 S3 凭据必须靠它；
     * 留空时回退读取 secrets 里的 `cf.bootstrap_token`。
     */
    val bootstrapToken: String? = null,
    /** 指定账户；留空取第一个可见账户。 */
    val accountId: String? = null,
)

/** R2 全自动配置结果（逐步结果向前透出，失败时也能看到卡在哪一步）。 */
@Serializable
data class CfR2ProvisionResponse(
    val ok: Boolean,
    val accountId: String = "",
    val bucket: String = "",
    val publicBaseUrl: String = "",
    val accessKeyId: String = "",
    val steps: List<CfProvisionStep> = emptyList(),
    val message: String
)

/** 给 R2 桶绑定自定义公共域名请求。 */
@Serializable
data class CfR2CustomDomainRequest(
    /** 完整子域名，如 media.example.com（域名需托管在 Cloudflare）。 */
    val domain: String,
    /** 目标桶；留空取当前绑定的 r2.bucket。 */
    val bucket: String? = null,
    /** 域名所属 zone id；留空时按域名后缀自动在已同步 Zones 中匹配。 */
    val zoneId: String? = null,
    /** 指定账户；留空取第一个可见账户。 */
    val accountId: String? = null,
)

/** R2 自定义域绑定结果。 */
@Serializable
data class CfR2CustomDomainResponse(
    val ok: Boolean,
    val bucket: String = "",
    val domain: CfR2CustomDomain? = null,
    val publicBaseUrl: String = "",
    val message: String
)

/** 自动安装 cloudflared 连接器请求。 */
@Serializable
data class CfInstallConnectorRequest(
    /** 直接用已持有的连接器 token；留空则按 tunnelId 去官方 API 取。 */
    val token: String? = null,
    val tunnelId: String? = null,
    val accountId: String? = null,
)

/** 自动安装 cloudflared 连接器结果。 */
@Serializable
data class CfInstallConnectorResponse(
    val ok: Boolean,
    val steps: List<CfProvisionStep> = emptyList(),
    /** 自动安装失败时仍给出可手动执行的命令作为兜底。 */
    val manualCommand: String? = null,
    val message: String
)

/** 保存 Cloudflare OAuth 授权应用凭据（Client ID / Client Secret）。 */
@Serializable
data class CfApplyOAuthClientRequest(
    val clientId: String,
    val clientSecret: String? = null,
    /** 可选:覆盖默认授权 scope（空格分隔，如 "offline_access"）。 */
    val scopes: String? = null
)

/** Cloudflare OAuth 一键授权跳转地址响应。 */
@Serializable
data class CfOAuthAuthorizeUrlResponse(
    val configured: Boolean,
    val authUrl: String? = null,
    val message: String? = null
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
